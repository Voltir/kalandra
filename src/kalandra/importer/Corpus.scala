package kalandra.importer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

/** The sources of grit's planning tree under a checkout, and each open plan that could not be
  * read, with why.
  */
final case class Corpus(sources: Vector[Source], missing: Vector[(String, String)])
    extends caps.Pure

/** The sources of grit's planning tree under a checkout, read from disk. */
object Corpus {

  /** The markdown files under `root` that the import reads, those directly in `roadmap/`,
    * `roadmap/mechanisms/` and `.local/backlog/`, and the open plans the roadmap names
    * ([[Importer.openPlans]]); with each open plan that is no file, and why. Refused when
    * `roadmap/ROADMAP.md` or a directory cannot be read.
    */
  def read(root: Path): Either[String, Corpus] =
    for {
      roadmap <- text(root, Mapping.Roadmap)
      tree <- Vector("roadmap/", Mapping.Mechanisms, Mapping.Backlog)
        .foldLeft[Either[String, Vector[Source]]](Right(Vector.empty)) { (done, dir) =>
          done.flatMap(sources => listed(root, dir).map(sources ++ _))
        }
      plans = Importer.openPlans(roadmap).map(p => p -> text(root, p))
    } yield Corpus(
      tree ++ plans.collect { case (p, Right(t)) => Source(p, t) },
      plans.collect { case (p, Left(why)) => p -> why }
    )

  private def listed(root: Path, dir: String): Either[String, Vector[Source]] =
    scala.util
      .Try(
        Files
          .list(root.resolve(dir))
          .iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".md"))
          .map(_.getFileName.toString)
          .toVector
          .sorted
      )
      .toEither
      .left
      .map(e => s"$dir cannot be listed: $e")
      .flatMap(names =>
        names.foldLeft[Either[String, Vector[Source]]](Right(Vector.empty)) { (done, name) =>
          done.flatMap(sources => text(root, dir + name).map(t => sources :+ Source(dir + name, t)))
        }
      )

  private def text(root: Path, path: String): Either[String, String] =
    scala.util
      .Try(Files.readString(root.resolve(path), StandardCharsets.UTF_8))
      .toEither
      .left
      .map(e => s"$path cannot be read: $e")
}
