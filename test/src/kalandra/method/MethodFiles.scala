package kalandra.method

import scala.io.Source
import scala.util.{Try, Using}

import grit.projects.method.{Declared, MethodJson}

/** The method's files, `methods/grit@<version>.json`, which the tests have on their classpath. */
object MethodFiles {

  /** The versions there are files of, oldest first. */
  val Versions: Vector[Int] = Vector(1, 2, 3, 4, 5, 6)

  /** Version `v`'s file as JSON, or why not: no such file, or not JSON. */
  def text(v: Int): Either[String, ujson.Value] =
    Using(Source.fromResource(s"grit@$v.json"))(_.mkString).toEither.left
      .map(_ => s"no file grit@$v.json")
      .flatMap(t => Try(ujson.read(t)).toEither.left.map(_.toString))

  /** Version `v`'s file read as `project_declare` reads it, or every reason not, joined. */
  def filed(v: Int): Either[String, Declared] =
    text(v).flatMap(MethodJson.read(_, Grit.Checks).left.map(_.mkString("; ")))
}
