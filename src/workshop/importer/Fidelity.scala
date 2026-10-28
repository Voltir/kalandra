package workshop.importer

import grit.projects.artifact.{Body, Project}
import grit.prose.form.{Mark, Span}
import grit.prose.markdown.Markdown

/** Whether a project holds an import whole: S7's cutover criterion (a). */
object Fidelity {

  /** `text` as markdown reads and writes it once: `Markdown.write(Markdown.parse(text))`, the
    * one function an artifact's text is compared under (grit has no `Body.normalise`; it is
    * defined here, since nothing in grit changes for the import).
    */
  def normalise(text: String): String = Markdown.write(Markdown.parse(text))

  /** Every way `stored` fails to hold `imported` of `sources`, none when it holds it whole: a
    * source whose lines are not each in exactly one artifact (a source with a part that failed
    * to import excepted: [[Imported.failed]] names it); an artifact's text that differs from its
    * source's lines other than in a link's target; an artifact missing from `stored`, or whose
    * head (kind, state, title, parent, cites) differs, or whose body differs from its text
    * under [[normalise]]; and an artifact of `stored` the import did not draft.
    */
  def problems(sources: Vector[Source], imported: Imported, stored: Project): Vector[String] = {
    val failedSources = imported.failed.map(_._1.takeWhile(c => c != ' ' && c != ':')).toSet
    val covered = imported.provenance.values.toVector.groupBy(_.source)
    val coverage = sources.filterNot(s => failedSources(s.path)).flatMap { s =>
      val lines = new Lines(s.text)
      val counts = covered
        .getOrElse(s.path, Vector.empty)
        .flatMap(_.lines.flatMap((a, b) => a until b))
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap
      val bad = lines.all.indices
        .filter(i => lines.all.lift(i).exists(!_.isBlank) && counts.getOrElse(i, 0) != 1)
      bad.headOption.map(i =>
        s"${s.path}: ${bad.size} lines not in exactly one artifact, the first line ${i + 1}"
      )
    }
    val byKey = stored.artifacts.map(a => a.head.key.value -> a).toMap
    val drafted = imported.project.artifacts.map(a => a.head.key.value).toSet
    val each = imported.project.artifacts.flatMap { a =>
      val key = a.head.key.value
      val local = a.head.key.local
      val provenance = imported.provenance.get(local)
      val rewritten = provenance.toVector.collect {
        case p if unlinked(p.original) != unlinked(p.text) =>
          s"$key: its text differs from ${p.source} beyond a link's target"
      }
      val held = byKey.get(key) match {
        case None => Vector(s"$key: not stored")
        case Some(s) =>
          val h = s.head
          val head = Option.when(
            (h.kind, h.state, h.title, h.parent, h.cites) !=
              (a.head.kind, a.head.state, a.head.title, a.head.parent, a.head.cites)
          )(s"$key: its stored head differs")
          val body = provenance.collect {
            case p if normalise(p.text) != normalise(Body.markdown(s.body)) =>
              s"$key: its stored body is not ${p.source}'s text under normalise"
          }
          head.toVector ++ body
      }
      rewritten ++ held
    }
    val extra = stored.artifacts
      .map(_.head.key.value)
      .filterNot(drafted)
      .map(k => s"$k: stored, but not drafted by the import")
    coverage ++ each ++ extra
  }

  /** `text` normalised with every link's target dropped, its link text kept. */
  private def unlinked(text: String): String =
    Markdown.write(
      Markdown
        .parse(text)
        .mapSpans(s => Span(s.text, s.marks.filter { case Mark.Link(_) => false; case _ => true }))
    )
}
