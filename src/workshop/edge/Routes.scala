package workshop.edge

import grit.core.clock.Clock
import grit.core.edge.{CommandError, PluginCommands}
import grit.core.identity.Account
import grit.core.place.Place
import grit.core.plugin.Answered
import grit.core.tool.ToolName
import grit.core.visibility.Integrity

/** What the edge answers one request: an HTTP status, and its text. */
final case class Reply(status: Int, text: String) extends caps.Pure

/** The edge's routes, each request run as `owner` in `room`, believed at `believed`, dated by
  * `clock`. `POST /command/<name>` runs the command `name` with its body, a JSON object, as its
  * arguments (an empty body: none). Nothing else runs anything.
  *
  * `project_claim`'s `for` is passed on as [[BriefFiles.slot]] maps it; once the claim is done,
  * `project_brief` for its key and role is written to that slot's file in `briefs`.
  * `project_release` here also takes `for` (400 without it), which it does not pass on; once
  * the release is done, that slot's file is deleted. A file that cannot be written or deleted,
  * or a brief refused, is said after the command's text; the command stays done.
  */
final class Routes(
    commands: PluginCommands,
    owner: Account,
    room: Place,
    believed: Integrity,
    clock: Clock^,
    briefs: BriefFiles
) {

  /** `method` at `path` with `body`, answered: the command's text with 200 when it did what it
    * was asked; its reason with 409 when it refused; 404 for a command no plugin has, or any
    * other path; 405 for a command not posted; 400 for a body that is no JSON object, or
    * arguments the command does not read; 403 for an owner no person is linked to; 500 when the
    * command's store call failed; 503 when the database could not be reached. Only a 200 kept
    * anything.
    */
  def answer(method: String, path: String, body: String): Reply =
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
      text(args, "for").map(BriefFiles.slot) match {
        case None =>
          Reply(400, "project_release here takes `for`, the slot whose brief file it deletes")
        case Some(s) =>
          val released = ran(name, replaced(args, "for", None))
          if (released.status != 200) released
          else
            briefs
              .delete(s)
              .fold(
                why => Reply(200, s"${released.text}\nThe brief file stays: $why"),
                _ => released
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

  private val Claim = "project_claim"
  private val Release = "project_release"
  private val Brief = ToolName("project_brief")

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
