package kalandra.method

import scala.io.Source
import scala.util.{Try, Using}

import grit.projects.method.{Declared, MethodJson}

/** The method's files, `methods/grit@<version>.json`, which the tests have on their classpath. */
object MethodFiles {

  /** The versions there are files of, oldest first. */
  val Versions: Vector[Int] = Vector(1, 2, 3, 4, 5, 6, 7, 8, 9)

  /** Version `v`'s file as JSON, or why not: no such file, or not JSON. */
  def text(v: Int): Either[String, ujson.Value] = json(raw(v))

  /** Version `v`'s file as it is written, or why not: no such file. */
  def raw(v: Int): Either[String, String] = resource(s"grit@$v.json")

  /** Version `v`'s file read as `project_declare` reads it, or every reason not, joined. */
  def filed(v: Int): Either[String, Declared] = declared(text(v))

  private def resource(path: String): Either[String, String] =
    Using(Source.fromResource(path))(_.mkString).toEither.left.map(_ => s"no file $path")

  private def json(raw: Either[String, String]): Either[String, ujson.Value] =
    raw.flatMap(t => Try(ujson.read(t)).toEither.left.map(_.toString))

  private def declared(text: Either[String, ujson.Value]): Either[String, Declared] =
    text.flatMap(MethodJson.read(_, Grit.Checks).left.map(_.mkString("; ")))
}
