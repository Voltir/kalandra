package kalandra.edge

import java.time.Instant

import grit.commands.declare.Ran
import grit.core.edge.{CommandError, Watched}
import grit.core.id.DocumentVersionId
import grit.core.plugin.CommandOutcome
import grit.core.tool.ToolName
import grit.core.visibility.Integrity
import grit.projects.artifact.{Artifact, ArtifactHead, ArtifactJson, Body}
import grit.projects.name.{ArtifactKey, KindName, StateName}
import grit.projects.plugin.Projects
import grit.projects.render.ProjectMarkdown

import kalandra.method.GritMethod
import utest.*

object OwnCommandsTests extends TestSuite {

  private def ok[A](e: Either[String, A]): A = e.fold(sys.error, identity)

  private def version(n: Long) = DocumentVersionId.of(n).getOrElse(sys.error("no version"))

  /** An artifact of the project `m17`, as `project_show` gives it kept as version `n`. */
  private def shownAs(local: String, kind: String, state: String, body: String, n: Long): String =
    ProjectMarkdown.shown(
      Artifact(
        ArtifactHead(
          ok(ArtifactKey.read(s"m17/$local")),
          ok(KindName.of(kind)),
          ok(StateName.of(state)),
          s"The $local",
          None,
          Vector.empty,
          None
        ),
        ok(Body.read(body))
      ),
      version(n)
    )

  private val below = "You make one change.\n\n## Rules\n\n- Never push."

  /** The section `m17/standing`, kept as version 7, its cap `cap` characters. */
  private def section(cap: Int): String =
    shownAs(
      "standing",
      "section",
      "current",
      s"**Cap: $cap characters** below.\n\n***\n\n$below",
      7
    )

  /** The amendment `m17/amend`, benched: its diff adds a rule, against version 7. */
  private val amendment = shownAs(
    "amend",
    "amendment",
    "benched",
    "## Diff\n\nAgainst [the standing](artifact:m17/standing) at version 7.\n\n" +
      "```old\n- Never push.\n```\n\n```new\n- Never push.\n- Red first.\n```",
    3
  )

  /** A [[Ran]] that keeps each call, in order, and answers `project_show` with the text `shows`
    * holds for its key, refusing any other key as the plugin does, and every other command
    * `answer`.
    */
  private final class Scripted(
      shows: Map[String, String],
      answer: Either[CommandError, CommandOutcome] = Right(CommandOutcome.Done("Kept. Moved."))
  ) {
    val calls = scala.collection.mutable.ArrayBuffer.empty[(String, ujson.Value)]
    val ran: Ran = (name, args) => {
      calls += ((ToolName.value(name), args))
      if (ToolName.value(name) != "project_show") answer
      else {
        val key = args.obj.get("key").flatMap(_.strOpt).getOrElse("")
        Right(shows.get(key) match {
          case Some(text) => CommandOutcome.Done(text)
          case None =>
            CommandOutcome.Refused(s"project ${key.takeWhile(_ != '/')} has no artifact $key")
        })
      }
    }
    def names: Vector[String] = calls.map(_._1).toVector
  }

  private def amending(cap: Int) =
    new Scripted(Map("m17/amend" -> amendment, "m17/standing" -> section(cap)))

  private def args(json: String): ujson.Obj = ujson.read(json).obj

  private val amendKey = ok(ArtifactKey.read("m17/amend"))

  /** What a move to adopted is refused with: the plugin's wording, then how a person adopts. */
  private val guarded =
    s"${Projects.guardedMove(amendKey, GritMethod.Amendment, GritMethod.Adopted)} " +
      OwnCommands.Adopting

  private val review = ok(StateName.of("review"))
  private val merged = ok(StateName.of("merged"))

