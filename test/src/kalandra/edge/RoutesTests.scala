package kalandra.edge

import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.{Files, Path}
import java.time.Instant

import scala.concurrent.duration.*

import grit.core.clock.Clock
import grit.core.edge.{CommandError, EdgeWatch, PluginCommands, WatchError, WatchFor, Watched}
import grit.core.id.{ClaimHolder, DocumentVersionId, SessionId}
import grit.core.identity.Account
import grit.core.place.Place
import grit.core.plugin.CommandOutcome
import grit.core.provider.ToolSchema
import grit.core.session.Event
import grit.core.store.StoreError
import grit.core.tool.ToolName
import grit.core.visibility.Integrity
import grit.projects.artifact.ArtifactJson
import grit.projects.name.{ArtifactKey, StateName}
import grit.projects.plugin.Projects

import kalandra.capture.Spooled
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
    * ran or refused; but `project_brief`, which answers each of `briefs` in turn as done, and
    * `project_claims`, which answers `claims` as done.
    */
  private final class Recording(
      answer: Either[CommandError, CommandOutcome],
      briefs: Vector[String] = Vector.empty,
      tools: Vector[ToolSchema] = Offered,
      claims: String = """{"claims":[]}"""
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
      else if (ToolName.value(name) == "project_claims") Right(CommandOutcome.Done(claims))
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
      dir: Path,
      claims: String = """{"claims":[]}"""
  ): (Routes^, Recording^) = {
    val commands = new Recording(answer, briefs, claims = claims)
    (
      new Routes(
        commands,
        EdgeWatch.Refusing,
        owner,
        room,
        Integrity.Member,
        Stopped,
        new BriefFiles(dir),
        Listen
      ),
      commands
    )
  }

  /** Routes over [[Recording]] commands offering `tools`. */
  private def offering(tools: Vector[ToolSchema]): (Routes^, Recording^) = {
    val commands = new Recording(Right(CommandOutcome.Done("shown")), Vector.empty, tools)
    val dir = Files.createTempDirectory("briefs")
    (
      new Routes(
        commands,
        EdgeWatch.Refusing,
        owner,
        room,
        Integrity.Member,
        Stopped,
        new BriefFiles(dir),
        Listen
      ),
      commands
    )
  }

  /** Records each watch and answers `answer`, as [[EdgeWatch.until]] would once it came to it. */
  private final class Watching(answer: Either[WatchError, Watched]) extends EdgeWatch {
    val asked =
      scala.collection.mutable.ArrayBuffer.empty[(Account, Place, WatchFor, FiniteDuration)]
    def until(
        by: Account,
        room: Place,
        what: WatchFor,
        within: FiniteDuration
    ): Either[WatchError, Watched] = {
      asked += ((by, room, what, within))
      answer
    }
  }

  /** Routes over a [[Watching]] watch answering `answer`. */
  private def watchedBy(answer: Either[WatchError, Watched]): (Routes^, Watching^) = {
    val watching = new Watching(answer)
    val commands = new Recording(Right(CommandOutcome.Done("shown")))
    val dir = Files.createTempDirectory("briefs")
    (
      new Routes(
        commands,
        watching,
        owner,
        room,
        Integrity.Member,
        Stopped,
        new BriefFiles(dir),
        Listen
      ),
      watching
    )
  }

  private def ok[A](e: Either[String, A]): A = e.fold(sys.error, identity)

  private val review = ok(StateName.of("review"))
  private val merged = ok(StateName.of("merged"))

  private val fixer = Place.read("fs:/work/fixer").fold(sys.error, identity)

  private val committed = Watched.Committed(
    ok(SessionId.of(Spooled.Harness, "s-1")),
    at,
    ok(Event.Shape.committed(fixer, "abc123", Some("the subject")))
  )

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

  private val claim = """{"key":"m17/s8","role":"builder","holder":"Coder_Opus/3F2A"}"""

  val tests = Tests {
    test("a claim that is Done writes the slot's brief file, a refused one writes none") {
      val dir = Files.createTempDirectory("briefs")
      val (routes, commands) =
        routedBy(Right(CommandOutcome.Done("Claimed.")), Vector("# m17/s8\n"), dir)
      routes.ask("POST", "/command/project_claim", claim) ==> Reply(200, "Claimed.")
      listed(dir) ==> Vector("coder-opus__3f2a.md")
      Files.readString(dir.resolve("coder-opus__3f2a.md")) ==> "# m17/s8\n"
      commands.ran.map(r => (ToolName.value(r.name), r.args)).toVector ==> Vector(
        "project_claim" -> ujson
          .Obj("key" -> "m17/s8", "role" -> "builder", "holder" -> "coder-opus/3f2a"),
        "project_brief" -> ujson.Obj("key" -> "m17/s8", "role" -> "builder", "chars" -> 9500)
      )
      val refusedDir = Files.createTempDirectory("briefs")
      val (refusing, refused) =
        routedBy(Right(CommandOutcome.Refused("claimed already")), Vector("no"), refusedDir)
      refusing.ask("POST", "/command/project_claim", claim) ==> Reply(409, "claimed already")
      listed(refusedDir) ==> Vector.empty[String]
      refused.ran.map(r => ToolName.value(r.name)).toVector ==> Vector("project_claim")
    }

    test("a keep or move that is Done rewrites each live claim's brief file there is") {
      val dir = Files.createTempDirectory("briefs")
      def held(key: String, slot: String): ujson.Value =
        ujson.Obj("key" -> key, "role" -> "builder", "holder" -> slot, "by" -> "someone")
      val live = ujson.Obj(
        "claims" -> ujson.Arr(held("m17/s8", "coder-opus/3f2a"), held("m17/s9", "elsewhere"))
      )
      val (routes, commands) = routedBy(
        Right(CommandOutcome.Done("Done.")),
        Vector("# claimed\n", "# kept\n", "# moved\n"),
        dir,
        live.render()
      )
      routes.ask("POST", "/command/project_claim", claim).status ==> 200
      routes.ask("POST", "/command/project_keep", """{"key":"m17/plan","text":"x"}""") ==>
        Reply(200, "Done.")
      val kept = Files.readString(dir.resolve("coder-opus__3f2a.md"))
      routes.ask("POST", "/command/project_move", """{"key":"m17/s9","state":"done"}""") ==>
        Reply(200, "Done.")
      (kept, Files.readString(dir.resolve("coder-opus__3f2a.md")), listed(dir)) ==>
        ("# kept\n", "# moved\n", Vector("coder-opus__3f2a.md"))
      commands.ran.map(r => (ToolName.value(r.name), r.args)).toVector.drop(2) ==> Vector(
        "project_keep" -> ujson.Obj("key" -> "m17/plan", "text" -> "x"),
        "project_claims" -> ujson.Obj("project" -> "m17"),
        "project_brief" -> ujson.Obj("key" -> "m17/s8", "role" -> "builder", "chars" -> 9500),
        "project_move" -> ujson.Obj("key" -> "m17/s9", "state" -> "done"),
        "project_claims" -> ujson.Obj("project" -> "m17"),
        "project_brief" -> ujson.Obj("key" -> "m17/s8", "role" -> "builder", "chars" -> 9500)
      )
    }

    test("a keep refused rewrites nothing") {
      val dir = Files.createTempDirectory("briefs")
      val (routes, commands) =
        routedBy(Right(CommandOutcome.Refused("no such state")), Vector("# brief\n"), dir)
      routes.ask("POST", "/command/project_keep", """{"key":"m17/plan"}""") ==>
        Reply(409, "no such state")
      commands.ran.map(r => ToolName.value(r.name)).toVector ==> Vector("project_keep")
    }

    test("a batch that is Done rewrites its project's brief files; a dry one rewrites none") {
      val dir = Files.createTempDirectory("briefs")
      val live = ujson.Obj(
        "claims" -> ujson.Arr(
          ujson.Obj("key" -> "m17/s8", "role" -> "builder", "holder" -> "coder-opus/3f2a")
        )
      )
      val (routes, commands) = routedBy(
        Right(CommandOutcome.Done("Done.")),
        Vector("# claimed\n", "# batched\n"),
        dir,
        live.render()
      )
      routes.ask("POST", "/command/project_claim", claim).status ==> 200
      val steps = """[{"command":"move","key":"m17/s9","state":"done"}]"""
      routes.ask("POST", "/command/project_batch", s"""{"project":"m17","steps":$steps,"dry":true}""") ==>
        Reply(200, "Done.")
      val dry = Files.readString(dir.resolve("coder-opus__3f2a.md"))
      routes.ask("POST", "/command/project_batch", s"""{"project":"m17","steps":$steps}""") ==>
        Reply(200, "Done.")
      (dry, Files.readString(dir.resolve("coder-opus__3f2a.md"))) ==> ("# claimed\n", "# batched\n")
      commands.ran.map(r => ToolName.value(r.name)).toVector.drop(2) ==> Vector(
        "project_batch",
        "project_batch",
        "project_claims",
        "project_brief"
      )
    }

    test("release deletes the slot's brief and both its digests") {
      val dir = Files.createTempDirectory("briefs")
      val released = """{"released":"m17/s8","holder":"coder-opus/3f2a"}"""
      val (routes, commands) =
        routedBy(Right(CommandOutcome.Done(released)), Vector("# brief\n"), dir)
      routes.ask("POST", "/command/project_claim", claim).status ==> 200
      new BriefFiles(dir).writeDigests("coder-opus/3f2a", _.toString) ==> Right(true)
      listed(dir).size ==> 3
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
      val (routes, _) =
        routedBy(Right(CommandOutcome.Done("Released m17/s8.")), Vector("# brief\n"), dir)
      routes.ask("POST", "/command/project_claim", claim).status ==> 200
      routes.ask("POST", "/command/project_release", """{"key":"m17/s8"}""") ==> Reply(
        200,
        "Released m17/s8.\nThe brief file stays: the release named no slot"
      )
      listed(dir) ==> Vector("coder-opus__3f2a.md")
    }

    test("a digest is written for each window beside a brief there is, and none without one") {
      val dir = Files.createTempDirectory("briefs")
      val files = new BriefFiles(dir)
      val none = files.writeDigests("coder-opus/3f2a", _.toString)
      val noneListed = listed(dir)
      ok(files.write("coder-opus/3f2a", "# brief\n"))
      val written = files.writeDigests("coder-opus/3f2a", _.toString)
      (
        none,
        noneListed,
        written,
        listed(dir),
        Files.readString(dir.resolve("coder-opus__3f2a.digest.md")),
        Files.readString(dir.resolve("coder-opus__3f2a.digest-said.md"))
      ) ==> (
        Right(false),
        Vector.empty[String],
        Right(true),
        Vector(
          "coder-opus__3f2a.digest-said.md",
          "coder-opus__3f2a.digest.md",
          "coder-opus__3f2a.md"
        ),
        "HoldsLastSaid",
        "LacksLastSaid"
      )
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
      val subagent =
        "coder-opus/051b9d0bda5b1cb78812ed53b34b66581629b8dd/faafcbf0-3592-4c99-8f14-21889a88798e"
      Vector("Explore", "coder-opus/3F2A", "Coder_Opus/3f2a", "_x", "a.b:c", subagent).map(
        BriefFiles.slot
      ) ==>
        Vector("explore", "coder-opus/3f2a", "coder-opus/3f2a", "x", "a-b-c", subagent)
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

    test(
      "initialize answers the protocol version asked for, or the latest, offers tools, and says a project artifact is not a claude.ai Artifact"
    ) {
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
            "serverInfo" -> ujson.Obj("name" -> "kalandra", "version" -> "1"),
            "instructions" -> ("kalandra keeps grit's projects. An artifact here is a project " +
              "artifact, a versioned record of a grit project, not a claude.ai Artifact.")
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
      val (claiming, _) =
        routedBy(Right(CommandOutcome.Done("Claimed.")), Vector("# m17/s8\n"), dir)
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

    test("a watch for an artifact's states waits as the owner in the room, 5 minutes unless told") {
      val version = DocumentVersionId.of(7L).getOrElse(sys.error("no version"))
      val (routes, watching) =
        watchedBy(Right(Watched.Held(version, Set(ArtifactJson.stateRef(review)), at)))
      val reply =
        routes.ask("POST", "/wait", """{"artifact":"grit/plan-x","state":["review","merged"]}""")
      val reaching = ok(Projects.reaching(ok(ArtifactKey.read("grit/plan-x")), Set(review, merged)))
      assert(
        watching.asked.toVector == Vector((owner, room, reaching, 5.minutes)),
        reply == Reply(200, s"grit/plan-x is in review (version 7, written $at)")
      )
    }

    test("a watch for a commit names a worktree or a slot, since now unless it says when") {
      val (routes, watching) = watchedBy(Right(committed))
      val replies = Vector(
        """{"commit":{"worktree":"/work/fixer"},"within":60}""",
        """{"commit":{"slot":"fixer"},"since":"2026-10-27T11:00:00Z"}"""
      ).map(routes.ask("POST", "/wait", _))
      val since = Instant.parse("2026-10-27T11:00:00Z")
      assert(
        watching.asked.map(a => (a._3, a._4)).toVector == Vector(
          (WatchFor.Committed(WatchFor.Worker.At(fixer), at), 60.seconds),
          (WatchFor.Committed(WatchFor.Worker.As(ok(ClaimHolder.of("fixer"))), since), 5.minutes)
        ),
        replies.distinct == Vector(
          Reply(200, s"committed abc123 the subject in fs:/work/fixer, on session s-1 at $at")
        )
      )
    }

    test("a lapsed watch is 408, a busy or unreadable one 503, an unlinked owner's 403") {
      Vector[Either[WatchError, Watched]](
        Right(Watched.Lapsed),
        Left(WatchError.Busy),
        Left(WatchError.Unavailable("down")),
        Left(WatchError.NotVouched(owner))
      ).map(a =>
        watchedBy(a)._1.ask("POST", "/wait", """{"commit":{"slot":"fixer"},"within":0}""")
      ) ==> Vector(
        Reply(408, "nothing met the watch within 0s"),
        Reply(503, s"${EdgeWatch.Most} watches are under way already: wait for one to end"),
        Reply(503, "the database is unavailable: down"),
        Reply(403, "no person is linked to workshop:local/someone")
      )
    }

    test("a watch's body it cannot read waits on nothing, and is 400; a GET is 405") {
      val (routes, watching) = watchedBy(Right(Watched.Lapsed))
      Vector(
        routes.ask("POST", "/wait", """{"artifact":"grit/plan-x","state":[]}"""),
        routes.ask("POST", "/wait", """{"artifact":"grit/plan-x","commit":{"slot":"f"}}"""),
        routes.ask("POST", "/wait", """{"commit":{"worktree":"work/fixer"}}"""),
        routes.ask("POST", "/wait", """{"commit":{"slot":"f"},"within":-1}"""),
        routes.ask("POST", "/wait", """{"commit":{"slot":"f"},"since":"yesterday"}"""),
        routes.ask("GET", "/wait", "")
      ).map(_.status) ==> Vector(400, 400, 400, 400, 400, 405)
      watching.asked.size ==> 0
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
