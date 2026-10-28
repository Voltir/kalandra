package kalandra.edge

import java.net.InetSocketAddress

import grit.core.clock.Clock
import grit.core.edge.{CommandError, PluginCommands}
import grit.core.identity.Account
import grit.core.place.Place
import grit.core.plugin.Answered
import grit.core.tool.ToolName
import grit.core.visibility.Integrity

/** What the edge answers one request: an HTTP status, its text, and that text's content type. */
final case class Reply(status: Int, text: String, contentType: String = Reply.PlainText)
    extends caps.Pure

object Reply {

  /** `text/plain; charset=utf-8`. */
  val PlainText: String = "text/plain; charset=utf-8"

  /** `application/json`. */
  val Json: String = "application/json"
}

/** The edge's routes, each request run as `owner` in `room`, believed at `believed`, dated by
  * `clock`. `POST /command/<name>` runs the command `name` with its body, a JSON object, as its
  * arguments (an empty body: none). `POST /mcp` takes one [[Mcp]] message: `initialize`, `ping`,
  * `tools/list` (the commands offered) and `tools/call`, which runs an offered command as
  * `/command/<name>` would, its text the content, an error unless that answered 200. Nothing
  * else runs anything.
  *
  * `project_claim`'s `for` is passed on as [[BriefFiles.slot]] maps it; once the claim is done,
  * `project_brief` for its key and role is written to that slot's file in `briefs`.
  * `project_release` takes its key alone and is passed on as given; once the release is done,
  * the slot its answer names (`{"released","for"}` JSON) has its file deleted. A file that
  * cannot be written or deleted, a brief refused, or a release answer naming no slot, is said
  * after the command's text; the command stays done.
  *
  * Every request is first checked as coming from this machine, since a web page can send one
  * too (through DNS rebinding, with its own name as `Host`): see [[answer]].
  */
final class Routes(
    commands: PluginCommands,
    owner: Account,
    room: Place,
    believed: Integrity,
    clock: Clock^,
    briefs: BriefFiles,
    listen: InetSocketAddress
) {

  /** `method` at `path` with `body`, answered: the command's text with 200 when it did what it
    * was asked; its reason with 409 when it refused; 404 for a command no plugin has, or any
    * other path; 405 for a command not posted; 400 for a body that is no JSON object, or
    * arguments the command does not read; 403 for an owner no person is linked to; 500 when the
    * command's store call failed; 503 when the database could not be reached. Only a 200 kept
    * anything. At [[Routes.McpPath]]: a POST answered as [[Mcp]] says, anything else 405.
    *
    * First, 403, running nothing, when `origin` (the `Origin` header) is given and is not
    * `http://` a loopback host (`127.0.0.1`, `localhost`, `[::1]`) at any port, or when `host`
    * (the `Host` header) is not `listen`'s address and port or `localhost` and its port. A
    * request with no `Origin` (curl, `bin/kalandra`, an MCP client) passes on its `Host`.
    */
  def answer(
      method: String,
      path: String,
      origin: Option[String],
      host: Option[String],
      body: String
  ): Reply =
    if (!origin.forall(Routes.loopbackOrigin) || !host.exists(local))
      Reply(403, "refused: only a request from this machine is answered")
    else if (path == Routes.McpPath) {
      if (method == "POST") mcp(body)
      else Reply(405, s"post a JSON-RPC message; $method runs nothing")
    } else
      path.stripPrefix(Routes.Command) match {
        case name if name == path || name.isEmpty || name.contains('/') =>
          Reply(404, s"no route $path; commands are posted to ${Routes.Command}<name>")
        case _ if method != "POST" => Reply(405, s"post a command; $method runs nothing")
        case raw =>
          (ToolName.of(raw), arguments(body)) match {
            case (Left(_), _) => Reply(404, s"no command is named $raw")
            case (_, Left(why)) => Reply(400, why)
            case (Right(name), Right(args)) => routed(name, args)
          }
      }

  /** Whether `host`, a `Host` header, names `listen`: its address or `localhost`, and its port. */
  private def local(host: String): Boolean = {
    val port = listen.getPort
    val address = listen.getAddress.getHostAddress
    val named = if (address.contains(':')) s"[$address]" else address
    Set(s"$named:$port", s"localhost:$port").contains(host.trim.toLowerCase(java.util.Locale.ROOT))
  }

  /** One MCP message, answered; a call to a command not offered runs nothing. */
  private def mcp(body: String): Reply = Mcp.read(body) match {
    case Left(error) => error
    case Right(Mcp.Message.Notice) => Reply(202, "")
    case Right(Mcp.Message.Request(id, method, params)) =>
      method match {
        case "initialize" => Mcp.initialized(id, params)
        case "ping" => Mcp.result(id, ujson.Obj())
        case "tools/list" => Mcp.result(id, Mcp.tools(commands.offered))
        case "tools/call" =>
          Mcp
            .call(params)
            .filterOrElse(
              c => commands.offered.exists(_.name == ToolName.value(c._1)),
              s"no tool is named ${params.value.get("name").flatMap(_.strOpt).getOrElse("")}"
            ) match {
            case Left(why) => Mcp.failed(id, Mcp.InvalidParams, why)
            case Right((name, args)) => Mcp.called(id, routed(name, args))
          }
        case other => Mcp.failed(id, Mcp.MethodNotFound, s"no method is named $other")
      }
  }

  /** `name` run with `args`, keeping the brief file of a claim or release that is done. */
  private def routed(name: ToolName, args: ujson.Obj): Reply = ToolName.value(name) match {
    case Routes.Claim =>
      val slot = text(args, "for").map(BriefFiles.slot)
      val claimed = ran(name, slot.fold(args)(s => replaced(args, "for", Some(s))))
      (claimed.status, slot, text(args, "key"), text(args, "role")) match {
        case (200, Some(s), Some(key), Some(role)) =>
          val brief = ran(Routes.Brief, ujson.Obj("key" -> key, "role" -> role))
          (if (brief.status == 200) briefs.write(s, brief.text) else Left(brief.text))
            .fold(
              why => Reply(200, s"${claimed.text}\nNo brief file was written: $why"),
              _ => claimed
            )
        case _ => claimed
      }
    case Routes.Release =>
      val released = ran(name, args)
      if (released.status != 200) released
      else
        Routes.releasedSlot(released.text) match {
          case None =>
            Reply(200, s"${released.text}\nThe brief file stays: the release named no slot")
          case Some((key, slot)) =>
            briefs
              .delete(slot)
              .fold(
                why => Reply(200, s"Released $key.\nThe brief file stays: $why"),
                _ => Reply(200, s"Released $key.")
              )
        }
    case _ => ran(name, args)
  }

  private def ran(name: ToolName, args: ujson.Obj): Reply =
    Routes.replied(commands.run(owner, room, believed, name, args, clock.now()))

  private def text(args: ujson.Obj, field: String): Option[String] =
    args.value.get(field).flatMap(_.strOpt)

  /** `args` with `field` set to `to`, or left out when `to` is none; `args` itself unchanged. */
  private def replaced(args: ujson.Obj, field: String, to: Option[String]): ujson.Obj =
    ujson.Obj.from(
      args.value.toSeq.filterNot(_._1 == field) ++ to.map(t => field -> (ujson.Str(t): ujson.Value))
    )

  private def arguments(body: String): Either[String, ujson.Obj] =
    if (body.trim.isEmpty) Right(ujson.Obj())
    else
      scala.util.Try(ujson.read(body)).toOption match {
        case Some(o: ujson.Obj) => Right(o)
        case _ => Left("the body is no JSON object of the command's arguments")
      }
}

