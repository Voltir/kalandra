package kalandra.edge

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

import grit.core.plugin.CommandOutcome

/** A client of a running serve's edge at `base` (`http://host:port`), which [[Routes]] answers.
  */
final class CommandClient private (base: String) {

  private val client = HttpClient.newHttpClient()

  /** The command `name` run with `args`: what it answered, done or refused; or why not, when
    * serve cannot be reached or answers another status (with that status and its text).
    */
  def run(name: String, args: ujson.Obj): Either[String, CommandOutcome] = {
    val uri = URI.create(s"$base${Routes.Command}$name")
    scala.util
      .Try(
        client.send(
          HttpRequest
            .newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString(ujson.write(args), StandardCharsets.UTF_8))
            .build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
      )
      .toEither
      .left
      .map(e => s"kalandra serve is not reachable at $uri: $e")
      .flatMap(r =>
        r.statusCode match {
          case 200 => Right(CommandOutcome.Done(r.body))
          case 409 => Right(CommandOutcome.Refused(r.body))
          case other => Left(s"$name answered $other: ${r.body}")
        }
      )
  }

  /** The project `project`'s export files (`project_export`, page by page), or why not: a
    * page refused or not an export page, or serve unreachable.
    */
  def exported(project: String): Either[String, Vector[(String, String)]] = {
    @scala.annotation.tailrec
    def pages(
        after: Option[String],
        done: Vector[(String, String)]
    ): Either[String, Vector[(String, String)]] = {
      val args = ujson.Obj("project" -> project)
      after.foreach(a => args("after") = a)
      val page = run("project_export", args).flatMap {
        case CommandOutcome.Done(text) =>
          scala.util
            .Try {
              val page = ujson.read(text)
              (
                page("files").arr.toVector.map(f => (f("path").str, f("text").str)),
                page.obj.get("next").flatMap(_.strOpt)
              )
            }
            .toEither
            .left
            .map(_ => s"not an export page: $text")
        case CommandOutcome.Refused(why) => Left(why)
      }
      page match {
        case Left(why) => Left(why)
        case Right((files, None)) => Right(done ++ files)
        case Right((files, next)) => pages(next, done ++ files)
      }
    }
    pages(None, Vector.empty)
  }
}

object CommandClient {

  /** A client of the serve `env` says listens ([[ClaudeCodeEdge.listening]]), or why not. */
  def of(env: Map[String, String]): Either[String, CommandClient] =
    ClaudeCodeEdge
      .listening(env)
      .left
      .map(_.message)
      .map(at => new CommandClient(s"http://${at.getHostString}:${at.getPort}"))
}
