package kalandra.amend

import grit.core.host.Replace
import grit.core.id.DocumentVersionId
import grit.projects.artifact.{Artifact, ArtifactHead, Body}
import grit.projects.name.{ArtifactKey, KindName, StateName}

import utest.*

object AdoptionTests extends TestSuite {

  private def right[A](e: Either[String, A]): A =
    e.fold(why => throw new java.lang.AssertionError(why), identity)

  private val key = right(ArtifactKey.read("p/standing"))
  private def version(n: Long) = DocumentVersionId.of(n).getOrElse(sys.error("no version"))

  /** A standing text with a character outside the BMP, two UTF-16 units and one code point. */
  private val below = "You make one change, café𝄞.\n\n## Rules\n\n- Stay in scope.\n- Never push."

  /** A standing section as it is kept, its cap as `cap` writes it. */
  private def section(cap: String, text: String = below): Artifact =
    Artifact(
      ArtifactHead(
        key,
        right(KindName.of("section")),
        right(StateName.of("current")),
        "How a coder works",
        None,
        Vector.empty,
        None
      ),
      right(
        Body.read(
          s"What a coder works by.\n\n**Cap: $cap characters** below the line.\n\n---\n\n$text"
        )
      )
    )

  private def standing(a: Artifact) = StandingSection.of(key, a.body)

  /** `markdown` read as a body, then as a standing section. */
  private def standing(markdown: String) = StandingSection.of(key, right(Body.read(markdown)))

  /** An amendment's body, its diff against version 7 of `p/standing` the blocks given. */
  private def amendment(blocks: String*): String =
    "Proposes a rule.\n\n## Diff\n\n" +
      "Against [How a coder works](artifact:p/standing) at version 7.\n\n" +
      blocks.mkString("\n\n") + "\n\n## Moments\n\n- trial 1, call 3: `run` was refused."

  private def block(tag: String, text: String) = s"```$tag\n$text\n```"

  private def redFirst =
    right(
      Diff.read(
        amendment(
          block("old", "- Never push."),
          block("new", "- Never push.\n- Red first,\n  always.")
        )
      )
    )

  val tests = Tests {
    test("a standing section's cap is read above its rule, its size the code points below it") {
      (
        standing(section("100")).map(s => (s.cap, s.text, s.size, s.file)),
        standing(section("3,550")).map(_.cap)
      ) ==> (Right((100, below, 69, below + "\n")), Right(3550))
    }

    test("no cap, two caps, no rule, or a blank text below it is no standing section") {
      (
        standing("Top.\n\n***\n\nBelow."),
        standing("**Cap: 9 characters** or **Cap: 8 characters**\n\n***\n\nB."),
        standing("**Cap: 9 characters**\n\nNo rule."),
        standing("**Cap: 9 characters**\n\n***\n\n  \n")
      ) ==> (
        Left(AdoptRefusal.NoCap(key)),
        Left(AdoptRefusal.Caps(key, Vector(9, 8))),
        Left(AdoptRefusal.NoRule(key)),
        Left(AdoptRefusal.Blank(key))
      )
    }

    test("a standing section's rule is a thematic break, not a `***` line in a code block") {
      standing("**Cap: 9 characters**\n\n```\n***\n```\n\n***\n\nBelow.").map(_.text) ==>
        Right("Below.")
    }

    test("a diff reads its section, version and replacements in order; an empty new deletes") {
      // Read from the amendment as it is kept, so the tags and lines survive a keep.
      val kept = Body.markdown(
        right(
          Body.read(
            amendment(
              block("old", "A."),
              block("new", "B.\n\nC."),
              block("old", "D."),
              "```new\n```"
            )
          )
        )
      )
      Diff.read(kept).map(d => (d.section, d.against, d.edits)) ==>
        Right((key, version(7), Vector(Replace("A.", "B.\n\nC."), Replace("D.", ""))))
    }

    test("a diff's section is the artifact its Against line cites, whatever its title holds") {
      val kept = Body.markdown(
        right(
          Body.read(
            "## Diff\n\nAgainst [How a coder works [draft] (v2)](artifact:p/standing) at version 7." +
              "\n\n" + block("old", "A.") + "\n\n" + block("new", "B.")
          )
        )
      )
      Diff.read(kept).map(d => (d.section, d.against)) ==> Right((key, version(7)))
    }

    test(
      "a diff with no Against line or two, no old block, or an unpaired block is refused, saying which"
    ) {
      (
        Diff.read("## Diff\n\n" + block("old", "A.") + "\n\n" + block("new", "B.")),
        Diff.read(amendment()),
        Diff.read(amendment(block("old", "A."), block("old", "B."), block("new", "C."))),
        Diff.read(amendment(block("new", "C."))),
        Diff.read(
          amendment(
            "Against [How a coder works](artifact:p/standing) at version 8.",
            block("old", "A."),
            block("new", "B.")
          )
        )
      ) ==> (
        Left("it has no line `Against [<title>](artifact:<key>) at version <n>.`"),
        Left("it has no code block tagged `old`"),
        Left("its `old` block 1 is not followed at once by a `new` block"),
        Left("its `new` block 1 follows no `old` block"),
        Left("it has 2 lines `Against … at version …`, not one")
      )
    }

    test("the candidate is the diff applied, normalised as a keep is, and its file is that text") {
      val made = right(Adoption.candidate(redFirst, section("100"), version(7)).left.map(_.message))
      val text = below + "\n- Red first, always."
      (
        made.standing.text,
        made.standing.file,
        standing(Artifact(section("100").head, made.body)).map(_.file)
      ) ==>
        (text, text + "\n", Right(text + "\n"))
    }

    test("a candidate over its cap is refused, saying by how much") {
      Adoption.candidate(redFirst, section("80"), version(7)).left.map(_.message) ==>
        Left("p/standing's standing text would be 90 characters, over its cap of 80: cut 10")
    }

    test("a diff against another version of its section is refused, naming both") {
      Adoption.candidate(redFirst, section("100"), version(9)).left.map(_.message) ==> Left(
        "p/standing is at version 9, not 7 as the diff is against: bench the amendment again " +
          "against it, or write a new one"
      )
    }

    test("an edit that does not apply is refused in the edit's own words") {
      val stale = right(Diff.read(amendment(block("old", "- Never pull."), block("new", "- No."))))
      Adoption.candidate(stale, section("100"), version(7)) ==> Left(
        AdoptRefusal.Unapplied(
          key,
          "edits[0].oldText was not found in p/standing. It must match the file exactly, " +
            "including whitespace and line breaks; read the file again to copy it."
        )
      )
    }

    test("an edit above the rule is refused: the cap is the section's own to change") {
      val raising = right(
        Diff.read(
          amendment(block("old", "**Cap: 80 characters**"), block("new", "**Cap: 999 characters**"))
        )
      )
      Adoption.candidate(raising, section("80"), version(7)) ==> Left(
        AdoptRefusal.AboveTheRule(key)
      )
    }
  }
}
