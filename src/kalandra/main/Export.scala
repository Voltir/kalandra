package kalandra.main

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import kalandra.edge.CommandClient

/** `kalandra export <project> <directory>`: a project as its export files (ADR 0049), asked
  * page by page of the edge a running `serve` listens with (`KALANDRA_LISTEN`), each written
  * under `directory` at its path, replacing a file there. Refused when serve cannot be reached,
  * a page is refused, or a path would leave `directory`.
  */
object Export {

  /** Writes `project`'s files under `into`; how many it wrote, or why it stopped. */
  def run(env: Map[String, String], project: String, into: Path): Either[String, Int] = {
    val root = into.toAbsolutePath.normalize
    for {
      client <- CommandClient.of(env)
      files <- client.exported(project)
      _ <- files.foldLeft[Either[String, Unit]](Right(())) { case (done, (path, body)) =>
        done.flatMap(_ => write(root, path, body))
      }
    } yield files.size
  }

  /** `text` written at `path` under `into`, replacing a file there; why not: `path` would leave
    * `into`, or the write fails.
    */
  private[main] def write(into: Path, path: String, text: String): Either[String, Unit] = {
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