  val tests = Tests {
    test(
      "a move to adopted is refused in the plugin's words, then the way a person adopts, and runs nothing"
    ) {
      val s = amending(100)
      (
        OwnCommands.move.run(s.ran, args("""{"key":"m17/amend","state":"adopted"}""")),
        s.names
      ) ==> (Right(CommandOutcome.Refused(guarded)), Vector())
    }

    test("a batch with a step to adopted, after a harmless one, is refused so, and runs nothing") {
      val s = amending(100)
      val steps =
        """[{"command":"keep","key":"m17/note","title":"N","body":"B."},""" +
          """{"command":"move","key":"m17/amend","state":"adopted"}]"""
      (
        OwnCommands.batch.run(s.ran, args(s"""{"project":"m17","steps":$steps}""")),
        s.names
      ) ==> (Right(CommandOutcome.Refused(guarded)), Vector())
    }

    test("any other move or batch is run by the plugin as it was sent, and answers what it did") {
      val s = new Scripted(Map.empty, Right(CommandOutcome.Refused("not that way")))
      val move = args("""{"key":"m17/amend","state":"benched"}""")
      val batch =
        args("""{"project":"m17","steps":[{"command":"move","key":"m17/a","state":"done"}]}""")
      (
        OwnCommands.move.run(s.ran, move),
        OwnCommands.batch.run(s.ran, batch),
        s.calls.toVector
      ) ==> (
        Right(CommandOutcome.Refused("not that way")),
        Right(CommandOutcome.Refused("not that way")),
        Vector[(String, ujson.Value)](("project_move", move), ("project_batch", batch))
      )
    }

    test(
      "adopt batches the section kept at the version the diff is against, then the amendment adopted"
    ) {
      val s = amending(100)
      (
        OwnCommands.adopt.run(s.ran, args("""{"key":"m17/amend"}""")),
        s.calls.toVector
      ) ==> (
        Right(
          CommandOutcome.Done(
            "Adopted m17/amend: m17/standing kept again, its standing text 58 characters of " +
              "its cap 100.\nKept. Moved."
          )
        ),
        Vector[(String, ujson.Value)](
          ("project_show", ujson.Obj("key" -> "m17/amend")),
          ("project_show", ujson.Obj("key" -> "m17/standing")),
          (
            "project_batch",
            ujson.Obj(
              "project" -> "m17",
              "steps" -> ujson.Arr(
                ujson.Obj(
                  "command" -> "keep",
                  "key" -> "m17/standing",
                  "title" -> "The standing",
                  "body" -> s"**Cap: 100 characters** below.\n\n***\n\n$below\n- Red first.",
                  "version" -> 7
                ),
                ujson.Obj("command" -> "move", "key" -> "m17/amend", "state" -> "adopted")
              )
            )
          )
        )
      )
    }

    test("an amendment over its section's cap is refused, and nothing is batched") {
      val s = amending(50)
      (OwnCommands.adopt.run(s.ran, args("""{"key":"m17/amend"}""")), s.names) ==> (
        Right(
          CommandOutcome.Refused(
            "m17/standing's standing text would be 58 characters, over its cap of 50: cut 8"
          )
        ),
        Vector("project_show", "project_show")
      )
    }

    test(
      "adopt of an amendment whose section is missing is refused as the show was, nothing batched"
    ) {
      val s = new Scripted(Map("m17/amend" -> amendment))
      (OwnCommands.adopt.run(s.ran, args("""{"key":"m17/amend"}""")), s.names) ==> (
        Right(CommandOutcome.Refused("project m17 has no artifact m17/standing")),
        Vector("project_show", "project_show")
      )
    }

    test("a show that is not an artifact's is a store failure, nothing batched") {
      val s = new Scripted(Map("m17/amend" -> "no front matter"))
      val adopted = OwnCommands.adopt.run(s.ran, args("""{"key":"m17/amend"}"""))
      assert(
        adopted match {
          case Left(CommandError.Store(n, _)) => ToolName.value(n) == "project_adopt"
          case _ => false
        },
        s.names == Vector("project_show")
      )
    }

    test("standing answers a section's text, or an amendment's candidate's, and keeps nothing") {
      val s = amending(100)
      (
        OwnCommands.standing.run(s.ran, args("""{"key":"m17/standing"}""")),
        OwnCommands.standing.run(s.ran, args("""{"key":"m17/amend"}""")),
        s.names.toSet
      ) ==> (
        Right(CommandOutcome.Done(below + "\n")),
        Right(CommandOutcome.Done(below + "\n- Red first.\n")),
        Set("project_show")
      )
    }

    test("adopt or standing with no key is unread, and runs nothing") {
      val s = amending(100)
      (
        OwnCommands.adopt.run(s.ran, args("{}")),
        OwnCommands.standing.run(s.ran, args("""{"title":"m17/standing"}""")),
        s.names
      ) ==> (
        Left(CommandError.Unread(ToolName("project_adopt"), OwnCommands.KeyAlone)),
        Left(CommandError.Unread(ToolName("project_standing"), OwnCommands.KeyAlone)),
        Vector()
      )
    }

    test("every own command asks a member's word, two guarding move and batch, two added") {
      OwnCommands.all.map(o => (ToolName.value(o.name), o.least, o.guards)) ==> Vector(
        ("project_move", Integrity.Member, true),
        ("project_batch", Integrity.Member, true),
        ("project_standing", Integrity.Member, false),
        ("project_adopt", Integrity.Member, false)
      )
    }

    test("the plugin offers the commands guarded, and none of those added") {
      val names = GritMethod.declared.left
        .map(_.mkString)
        .flatMap(Projects.of(_, GritMethod.Name).left.map(_.toString))
        .map(_.commands.map(c => ToolName.value(c.spec.name)).toSet)
        .fold(sys.error, identity)
      OwnCommands.all.map(o => (ToolName.value(o.name), names.contains(ToolName.value(o.name)))) ==>
        Vector(
          ("project_move", true),
          ("project_batch", true),
          ("project_standing", false),
          ("project_adopt", false)
        )
    }

    test("the holding watch reads an artifact and its states, and nothing without an artifact") {
      val read = OwnCommands.reaching.read
      (
        OwnCommands.reaching.keys,
        read(args("""{"artifact":"grit/plan-x","state":["review","merged"]}""")),
        read(args("""{"state":["review"]}"""))
      ) ==> (
        Set("artifact", "state"),
        Some(Projects.reaching(ok(ArtifactKey.read("grit/plan-x")), Set(review, merged))),
        None
      )
    }

    test("a holding watch with a bad key, no states, or a state that is no name is refused") {
      val read = OwnCommands.reaching.read
      Vector(
        """{"artifact":"no-slash","state":["review"]}""",
        """{"artifact":"grit/plan-x","state":[]}""",
        """{"artifact":"grit/plan-x"}""",
        """{"artifact":"grit/plan-x","state":[3]}"""
      ).map(b => read(args(b)).map(_.isLeft)) ==> Vector.fill(4)(Some(true))
    }

    test("a met holding watch says the artifact, the states it is in, its version and when") {
      val at = Instant.parse("2026-10-27T12:00:00Z")
      val watch = ok(Projects.reaching(ok(ArtifactKey.read("grit/plan-x")), Set(review, merged)))
      OwnCommands.reaching.met(
        watch,
        Watched.Held(version(7), Set(ArtifactJson.stateRef(review)), at)
      ) ==> s"grit/plan-x is in review (version 7, written $at)"
    }
  }
}
