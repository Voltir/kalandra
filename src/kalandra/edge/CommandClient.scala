package kalandra.edge

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import grit.core.plugin.CommandOutcome

/** A client of a running serve's commands edge at `base` (`http://host:port`), each command
  * posted to `/command/<name>` bearing the credential read from `file`, `credential`.
  */
final class CommandClient private (base: String, file: Path, credential: String) {

  private val client = HttpClient.newHttpClient()

  /** The command `name` run with `args`: what it answered, done or refused; or why not, when
    * serve cannot be reached, refuses the credential (401, naming its file), or answers another
    * status (with that status and its text).
    */
  def run(name: String, args: ujson.Obj): Either[String, CommandOutcome] = {
    val uri = URI.create(s"$base/command/$name")
    scala.util
      .Try(
        client.send(
          HttpRequest
            .newBuilder(uri)
            .header("Authorization", s"Bearer $credential")
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
          case 401 =>
            Left(
              s"serve refused the credential in $file (401) for $name; ${CommandClient.asking(file)}"
            )
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

  /** `KALANDRA_CREDENTIAL`: the path of the file holding the credential each request bears. */
  val CredentialVar: String = "KALANDRA_CREDENTIAL"

  /** A client of the serve `env` says listens ([[Mount.listening]]), each request bearing the
    * credential in [[CredentialVar]]'s file, its text up to trailing whitespace; or why not: the
    * variable unset, or its file unreadable or empty, named by its path.
    */
  def of(env: Map[String, String]): Either[String, CommandClient] =
    for {
      at <- Mount.listening(env)
      named <- env
        .get(CredentialVar)
        .filter(_.nonEmpty)
        .toRight(
          s"$CredentialVar is not set: the path of the file holding the credential to send, " +
            "which bin/kalandra sets"
        )
      file = Path.of(named)
      text <- scala.util
        .Try(Files.readString(file).strip)
        .toOption
        .filter(_.nonEmpty)
        .toRight(s"cannot read a credential in $file; nothing was sent: ${asking(file)}")
    } yield new CommandClient(s"http://${at.shown}", file, text)

  /** What a person does about a credential `file` that is missing or refused. */
  private def asking(file: Path): String =
    s"ask whoever runs serve for this credential and put it in $file"
}
