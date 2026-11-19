package kalandra.main

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import kalandra.edge.CommandClient

/** `kalandra export <project> <directory>`: a project as its export files (ADR 0049), asked
  * page by page of the edge a running `serve` listens with (`KALANDRA_LISTEN`), each written
  * under `directory` at its path. Refused when serve cannot be reached, a page is refused, or as
  * [[written]] refuses.
  */
object Export {

  /** Writes `project`'s files under `into`; how many it wrote, or why it stopped. */
  def run(env: Map[String, String], project: String, into: Path): Either[String, Int] =
    for {
      client <- CommandClient.of(env)
      files <- client.exported(project)
      n <- written(into, files)
    } yield n

  /** `files` written under `into`, each at its path, creating `into` when it is missing; how
    * many, or why it stopped: `into` is a file or a directory holding one (an artifact's file moves when its parent
    * does, so a second export over a first would leave the old file beside the new), a path
    * would leave `into`, or a write fails.
    */
  private[main] def written(into: Path, files: Vector[(String, String)]): Either[String, Int] = {
    val root = into.toAbsolutePath.normalize
    val empty = scala.util
      .Using(Files.list(root))(_.findAny.isEmpty)
      .getOrElse(!Files.exists(root))
    Either
      .cond(
        empty,
        (),
        s"$root is neither new nor an empty directory: kalandra export writes only into one"
      )
      .flatMap(_ =>
        files.foldLeft[Either[String, Unit]](Right(())) { case (done, (path, body)) =>
          done.flatMap(_ => write(root, path, body))
        }
      )
      .map(_ => files.size)
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
