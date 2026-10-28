package workshop.main

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import workshop.edge.{ClaudeCodeEdge, Routes}

/** `workshop export <project> <directory>`: a project as its export files (ADR 0049), asked
  * page by page of the edge a running `serve` listens with (`WORKSHOP_LISTEN`), each written
  * under `directory` at its path, replacing a file there. Refused when serve cannot be reached,
  * a page is refused, or a path would leave `directory`.
  */
object Export {

  /** Writes `project`'s files under `into`; how many it wrote, or why it stopped. */
  def run(env: Map[String, String], project: String, into: Path): Either[String, Int] =
    for {
      at <- ClaudeCodeEdge.listening(env).left.map(_.message)
      base = URI.create(s"http://${at.getHostString}:${at.getPort}${Routes.Command}project_export")
      client = HttpClient.newHttpClient()
      written <- pages(client, base, project, into.toAbsolutePath.normalize, None, 0)
    } yield written

  private def pages(
      client: HttpClient,
      base: URI,
      project: String,
      into: Path,
      after: Option[String],
      written: Int
  ): Either[String, Int] = {
    val args = ujson.Obj("project" -> project)
    after.foreach(a => args("after") = a)
    for {
      text <- asked(client, base, ujson.write(args))
      page <- scala.util.Try(ujson.read(text)).toEither.left.map(_ => s"not an export page: $text")
      files <- scala.util
        .Try(page("files").arr.toVector.map(f => (f("path").str, f("text").str)))
        .toEither
        .left
        .map(_ => s"not an export page: $text")
      _ <- files.foldLeft[Either[String, Unit]](Right(())) { case (done, (path, body)) =>
        done.flatMap(_ => write(into, path, body))
      }
      next = page.obj.get("next").flatMap(_.strOpt)
      total <- next.fold(Right(written + files.size))(n =>
        pages(client, base, project, into, Some(n), written + files.size)
      )
    } yield total
  }

  private def asked(client: HttpClient, uri: URI, body: String): Either[String, String] =
    scala.util
      .Try(
        client.send(
          HttpRequest
            .newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
      )
      .toEither
      .left
      .map(e => s"workshop serve is not reachable at $uri: $e")
      .flatMap(r => Either.cond(r.statusCode == 200, r.body, s"${r.statusCode}: ${r.body}"))

  private def write(into: Path, path: String, text: String): Either[String, Unit] = {
    val file = into.resolve(path).normalize
    if (!file.startsWith(into) || file == into) Left(s"the export path $path leaves $into")
    else
      scala.util
        .Try {
          Files.createDirectories(file.getParent)
          Files.writeString(file, text, StandardCharsets.UTF_8)
          ()
        }
        .toEither
        .left
        .map(e => s"$file not written: $e")
  }
}
