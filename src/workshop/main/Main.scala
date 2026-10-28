package workshop.main

import grit.core.place.Directory
import grit.kit.run.Kit

import workshop.deployment.Workshop

/** `workshop serve`: serves the workshop deployment until the process is
  * stopped; `workshop export`, [[Export]]. It reads the environment alone: `GRIT_DATABASE_URL` (default
  * [[Main.DefaultDatabase]]; a URL naming another deployment's database, `grit`
  * or `grit_bort`, is refused) and `WORKSHOP_CHECKOUT`, the grit checkout its
  * room is (default `$HOME/Projects/grit`). No model's key is passed on, so
  * nothing it runs can spend.
  */
object Main {

  /** `jdbc:postgresql://localhost:5432/grit_workshop`. */
  val DefaultDatabase: String = "jdbc:postgresql://localhost:5432/grit_workshop"

  /** The databases workshop never opens: the operator's own and another
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
      s"GRIT_DATABASE_URL names $database, which workshop never opens"
    )
  }

  /** The checkout `env` names, or why it is no directory. */
  def checkout(env: Map[String, String]): Either[String, Directory] =
    Directory.of(
      env.getOrElse(
        "WORKSHOP_CHECKOUT",
        s"${env.getOrElse("HOME", "")}/Projects/grit"
      )
    )

  def main(args: Array[String]): Unit = {
    val ran: Either[String, Unit] = args.toList match {
      case List("serve") => serve(sys.env)
      case List("export", project, into) =>
        Export.run(sys.env, project, java.nio.file.Path.of(into)).map(n => println(s"$n files"))
      case _ => Left("usage: workshop serve | workshop export <project> <directory>")
    }
    ran.left.foreach { why =>
      System.err.println(s"[workshop] $why")
      sys.exit(1)
    }
  }

  private def serve(raw: Map[String, String]): Either[String, Unit] =
    for {
      env <- environment(raw)
      dir <- checkout(env)
      workshop <- Workshop.deployment(dir)
      _ <- Kit.serve(workshop, env).left.map(_.message)
    } yield ()
}
