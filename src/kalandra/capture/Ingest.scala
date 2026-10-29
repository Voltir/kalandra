package kalandra.capture

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

import grit.core.edge.EdgeSessions
import grit.core.identity.Account
import grit.core.place.Place
import grit.core.store.StoreError

/** The sessions `hooks/spool-hook.sh` spools in `dir`, recorded through `sessions` as `owner`
  * in `room`; what went wrong told to `log`.
  */
final class Ingest(
    dir: Path,
    sessions: EdgeSessions,
    owner: Account,
    room: Place,
    log: String => Unit
) extends Runnable {

  /** One pass ([[once]]), for a scheduler: a pass that throws is said to `log`, not thrown, so
    * the passes after it still run.
    */
  def run(): Unit =
    try { val _ = once() }
    catch { case NonFatal(e) => log(s"claude-code spool: a pass failed: $e") }

  /** One pass over the spool; how many files it recorded. First each file an earlier pass took
    * (a stopped serve's included) is recorded, all its sessions in one call, and deleted; a line
    * [[Spooled.of]] refuses is dropped, and said, with why. A file the store refuses
    * ([[StoreError.Invalid]]) is kept for the next pass, and after [[Ingest.Tries]] refusals
    * moved to `quarantine/`, which keeps its newest [[Ingest.Quarantined]]. Any other failure
    * is the store out of reach: the pass ends there, said once, the file untried and nothing
    * taken, so new lines wait in the hook's files, which the hook bounds. Otherwise each session
    * file the hook appends to is then taken, renamed for the next pass, so that an append begun
    * before the rename is in it before it is read. A spool that is no directory is a pass that
    * records nothing.
    */
  def once(): Int =
    if (!Files.isDirectory(dir)) 0
    else {
      val (recorded, unreachable) =
        listed(dir, Ingest.Taken).foldLeft((0, Option.empty[StoreError])) {
          case ((n, None), file) =>
            record(file) match {
              case Ingest.Outcome.Recorded => (n + 1, None)
              case Ingest.Outcome.Kept => (n, None)
              case Ingest.Outcome.Unreachable(why) => (n, Some(why))
            }
          case (ended, _) => ended
        }
      unreachable match {
        case Some(why) =>
          log(s"claude-code spool: the store is unreachable, the pass ended: $why")
        case None => listed(dir, ".jsonl").foreach(take)
      }
      recorded
    }

  /** `<session>.jsonl` renamed `<session>.<stamp>.0.ingesting`. */
  private def take(file: Path): Unit = {
    val session = file.getFileName.toString.stripSuffix(".jsonl")
    val taken = dir.resolve(s"$session.${System.nanoTime()}.0${Ingest.Taken}")
    attempt(s"take ${file.getFileName}")(Files.move(file, taken, StandardCopyOption.ATOMIC_MOVE))
  }

  /** What became of `file`: recorded and deleted, kept or dropped, or not tried for want of
    * the store.
    */
  private def record(file: Path): Ingest.Outcome = {
    val name = file.getFileName.toString
    val lines = attempt(s"read $name")(
      new String(Files.readAllBytes(file), StandardCharsets.UTF_8).linesIterator.toVector
    ).getOrElse(Vector.empty).filter(_.trim.nonEmpty)
    val (dropped, read) = lines.map(Spooled.of).partitionMap(identity)
    if (dropped.nonEmpty)
      log(
        s"claude-code spool: $name: ${dropped.size} lines dropped: ${dropped.distinct.mkString("; ")}"
      )
    val batches = read.map(s => (s.id, s.parent)).distinct.map { (id, parent) =>
      val its = read.filter(s => s.id == id && s.parent == parent)
      EdgeSessions.Batch(
        id,
        parent,
        its.headOption.fold(Place.Everywhere)(_.at),
        its.flatMap(_.holder).headOption,
        its.map(_.event)
      )
    }
    if (batches.isEmpty) {
      attempt(s"delete $name")(Files.delete(file))
      Ingest.Outcome.Kept
    } else
      sessions.record(owner, room, batches) match {
        case Right(_) =>
          attempt(s"delete $name")(Files.delete(file))
          Ingest.Outcome.Recorded
        // Only a refusal of what the file holds is the file's fault; any other failure is the
        // store out of reach, which no try of this file can mend.
        case Left(why) if !why.isInstanceOf[StoreError.Invalid] => Ingest.Outcome.Unreachable(why)
        case Left(why) =>
          val (stem, tries) = Ingest.tried(name)
          val next = s"$stem.${tries + 1}${Ingest.Taken}"
          if (tries + 1 >= Ingest.Tries) {
            val quarantine = dir.resolve("quarantine")
            attempt(s"quarantine $name") {
              Files.createDirectories(quarantine)
              Files.move(file, quarantine.resolve(next), StandardCopyOption.ATOMIC_MOVE)
            }
            listed(quarantine, Ingest.Taken)
              .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
              .drop(Ingest.Quarantined)
              .foreach(old => attempt(s"delete ${old.getFileName}")(Files.delete(old)))
            log(s"claude-code spool: $next quarantined after ${Ingest.Tries} tries: $why")
          } else {
            attempt(s"keep $name")(
              Files.move(file, dir.resolve(next), StandardCopyOption.ATOMIC_MOVE)
            )
            log(s"claude-code spool: $name not recorded, try ${tries + 1} of ${Ingest.Tries}: $why")
          }
          Ingest.Outcome.Kept
      }
  }

  /** The files in `in` whose names end with `suffix`, by name; none when it cannot be listed. */
  private def listed(in: Path, suffix: String): Vector[Path] =
    attempt(s"list $in")(Using.resource(Files.list(in)) { paths =>
      paths
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(suffix))
        .toVector
        .sortBy(_.getFileName.toString)
    }).getOrElse(Vector.empty)

  /** `body`'s result, or `None` with what `doing` failed said to `log`. */
  private def attempt[A](doing: String)(body: => A): Option[A] =
    try Some(body)
    catch {
      case NonFatal(e) =>
        log(s"claude-code spool: could not $doing: $e")
        None
    }
}

