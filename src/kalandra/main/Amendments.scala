package kalandra.main

import java.nio.file.Path

import grit.projects.name.LocalKey
import grit.projects.render.ProjectMarkdown

import kalandra.edge.CommandClient
import kalandra.method.Grit

/** `kalandra amendments <directory>`: every amendment ([[Grit.Amendment]]) of the project
  * [[Main.Imported]], ended ones included, each in a file of its own, as `scripts/eval propose
  * --amendments` reads them.
  */
object Amendments {

  /** Of `files`, a project's export files, each amendment's: `<name>.md`, `<name>` the artifact's
    * part of its key, holding its file as exported, in order of key. Why not: `files` are not a
    * project ([[ProjectMarkdown.read]]), naming each file it refuses and why.
    */
  def of(files: Vector[(String, String)]): Either[String, Vector[(String, String)]] =
    ProjectMarkdown
      .read(files)
      .left
      .map(_.map((path, why) => s"$path: $why").mkString("; "))
      .map(
        _.artifacts
          .filter(_.head.kind == Grit.Amendment)
          .sortBy(_.head.key.value)
          .map(a => s"${LocalKey.value(a.head.key.local)}.md" -> ProjectMarkdown.file(a)._2)
      )

  /** Writes [[of]] the project's export files, asked of a running serve ([[CommandClient]]),
    * under `into`, replacing a file there; how many it wrote, or why it stopped: serve cannot be
    * reached, a page is refused, or as [[of]] refuses.
    */
  def run(env: Map[String, String], into: Path): Either[String, Int] = {
    val root = into.toAbsolutePath.normalize
    for {
      client <- CommandClient.of(env)
      files <- client.exported(Main.Imported)
      amendments <- of(files)
      _ <- amendments.foldLeft[Either[String, Unit]](Right(())) { case (done, (path, text)) =>
        done.flatMap(_ => Export.write(root, path, text))
      }
    } yield amendments.size
  }
}
