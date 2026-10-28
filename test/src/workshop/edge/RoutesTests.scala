package workshop.edge

import java.nio.file.{Files, Path}
import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import grit.core.clock.Clock
import grit.core.edge.{CommandError, PluginCommands}
import grit.core.identity.Account
import grit.core.place.Place
import grit.core.plugin.Answered
import grit.core.provider.ToolSchema
import grit.core.store.StoreError
import grit.core.tool.ToolName
import grit.core.visibility.Integrity

import utest.*

object RoutesTests extends TestSuite {

  /** One call of [[PluginCommands.run]], as it was given. */
  private final case class Ran(
      by: Account,
      room: Place,
      believed: Integrity,
      name: ToolName,
      args: ujson.Value,
      at: Instant
  )

  /** Records each run and answers `answer`, as [[PluginCommands.run]] would for a command it
    * ran or refused; but `project_brief`, which answers each of `briefs` in turn as done.
    */
  private final class Recording(
      answer: Either[CommandError, Answered],
      briefs: Vector[String] = Vector.empty
  ) extends PluginCommands {
    val ran = scala.collection.mutable.ArrayBuffer.empty[Ran]
    def offered: Vector[ToolSchema] = Vector.empty
    def run(
        by: Account,
        room: Place,
        believed: Integrity,
        name: ToolName,
        args: ujson.Value,
        at: Instant
    ): Either[CommandError, Answered] = {
      ran += Ran(by, room, believed, name, args, at)
      if (name == Brief)
        Right(Answered.Done(briefs.lift(ran.count(_.name == Brief) - 1).getOrElse("")))
      else answer
    }
  }

  private val Brief = ToolName("project_brief")

  private val at = Instant.parse("2026-10-27T12:00:00Z")

  private object Stopped extends Clock {
    def now(): Instant = at
    def millis(): Long = 0
    def sleep(duration: FiniteDuration): Unit = ()
  }

  private val owner = Account.of("workshop", "local/someone").fold(sys.error, identity)
  private val room = Place.read("fs:/home/someone/grit").fold(sys.error, identity)

  private def routed(answer: Either[CommandError, Answered]): (Routes^, Recording^) =
    routedBy(answer, Vector.empty, Files.createTempDirectory("briefs"))

  /** Routes over [[Recording]] commands, writing brief files into `dir`. */
  private def routedBy(
      answer: Either[CommandError, Answered],
      briefs: Vector[String],
      dir: Path
  ): (Routes^, Recording^) = {
    val commands = new Recording(answer, briefs)
    (new Routes(commands, owner, room, Integrity.Member, Stopped, new BriefFiles(dir)), commands)
  }

  /** The names of the files in `dir`, sorted. */
  private def listed(dir: Path): Vector[String] =
    dir.toFile.list().toVector.sorted

  private val claim = """{"key":"m17/s8","role":"builder","for":"Coder_Opus/3F2A"}"""

