package kalandra.edge

import java.time.Instant

import grit.commands.declare.RunCommand
import grit.core.edge.{CommandError, Watched}
import grit.core.id.{DocKey, DocumentVersionId}
import grit.core.plugin.CommandOutcome
import grit.core.tool.ToolName
import grit.core.visibility.Integrity
import grit.projects.artifact.{Artifact, ArtifactHead, ArtifactJson, Body, Rank}
import grit.projects.name.{ArtifactKey, ArtifactRef, Handle, KindName, StateName}
import grit.projects.plugin.Projects
import grit.projects.render.ProjectMarkdown

import utest.*

object OwnCommandsTests extends TestSuite {

  private def ok[A](e: Either[String, A]): A = e.fold(sys.error, identity)

  private def version(n: Long) = DocumentVersionId.of(n).getOrElse(sys.error("no version"))

  /** The number of each artifact of the project `m17` by its handle. */
  private val Numbers: Map[String, Int] = Map("amend" -> 1, "standing" -> 2)

  /** An artifact of the project `m17`, as `project_show` gives it kept as version `n`. */
  private def shownAs(local: String, kind: String, state: String, body: String, n: Long): String =
    ProjectMarkdown.shown(
      Artifact(
        ArtifactHead(
          ok(ArtifactKey.read(s"m17/${Numbers.getOrElse(local, 0)}")),
          Some(ok(Handle.of(local))),
          ok(KindName.of(kind)),
          ok(StateName.of(state)),
          s"The $local",
          None,
          Rank.Middle,
          Vector.empty,
          None
        ),
        ok(Body.read(body, ArtifactRef.id))
      ),
      version(n)
    )

  private val below = "You make one change.\n\n## Rules\n\n- Never push."

  /** The section `m17/2`, kept as version 7, its cap `cap` characters. */
  private def section(cap: Int): String =
    shownAs(
      "standing",
      "section",
      "current",
      s"**Cap: $cap characters** below.\n\n***\n\n$below",
      7
    )

  /** The amendment `m17/1`, benched: its diff adds a rule, against version 7. */
  private val amendment = shownAs(
    "amend",
    "amendment",
    "benched",
    "## Diff\n\nAgainst [the standing](artifact:m17/2) at version 7.\n\n" +
      "```old\n- Never push.\n```\n\n```new\n- Never push.\n- Red first.\n```",
    3
  )

