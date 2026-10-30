package kalandra.main

import java.nio.file.Path

import scala.concurrent.duration.Duration

import grit.assembly.estimate.CharEstimate
import grit.chat.look.Theme
import grit.chat.run.Chat
import grit.core.clock.{Clock, Fresh}
import grit.core.id.ConversationId
import grit.core.model.ModelId
import grit.core.place.Directory
import grit.core.store.Origin
import grit.host.checkout.LocalInstructions
import grit.kit.environment.Secrets
import grit.kit.run.{Kit, KitFailure, LocalEdge, LocalTools}

import org.slf4j.LoggerFactory
import kalandra.deployment.Kalandra
import kalandra.edge.CommandClient
import kalandra.importer.Imports

/** `kalandra serve`: serves the kalandra deployment until the process is
  * stopped; `kalandra chat`, the chat on this terminal in its room; `kalandra cache-report
  * [conversation-id]`, what a conversation's model calls read from and wrote to the cache
  * (the chat's, with no id); `kalandra export`, [[Export]]; `kalandra import <checkout> [--dry-run]` and
  * `kalandra verify <checkout>`, [[Imports]] into the project [[Imported]], through a running
  * serve. It reads the environment alone: `GRIT_DATABASE_URL` (default
  * [[Main.DefaultDatabase]]; a URL naming another deployment's database, `grit`
  * or `grit_bort`, is refused) and `KALANDRA_CHECKOUT`, the grit checkout its
  * room is (default `$HOME/Projects/grit`). `serve` and `chat` run turns: each needs
  * `ANTHROPIC_API_KEY` and `JEV_API_KEY`, and is refused without either; OpenRouter's key is
  * never passed on.
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

  /** OpenRouter's key, removed before the kit reads the environment: every pair kalandra calls
    * is at Anthropic's API.
    */
  val Unread: Set[String] = Set("OPENROUTER_API_KEY")

  /** What a refusal over a missing or malformed key ends with: where the key goes. */
  val KeysHint: String = "kalandra reads its keys from .env in its checkout"

  /** `env`, or why its turns cannot run: `ANTHROPIC_API_KEY` unset or blank. Without it the kit
    * answers turns with the stub model, which a command meant to run turns must not.
    */
  def keyed(env: Map[String, String]): Either[String, Map[String, String]] =
    Either.cond(
      env.get("ANTHROPIC_API_KEY").exists(_.trim.nonEmpty),
      env,
      s"ANTHROPIC_API_KEY is not set, and kalandra's turns call Claude through it; $KeysHint"
    )

  /** `failure` for a person: its message, and [[KeysHint]] when a key was missing. */
  def said(failure: KitFailure): String = failure match {
    case KitFailure.Unconfigured(_) => s"${failure.message}; $KeysHint"
    case _ => failure.message
  }

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
      case List("chat") =>
        // DBOS's threads are non-daemon: the process ends here, once the chat has closed.
        chat(sys.env).map(_ => sys.exit(0))
      case "cache-report" :: id if id.sizeIs <= 1 => cacheReport(sys.env, id.headOption)
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
          "usage: kalandra serve | kalandra chat | kalandra cache-report [conversation-id] | " +
            "kalandra export <project> <directory> | " +
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
      env <- environment(raw).flatMap(keyed)
      dir <- checkout(env)
      kalandra <- Kalandra.deployment(dir)
      _ <- Kit.serve(kalandra, env).left.map(said)
    } yield ()

  /** The chat on this terminal, in the checkout's room, until the person quits: on the engine
    * serve holds, attached, or on one it starts here when none runs. Its log goes to
    * `chat.log` under [[logs]], never to the screen.
    */
  private def chat(raw: Map[String, String]): Either[String, Unit] = {
    // Before anything loads slf4j (DBOS does): a log line on stderr would paint over the screen.
    val log = logs(raw).resolve("chat.log")
    val _ = System.setProperty("org.slf4j.simpleLogger.logFile", log.toString)
    val read = for {
      env <- environment(raw).flatMap(keyed)
      dir <- checkout(env)
      kalandra <- Kalandra.deployment(dir)
      secrets <- Secrets.of(env, kalandra).left.map(r => said(KitFailure.Unconfigured(r)))
    } yield (dir, kalandra, secrets)
    // The capabilities are opened in matches, not a for: a for's closures would capture them.
    read.flatMap { (dir, kalandra, secrets) =>
      LocalEdge.over(
        dir,
        new LocalTools(kalandra.offer.tools),
        new LocalInstructions(),
        LoggerFactory.getLogger("kalandra.chat")
      ) match {
        case Left(refused) => Left(s"the coding tools cannot be offered: ${refused.message}")
        case Right(here) =>
          val clock = Clock.system()
          Kit.console(kalandra, secrets, Duration.Zero, clock, here) match {
            case Left(failure) => Left(said(failure))
            case Right(opener) =>
              Right(
                Chat.run(
                  opener,
                  ChatOrigin(dir),
                  model = ModelId.value(kalandra.policy.turn.first.assignment.ref.model),
                  session = s"kalandra · ${Directory.value(dir)}",
                  Theme.Default,
                  Kalandra.TurnBudget,
                  CharEstimate,
                  prefs = None,
                  log = Some(log),
                  clock,
                  Fresh.random()
                )
              )
          }
      }
    }
  }

  /** The cache report of the conversation `id`, or, with none, of the one `kalandra chat` holds
    * in the checkout, printed a line at a time. Its log goes to `cache-report.log` under
    * [[logs]]. No engine is started, and no conversation created.
    */
  private def cacheReport(raw: Map[String, String], id: Option[String]): Either[String, Unit] = {
    val _ = System.setProperty(
      "org.slf4j.simpleLogger.logFile",
      logs(raw).resolve("cache-report.log").toString
    )
    val read = for {
      env <- environment(raw)
      dir <- checkout(env)
      kalandra <- Kalandra.deployment(dir)
      secrets <- Secrets.of(env, kalandra).left.map(r => said(KitFailure.Unconfigured(r)))
    } yield (dir, kalandra, secrets)
    read.flatMap { (dir, kalandra, secrets) =>
      val clock = Clock.system()
      val conversation = id match {
        case Some(named) => Right(Some(ConversationId(named)))
        case None => Kit.find(kalandra, secrets, ChatOrigin(dir), clock).left.map(said)
      }
      conversation.flatMap {
        case None => Left(s"no chat has been held in ${Directory.value(dir)} yet")
        case Some(c) =>
          Kit.cacheReport(kalandra, secrets, c, clock).left.map(said).map(_.foreach(println))
      }
    }
  }

  /** The conversation `kalandra chat` holds in the checkout `checkout`. */
  def ChatOrigin(checkout: Directory): Origin = Origin.Tui(checkout, "chat")

  /** Where `env`'s logs go: `KALANDRA_LOGS`, or `logs` under the working directory. */
  def logs(env: Map[String, String]): Path = Path.of(env.getOrElse("KALANDRA_LOGS", "logs"))
}
