package kalandra.importer

import scala.util.matching.Regex

import grit.projects.artifact.{Artifact, Body, Project}
import grit.projects.method.Method
import grit.projects.name.{ArtifactKey, KindName, StateName}
import grit.projects.render.ProjectMarkdown

/** One write the import asks of a running serve. */
enum Write extends caps.Pure {

  /** `project_keep` with `args`, keeping the artifact `key`. */
  case Keep(key: ArtifactKey, args: ujson.Obj)

  /** `project_move` of `key` to `to`. */
  case Move(key: ArtifactKey, to: StateName)

  /** The command's name. */
  def command: String = this match {
    case Keep(_, _) => "project_keep"
    case Move(_, _) => "project_move"
  }

  /** The command's arguments. */
  def arguments: ujson.Obj = this match {
    case Keep(_, args) => args
    case Move(key, to) => ujson.Obj("key" -> key.value, "state" -> StateName.value(to))
  }
}

/** What the import writes ([[Sync.writes]]): the writes; each milestone move it could not make,
  * as `move <key> to <state>` with why; and each write's line, what a keep changes or a move's
  * from and to.
  */
final case class Synced(
    writes: Vector[Write],
    unmoved: Vector[(String, String)],
    described: Vector[String]
) extends caps.Pure

/** What an import writes to a project already holding `existing`. */
object Sync {

  /** The writes that make `existing` hold `imported` (as it reads back after one render, the
    * law's normal form), of `method`; none when it holds it already, so a second import writes
    * nothing. Each artifact not held already, or held with another title, parent, cites or
    * body, is kept, a parent before its children; one citing what is not kept yet is first
    * kept without those cites (its body's links to them left as their text), then again whole
    * once every artifact is kept. Then each new artifact is moved, its children first, along the
    * method's shortest way from its kind's first state to its imported state. An existing
    * artifact keeps the state `existing` holds, except a milestone, whose state the roadmap
    * decides: it is moved along the way from the state it holds, and one with no such way is
    * in [[Synced.unmoved]] rather than a stop. Nothing is deleted: an artifact `existing` holds
    * that the import does not is left as it is ([[Fidelity]] names it). Refused when the
    * import's normal form is not a project, an artifact would change its kind, or a new
    * artifact has no way of moves from its kind's first state to its own.
    */
  def writes(imported: Project, existing: Project, method: Method): Either[String, Synced] =
    for {
      desired <- ProjectMarkdown
        .read(ProjectMarkdown.render(imported))
        .left
        .map(bad => s"the import does not read back after a render: ${bad.mkString("; ")}")
      held = existing.artifacts.map(a => a.head.key -> a).toMap
      _ <- desired.artifacts
        .find(a => held.get(a.head.key).exists(_.head.kind != a.head.kind))
        .map(a => s"${a.head.key.value} would change its kind")
        .toLeft(())
      ordered = desired.artifacts.sortBy(a => depth(desired, a))
      keeps = kept(ordered, held)
      wanted = desired.artifacts.map(a => a.head.key -> a).toMap
      moves <- ordered.reverse.foldLeft[Either[String, Synced]](
        Right(Synced(Vector.empty, Vector.empty, Vector.empty))
      ) { (done, a) =>
        for {
          s <- done
          step <- moved(method, held.get(a.head.key), a)
        } yield Synced(
          s.writes ++ step.writes,
          s.unmoved ++ step.unmoved,
          s.described ++ step.described
        )
      }
      keepLines = keeps.flatMap {
        case Write.Keep(k, _) =>
          wanted.get(k).map(a => s"keep ${k.value}: ${changes(held.get(k), a)}").toVector
        case Write.Move(_, _) => Vector.empty
      }
    } yield Synced(keeps ++ moves.writes, moves.unmoved, keepLines ++ moves.described)

  /** The moves `a` gets, given the artifact `existing` holds under its key, if any. A new
    * artifact is moved from its kind's first state. An existing one that is no milestone keeps
    * its stored state, so gets none; an existing milestone is moved from the state it holds,
    * and when the method has no way there it is unmoved, with why.
    */
  private def moved(method: Method, held: Option[Artifact], a: Artifact): Either[String, Synced] =
    held match {
      case Some(_) if KindName.value(a.head.kind) != "milestone" =>
        Right(Synced(Vector.empty, Vector.empty, Vector.empty))
      case Some(h) =>
        Right(
          way(method, a.head.kind, h.head.state, a.head.state).fold(
            why =>
              Synced(
                Vector.empty,
                Vector(s"move ${a.head.key.value} to ${StateName.value(a.head.state)}" -> why),
                Vector.empty
              ),
            path => walk(a, h.head.state, path)
          )
        )
      case None =>
        first(method, a.head.kind)
          .toRight(s"${KindName.value(a.head.kind)} is no kind of the method")
          .flatMap(from => way(method, a.head.kind, from, a.head.state).map(walk(a, from, _)))
    }

