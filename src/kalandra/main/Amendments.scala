package kalandra.main

import java.nio.file.Path

import grit.projects.name.{ArtifactNumber, Handle}
import grit.projects.render.ProjectMarkdown

import kalandra.edge.CommandClient
import kalandra.method.Grit

/** `kalandra amendments <directory>`: every amendment ([[Grit.Amendment]]) of the project
  * [[Main.Planned]], ended ones included, each in a file of its own, as `scripts/eval propose
  * --amendments` reads them.
  */
object Amendments {

  /** Of `files`, a project's export files, each amendment's: `<name>.md`, `<name>` its handle,
    * or its number when it has none, holding its file as exported, in order of number. Why not: `files` are not a
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
          .sortBy(_.head.key)
          .map(a =>
            s"${a.head.handle.fold(ArtifactNumber.value(a.head.key.number).toString)(Handle.value)}.md" ->
              ProjectMarkdown.text(a)
          )
      )

  /** Writes [[of]] the project's export files, asked of a running serve ([[CommandClient]]),
    * under `into` ([[written]]); how many it wrote, or why it stopped: serve cannot be reached, a
    * page is refused, or as [[of]] or [[written]] refuses.
    */
  def run(env: Map[String, String], into: Path): Either[String, Int] = {
    val root = into.toAbsolutePath.normalize
    for {
      client <- CommandClient.of(env)
      files <- client.exported(Main.Planned)
      amendments <- of(files)
      n <- written(root, amendments)
    } yield n
  }

  /** `amendments` ([[of]]) written under `into`, replacing a file there, and every other `.md`
    * file directly in `into` deleted, so an amendment whose handle changed leaves no file of its
    * old one: how many it wrote, or why it stopped, a file not listed or not deleted.
    */
  private[main] def written(
      into: Path,
      amendments: Vector[(String, String)]
  ): Either[String, Int] = {
    val writing = amendments.map(_._1).toSet
    for {
      there <- (
        if (!java.nio.file.Files.exists(into)) scala.util.Success(Vector.empty[Path])
        else
          scala.util.Using(java.nio.file.Files.list(into))(
            _.toArray.toVector.collect { case p: Path => p }
          )
      ).toEither.left
        .map(e => s"$into not listed: $e")
      _ <- there
        .filter(p => p.getFileName.toString.endsWith(".md") && !writing(p.getFileName.toString))
        .foldLeft[Either[String, Unit]](Right(())) { (done, stale) =>
          done.flatMap(_ =>
            scala.util
              .Try(java.nio.file.Files.delete(stale))
              .toEither
              .left
              .map(e => s"$stale not deleted: $e")
          )
        }
      _ <- amendments.foldLeft[Either[String, Unit]](Right(())) { case (done, (path, text)) =>
        done.flatMap(_ => Export.write(into, path, text))
      }
    } yield amendments.size
  }
}
