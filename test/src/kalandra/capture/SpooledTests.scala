package kalandra.capture

import java.time.Instant

import grit.core.id.{ClaimHolder, SessionId}
import grit.core.place.{Namespace, Place}
import grit.core.session.{Event, Session}

import utest.*

/** A spool line, as `hooks/spool-hook.sh` writes it, read as an event in grit's words. */
object SpooledTests extends TestSuite {

  private val S = "faafcbf0-3592-4c99-8f14-21889a88798e"
  private val N = 1_793_245_884_897_034_415L
  private val At = Instant.ofEpochSecond(1_793_245_884L, 897_034_415L)
  private val Grit = Place.under(Namespace.Fs, Vector("/work/grit"))

  private def ok[A](e: Either[String, A]): A = e.fold(sys.error, identity)

  private def id(local: String): SessionId = ok(SessionId.of(Spooled.Harness, local))

  private def line(k: String, d: ujson.Obj, more: (String, ujson.Value)*): String =
    ujson.write(
      ujson.Obj.from(
        Vector[(String, ujson.Value)](
          "s" -> S,
          "n" -> N.toString,
          "k" -> k,
          "w" -> "/work/grit",
          "d" -> d
        ) ++
          more
      )
    )

  /** What `line` reads as: its session, parent and place, its kind, and its shape as written. */
  private def read(
      line: String
  ): Either[String, (SessionId, Option[SessionId], Place, Event.Kind, String)] =
    Spooled
      .of(line)
      .map(s => (s.id, s.parent, s.at, s.event.kind, Event.Shape.value(s.event.shape)))

