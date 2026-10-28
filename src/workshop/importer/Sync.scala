package workshop.importer

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
    case Move(key, to) => ujson.Obj("key" -> key.value, "to" -> StateName.value(to))
  }
}

/** What an import writes to a project already holding `existing`. */
object Sync {

  /** The writes that make `existing` hold `imported` (as it reads back after one render, the
    * law's normal form), of `method`; none when it holds it already, so a second import writes
    * nothing. Each artifact not held already, or held with another title, parent, cites or
    * body, is kept, a parent before its children; one citing what is not kept yet is first
    * kept without those cites (its body's links to them left as their text), then again whole
    * once every artifact is kept. Then each is moved, its children first, along the method's
    * shortest way from the state it is in to its imported state. Nothing is deleted: an
    * artifact `existing` holds that the import does not is left as it is ([[Fidelity]] names
    * it). Refused when the import's normal form is not a project, an artifact would change its
    * kind, or no way of moves reaches its state.
    */
  def writes(imported: Project, existing: Project, method: Method): Either[String, Vector[Write]] =
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
      moves <- ordered.reverse.foldLeft[Either[String, Vector[Write]]](Right(Vector.empty)) {
        (done, a) =>
          for {
            ws <- done
            from = held.get(a.head.key).map(_.head.state).orElse(first(method, a.head.kind))
            way <- from.fold[Either[String, Vector[StateName]]](
              Left(s"${KindName.value(a.head.kind)} is no kind of the method")
            )(f => way(method, a.head.kind, f, a.head.state))
          } yield ws ++ way.map(Write.Move(a.head.key, _))
      }
    } yield keeps ++ moves

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
