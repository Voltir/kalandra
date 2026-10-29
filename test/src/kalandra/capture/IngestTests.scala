package kalandra.capture

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import grit.core.edge.EdgeSessions
import grit.core.id.SessionId
import grit.core.identity.Account
import grit.core.place.{Namespace, Place}
import grit.core.store.StoreError

import utest.*

/** [[Ingest]] over a spool directory, recording through a fake [[EdgeSessions]] that keeps
  * what it was asked and answers as told.
  */
object IngestTests extends TestSuite {

  private val Owner: Account = Account.Local
  private val Room: Place = Place.under(Namespace.Task, Vector("kalandra"))
  private val S = "faafcbf0-3592-4c99-8f14-21889a88798e"

  /** Keeps each call's batches, and answers `answer` (how many lines it logged, or why not). */
  private final class Recorder(answer: Either[StoreError, Int]) extends EdgeSessions {
    val calls = new ConcurrentLinkedQueue[(Account, Place, Vector[EdgeSessions.Batch])]()
    def record(
        by: Account,
        room: Place,
        batches: Vector[EdgeSessions.Batch]
    ): Either[StoreError, Int] = {
      calls.add((by, room, batches))
      answer
    }
  }

  private def line(n: Long, k: String, more: (String, ujson.Value)*): String =
    ujson.write(
      ujson.Obj.from(
        Vector[(String, ujson.Value)](
          "s" -> S,
          "n" -> n.toString,
          "k" -> k,
          "w" -> "/work/grit",
          "d" -> ujson.Obj()
        ) ++ more
      )
    )

  private def spooled(dir: Path, name: String, lines: String*): Path =
    Files.writeString(dir.resolve(name), lines.map(_ + "\n").mkString, StandardCharsets.UTF_8)

  private def names(dir: Path): Set[String] =
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).toSet

  private def id(local: String): SessionId =
    SessionId.of(Spooled.Harness, local).fold(sys.error, identity)

  private def ingest(
      dir: Path,
      sessions: EdgeSessions,
      said: ConcurrentLinkedQueue[String]
  ): Ingest^ =
    new Ingest(dir, sessions, Owner, Room, s => { said.add(s); () })

  val tests = Tests {

    test("a spooled file is taken on one pass, then recorded and deleted on the next") {
      val dir = Files.createTempDirectory("spool")
      val sessions = new Recorder(Right(2))
      val said = new ConcurrentLinkedQueue[String]()
      val spool = ingest(dir, sessions, said)
      spooled(dir, s"$S.jsonl", line(1, "SessionStart"), line(2, "Edit"))
      val first = spool.once()
      val afterFirst = (sessions.calls.size, names(dir).exists(_.endsWith(".ingesting")))
      val second = spool.once()
      assert(
        (first, afterFirst) == ((0, (0, true))),
        second == 1,
        sessions.calls.asScala.toVector.map(call =>
          (call._1, call._2, call._3.map(b => (b.id, b.parent, b.at, b.events.map(_.n))))
        ) == Vector(
          (
            Owner,
            Room,
            Vector((id(S), None, Place.under(Namespace.Fs, Vector("/work/grit")), Vector(1L, 2L)))
          )
        ),
        names(dir) == Set.empty
      )
    }

    test("a file a stopped serve left taken is recorded on the first pass") {
      val dir = Files.createTempDirectory("spool")
      val sessions = new Recorder(Right(1))
      spooled(dir, s"$S.17.0.ingesting", line(1, "SessionStart"))
      val recorded = ingest(dir, sessions, new ConcurrentLinkedQueue[String]()).once()
      assert(recorded == 1, sessions.calls.size == 1, names(dir) == Set.empty)
    }

    test("each session of a file is its own batch, a subagent's with its parent and place") {
      val dir = Files.createTempDirectory("spool")
      val sessions = new Recorder(Right(4))
      spooled(
        dir,
        s"$S.1.0.ingesting",
        line(1, "SessionStart"),
        line(
          2,
          "SubagentStart",
          "a" -> "a1",
          "w" -> "/work/spike",
          "d" -> ujson.Obj("type" -> "Explore")
        ),
        line(3, "Edit"),
        line(4, "Write", "a" -> "a1", "w" -> "/work/spike")
      )
      val _ = ingest(dir, sessions, new ConcurrentLinkedQueue[String]()).once()
      sessions.calls.asScala.toVector
        .flatMap(_._3)
        .map(b => (b.id, b.parent, b.at, b.events.map(_.n))) ==>
        Vector(
          (id(S), None, Place.under(Namespace.Fs, Vector("/work/grit")), Vector(1L, 3L)),
          (
            id(s"$S/a1"),
            Some(id(S)),
            Place.under(Namespace.Fs, Vector("/work/spike")),
            Vector(2L, 4L)
          )
        )
    }

    test("lines it cannot read are reported and dropped, and the rest recorded") {
      val dir = Files.createTempDirectory("spool")
      val sessions = new Recorder(Right(1))
      val said = new ConcurrentLinkedQueue[String]()
      spooled(
        dir,
        s"$S.1.0.ingesting",
        "{torn",
        line(1, "SessionStart"),
        line(2, "SessionStart", "w" -> "")
      )
      val recorded = ingest(dir, sessions, said).once()
      assert(
        recorded == 1,
        sessions.calls.asScala.toVector.flatMap(_._3).map(_.events.map(_.n)) == Vector(Vector(1L)),
        said.asScala.toVector == Vector(
          s"claude-code spool: $S.1.0.ingesting: 2 lines dropped: no JSON object; " +
            s"session claude-code:$S cannot be placed: no worktree"
        )
      )
    }

    test("a file whose recording fails is tried 3 times, then quarantined; 20 are kept there") {
      val dir = Files.createTempDirectory("spool")
      val sessions = new Recorder(Left(StoreError.DatabaseError("down")))
      val said = new ConcurrentLinkedQueue[String]()
      val quarantine = Files.createDirectories(dir.resolve("quarantine"))
      (1 to 20).foreach { i =>
        val f = Files.writeString(quarantine.resolve(f"old-$i%02d.ingesting"), "{}\n")
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_000_000L * i))
      }
      spooled(dir, s"$S.1.0.ingesting", line(1, "SessionStart"))
      val spool = ingest(dir, sessions, said)
      val passes = (1 to 4).map(_ => spool.once())
      val held = names(quarantine)
      assert(
        passes == Vector(0, 0, 0, 0),
        sessions.calls.size == 3,
        names(dir) == Set("quarantine"),
        held.size == 20,
        held.contains(s"$S.1.3.ingesting"),
        !held.contains("old-01.ingesting"),
        held.contains("old-02.ingesting"),
        said.asScala.toVector.last ==
          s"claude-code spool: $S.1.3.ingesting quarantined after 3 tries: DatabaseError(down)"
      )
    }
  }
}