  /** The moves of `a` along `path` from `from`, each with its line: the state it leaves and the
    * state it takes.
    */
  private def walk(a: Artifact, from: StateName, path: Vector[StateName]): Synced = {
    val steps = (from +: path.dropRight(1)).zip(path).map { (f, t) =>
      (
        Write.Move(a.head.key, t),
        s"move ${a.head.key.value}: state ${StateName.value(f)} to ${StateName.value(t)}"
      )
    }
    Synced(steps.map(_._1), Vector.empty, steps.map(_._2))
  }

  /** What keeping `a` changes from what `held` holds under its key, or `new` when nothing is. */
  private def changes(held: Option[Artifact], a: Artifact): String =
    held.fold("new") { h =>
      Vector(
        Option.when(h.head.title != a.head.title)("title"),
        Option.when(h.head.parent != a.head.parent)("parent"),
        Option.when(h.head.cites != a.head.cites)("cites"),
        Option.when(h.body != a.body)("body")
      ).flatten.mkString(", ")
    }

  private def kept(ordered: Vector[Artifact], held: Map[ArtifactKey, Artifact]): Vector[Write] = {
    val start = (held.keySet, Vector.empty[Write], Vector.empty[Artifact])
    val (_, first, deferred) = ordered.foldLeft(start) { case ((present, ws, later), a) =>
      if (held.get(a.head.key).exists(same(_, a))) (present, ws, later)
      else {
        val refs = (a.head.cites ++ Body.cites(a.body)).filterNot(_ == a.head.key)
        if (refs.forall(present)) (present + a.head.key, ws :+ keep(a, a.head.cites, None), later)
        else
          (
            present + a.head.key,
            ws :+ keep(a, a.head.cites.filter(present), Some(present)),
            later :+ a
          )
      }
    }
    first ++ deferred.map(a => keep(a, a.head.cites, None))
  }

  private def same(held: Artifact, a: Artifact): Boolean =
    (held.head.title, held.head.parent, held.head.cites, held.body) ==
      (a.head.title, a.head.parent, a.head.cites, a.body)

  // A link to an artifact as Body.markdown writes it: its text, where `]` and `\` are escaped.
  // Possessive, so a long body does not recurse a frame a character.
  private val Reference: Regex = """\[((?:[^\]\\\n]++|\\.)*+)\]\(artifact:([^)\s]+)\)""".r

  // `a` kept with `cites`; with `present`, each link of its body to an artifact not among them
  // left as its text.
  private def keep(
      a: Artifact,
      cites: Vector[ArtifactKey],
      present: Option[Set[ArtifactKey]]
  ): Write = {
    val markdown = Body.markdown(a.body)
    val body = present.fold(markdown)(ks =>
      Reference.replaceAllIn(
        markdown,
        m =>
          Regex.quoteReplacement(
            if (ArtifactKey.read(m.group(2)).toOption.exists(ks)) m.matched else m.group(1)
          )
      )
    )
    val args = ujson.Obj(
      "key" -> a.head.key.value,
      "kind" -> KindName.value(a.head.kind),
      "title" -> a.head.title,
      "body" -> body,
      "cites" -> ujson.Arr.from(cites.map(c => ujson.Str(c.value)))
    )
    a.head.parent.foreach(p => args("parent") = p.value)
    Write.Keep(a.head.key, args)
  }

  private def depth(p: Project, a: Artifact): Int = {
    val parents = p.artifacts.map(x => x.head.key -> x.head.parent).toMap
    Iterator
      .iterate(a.head.parent)(_.flatMap(k => parents.getOrElse(k, None)))
      .takeWhile(_.isDefined)
      .take(p.artifacts.size + 1)
      .size
  }

  private def first(method: Method, kind: KindName): Option[StateName] =
    method.kinds.find(_.name == kind).map(_.first)

  /** The states the method's shortest way of moves passes from `from` to `to`, `to` last. */
  private def way(
      method: Method,
      kind: KindName,
      from: StateName,
      to: StateName
  ): Either[String, Vector[StateName]] = {
    @scala.annotation.tailrec
    def search(
        frontier: Vector[Vector[StateName]],
        seen: Set[StateName]
    ): Option[Vector[StateName]] =
      frontier.find(_.lastOption.contains(to)) match {
        case Some(path) => Some(path)
        case None if frontier.isEmpty => None
        case None =>
          val next = frontier.flatMap(path =>
            path.lastOption.toVector.flatMap(s =>
              method.from(kind, s).map(_.to).filterNot(seen).map(path :+ _)
            )
          )
          search(next, seen ++ next.flatMap(_.lastOption))
      }
    search(Vector(Vector(from)), Set(from))
      .map(_.drop(1))
      .toRight(
        s"no moves take a ${KindName.value(kind)} from ${StateName.value(from)} to ${StateName.value(to)}"
      )
  }
}
