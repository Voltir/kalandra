package kalandra.method

import grit.projects.artifact.{Artifact, ArtifactHead, Body, Rank}
import grit.projects.name.{ArtifactKey, ArtifactRef, Handle, KindName, StateName}

import utest.*

object AmendmentChecksTests extends TestSuite {

  private def right[A](e: Either[String, A]): A =
    e.fold(why => throw new java.lang.AssertionError(why), identity)

  private def judged(body: String): Artifact =
    Artifact(
      ArtifactHead(
        right(ArtifactKey.read("p/1")),
        Some(right(Handle.of("amend"))),
        right(KindName.of("amendment")),
        right(StateName.of("benched")),
        "An amendment",
        None,
        Rank.Middle,
        Vector.empty,
        None
      ),
      right(Body.read(body, ArtifactRef.id))
    )

  private val features =
    "- By: a person, for a reason.\n- Activation: 1 of 1.\n- Checks: 7 of 7.\n" +
      "- Cost: +1¢, +800 tokens, +2 calls.\n- Held-out regression: none."

  private def decided(verdict: String, verb: String, listed: String = features): String =
    s"## Diff\n\nThe diff.\n\n## Contrast\n\nkept -> amendment -> moment.\n\n" +
      s"## Verdict: $verdict\n\nWhy.\n\n## Decision: $verb\n\n$listed\n\n## Notes\n\n- Cost: elsewhere."

  private def adopt(body: String) =
    AmendmentChecks.DecidedAdopt.holds(judged(body), Vector.empty, Vector.empty)

  val tests = Tests {
    test(
      "decided-adopt holds over a contrast, one verdict and an adopt decision naming each feature"
    ) {
      Verdict.values.toVector.map(v => adopt(decided(v.word, "adopt"))) ==>
        Vector.fill(4)(Right(()))
    }

    test("decided-adopt names each section a body lacks") {
      adopt("## Diff\n\nThe diff.") ==> Left(
        "its body lacks ## Contrast; its body lacks ## Verdict: <verdict>; " +
          "its body lacks ## Decision: adopt"
      )
    }

    test("a verdict outside the four is refused, naming them") {
      adopt(decided("promising", "adopt")) ==> Left(
        "its verdict is `promising`, not one of helped, hurt, no observed effect, confounded"
      )
    }

    test("each check refuses the other's decision") {
      (
        adopt(decided("hurt", "reject")),
        AmendmentChecks.DecidedReject.holds(
          judged(decided("hurt", "adopt")),
          Vector.empty,
          Vector.empty
        ),
        AmendmentChecks.DecidedReject.holds(
          judged(decided("hurt", "reject")),
          Vector.empty,
          Vector.empty
        )
      ) ==> (
        Left("its decision is reject, not adopt"),
        Left("its decision is adopt, not reject"),
        Right(())
      )
    }

    test("a decision lacking a feature's line names it; one under another heading is not its") {
      val listed = features.split("\n").filterNot(_.startsWith("- Cost")).mkString("\n")
      adopt(decided("confounded", "adopt", listed)) ==>
        Left("its decision lacks a line `- Cost: …`")
    }

    test(
      "a decision's features are its bullets, a `#` line in a code block above them no heading"
    ) {
      adopt(decided("helped", "adopt", "```\n# the run\n```\n\n" + features)) ==> Right(())
    }

    test("two verdicts or two decisions are refused") {
      adopt(decided("hurt", "adopt") + "\n\n## Verdict: helped\n\n## Decision: adopt") ==>
        Left("it holds 2 verdicts, not one; it holds 2 decisions, not one")
    }
  }
}
