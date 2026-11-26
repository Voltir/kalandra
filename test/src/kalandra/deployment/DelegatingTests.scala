package kalandra.deployment

import scala.concurrent.duration.DurationInt

import grit.core.act.{Acting, ActsFor, Allowance, Delegation, Gates}
import grit.core.agent.Subagent
import grit.core.id.{AgentType, ConversationId, TurnRef, TurnSeq}
import grit.core.message.Cost
import grit.core.place.{Directory, Service}
import grit.core.visibility.Integrity

import utest.*

/** Which of kalandra's subagent types a turn may start, judged by core's delegation rule over
  * kalandra's own declarations: no turn runs, and no model is called.
  */
object DelegatingTests extends TestSuite {

  /** A service kalandra declares no cap for, as a web search would be: a fake, never called. */
  private val Web: Service = Service.of("web").fold(sys.error, identity)

  private val checkout: Directory = Directory.of("/home/someone/grit").fold(sys.error, identity)

  private lazy val kalandra = Kalandra
    .deployment(checkout, KalandraTests.Own, KalandraTests.Listening, KalandraTests.Calling)
    .fold(sys.error, identity)

  /** A turn of the owner's in kalandra's room, believed at member. */
  private val parent = Acting(
    TurnRef(ConversationId("parent"), TurnSeq.First),
    ActsFor.Asker,
    Allowance.Admitted,
    Gates.Asker(1.minute),
    Integrity.Member
  )

  /** Each of kalandra's types, by name, narrowed from [[parent]] briefed at `brief`. */
  private def started(brief: Integrity): Vector[(String, Either[Delegation.Refused, Unit])] =
    Kalandra.AgentTypes.map { (as: Subagent) =>
      AgentType.value(as.agent) -> Delegation
        .narrowed(
          parent,
          as,
          brief,
          Kalandra.Kept,
          Kalandra.Kept,
          kalandra.hosted.map(_.cloned.repository).toSet,
          Cost.Zero
        )
        .map(_ => ())
    }

  val tests = Tests {
    test("a service kalandra declares no cap for is believed untrusted") {
      kalandra.visibility.outputOf(Web) ==> Integrity.Untrusted
    }

    test("a turn at member in kalandra's room may start each of its types") {
      started(Integrity.Member) ==> Vector(
        "grit-coder" -> Right(()),
        "kalandra-coder" -> Right(()),
        "specialist" -> Right(())
      )
    }

    test(
      "a turn briefing a child with what an untrusted service said starts none: each is refused below its type's integrity"
    ) {
      val below = Left(Delegation.Refused.Below(Integrity.Member, Integrity.Untrusted))
      started(Integrity.Member.meet(kalandra.visibility.outputOf(Web))) ==> Vector(
        "grit-coder" -> below,
        "kalandra-coder" -> below,
        "specialist" -> below
      )
    }
  }
}