object Ingest {

  /** 2 seconds: how long after one pass the next begins. */
  val Every: FiniteDuration = 2.seconds

  /** 3: the passes the store may refuse a file's recording on before it is quarantined. */
  val Tries: Int = 3

  /** What one pass did with a taken file. */
  private enum Outcome {

    /** Recorded, and deleted. */
    case Recorded

    /** Left for a later pass, quarantined, or deleted with nothing in it to record. */
    case Kept

    /** Not tried: the store could not be reached, `why`. */
    case Unreachable(why: StoreError)
  }

  /** 20: the quarantined files kept, the newest. */
  val Quarantined: Int = 20

  /** `.ingesting`: how a file a pass took ends, `<session>.<stamp>.<tries>.ingesting`. */
  private val Taken: String = ".ingesting"

  /** A taken file's name without its tries, and its tries: 0 for a name not as [[Taken]] says. */
  private def tried(name: String): (String, Int) = {
    val stem = name.stripSuffix(Taken)
    val dot = stem.lastIndexOf('.')
    stem.drop(dot + 1).toIntOption match {
      case Some(tries) if dot > 0 => (stem.take(dot), tries)
      case _ => (stem, 0)
    }
  }

  /** The variable naming the spool's directory: `KALANDRA_SPOOL`. */
  val SpoolVar: String = "KALANDRA_SPOOL"

  /** The spool's directory `env` names, by default `$HOME/.local/state/kalandra/spool`. */
  def spool(env: Map[String, String]): Path =
    env
      .get(SpoolVar)
      .filter(_.trim.nonEmpty)
      .fold(Paths.get(env.getOrElse("HOME", ""), ".local", "state", "kalandra", "spool"))(
        Paths.get(_)
      )
}