object Routes {

  /** `/command/`: where a command is posted, its name after. */
  val Command: String = "/command/"

  /** Whether `origin`, an `Origin` header, is `http://` a loopback host at a port, or none. */
  private def loopbackOrigin(origin: String): Boolean =
    LoopbackOrigin.matches(origin.trim.toLowerCase(java.util.Locale.ROOT))

  private val LoopbackOrigin = raw"http://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]{1,5})?".r

  /** `/mcp`: where MCP messages are posted. */
  val McpPath: String = "/mcp"

  private val Claim = "project_claim"
  private[edge] val Release = "project_release"
  private val Brief = ToolName("project_brief")

  /** The key released and the slot it was for, from a release's answer: a JSON object with
    * string `released` and `for`. None for any other text.
    */
  private def releasedSlot(text: String): Option[(String, String)] =
    scala.util.Try(ujson.read(text)).toOption match {
      case Some(o: ujson.Obj) =>
        for {
          key <- o.value.get("released").flatMap(_.strOpt)
          slot <- o.value.get("for").flatMap(_.strOpt)
        } yield (key, BriefFiles.slot(slot))
      case _ => None
    }

  private def replied(ran: Either[CommandError, Answered]): Reply = ran match {
    case Right(Answered.Done(text)) => Reply(200, text)
    case Right(Answered.Refused(why)) => Reply(409, why)
    case Left(CommandError.Unknown(name)) =>
      Reply(404, s"no command is named ${ToolName.value(name)}")
    case Left(CommandError.Unread(_, reason)) => Reply(400, reason)
    case Left(CommandError.NotVouched(by)) =>
      Reply(403, s"no person is linked to ${Account.written(by)}")
    case Left(CommandError.Store(_, error)) => Reply(500, s"nothing was kept: $error")
    case Left(CommandError.Unavailable(cause)) => Reply(503, s"the database is unavailable: $cause")
  }
}
