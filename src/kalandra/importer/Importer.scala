package kalandra.importer

import grit.projects.artifact.Project
import grit.projects.method.Method
import grit.projects.name.{KindName, MethodName, Pin, ProjectKey}
import grit.projects.render.ProjectMarkdown

/** grit's planning tree imported as one project (S7's mapping): `project`, read from its
  * export-shaped `files`, each artifact's [[Provenance]] by its key's artifact part, and each
  * part of a source that could not be imported, with why.
  */
final case class Imported(
    project: Project,
    files: Vector[(String, String)],
    provenance: Map[String, Provenance],
    failed: Vector[(String, String)]
) extends caps.Pure

/** grit's markdown tree (`roadmap/ROADMAP.md`, `roadmap/done.md`, the rest of `roadmap/`,
  * `roadmap/mechanisms/`, `.local/backlog/`, and the open plans of `.local/plans/`) as a
  * project of method `grit` version 2, through its export files (ADR 0049):
  *
  *   - the roadmap's preamble is a `section`, `roadmap`, each of its `##` sections a `section`
  *     under it; each item of Milestones, Now, Next and Parallel a `milestone` under it, sitting
  *     `later`, `now`, `next` or `parallel` as its section says, and `now` when the Now section
  *     names its `M<n>` in bold;
  *   - each entry of `done.md` and each of its `##` sections a `milestone` `done`;
  *   - each mechanism doc a `mechanism`, each backlog file and the rest of `roadmap/` a `note`,
  *     each directory's README a `section` they sit under;
  *   - each open plan a `plan`, under the milestone naming it, each of its `##` sections a child
  *     `stage` (a heading `S<n>`, `CP<n>`, `Stage <n>` or `Checkpoint <n>`) or `section`;
  *   - any artifact over the stored cap ([[grit.projects.artifact.Body.StoredMost]]) split at its
  *     `##`, then `###`, headings into child `section`s.
  *
  * A markdown link to an imported file becomes a reference to its artifact; a code span naming
  * one (`` `.local/backlog/x.md` ``), a cite in the head; a link to anything else, a closed plan
  * among them, stays as written.
  */
object Importer {

  /** `sources` as the project `key` pinned at `pin`, of `method`; or every file of the import
    * that is not one, with why, when the project's index is among them.
    */
  def of(
      key: ProjectKey,
      pin: Pin,
      method: Method,
      sources: Vector[Source]
  ): Either[Vector[(String, String)], Imported] = {
    val drafted = Mapping.of(ProjectKey.value(key), sources)
    val project = ProjectKey.value(key)
    val index = ProjectMarkdown.Index -> front(
      Vector(
        "key" -> project,
        "method" -> MethodName.value(pin.method),
        "version" -> pin.version.toString,
        "digest" -> pin.digest
      )
    )
    def file(d: Draft): (String, String) = {
      val first = method.kinds
        .find(k => KindName.value(k.name) == d.kind)
        .fold(d.kind)(k => grit.projects.name.StateName.value(k.first))
      s"$project/${d.local}.md" -> (front(
        Vector(
          "key" -> s"$project/${d.local}",
          "kind" -> d.kind,
          "state" -> d.state.getOrElse(first),
          "title" -> ujson.write(ujson.Str(d.title)),
          "parent" -> d.parent.fold("null")(p => s"$project/$p"),
          "cites" -> d.cites.map(c => s"$project/$c").mkString("[", ", ", "]"),
          "by" -> "null"
        )
      ) + "\n" + d.provenance.text + "\n")
    }
    // Reads the files, dropping each the reader refuses, until what is left is a project.
    @scala.annotation.tailrec
    def read(
        drafts: Vector[Draft],
        failed: Vector[(String, String)]
    ): Either[Vector[(String, String)], Imported] = {
      val files = drafts.map(d => d -> file(d))
      ProjectMarkdown.read(index +: files.map(_._2)) match {
        case Right(p) =>
          Right(
            Imported(
              p,
              index +: files.map(_._2),
              drafts.map(d => d.local -> d.provenance).toMap,
              failed
            )
          )
        case Left(bad) if bad.exists(_._1 == ProjectMarkdown.Index) || bad.isEmpty => Left(bad)
        case Left(bad) =>
          val refused = bad.toMap
          val (out, in) = files.partition((_, f) => refused.contains(f._1))
          read(
            in.map(_._1),
            failed ++ out.map((d, f) =>
              s"${d.provenance.source} (${f._1})" -> refused.getOrElse(f._1, "")
            )
          )
      }
    }
    read(drafted.drafts, drafted.failed)
  }

  /** The plans `roadmap`, the roadmap's text, names outside its Done and Update Protocol
    * sections: the open plans, as paths under the checkout.
    */
  def openPlans(roadmap: String): Vector[String] = Mapping.openPlans(roadmap)

  private def front(fields: Vector[(String, String)]): String =
    fields.map((k, v) => s"$k: $v").mkString("---\n", "\n", "\n---\n")
}
