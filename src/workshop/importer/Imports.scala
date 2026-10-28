package workshop.importer

import java.nio.file.Path

import grit.core.plugin.Answered
import grit.projects.artifact.Project
import grit.projects.method.Method
import grit.projects.name.{KindName, MethodName, ProjectKey}
import grit.projects.render.ProjectMarkdown

import workshop.edge.CommandClient
import workshop.method.GritMethod

/** What one import did: the sources read, the artifacts imported by kind, the writes asked
  * and how many of them were refused (each with why), each part of a source that could not be
  * imported, with why, and, for a dry run, every way the import's own normal form fails to hold
  * it ([[Fidelity.problems]]).
  */
final case class Report(
    sources: Int,
    kinds: Map[String, Int],
    keeps: Int,
    moves: Int,
    refused: Vector[(String, String)],
    unmoved: Vector[(String, String)],
    failed: Vector[(String, String)],
    problems: Vector[String]
) extends caps.Pure {

  /** The report, a line each. */
  def lines: Vector[String] =
    Vector(
      s"sources: $sources",
      s"artifacts: ${kinds.values.sum} (${kinds.toVector.sorted.map((k, n) => s"$k $n").mkString(", ")})",
      s"writes: ${keeps + moves} ($keeps keeps, $moves moves), ${refused.size} refused, ${unmoved.size} unmoved"
    ) ++ refused.map((k, why) => s"refused: $k: $why") ++
      unmoved.map((k, why) => s"unmoved: $k: $why") ++
      failed.map((p, why) => s"not imported: $p: $why") ++ problems.map(p => s"problem: $p")
}

/** What `workshop verify` found: the import, and every way the project fails to hold it. */
final case class Verified(imported: Imported, problems: Vector[String]) extends caps.Pure

/** `workshop import` and `workshop verify`: grit's planning tree under a checkout, against the
  * project of a running serve, started under the deployment's default method when it is not
  * there.
  */
object Imports {

  /** Imports the tree under `root` into `project` through `client`, writing nothing when
    * `dry` (the project is still started when it is not there); what it did, or why it stopped before writing: serve unreachable, the project
    * unreadable or pinned to a method version not declared here, the roadmap unreadable, or
    * the import not a project ([[Sync.writes]]). A write refused, or a milestone move the
    * method has no way to, is reported, not a stop.
    */
  def run(
      client: CommandClient,
      root: Path,
      project: String,
      dry: Boolean
  ): Either[String, Report] =
    for {
      read <- prepared(client, root, project)
      synced <- Sync.writes(read.imported.project, read.existing, read.method)
      writes = synced.writes
      refused = if (dry) Vector.empty else writes.flatMap(w => asked(client, w))
      problems =
        if (!dry) Vector.empty
        else
          ProjectMarkdown.read(ProjectMarkdown.render(read.imported.project)) match {
            case Right(normal) => Fidelity.problems(read.sources, read.imported, normal)
            case Left(bad) => bad.map((f, why) => s"$f: does not read back: $why")
          }
    } yield Report(
      read.sources.size,
      read.imported.project.artifacts
        .groupBy(a => KindName.value(a.head.kind))
        .view
        .mapValues(_.size)
        .toMap,
      writes.count(_.command == "project_keep"),
      writes.count(_.command == "project_move"),
      refused,
      synced.unmoved,
      read.imported.failed,
      problems
    )

  /** Every way `project`, as `client` exports it, fails to hold the import of the tree under
    * `root` ([[Fidelity.problems]]), and every way its export is not at the law's normal form
    * (read after a render is not what was read); with the import, or why it could not be
    * checked.
    */
  def verify(client: CommandClient, root: Path, project: String): Either[String, Verified] =
    for {
      read <- prepared(client, root, project)
      normal = ProjectMarkdown.read(ProjectMarkdown.render(read.existing)) match {
        case Right(again) if again == read.existing => Vector.empty
        case Right(_) => Vector(s"$project's export reads back other than it was read")
        case Left(bad) => bad.map((f, why) => s"$f: does not read back: $why")
      }
    } yield Verified(
      read.imported,
      normal ++ Fidelity.problems(read.sources, read.imported, read.existing)
    )

  private def prepared(
      client: CommandClient,
      root: Path,
      project: String
  ): Either[String, Prepared] =
    for {
      key <- ProjectKey.of(project)
      _ <- started(client, project)
      files <- client.exported(project)
      existing <- ProjectMarkdown
        .read(files)
        .left
        .map(bad => s"$project cannot be read: ${bad.mkString("; ")}")
      methods <- GritMethod.all.left.map(_.mkString("; "))
      method <- methods
        .find(m =>
          m.name == existing.pin.method && m.version == existing.pin.version &&
            m.digest == existing.pin.digest
        )
        .toRight(
          s"$project holds to ${MethodName.value(existing.pin.method)} version ${existing.pin.version}, " +
            "not declared here with its digest"
        )
      corpus <- Corpus.read(root)
      imported <- Importer
        .of(key, existing.pin, method, corpus.sources)
        .left
        .map(bad => s"the import is no project: ${bad.mkString("; ")}")
    } yield Prepared(
      imported.copy(failed = corpus.missing ++ imported.failed),
      existing,
      method,
      corpus.sources
    )

  /** The import, what the project held before it, its method, and the sources read. */
  private final case class Prepared(
      imported: Imported,
      existing: Project,
      method: Method,
      sources: Vector[Source]
  )

  private def started(client: CommandClient, project: String): Either[String, Unit] =
    client.run("project_new", ujson.Obj("project" -> project)).flatMap {
      case Answered.Done(_) => Right(())
      case Answered.Refused(why) if why.endsWith("exists already") => Right(())
      case Answered.Refused(why) => Left(why)
    }

  private def asked(client: CommandClient, w: Write): Option[(String, String)] = {
    val key = w match {
      case Write.Keep(k, _) => s"keep ${k.value}"
      case Write.Move(k, to) => s"move ${k.value} to ${grit.projects.name.StateName.value(to)}"
    }
    client.run(w.command, w.arguments) match {
      case Right(Answered.Done(_)) => None
      case Right(Answered.Refused(why)) => Some(key -> why)
      case Left(why) => Some(key -> why)
    }
  }
}