  val tests = Tests {

    test("each kind the hook writes is its grit word, with its shape, session, time and place") {
      val summary = ok(Session.Summary.of("the work so far"))
      val kinds = Vector(
        line("SessionStart", ujson.Obj("source" -> "startup")) -> Event.Kind.Started,
        line("Read", ujson.Obj("path" -> "A.scala", "ok" -> true, "bytes" -> 6)) -> Event.Kind.Read(
          6
        ),
        line("Edit", ujson.Obj("path" -> "B.scala", "ok" -> true)) -> Event.Kind.Edited,
        line("Write", ujson.Obj("path" -> "B.scala", "ok" -> true)) -> Event.Kind.Edited,
        line("MultiEdit", ujson.Obj("path" -> "B.scala", "ok" -> true)) -> Event.Kind.Edited,
        line("NotebookEdit", ujson.Obj("path" -> "B.ipynb", "ok" -> true)) -> Event.Kind.Edited,
        line("Bash", ujson.Obj("program" -> "git", "ok" -> false)) -> Event.Kind.Ran,
        line(
          "mcp__scout__show",
          ujson.Obj("named" -> "Ledger.post", "tool" -> "show", "ok" -> true, "bytes" -> 9)
        ) -> Event.Kind.Read(9),
        line("commit", ujson.Obj("sha" -> "abc", "subject" -> "s")) -> Event.Kind.Commit,
        line("Stop", ujson.Obj(), "t" -> "the work so far") -> Event.Kind.Closing(summary),
        line("PostCompact", ujson.Obj("trigger" -> "auto"), "t" -> "the work so far") ->
          Event.Kind.Closing(summary),
        line("SessionEnd", ujson.Obj("reason" -> "other")) -> Event.Kind.Ended
      )
      val read = kinds.map((l, _) => Spooled.of(l))
      assert(
        read.map(_.map(s => (s.id, s.parent, s.at, s.event.kind))) ==
          kinds.map((_, k) => Right((id(S), None, Grit, k))),
        read.map(_.map(_.event.at)) == Vector.fill(kinds.size)(Right(At)),
        read.map(_.map(_.event.n)) == Vector.fill(kinds.size)(Right(N)),
        read.map(_.map(s => Event.Shape.value(s.event.shape))).take(2) ==
          Vector(
            Right("""{"source":"startup"}"""),
            Right("""{"path":"A.scala","ok":true,"bytes":6}""")
          )
      )
    }

    test("a commit's shape names its worktree; one too long with its subject leaves it out") {
      val worktree = "/work/" + ("w" * 150)
      def commit(subject: String) = ujson.write(
        ujson.Obj(
          "s" -> S,
          "n" -> N.toString,
          "k" -> "commit",
          "w" -> worktree,
          "d" -> ujson.Obj("sha" -> "abc", "subject" -> subject)
        )
      )
      val written = s"fs:$worktree"
      assert(
        read(commit("fix")).map(_._5) ==
          Right(s"""{"sha":"abc","subject":"fix","worktree":"$written"}"""),
        read(commit("é" * 100)).map(_._5) == Right(s"""{"sha":"abc","worktree":"$written"}""")
      )
    }

    test(
      "an edit's or a read's worktree is written as a place; one that is no absolute path refuses the line"
    ) {
      val fixer = "/work/grit/.claude/worktrees/fixer"
      def touched(k: String, w: String) =
        line(k, ujson.Obj("path" -> "core/X.scala", "ok" -> true, "bytes" -> 3, "worktree" -> w))
      assert(
        read(touched("Edit", fixer)).map(_._5) ==
          Right(s"""{"path":"core/X.scala","ok":true,"bytes":3,"worktree":"fs:$fixer"}"""),
        read(touched("Read", fixer)).map(_._5) ==
          Right(s"""{"path":"core/X.scala","ok":true,"bytes":3,"worktree":"fs:$fixer"}"""),
        read(touched("Edit", "fixer")).isLeft
      )
    }

    test("a subagent's line is a session of its own, its parent's child, at its own worktree") {
      val sub = ujson.write(
        ujson.Obj(
          "s" -> S,
          "a" -> "a068ac6",
          "n" -> N.toString,
          "k" -> "SubagentStart",
          "w" -> "/work/spike",
          "d" -> ujson.Obj("type" -> "Explore")
        )
      )
      val stop = sub.replace("SubagentStart", "SubagentStop")
      Vector(read(sub), read(stop)) ==> Vector(
        Right(
          (
            id(s"$S/a068ac6"),
            Some(id(S)),
            Place.under(Namespace.Fs, Vector("/work/spike")),
            Event.Kind.Started,
            """{"type":"Explore"}"""
          )
        ),
        Right(
          (
            id(s"$S/a068ac6"),
            Some(id(S)),
            Place.under(Namespace.Fs, Vector("/work/spike")),
            Event.Kind.Ended,
            """{"type":"Explore"}"""
          )
        )
      )
    }

    test("refused, saying why: what no hook writes, or what grit would refuse") {
      val start = line("SessionStart", ujson.Obj("source" -> "startup"))
      Vector(
        "{not json",
        "[1]",
        start + " " * Spooled.Longest,
        line("WebFetch", ujson.Obj()),
        start.replace(N.toString, "0"),
        start.replace("\"w\":\"/work/grit\"", "\"w\":\"\""),
        start.replace("\"w\":\"/work/grit\"", "\"w\":\"work/grit\""),
        start.replace(S, ""),
        line("Stop", ujson.Obj()),
        line("Read", ujson.Obj("bytes" -> -1)),
        line("SessionStart", ujson.Obj("source" -> "x" * 400))
      ).map(l => Spooled.of(l).left.map(_ => "refused")) ==> Vector.fill(11)(Left("refused"))
    }

    test("a start line's `h` is the holder its session works as; a line without one names none") {
      val start = line("SessionStart", ujson.Obj("source" -> "startup"), "h" -> "0beec7b5")
      val read = line("Read", ujson.Obj("path" -> "A.scala", "ok" -> true, "bytes" -> 6))
      Vector(start, read).map(l => Spooled.of(l).map(_.holder)) ==>
        Vector(Right(Some(ok(ClaimHolder.of("0beec7b5")))), Right(None))
    }

    test("a holder grit refuses drops its line, saying why") {
      val start = line("SessionStart", ujson.Obj("source" -> "startup"), "h" -> "x" * 161)
      Spooled.of(start) ==> Left("a claim's holder is 1 to 160 characters")
    }

    test("a line with no worktree says which session it cannot place") {
      val unplaced = line("SessionStart", ujson.Obj("source" -> "startup"))
        .replace("\"w\":\"/work/grit\"", "\"w\":\"\"")
      Spooled.of(unplaced) ==> Left(s"session claude-code:$S cannot be placed: no worktree")
    }
  }
}
