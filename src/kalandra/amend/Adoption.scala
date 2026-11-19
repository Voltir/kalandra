package kalandra.amend

import grit.core.host.{RelPath, Replace}
import grit.core.id.DocumentVersionId
import grit.projects.artifact.{Artifact, Body}
import grit.projects.name.{ArtifactKey, ArtifactRef}
import grit.prose.form.Block
import grit.prose.markdown.Markdown

/** A section a standing text is kept in: its header, the markdown above its first rule
  * ([[Body.aroundRule]]), stating its cap as `**Cap: N characters**` (`N` with or without
  * thousands commas); and its standing text, the markdown below that rule, surrounding
  * whitespace trimmed, never blank. It may be over its cap.
  */
final case class StandingSection private (header: String, text: String, cap: Int)
    extends caps.Pure {

  /** The code points of [[text]], everything below the first rule: what [[cap]] bounds. */
  def size: Int = text.codePointCount(0, text.length)

  /** [[text]] ending in one newline: what a bench's standing-text file holds, and so the text
    * its digest is of.
    */
  def file: String = text + "\n"
}

object StandingSection {

  /** `body`, the body of the artifact `section`, as a standing section, or why not: no rule
    * ([[AdoptRefusal.NoRule]]), a header stating no cap or more than one ([[AdoptRefusal.NoCap]],
    * [[AdoptRefusal.Caps]]), or a blank standing text ([[AdoptRefusal.Blank]]), which a bench
    * refuses to run under.
    */
  def of(section: ArtifactKey, body: Body): Either[AdoptRefusal, StandingSection] =
    Body.aroundRule(body) match {
      case None => Left(AdoptRefusal.NoRule(section))
      case Some((header, below)) =>
        val text = below.trim
        Cap.findAllMatchIn(header).map(_.group(1).replace(",", "").toInt).toVector match {
          case Vector(_) if text.isEmpty => Left(AdoptRefusal.Blank(section))
          case Vector(cap) => Right(StandingSection(header, text, cap))
          case Vector() => Left(AdoptRefusal.NoCap(section))
          case several => Left(AdoptRefusal.Caps(section, several))
        }
    }

  private val Cap = raw"\*\*Cap: ([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,9}) characters\*\*".r
}

/** An amendment's diff, as its `## Diff` states it: the artifact it amends, the version of that
  * artifact it was written against, and its exact replacements, in order, each applied as
  * [[Replace.onto]] applies a coder's edits.
  */
final case class Diff private (
    section: ArtifactKey,
    against: DocumentVersionId,
    edits: Vector[Replace]
) extends caps.Pure

object Diff {

  /** The diff `markdown` (an amendment's body as [[Body.markdown]] writes it) holds, wherever in
    * it its lines and blocks sit, or why not, in words: no line `Against [<title>](artifact:<key>)
    * at version <n>.`, or more than one; one citing no artifact, or several, or what
    * [[Body.read]] refuses of it; no code block tagged `old`; an `old` block not followed at once
    * by one tagged `new`, or a `new` block after no `old`. An empty `new` block deletes its `old`
    * text.
    */
  def read(markdown: String): Either[String, Diff] = {
    val against = markdown.split("\n", -1).toVector.flatMap { line =>
      Against.unapplySeq(line).collect { case List(version) => (line, version) }
    }
    val tagged = Markdown.parse(markdown, Markdown.Newline.Space).blocks.collect {
      case Block.Code(Some(tag), lines) if tag == "old" || tag == "new" =>
        tag -> lines.mkString("\n")
    }
    for {
      named <- against match {
        case Vector(one) => Right(one)
        case Vector() =>
          Left("it has no line `Against [<title>](artifact:<key>) at version <n>.`")
        case several => Left(s"it has ${several.size} lines `Against … at version …`, not one")
      }
      section <- Body
        .read(named._1, ArtifactRef.id)
        .flatMap(line =>
          Body.cites(line) match {
            case Vector(one) => Right(one)
            case several => Left(s"its line `Against …` cites ${several.size} artifacts, not one")
          }
        )
      version <- named._2.toLongOption
        .flatMap(DocumentVersionId.of)
        .toRight(s"version ${named._2} is no version")
      edits <- paired(tagged, Vector.empty)
    } yield Diff(section, version, edits)
  }

  /** A line naming the version a diff is against, its section the artifact the rest cites. */
  private val Against = raw"Against .+ at version ([0-9]+)\.".r

