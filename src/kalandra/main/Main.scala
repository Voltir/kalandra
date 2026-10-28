package kalandra.main

import grit.core.place.Directory
import grit.kit.run.Kit

import kalandra.deployment.Kalandra
import kalandra.edge.CommandClient
import kalandra.importer.Imports

/** `kalandra serve`: serves the kalandra deployment until the process is
  * stopped; `kalandra export`, [[Export]]; `kalandra import <checkout> [--dry-run]` and
  * `kalandra verify <checkout>`, [[Imports]] into the project [[Imported]], through a running
  * serve. It reads the environment alone: `GRIT_DATABASE_URL` (default
  * [[Main.DefaultDatabase]]; a URL naming another deployment's database, `grit`
  * or `grit_bort`, is refused) and `KALANDRA_CHECKOUT`, the grit checkout its
  * room is (default `$HOME/Projects/grit`). No model's key is passed on, so
  * nothing it runs can spend.
  */
object Main {

  /** `grit`: the project `import` and `verify` keep grit's planning tree in. */
  val Imported: String = "grit"

  /** `jdbc:postgresql://localhost:5432/grit_kalandra`. */
  val DefaultDatabase: String = "jdbc:postgresql://localhost:5432/grit_kalandra"

  /** The databases kalandra never opens: the operator's own and another
    * deployment's.
    */
  val Refused: Set[String] = Set("grit", "grit_bort")

  /** The variables of model keys, removed before the kit reads the environment.
    */
  val Unread: Set[String] = Set("OPENROUTER_API_KEY", "JEV_API_KEY")

  /** `env` as the kit is given it, or why not. */
  def environment(
      env: Map[String, String]
  ): Either[String, Map[String, String]] = {
    val url = env.getOrElse("GRIT_DATABASE_URL", DefaultDatabase)
    val database = url.takeWhile(_ != '?').split('/').lastOption.getOrElse("")
    Either.cond(
      !Refused.contains(database),
      env.removedAll(Unread) + ("GRIT_DATABASE_URL" -> url),
      s"GRIT_DATABASE_URL names $database, which kalandra never opens"
    )
  }

  /** The checkout `env` names, or why it is no directory. */
  def checkout(env: Map[String, String]): Either[String, Directory] =
    Directory.of(
      env.getOrElse(
        "KALANDRA_CHECKOUT",
        s"${env.getOrElse("HOME", "")}/Projects/grit"
      )
    )

  def main(args: Array[String]): Unit = {
    val ran: Either[String, Unit] = args.toList match {
      case List("serve") => serve(sys.env)
      case List("export", project, into) =>
        Export.run(sys.env, project, java.nio.file.Path.of(into)).map(n => println(s"$n files"))
      case "import" :: root :: flags if flags.forall(_ == "--dry-run") =>
        for {
          client <- CommandClient.of(sys.env)
          report <- Imports.run(client, java.nio.file.Path.of(root), Imported, flags.nonEmpty)
        } yield report.lines.foreach(println)
      case List("verify", root) =>
        for {
          client <- CommandClient.of(sys.env)
          checked <- Imports.verify(client, java.nio.file.Path.of(root), Imported)
          _ = checked.problems.foreach(println)
          _ = checked.imported.failed.foreach((p, why) => println(s"not imported: $p: $why"))
          _ = println(
            s"${checked.imported.project.artifacts.size} artifacts, " +
              s"${checked.problems.size} problems, ${checked.imported.failed.size} not imported"
          )
          _ <- Either.cond(checked.problems.isEmpty, (), s"${checked.problems.size} problems")
        } yield ()
      case _ =>
        Left(
          "usage: kalandra serve | kalandra export <project> <directory> | " +
            "kalandra import <grit checkout> [--dry-run] | kalandra verify <grit checkout>"
        )
    }
    ran.left.foreach { why =>
      System.err.println(s"[kalandra] $why")
      sys.exit(1)
    }
  }

  private def serve(raw: Map[String, String]): Either[String, Unit] =
    for {
      env <- environment(raw)
      dir <- checkout(env)
      kalandra <- Kalandra.deployment(dir)
      _ <- Kit.serve(kalandra, env).left.map(_.message)
    } yield ()
}