  val tests = Tests {
    test("a claim that is Done writes the slot's brief file, a refused one writes none") {
      val dir = Files.createTempDirectory("briefs")
      val (routes, commands) = routedBy(Right(Answered.Done("Claimed.")), Vector("# m17/s8\n"), dir)
      routes.answer("POST", "/command/project_claim", claim) ==> Reply(200, "Claimed.")
      listed(dir) ==> Vector("coder-opus__3f2a.md")
      Files.readString(dir.resolve("coder-opus__3f2a.md")) ==> "# m17/s8\n"
      commands.ran.map(r => (ToolName.value(r.name), r.args)).toVector ==> Vector(
        "project_claim" -> ujson
          .Obj("key" -> "m17/s8", "role" -> "builder", "for" -> "coder-opus/3f2a"),
        "project_brief" -> ujson.Obj("key" -> "m17/s8", "role" -> "builder")
      )
      val refusedDir = Files.createTempDirectory("briefs")
      val (refusing, refused) =
        routedBy(Right(Answered.Refused("claimed already")), Vector("no"), refusedDir)
      refusing.answer("POST", "/command/project_claim", claim) ==> Reply(409, "claimed already")
      listed(refusedDir) ==> Vector.empty[String]
      refused.ran.map(r => ToolName.value(r.name)).toVector ==> Vector("project_claim")
    }

    test("release deletes the file") {
      val dir = Files.createTempDirectory("briefs")
      val (routes, commands) = routedBy(Right(Answered.Done("Done.")), Vector("# brief\n"), dir)
      routes.answer("POST", "/command/project_claim", claim).status ==> 200
      listed(dir) ==> Vector("coder-opus__3f2a.md")
      routes.answer(
        "POST",
        "/command/project_release",
        """{"key":"m17/s8","for":"Coder_Opus/3F2A"}"""
      ) ==> Reply(200, "Done.")
      listed(dir) ==> Vector.empty[String]
      commands.ran.lastOption.map(r => (ToolName.value(r.name), r.args)) ==>
        Some("project_release" -> ujson.Obj("key" -> "m17/s8"))
      val before = commands.ran.size
      routes.answer("POST", "/command/project_release", """{"key":"m17/s8"}""").status ==> 400
      commands.ran.size ==> before
    }

    test("the file is replaced whole, never half-written") {
      val dir = Files.createTempDirectory("briefs")
      val texts = Vector("first brief, longer than the second\n", "second\n")
      val (routes, _) = routedBy(Right(Answered.Done("Claimed.")), texts, dir)
      routes.answer("POST", "/command/project_claim", claim).status ==> 200
      routes.answer("POST", "/command/project_claim", claim).status ==> 200
      listed(dir) ==> Vector("coder-opus__3f2a.md")
      Files.readString(dir.resolve("coder-opus__3f2a.md")) ==> "second\n"
    }

    test("a slot is lower-cased, each character outside a slug part becoming -") {
      Vector("Explore", "coder-opus/3F2A", "Coder_Opus/3f2a", "_x", "a.b:c").map(
        BriefFiles.slot
      ) ==>
        Vector("explore", "coder-opus/3f2a", "coder-opus/3f2a", "x", "a-b-c")
    }

    test(
      "POST /command/<name> runs it as the owner in the room, believed as given, its body as arguments"
    ) {
      val (routes, commands) = routed(Right(Answered.Done("shown")))
      routes.answer("POST", "/command/project_show", """{"key":"m17/s6"}""") ==> Reply(200, "shown")
      commands.ran.toVector ==> Vector(
        Ran(
          owner,
          room,
          Integrity.Member,
          ToolName("project_show"),
          ujson.Obj("key" -> "m17/s6"),
          at
        )
      )
    }

    test("an empty body is no arguments") {
      val (routes, commands) = routed(Right(Answered.Done("listed")))
      routes.answer("POST", "/command/project_list", "") ==> Reply(200, "listed")
      commands.ran.map(_.args).toVector ==> Vector(ujson.Obj())
    }

    test("a refusal and each command error answer their own status") {
      val name = ToolName("project_move")
      Vector[Either[CommandError, Answered]](
        Right(Answered.Refused("not ready")),
        Left(CommandError.Unknown(name)),
        Left(CommandError.Unread(name, "no key")),
        Left(CommandError.NotVouched(owner)),
        Left(CommandError.Store(name, StoreError.Full(20000))),
        Left(CommandError.Unavailable("down"))
      ).map(a => routed(a)._1.answer("POST", "/command/project_move", "{}").status) ==>
        Vector(409, 404, 400, 403, 500, 503)
    }

    test("a body that is not a JSON object runs nothing, and is 400") {
      val (routes, commands) = routed(Right(Answered.Done("kept")))
      Vector("{not json", "[1]").map(routes.answer("POST", "/command/project_keep", _).status) ==>
        Vector(400, 400)
      commands.ran.size ==> 0
    }

    test("another path or method runs nothing: 404 and 405") {
      val (routes, commands) = routed(Right(Answered.Done("kept")))
      Vector(
        routes.answer("POST", "/commands/project_keep", "{}"),
        routes.answer("POST", "/command/", "{}"),
        routes.answer("GET", "/command/project_keep", "")
      ).map(_.status) ==> Vector(404, 404, 405)
      commands.ran.size ==> 0
    }
  }
}
