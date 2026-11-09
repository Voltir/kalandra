package kalandra.method

import grit.projects.artifact.{Artifact, ArtifactHead, Body}
import grit.projects.method.{ArtifactKind, TransitionCheck}
import grit.projects.name.CheckName

/** What a benched amendment's verdict says the bench saw. */
enum Verdict(val word: String) extends caps.Pure {
  case Helped extends Verdict("helped")
  case Hurt extends Verdict("hurt")
  case NoObservedEffect extends Verdict("no observed effect")
  case Confounded extends Verdict("confounded")
}

object Verdict {

  /** The verdict `word` names, as an amendment's `## Verdict: <word>` writes it, or none. */
  def of(word: String): Option[Verdict] = values.find(_.word == word)
}

/** kalandra's compiled checks (ADR 0049), each at version 1. */
object AmendmentChecks {

  /** `decided-adopt`: its body holds `## Contrast`, exactly one `## Verdict: <word>` naming a
    * [[Verdict]], and exactly one `## Decision: …`, which is `adopt`, with a bullet starting with
    * each of [[Features]] and a colon (`- Activation: …`) before the next heading.
    */
  val DecidedAdopt: TransitionCheck = Decided("adopt")

  /** `decided-reject`: as [[DecidedAdopt]], its decision `reject`. */
  val DecidedReject: TransitionCheck = Decided("reject")

  /** Each of kalandra's checks. */
  val all: Vector[TransitionCheck] = Vector(DecidedAdopt, DecidedReject)

  /** The labels a decision's bullets start with: who decided, how often the amendment's cue
    * was acted on per task, the checks per task, the deltas in cost, tokens and calls, and any
    * regression on the held-out task. Only the labels are checked; what follows is free.
    */
  val Features: Vector[String] = Vector("By", "Activation", "Checks", "Cost", "Held-out regression")

  private final case class Decided(verb: String) extends TransitionCheck {
    val name: CheckName =
      // A literal CheckName.of accepts.
      CheckName.of(s"decided-$verb").fold(why => throw new IllegalStateException(why), identity)
    val version: Int = 1

    def holds(
        judged: Artifact,
        heads: Vector[ArtifactHead],
        kinds: Vector[ArtifactKind]
    ): Either[String, Unit] = {
      val titles = Body.sections(judged.body)
      val verdicts = titles.filter(_.startsWith(VerdictTitle)).map(_.drop(VerdictTitle.length))
      val decisions = titles.filter(_.startsWith(DecisionTitle)).map(_.drop(DecisionTitle.length))
      val decided = s"## $DecisionTitle$verb"
      val under = Body
        .markdown(judged.body)
        .split("\n", -1)
        .toVector
        .dropWhile(_ != decided)
        .drop(1)
        .takeWhile(!_.startsWith("#"))
      val failed =
        Option.when(!titles.contains("Contrast"))("its body lacks ## Contrast").toVector ++
          (verdicts match {
            case Vector(word) if Verdict.of(word).nonEmpty => None
            case Vector(word) =>
              Some(
                s"its verdict is `$word`, not one of ${Verdict.values.map(_.word).mkString(", ")}"
              )
            case Vector() => Some(s"its body lacks ## ${VerdictTitle}<verdict>")
            case several => Some(s"it holds ${several.size} verdicts, not one")
          }) ++
          (decisions match {
            case Vector(v) if v == verb =>
              Features
                .filterNot(f => under.exists(_.startsWith(s"- $f:")))
                .map(f => s"its decision lacks a line `- $f: …`")
            case Vector() => Vector(s"its body lacks $decided")
            case Vector(other) => Vector(s"its decision is $other, not $verb")
            case several => Vector(s"it holds ${several.size} decisions, not one")
          })
      if (failed.isEmpty) Right(()) else Left(failed.mkString("; "))
    }
  }

  private val VerdictTitle = "Verdict: "
  private val DecisionTitle = "Decision: "
}
