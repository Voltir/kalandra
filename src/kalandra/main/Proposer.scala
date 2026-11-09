package kalandra.main

import java.nio.file.Path

import scala.concurrent.duration.DurationInt

import grit.core.clock.{Clock, Fresh}
import grit.core.edge.Console
import grit.core.place.{Directory, Place}
import grit.core.store.Origin
import grit.host.checkout.Redaction
import grit.kit.environment.Secrets
import grit.kit.run.{KitFailure, LocalEdge}
import grit.kit.work.Work

import org.slf4j.LoggerFactory
import kalandra.deployment.Kalandra

/** `kalandra work [--dry] <file>`: the text in `<file>` said to the proposer
  * ([[Kalandra.proposing]]) in the evidence checkout the process runs in, which must lie under the
  * evidence root ([[evidence]]), and its turns awaited, at most [[Kalandra.ProposerTurns]] (grit's
  * `work`, [[Work.run]], under the program's name `kalandra`). Not dry, it needs
  * `ANTHROPIC_API_KEY` and runs only on an engine it starts ([[admit]]): serve stopped first,
  * which `bin/kalandra work` does. Dry, it needs no key and is refused with one, and shows what the
  * first model call would send, nothing said, serve left up. Its log goes to `work.log` under
  * [[Main.logs]]. A run cut short is resumed only by running `work` again.
  */
object Proposer {

  /** The program `work` runs under: `kalandra`. */
  val Program: String = "kalandra"

  /** The evidence root `env` names: `KALANDRA_EVIDENCE`, else `$HOME/.cache/grit-propose`, where
    * `scripts/eval propose` writes its checkouts; or why it is no directory.
    */
  def evidence(env: Map[String, String]): Either[String, Directory] =
    Directory.of(
      env.getOrElse("KALANDRA_EVIDENCE", s"${env.getOrElse("HOME", "")}/.cache/grit-propose")
    )

  /** `dir` when it is `root` or lies under it, path segment by segment; why not otherwise. */
  def within(root: Directory, dir: Directory): Either[String, Directory] =
    Either.cond(
      Place.of(dir).within(Place.of(root)),
      dir,
      s"${Directory.value(dir)} is not under the evidence root ${Directory.value(root)} " +
        "(KALANDRA_EVIDENCE): a proposal runs only in a checkout `scripts/eval propose` wrote"
    )

  /** Whether a run that is not dry may go on given who runs the database's engine: only this
    * process, so the proposer's deployment, and no other, decides its turns' tools. Refused while
    * serve, or any other process, holds it.
    */
  def admit(runner: Console.Runner): Either[String, Unit] = runner match {
    case Console.Runner.Here => Right(())
    case Console.Runner.Elsewhere(_) =>
      Left(
        "serve runs grit_kalandra's engine, so a proposal would run under serve's tools: stop " +
          "it first (bin/kalandra work stops serve, runs, and starts it again)"
      )
  }

  /** `kalandra work` with the arguments `rest`, run in the directory `cwd`, over the environment
    * `raw`. Why not, and then nothing is said: as [[Work.asked]], [[Main.environment]] and, not
    * dry, [[Main.keyed]] refuse; `cwd` is not under [[evidence]]; dry with a model key set; the
    * file unread or blank; or as [[Work.run]] refuses.
    */
  def run(raw: Map[String, String], rest: Vector[String], cwd: Path): Either[String, Unit] = {
    // Before anything loads slf4j (DBOS does): the run prints its own lines on stdout.
    val log = Main.logs(raw).resolve("work.log")
    val _ = System.setProperty("org.slf4j.simpleLogger.logFile", log.toString)
    val read = for {
      asked <- Work.asked(Program, rest)
      known <- Main.environment(raw)
      env <- if (asked.dry) Right(known) else Main.keyed(known)
      grit <- Main.checkout(env)
      root <- evidence(env)
      here <- Directory.of(cwd.toAbsolutePath.normalize.toString).flatMap(within(root, _))
      proposer <- Kalandra.proposing(grit, root)
      secrets <- Secrets.of(env, proposer).left.map(r => Main.said(KitFailure.Unconfigured(r)))
      _ <- Either.cond(!(asked.dry && secrets.keys.nonEmpty), (), Work.keyed(Program))
      text <- Work.text(Program, asked.file)
    } yield (asked, env, here, proposer, secrets, text)
    // The capabilities are opened inside, not in the for: its closures would capture them.
    read.flatMap { (asked, env, here, proposer, secrets, text) =>
      val clock = Clock.system()
      val fresh = Fresh.random()
      val redaction = Redaction.of(raw)
      val under = Work.Under(
        proposer,
        secrets,
        Origin.Tui(here, "work"),
        cwd,
        env,
        redaction,
        0.millis,
        admit
      )
      LocalEdge
        .serving(
          here,
          proposer.offer.tools,
          redaction,
          clock,
          fresh,
          LoggerFactory.getLogger("kalandra.work")
        ) { edge =>
          Work.run(asked, text, Kalandra.ProposerTurns, under, edge, clock, fresh, println)
        }
        .flatten
    }
  }
}