  /** `blocks`, tagged `old` or `new`, read as replacements after `done`. */
  @annotation.tailrec
  private def paired(
      blocks: Vector[(String, String)],
      done: Vector[Replace]
  ): Either[String, Vector[Replace]] =
    blocks match {
      case Vector() if done.isEmpty => Left("it has no code block tagged `old`")
      case Vector() => Right(done)
      case ("old", from) +: ("new", to) +: rest => paired(rest, done :+ Replace(from, to))
      case ("old", _) +: _ =>
        Left(s"its `old` block ${done.size + 1} is not followed at once by a `new` block")
      case _ => Left(s"its `new` block ${done.size + 1} follows no `old` block")
    }
}

/** A standing section's next version, an amendment's diff applied: the body to keep, and its
  * standing section, whose [[StandingSection.file]] a bench runs under.
  */
final case class Candidate(body: Body, standing: StandingSection) extends caps.Pure

/** Why an amendment's candidate is refused; nothing was kept. [[message]] is what the person
  * giving the command reads.
  */
enum AdoptRefusal extends caps.Pure {
  case NotADiff(amendment: ArtifactKey, why: String)
  case NoRule(section: ArtifactKey)
  case NoCap(section: ArtifactKey)
  case Caps(section: ArtifactKey, caps: Vector[Int])
  case Blank(section: ArtifactKey)
  case Moved(section: ArtifactKey, against: DocumentVersionId, now: DocumentVersionId)
  case Unapplied(section: ArtifactKey, why: String)
  case AboveTheRule(section: ArtifactKey)
  case Unkept(section: ArtifactKey, why: String)
  case OverCap(section: ArtifactKey, size: Int, cap: Int)

  def message: String = this match {
    case NotADiff(a, why) => s"${a.value} states no diff to apply: $why"
    case NoRule(s) => s"${s.value} has no rule line (***) below which its standing text sits"
    case NoCap(s) => s"${s.value} states no cap above its rule (**Cap: N characters**)"
    case Caps(s, cs) => s"${s.value} states ${cs.size} caps above its rule, not one"
    case Blank(s) => s"${s.value}'s standing text, below its rule, is blank"
    case Moved(s, a, n) =>
      s"${s.value} is at version ${DocumentVersionId.value(n)}, not " +
        s"${DocumentVersionId.value(a)} as the diff is against: bench the amendment again " +
        "against it, or write a new one"
    case Unapplied(s, why) => s"the diff does not apply to ${s.value}: $why"
    case AboveTheRule(s) =>
      s"the diff changes ${s.value} above its rule, where its cap is stated; a cap is changed " +
        "by keeping the section"
    case Unkept(s, why) => s"${s.value} with the diff applied is no body: $why"
    case OverCap(s, size, cap) =>
      s"${s.value}'s standing text would be ${f"$size%,d"} characters, over its cap of " +
        s"${f"$cap%,d"}: cut ${f"${size - cap}%,d"}"
  }
}

object Adoption {

  /** `section`, kept as `version`, with `diff` applied, normalised as a keep normalises a body
    * ([[Body.read]], then [[Body.markdown]]), or why not: `version` is not the diff's
    * ([[AdoptRefusal.Moved]]); `section` is no standing section; an edit does not apply
    * ([[AdoptRefusal.Unapplied]], with [[grit.core.host.EditError]]'s words); the result
    * changes what is above the rule, the rule included ([[AdoptRefusal.AboveTheRule]]); it is
    * no body ([[AdoptRefusal.Unkept]]); or its standing text is over the cap
    * ([[AdoptRefusal.OverCap]]).
    */
  def candidate(
      diff: Diff,
      section: Artifact,
      version: DocumentVersionId
  ): Either[AdoptRefusal, Candidate] = {
    val key = section.head.key
    val text = Body.markdown(section.body)
    for {
      _ <- Either.cond(version == diff.against, (), AdoptRefusal.Moved(key, diff.against, version))
      before <- StandingSection.of(key, section.body)
      path <- RelPath.of(key.value).left.map(e => AdoptRefusal.Unapplied(key, e.toString))
      edited <- Replace
        .onto(path, text, diff.edits)
        .left
        .map(e => AdoptRefusal.Unapplied(key, e.message))
      body <- Body.read(edited._1, ArtifactRef.id).left.map(AdoptRefusal.Unkept(key, _))
      after <- StandingSection
        .of(key, body)
        .filterOrElse(_.header == before.header, AdoptRefusal.AboveTheRule(key))
        .left
        .map {
          case AdoptRefusal.NoRule(_) | AdoptRefusal.NoCap(_) | AdoptRefusal.Caps(_, _) =>
            AdoptRefusal.AboveTheRule(key)
          case other => other
        }
      _ <- Either.cond(
        after.size <= after.cap,
        (),
        AdoptRefusal.OverCap(key, after.size, after.cap)
      )
    } yield Candidate(body, after)
  }
}
