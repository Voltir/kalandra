package kalandra.capture

import java.time.Instant

import grit.core.id.{ClaimHolder, Harness, SessionId}
import grit.core.place.{Namespace, Place}
import grit.core.session.{Event, Session}

/** One event `hooks/spool-hook.sh` spooled, in grit's words: on the session `id`, a child of
  * `parent` when a subagent's, worked at `at`, as the claim holder `holder` when its line names
  * one (a start's, its slot).
  */
final case class Spooled(
    id: SessionId,
    parent: Option[SessionId],
    at: Place,
    holder: Option[ClaimHolder],
    event: Event
)

object Spooled {

  /** 16,384: the most UTF-8 bytes of a line read; a closing's 2,000 characters fit, escaped. */
  val Longest: Int = 16384

  /** `claude-code`: the harness every spooled session is recorded under. */
  val Harness: Harness = grit.core.id.Harness.of("claude-code").fold(sys.error, identity)

  /** The event a spool line says, or why not: no JSON object, over [[Longest]] bytes, a kind
    * no hook writes, an ordinal not above 0, no absolute worktree to place it at, an id
    * [[SessionId.of]] refuses, a holder [[ClaimHolder.of]] refuses, a closing with no text, or a
    * shape [[Event.Shape.of]] refuses. A commit's shape names the worktree it was made in
    * ([[Event.Shape.committed]]), its subject left out when the shape would be too long with it.
    */
  def of(line: String): Either[String, Spooled] =
    for {
      _ <- Either.cond(
        line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= Longest,
        (),
        s"a line over $Longest bytes"
      )
      o <- scala.util.Try(ujson.read(line)).toOption.flatMap(_.objOpt).toRight("no JSON object")
      s <- text(o, "s")
      self <- SessionId.of(Harness, s)
      child <- o.get("a").flatMap(_.strOpt).filter(_.nonEmpty) match {
        case None => Right(None)
        case Some(a) => SessionId.of(Harness, s"$s/$a").map(Some(_))
      }
      id = child.getOrElse(self)
      n <- o.get("n").flatMap(_.strOpt).flatMap(_.toLongOption).toRight("no ordinal")
      at <- o.get("w").flatMap(_.strOpt).flatMap(worktree) match {
        case Some(w) => Right(w)
        case None =>
          Left(
            s"session ${grit.core.id.Harness.value(Harness)}:${id.local} cannot be placed: no worktree"
          )
      }
      holder <- o.get("h").flatMap(_.strOpt) match {
        case None => Right(None)
        case Some(h) => ClaimHolder.of(h).map(Some(_))
      }
      k <- text(o, "k")
      d = o.get("d").flatMap(_.objOpt).fold(ujson.Obj())(ujson.Obj.from(_))
      kind <- kindOf(k, d, o.get("t").flatMap(_.strOpt))
      shape <- kind match {
        case Event.Kind.Commit =>
          Event.Shape
            .committed(at, d)
            .orElse(
              Event.Shape
                .committed(at, ujson.Obj.from(d.value.toSeq.filterNot(_._1 == Event.Shape.Subject)))
            )
        case _ => Event.Shape.of(d)
      }
      event <- Event.of(
        n,
        Instant.ofEpochSecond(Math.floorDiv(n, Billion), Math.floorMod(n, Billion)),
        kind,
        shape
      )
    } yield Spooled(id, child.map(_ => self), at, holder, event)

  /** The worktree at the absolute path `path`, as a session's place; none for a relative one. */
  def worktree(path: String): Option[Place] =
    Option.when(path.startsWith("/"))(Place.under(Namespace.Fs, Vector(path)))

  private val Billion = 1_000_000_000L

  /** `mcp__scout__`: how Claude Code names scout's tools; a query of one is a read. */
  private val Scout = "mcp__scout__"

  private def text(o: collection.Map[String, ujson.Value], key: String): Either[String, String] =
    o.get(key).flatMap(_.strOpt).toRight(s"no $key")

  /** The grit word for the hook's kind `k`, with its shape `d` and text `t`. */
  private def kindOf(k: String, d: ujson.Obj, t: Option[String]): Either[String, Event.Kind] =
    k match {
      case "SessionStart" | "SubagentStart" => Right(Event.Kind.Started)
      case read if read == "Read" || read.startsWith(Spooled.Scout) =>
        d.value
          .get("bytes")
          .flatMap(_.numOpt)
          .map(_.toLong)
          .toRight("a read with no bytes")
          .map(Event.Kind.Read(_))
      case "Edit" | "Write" | "MultiEdit" | "NotebookEdit" => Right(Event.Kind.Edited)
      case "Bash" => Right(Event.Kind.Ran)
      case "commit" => Right(Event.Kind.Commit)
      case "Stop" | "PostCompact" =>
        t.toRight(s"a $k with no text").flatMap(Session.Summary.of).map(Event.Kind.Closing(_))
      case "SessionEnd" | "SubagentStop" => Right(Event.Kind.Ended)
      case other => Left(s"no hook writes the kind $other")
    }
}
