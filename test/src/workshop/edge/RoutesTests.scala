package workshop.edge

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
    * ran or refused.
    */
  private final class Recording(answer: Either[CommandError, Answered]) extends PluginCommands {
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
      answer
    }
  }

  private val at = Instant.parse("2026-10-27T12:00:00Z")

  private object Stopped extends Clock {
    def now(): Instant = at
    def millis(): Long = 0
    def sleep(duration: FiniteDuration): Unit = ()
  }

  private val owner = Account.of("workshop", "local/someone").fold(sys.error, identity)
  private val room = Place.read("fs:/home/someone/grit").fold(sys.error, identity)

  private def routed(answer: Either[CommandError, Answered]): (Routes^, Recording^) = {
    val commands = new Recording(answer)
    (new Routes(commands, owner, room, Integrity.Member, Stopped), commands)
  }

  val tests = Tests {
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