  /** A [[RunCommand]] that keeps each call, in order, and answers `project_show` with the text `shows`
    * holds for its key, refusing any other key as the plugin does, and every other command
    * `answer`.
    */
  private final class Scripted(
      shows: Map[String, String],
      answer: Either[CommandError, CommandOutcome] = Right(CommandOutcome.Done("Kept. Moved."))
  ) {
    val calls = scala.collection.mutable.ArrayBuffer.empty[(String, ujson.Value)]
    val ran: RunCommand = (name, args) => {
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
    new Scripted(Map("m17/1" -> amendment, "m17/2" -> section(cap)))

  private def args(json: String): ujson.Obj = ujson.read(json).obj

  private val review = ok(StateName.of("review"))
  private val merged = ok(StateName.of("merged"))

  val tests = Tests {
    test(
      "adopt batches the section kept at the version the diff is against, then the amendment adopted"
    ) {
      val s = amending(100)
      (
        OwnCommands.adopt.run(s.ran, args("""{"key":"m17/1"}""")),
        s.calls.toVector
      ) ==> (
        Right(
          CommandOutcome.Done(
            "Adopted m17/1: m17/2 kept again, its standing text 58 characters of " +
              "its cap 100.\nKept. Moved."
          )
        ),
        Vector[(String, ujson.Value)](
          ("project_show", ujson.Obj("key" -> "m17/1")),
          ("project_show", ujson.Obj("key" -> "m17/2")),
          (
            "project_batch",
            ujson.Obj(
              "project" -> "m17",
              "steps" -> ujson.Arr(
                ujson.Obj(
                  "command" -> "keep",
                  "key" -> "m17/2",
                  "title" -> "The standing",
                  "body" -> s"**Cap: 100 characters** below.\n\n***\n\n$below\n- Red first.",
                  "version" -> 7
                ),
                ujson.Obj("command" -> "move", "key" -> "m17/1", "state" -> "adopted")
              )
            )
          )
        )
      )
    }

    test("an amendment over its section's cap is refused, and nothing is batched") {
      val s = amending(50)
      (OwnCommands.adopt.run(s.ran, args("""{"key":"m17/1"}""")), s.names) ==> (
        Right(
          CommandOutcome.Refused(
            "m17/2's standing text would be 58 characters, over its cap of 50: cut 8"
          )
        ),
        Vector("project_show", "project_show")
      )
    }

    test(
      "adopt of an amendment whose section is missing is refused as the show was, nothing batched"
    ) {
      val s = new Scripted(Map("m17/1" -> amendment))
      (OwnCommands.adopt.run(s.ran, args("""{"key":"m17/1"}""")), s.names) ==> (
        Right(CommandOutcome.Refused("project m17 has no artifact m17/2")),
        Vector("project_show", "project_show")
      )
    }

    test("a show that is not an artifact's is a store failure, nothing batched") {
      val s = new Scripted(Map("m17/1" -> "no front matter"))
      val adopted = OwnCommands.adopt.run(s.ran, args("""{"key":"m17/1"}"""))
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
        OwnCommands.standing.run(s.ran, args("""{"key":"m17/2"}""")),
        OwnCommands.standing.run(s.ran, args("""{"key":"m17/1"}""")),
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
        OwnCommands.standing.run(s.ran, args("""{"title":"m17/2"}""")),
        s.names
      ) ==> (
        Left(CommandError.Unread(ToolName("project_adopt"), OwnCommands.KeyAlone)),
        Left(CommandError.Unread(ToolName("project_standing"), OwnCommands.KeyAlone)),
        Vector()
      )
    }

    test("standing asks a member's word and adopt an authority's, both added, none guarding") {
      OwnCommands.all.map(o => (ToolName.value(o.name), o.least, o.guards)) ==> Vector(
        ("project_standing", Integrity.Member, false),
        ("project_adopt", Integrity.Authority, false)
      )
    }

    test(
      "the holding watch watches the key project_stored gives an artifact's, and nothing without an artifact"
    ) {
      val stored = new Scripted(Map.empty, Right(CommandOutcome.Done("grit/412")))
      (
        OwnCommands.reaching.keys,
        OwnCommands.reaching.read(
          stored.ran,
          args("""{"artifact":"grit/plan-x","state":["review","merged"]}""")
        ),
        stored.calls.toVector,
        OwnCommands.reaching.read(stored.ran, args("""{"state":["review"]}"""))
      ) ==> (
        Set("artifact", "state"),
        Right(Some(ok(Projects.reaching(ok(DocKey.of("grit/412")), Set(review, merged))))),
        Vector("project_stored" -> ujson.Obj("key" -> "grit/plan-x")),
        Right(None)
      )
    }

    test(
      "a holding watch with a bad key, no states, a state that is no name, or a key project_stored refuses is unread, saying why"
    ) {
      val stored = new Scripted(
        Map.empty,
        Right(CommandOutcome.Refused("project grit has no artifact named plan-y"))
      )
      Vector(
        """{"artifact":"no-slash","state":["review"]}""",
        """{"artifact":"grit/plan-x","state":[]}""",
        """{"artifact":"grit/plan-x"}""",
        """{"artifact":"grit/plan-x","state":[3]}""",
        """{"artifact":"grit/plan-y","state":["review"]}"""
      ).map(b =>
        OwnCommands.reaching.read(stored.ran, args(b)).left.map {
          case CommandError.Unread(name, why) => s"${ToolName.value(name)}: $why"
          case other => other.toString
        }
      ) ==> Vector(
        Left("project_stored: an artifact's key has no '/': no-slash"),
        Left("project_stored: a watch names a ref to hold"),
        Left("project_stored: state is a list of states' names"),
        Left("project_stored: 3 is no state's name"),
        Left("project_stored: project grit has no artifact named plan-y")
      )
    }

    test("a met holding watch says the artifact, the states it is in, its version and when") {
      val at = Instant.parse("2026-10-27T12:00:00Z")
      val watch = ok(Projects.reaching(ok(DocKey.of("grit/plan-x")), Set(review, merged)))
      OwnCommands.reaching.met(
        watch,
        Watched.Held(version(7), Set(ArtifactJson.stateRef(review)), at)
      ) ==> s"grit/plan-x is in review (version 7, written $at)"
    }
  }
}
