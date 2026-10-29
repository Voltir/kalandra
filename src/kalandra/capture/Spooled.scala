package kalandra.capture

import java.time.Instant

import grit.core.id.{Harness, SessionId}
import grit.core.place.{Namespace, Place}
import grit.core.session.{Event, Session}

/** One event `hooks/spool-hook.sh` spooled, in grit's words: on the session `id`, a child of
  * `parent` when a subagent's, worked at `at`.
  */
final case class Spooled(id: SessionId, parent: Option[SessionId], at: Place, event: Event)

object Spooled {

  /** 16,384: the most UTF-8 bytes of a line read; a closing's 2,000 characters fit, escaped. */
  val Longest: Int = 16384

  /** `claude-code`: the harness every spooled session is recorded under. */
  val Harness: Harness = grit.core.id.Harness.of("claude-code").fold(sys.error, identity)

  /** The event a spool line says, or why not: no JSON object, over [[Longest]] bytes, a kind
    * no hook writes, an ordinal not above 0, no absolute worktree to place it at, an id
    * [[SessionId.of]] refuses, a closing with no text, or a shape [[Event.Shape.of]] refuses.
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
      at <- o.get("w").flatMap(_.strOpt).filter(_.startsWith("/")) match {
        case Some(w) => Right(Place.under(Namespace.Fs, Vector(w)))
        case None =>
          Left(
            s"session ${grit.core.id.Harness.value(Harness)}:${id.local} cannot be placed: no worktree"
          )
      }
      k <- text(o, "k")
      d = o.get("d").flatMap(_.objOpt).fold(ujson.Obj())(ujson.Obj.from(_))
      kind <- kindOf(k, d, o.get("t").flatMap(_.strOpt))
      shape <- Event.Shape.of(d)
      event <- Event.of(
        n,
        Instant.ofEpochSecond(Math.floorDiv(n, Billion), Math.floorMod(n, Billion)),
        kind,
        shape
      )
    } yield Spooled(id, child.map(_ => self), at, event)

  private val Billion = 1_000_000_000L

  private def text(o: collection.Map[String, ujson.Value], key: String): Either[String, String] =
    o.get(key).flatMap(_.strOpt).toRight(s"no $key")

  /** The grit word for the hook's kind `k`, with its shape `d` and text `t`. */
  private def kindOf(k: String, d: ujson.Obj, t: Option[String]): Either[String, Event.Kind] =
    k match {
      case "SessionStart" | "SubagentStart" => Right(Event.Kind.Started)
      case "Read" =>
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
