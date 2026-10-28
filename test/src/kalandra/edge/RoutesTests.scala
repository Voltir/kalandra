package kalandra.edge

import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.{Files, Path}
import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import grit.core.clock.Clock
import grit.core.edge.{CommandError, PluginCommands}
import grit.core.identity.Account
import grit.core.place.Place
import grit.core.plugin.CommandOutcome
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
      answer: Either[CommandError, CommandOutcome],
      briefs: Vector[String] = Vector.empty,
      tools: Vector[ToolSchema] = Offered
  ) extends PluginCommands {
    val ran = scala.collection.mutable.ArrayBuffer.empty[Ran]
    def offered: Vector[ToolSchema] = tools
    def run(
        by: Account,
        room: Place,
        believed: Integrity,
        name: ToolName,
        args: ujson.Value,
        at: Instant
    ): Either[CommandError, CommandOutcome] = {
      ran += Ran(by, room, believed, name, args, at)
      if (name == Brief)
        Right(CommandOutcome.Done(briefs.lift(ran.count(_.name == Brief) - 1).getOrElse("")))
      else answer
    }
  }

  private val Brief = ToolName("project_brief")

  /** What [[Recording]] offers unless told otherwise: the commands these tests run. */
  private def Offered: Vector[ToolSchema] =
    Vector("project_show", "project_claim").map(n => ToolSchema(n, s"$n.", keyed()))

  private val at = Instant.parse("2026-10-27T12:00:00Z")

  private object Stopped extends Clock {
    def now(): Instant = at
    def millis(): Long = 0
    def sleep(duration: FiniteDuration): Unit = ()
  }

  // Stored, so kept from the deployment's earlier name: workshop.
  private val owner = Account.of("workshop", "local/someone").fold(sys.error, identity)
  private val room = Place.read("fs:/home/someone/grit").fold(sys.error, identity)

  private def routed(answer: Either[CommandError, CommandOutcome]): (Routes^, Recording^) =
    routedBy(answer, Vector.empty, Files.createTempDirectory("briefs"))

  /** Routes over [[Recording]] commands, writing brief files into `dir`. */
  private def routedBy(
      answer: Either[CommandError, CommandOutcome],
      briefs: Vector[String],
      dir: Path
  ): (Routes^, Recording^) = {
    val commands = new Recording(answer, briefs)
    (
      new Routes(commands, owner, room, Integrity.Member, Stopped, new BriefFiles(dir), Listen),
      commands
    )
  }

  /** Routes over [[Recording]] commands offering `tools`. */
  private def offering(tools: Vector[ToolSchema]): (Routes^, Recording^) = {
    val commands = new Recording(Right(CommandOutcome.Done("shown")), Vector.empty, tools)
    val dir = Files.createTempDirectory("briefs")
    (
      new Routes(commands, owner, room, Integrity.Member, Stopped, new BriefFiles(dir), Listen),
      commands
    )
  }

  /** A JSON-RPC request to `method` with `params`, as `id` 7. */
  private def request(method: String, params: ujson.Value): String =
    ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> 7, "method" -> method, "params" -> params))

  /** `reply`'s status, and its text read as JSON (none when it is not). */
  private def rpc(reply: Reply): (Int, Option[ujson.Value]) =
    (reply.status, scala.util.Try(ujson.read(reply.text)).toOption)

  /** A JSON-RPC response to `id` 7 with `result`. */
  private def result(value: ujson.Value): Option[ujson.Value] =
    Some(ujson.Obj("jsonrpc" -> "2.0", "id" -> 7, "result" -> value))

  /** A JSON-RPC error response to `id` with `code`, whatever its message. */
  private def errorCode(reply: Reply): (Int, Option[(ujson.Value, Double)]) = {
    val (status, json) = rpc(reply)
    (status, json.flatMap(j => scala.util.Try((j("id"), j("error")("code").num)).toOption))
  }

  private def keyed(extra: (String, ujson.Value)*): ujson.Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj.from(Seq("key" -> ujson.Obj("type" -> "string")) ++ extra),
      "required" -> ujson.Arr.from(Seq[ujson.Value]("key") ++ extra.map(e => ujson.Str(e._1)))
    )

  /** The names of the files in `dir`, sorted. */
  private def listed(dir: Path): Vector[String] =
    dir.toFile.list().toVector.sorted

  private val Listen = new InetSocketAddress(InetAddress.getLoopbackAddress, 7317)

  /** `ask` is `answer` for a request as `bin/kalandra` sends it: no `Origin`, `Host` the listen
    * address.
    */
  extension (routes: Routes^) {
    private def ask(method: String, path: String, body: String): Reply =
      routes.answer(method, path, None, Some("127.0.0.1:7317"), body)
  }

  private val claim = """{"key":"m17/s8","role":"builder","for":"Coder_Opus/3F2A"}"""

  val tests = Tests {
    test("a claim that is Done writes the slot's brief file, a refused one writes none") {
      val dir = Files.createTempDirectory("briefs")
      val (routes, commands) = routedBy(Right(CommandOutcome.Done("Claimed.")), Vector("# m17/s8\n"), dir)
      routes.ask("POST", "/command/project_claim", claim) ==> Reply(200, "Claimed.")
      listed(dir) ==> Vector("coder-opus__3f2a.md")
      Files.readString(dir.resolve("coder-opus__3f2a.md")) ==> "# m17/s8\n"
      commands.ran.map(r => (ToolName.value(r.name), r.args)).toVector ==> Vector(
        "project_claim" -> ujson
          .Obj("key" -> "m17/s8", "role" -> "builder", "for" -> "coder-opus/3f2a"),
        "project_brief" -> ujson.Obj("key" -> "m17/s8", "role" -> "builder")
      )
      val refusedDir = Files.createTempDirectory("briefs")
      val (refusing, refused) =
        routedBy(Right(CommandOutcome.Refused("claimed already")), Vector("no"), refusedDir)
      refusing.ask("POST", "/command/project_claim", claim) ==> Reply(409, "claimed already")
      listed(refusedDir) ==> Vector.empty[String]
      refused.ran.map(r => ToolName.value(r.name)).toVector ==> Vector("project_claim")
    }

    test("release deletes the file") {
      val dir = Files.createTempDirectory("briefs")
      val released = """{"released":"m17/s8","for":"coder-opus/3f2a"}"""
      val (routes, commands) = routedBy(Right(CommandOutcome.Done(released)), Vector("# brief\n"), dir)
      routes.ask("POST", "/command/project_claim", claim).status ==> 200
      listed(dir) ==> Vector("coder-opus__3f2a.md")
      routes.ask("POST", "/command/project_release", """{"key":"m17/s8"}""") ==> Reply(
        200,
        "Released m17/s8."
      )
      listed(dir) ==> Vector.empty[String]
      commands.ran.lastOption.map(r => (ToolName.value(r.name), r.args)) ==>
        Some("project_release" -> ujson.Obj("key" -> "m17/s8"))
    }

    test("a release whose answer names no slot keeps the file, and says so") {
      val dir = Files.createTempDirectory("briefs")
      val (routes, _) = routedBy(Right(CommandOutcome.Done("Released m17/s8.")), Vector("# brief\n"), dir)
      routes.ask("POST", "/command/project_claim", claim).status ==> 200
      routes.ask("POST", "/command/project_release", """{"key":"m17/s8"}""") ==> Reply(
        200,
        "Released m17/s8.\nThe brief file stays: the release named no slot"
      )
      listed(dir) ==> Vector("coder-opus__3f2a.md")
    }

    test("the file is replaced whole, never half-written") {
      val dir = Files.createTempDirectory("briefs")
      val texts = Vector("first brief, longer than the second\n", "second\n")
      val (routes, _) = routedBy(Right(CommandOutcome.Done("Claimed.")), texts, dir)
      routes.ask("POST", "/command/project_claim", claim).status ==> 200
      routes.ask("POST", "/command/project_claim", claim).status ==> 200
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
      val (routes, commands) = routed(Right(CommandOutcome.Done("shown")))
      routes.ask("POST", "/command/project_show", """{"key":"m17/s6"}""") ==> Reply(200, "shown")
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
      val (routes, commands) = routed(Right(CommandOutcome.Done("listed")))
      routes.ask("POST", "/command/project_list", "") ==> Reply(200, "listed")
      commands.ran.map(_.args).toVector ==> Vector(ujson.Obj())
    }

    test("a refusal and each command error answer their own status") {
      val name = ToolName("project_move")
      Vector[Either[CommandError, CommandOutcome]](
        Right(CommandOutcome.Refused("not ready")),
        Left(CommandError.Unknown(name)),
        Left(CommandError.Unread(name, "no key")),
        Left(CommandError.NotVouched(owner)),
        Left(CommandError.Store(name, StoreError.Full(20000))),
        Left(CommandError.Unavailable("down"))
      ).map(a => routed(a)._1.ask("POST", "/command/project_move", "{}").status) ==>
        Vector(409, 404, 400, 403, 500, 503)
    }

    test("a body that is not a JSON object runs nothing, and is 400") {
      val (routes, commands) = routed(Right(CommandOutcome.Done("kept")))
      Vector("{not json", "[1]").map(routes.ask("POST", "/command/project_keep", _).status) ==>
        Vector(400, 400)
      commands.ran.size ==> 0
    }

    test("initialize answers the protocol version asked for, or the latest, and offers tools") {
      val (routes, commands) = offering(Vector.empty)
      def initialize(version: String) =
        rpc(
          routes.ask(
            "POST",
            "/mcp",
            request(
              "initialize",
              ujson.Obj(
                "protocolVersion" -> version,
                "capabilities" -> ujson.Obj(),
                "clientInfo" -> ujson.Obj("name" -> "claude-code", "version" -> "2.1.303")
              )
            )
          )
        )
      def answered(version: String) = (
        200,
        result(
          ujson.Obj(
            "protocolVersion" -> version,
            "capabilities" -> ujson.Obj("tools" -> ujson.Obj("listChanged" -> false)),
            "serverInfo" -> ujson.Obj("name" -> "kalandra", "version" -> "1")
          )
        )
      )
      Vector(initialize("2025-03-26"), initialize("1999-01-01")) ==>
        Vector(answered("2025-03-26"), answered("2025-11-25"))
      commands.ran.size ==> 0
    }

    test("a notification is accepted with 202 and no body, and runs nothing") {
      val (routes, commands) = offering(Vector.empty)
      routes.ask(
        "POST",
        "/mcp",
        """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
      ) ==> Reply(202, "")
      commands.ran.size ==> 0
    }

    test("tools/list lists each offered command, each taking its key alone") {
      val (routes, _) = offering(
        Vector(
          ToolSchema("project_show", "Shows an artifact.", keyed()),
          ToolSchema("project_release", "Releases a claim.", keyed())
        )
      )
      routes.ask("POST", "/mcp", request("tools/list", ujson.Obj())).contentType ==> Reply.Json
      rpc(routes.ask("POST", "/mcp", request("tools/list", ujson.Obj()))) ==> (
        200,
        result(
          ujson.Obj(
            "tools" -> ujson.Arr(
              ujson.Obj(
                "name" -> "project_show",
                "description" -> "Shows an artifact.",
                "inputSchema" -> keyed()
              ),
              ujson.Obj(
                "name" -> "project_release",
                "description" -> "Releases a claim.",
                "inputSchema" -> keyed()
              )
            )
          )
        )
      )
    }

    test("tools/call runs the command as /command does, its text the content") {
      val (routes, commands) = routed(Right(CommandOutcome.Done("shown")))
      rpc(
        routes.ask(
          "POST",
          "/mcp",
          request(
            "tools/call",
            ujson.Obj("name" -> "project_show", "arguments" -> ujson.Obj("key" -> "m17/s6"))
          )
        )
      ) ==> (
        200,
        result(
          ujson.Obj(
            "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "shown")),
            "isError" -> false
          )
        )
      )
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
      val dir = Files.createTempDirectory("briefs")
      val (claiming, _) = routedBy(Right(CommandOutcome.Done("Claimed.")), Vector("# m17/s8\n"), dir)
      val (refusing, _) = routed(Right(CommandOutcome.Refused("claimed already")))
      Vector(claiming, refusing).map(r =>
        rpc(
          r.ask(
            "POST",
            "/mcp",
            request(
              "tools/call",
              ujson.Obj("name" -> "project_claim", "arguments" -> ujson.read(claim))
            )
          )
        )._2.map(_("result"))
      ) ==> Vector(
        Some(
          ujson.Obj(
            "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "Claimed.")),
            "isError" -> false
          )
        ),
        Some(
          ujson.Obj(
            "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "claimed already")),
            "isError" -> true
          )
        )
      )
      listed(dir) ==> Vector("coder-opus__3f2a.md")
    }

    test("an unknown method, a call naming no command, or no JSON-RPC: an error, nothing run") {
      val (routes, commands) = routed(Right(CommandOutcome.Done("kept")))
      Vector(
        routes.ask("POST", "/mcp", request("resources/list", ujson.Obj())),
        routes.ask("POST", "/mcp", request("tools/call", ujson.Obj("arguments" -> ujson.Obj()))),
        routes.ask(
          "POST",
          "/mcp",
          request("tools/call", ujson.Obj("name" -> "project_move", "arguments" -> ujson.Obj()))
        ),
        routes.ask(
          "POST",
          "/mcp",
          request("tools/call", ujson.Obj("name" -> "project_show", "arguments" -> ujson.Arr()))
        ),
        routes.ask("POST", "/mcp", "{not json"),
        routes.ask("POST", "/mcp", """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
      ).map(errorCode) ==> Vector(
        (200, Some((ujson.Num(7), -32601.0))),
        (200, Some((ujson.Num(7), -32602.0))),
        (200, Some((ujson.Num(7), -32602.0))),
        (200, Some((ujson.Num(7), -32602.0))),
        (400, Some((ujson.Null, -32700.0))),
        (400, Some((ujson.Null, -32600.0)))
      )
      routes.ask("GET", "/mcp", "").status ==> 405
      commands.ran.size ==> 0
    }

    test("a foreign Origin is refused and runs nothing") {
      val (routes, commands) = routed(Right(CommandOutcome.Done("kept")))
      val show = """{"key":"m17/s6"}"""
      val list = request("tools/list", ujson.Obj())
      Vector(
        "http://evil.example",
        "http://127.0.0.1.evil.example:7317",
        "null",
        "https://localhost:7317"
      )
        .flatMap(o =>
          Vector(
            routes.answer("POST", "/command/project_show", Some(o), Some("127.0.0.1:7317"), show),
            routes.answer("POST", "/mcp", Some(o), Some("127.0.0.1:7317"), list)
          )
        )
        .map(_.status) ==> Vector.fill(8)(403)
      commands.ran.size ==> 0
    }

    test("a rebinding Host is refused and runs nothing") {
      val (routes, commands) = routed(Right(CommandOutcome.Done("kept")))
      val show = """{"key":"m17/s6"}"""
      Vector(Some("evil.example:7317"), Some("127.0.0.1:80"), Some("localhost"), None)
        .flatMap(h =>
          Vector(
            routes.answer("POST", "/command/project_show", None, h, show),
            routes.answer(
              "POST",
              "/mcp",
              Some("http://localhost:7317"),
              h,
              request("ping", ujson.Obj())
            )
          )
        )
        .map(_.status) ==> Vector.fill(8)(403)
      commands.ran.size ==> 0
    }

    test("the CLI's request, no Origin and a loopback Host, still runs") {
      val (routes, commands) = routed(Right(CommandOutcome.Done("shown")))
      val show = """{"key":"m17/s6"}"""
      Vector(
        routes.answer("POST", "/command/project_show", None, Some("127.0.0.1:7317"), show),
        routes.answer("POST", "/command/project_show", None, Some("LocalHost:7317"), show),
        routes.answer(
          "POST",
          "/command/project_show",
          Some("http://[::1]:5173"),
          Some("localhost:7317"),
          show
        )
      ).map(_.status) ==> Vector(200, 200, 200)
      commands.ran.size ==> 3
    }

    test("another path or method runs nothing: 404 and 405") {
      val (routes, commands) = routed(Right(CommandOutcome.Done("kept")))
      Vector(
        routes.ask("POST", "/commands/project_keep", "{}"),
        routes.ask("POST", "/command/", "{}"),
        routes.ask("GET", "/command/project_keep", "")
      ).map(_.status) ==> Vector(404, 404, 405)
      commands.ran.size ==> 0
    }
  }
}
