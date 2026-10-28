package workshop.importer

import scala.util.matching.Regex

import grit.projects.artifact.Body
import grit.prose.form.{Mark, Span}
import grit.prose.markdown.Markdown

/** One artifact the mapping drafts, its key's artifact part `local`: in `state`, or its kind's
  * first state when none.
  */
private[importer] final case class Draft(
    local: String,
    kind: String,
    state: Option[String],
    title: String,
    parent: Option[String],
    cites: Vector[String],
    provenance: Provenance
) extends caps.Pure

/** What the mapping drafted, and each part of a source it could not, with why. */
private[importer] final case class Drafted(drafts: Vector[Draft], failed: Vector[(String, String)])
    extends caps.Pure {
  def ++(other: Drafted): Drafted = Drafted(drafts ++ other.drafts, failed ++ other.failed)
}

private[importer] object Drafted {
  val empty: Drafted = Drafted(Vector.empty, Vector.empty)
}

/** A code span's text, as a note on its span. */
private final case class Mention(text: String) extends caps.Pure

/** grit's planning tree as drafts of artifacts of method `grit` version 2 (S7's mapping). */
private[importer] object Mapping {

  val Roadmap: String = "roadmap/ROADMAP.md"
  val Done: String = "roadmap/done.md"
  val Mechanisms: String = "roadmap/mechanisms/"
  val Backlog: String = ".local/backlog/"
  val Plans: String = ".local/plans/"
  val Index: String = "README.md"

  /** A plan's path as the roadmap names it: `.local/plans/<name>.md`, or `plans/<name>.md`. */
  val PlanRef: Regex = """(?:\.local/)?plans/([A-Za-z0-9._-]+\.md)""".r

  /** The `##` sections of the roadmap whose items are milestones, with the state each sits in.
    */
  private val ItemSections: Map[String, String] =
    Map("Milestones" -> "later", "Now" -> "now", "Next" -> "next", "Parallel" -> "parallel")

  /** The roadmap's sections that name no open work. */
  val Closed: Set[String] = Set("Done", "Update Protocol")

  private val RoadmapItem: Regex = """^- (?:\[[ xX]\] )?\*\*(.+?)\*\*""".r.unanchored
  private val DoneItem: Regex = """^(?:- )?\*\*(.+?)\*\*""".r.unanchored
  private val MilestoneId: Regex = """^M(\d+(?:\.\d+)?)(?![.\d])""".r.unanchored
  private val NowNames: Regex = """\*\*M(\d+(?:\.\d+)?)(?![.\d])""".r
  private val Stage: Regex = """^(?:S\d+[a-z]?|CP\d+[a-z]?|Stage \d+|Checkpoint \d+)\b.*""".r
  private val Link: Regex = """\[([^\]\n]*)\]\(([^)\s]+)\)""".r
  private val Path: Regex = """[\w./-]+\.md(?:#\S*)?""".r

  /** `sources` drafted as artifacts of `project`. */
  def of(project: String, sources: Vector[Source]): Drafted = {
    val taken = scala.collection.mutable.Set.empty[String]
    def unique(base: String): String = {
      val b = clip(base, 72)
      val key = Iterator.from(1).map(n => if (n == 1) b else s"$b-$n").dropWhile(taken).next()
      taken += key
      key
    }
    val ordered = sources.sortBy(s => (rank(s.path), s.path))
    val roots: Map[String, String] = ordered.map(s => s.path -> unique(fileKey(s.path))).toMap
    val byName: Map[String, String] =
      roots.toVector.groupBy((p, _) => p.split('/').lastOption.getOrElse(p)).collect {
        case (name, Vector((_, local))) => name -> local
      }
    def resolve(from: String, ref: String): Option[String] = {
      val p = ref.takeWhile(_ != '#')
      if (p.isEmpty || p.contains(':')) None
      else
        roots
          .get(normal(dir(from) + p))
          .orElse(roots.get(normal(p)))
          .orElse(if (p.contains('/')) None else byName.get(p))
    }
    val prepared = ordered.map { s =>
      val lines = new Lines(s.text)
      val self = roots.getOrElse(s.path, "")
      val rewritten = lines.all.zipWithIndex.map { (line, i) =>
        if (lines.fenced(i)) line
        else
          Link.replaceAllIn(
            line,
            m => {
              val inCode = line.take(m.start).count(_ == '`') % 2 == 1
              val target = if (inCode) None else resolve(s.path, m.group(2)).filter(_ != self)
              Regex.quoteReplacement(
                target.fold(m.matched)(t => s"[${m.group(1)}](${Body.Scheme}$project/$t)")
              )
            }
          )
      }
      Prepared(s.path, self, lines, rewritten)
    }
    val roadmapRoot = roots.get(Roadmap)

    def draft(
        src: Prepared,
        local: String,
        kind: String,
        state: Option[String],
        title: String,
        parent: Option[String],
        ranges: Vector[(Int, Int)]
    ): Draft = {
      val text = joined(src.rewritten, ranges)
      val mentioned = Markdown
        .parse(text)
        .mapSpans(s =>
          Span(s.text, s.marks, if (s.marks(Mark.Code)) Vector(Mention(s.text)) else Vector.empty)
        )
        .notes
        .map(_.text.trim)
        .filter(Path.matches)
        .flatMap(resolve(src.path, _))
        .filterNot(_ == src.self)
        .distinct
      Draft(
        local,
        kind,
        state,
        title,
        parent,
        mentioned,
        Provenance(src.path, ranges, joined(src.lines.all, ranges), text)
      )
    }

    def checked(d: Draft): Drafted =
      Body.read(d.provenance.text) match {
        case Right(_) => Drafted(Vector(d), Vector.empty)
        case Left(why) => Drafted(Vector.empty, Vector(where(d) -> why))
      }

    // An artifact of `range`, split at its `level` headings (then deeper, to `###`) when it is
    // over the stored cap or `split` says to; each part's kind `part` of its heading.
    def document(
        src: Prepared,
        local: String,
        kind: String,
        state: Option[String],
        title: String,
        parent: Option[String],
        range: (Int, Int),
        level: Int,
        split: Boolean,
        part: String => String
    ): Drafted = {
      val whole = draft(src, local, kind, state, title, parent, Vector(range))
      if (!split && Body.read(whole.provenance.text).isRight) Drafted(Vector(whole), Vector.empty)
      else
        src.lines.sections(range, level) match {
          case (_, Vector()) if level < 3 =>
            document(src, local, kind, state, title, parent, range, level + 1, split, part)
          case (_, Vector()) => checked(whole)
          case (pre, sections) =>
            val (own, rest) =
              if (src.lines.blank(pre)) (sections.take(1).map(_._2), sections.drop(1))
              else (Vector(pre), sections)
            checked(draft(src, local, kind, state, title, parent, own)) ++
              rest.foldLeft(Drafted.empty) { case (done, (heading, r)) =>
                val k = part(heading)
                done ++ document(
                  src,
                  unique(s"$local-${slug(heading)}"),
                  k,
                  None,
                  heading,
                  Some(local),
                  r,
                  level + 1,
                  false,
                  _ => "section"
                )
              }
        }
    }

    // The roadmap: its preamble the root, each `##` section a section under it, and the items
    // of the sections naming milestones each a milestone; with the plans those milestones name.
    def roadmap(src: Prepared): (Drafted, Map[String, String]) = {
      val (pre, sections) = src.lines.sections((0, src.lines.size), 2)
      val nowText =
        sections.collect { case ("Now", r) => joined(src.lines.all, Vector(r)) }.mkString("\n")
      val nowIds = NowNames.findAllMatchIn(nowText).map(_.group(1)).toSet
      val root = checked(draft(src, src.self, "section", None, title(src), None, Vector(pre)))
      val parts = sections.map { case (heading, (from, until)) =>
        val local = unique(s"${src.self}-${slug(heading)}")
        ItemSections.get(heading) match {
          case None =>
            val whole = (from, until)
            (
              document(
                src,
                local,
                "section",
                None,
                heading,
                Some(src.self),
                whole,
                3,
                false,
                _ => "section"
              ),
              Vector.empty[Draft]
            )
          case Some(state) =>
            val (items, rest) = src.lines.items(from + 1, until, itemTitle(RoadmapItem, _))
            val container = (from, from + 1) +: rest
            val milestones = items.map { case (name, r) =>
              val id = MilestoneId.findFirstMatchIn(name).map(_.group(1))
              val at = if (id.exists(nowIds)) "now" else state
              val key = unique(id.fold(slug(name))(i => s"m${slug(i)}"))
              draft(src, key, "milestone", Some(at), name, Some(src.self), Vector(r))
            }
            (
              checked(draft(src, local, "section", None, heading, Some(src.self), container)),
              milestones
            )
        }
      }
      val milestones = parts.flatMap(_._2)
      val named = milestones.flatMap { d =>
        PlanRef.findAllMatchIn(d.provenance.original).map(m => s"$Plans${m.group(1)}" -> d.local)
      }
      val now = milestones.filter(_.state.contains("now")).map(_.local) match {
        case Vector(one) =>
          PlanRef.findAllMatchIn(nowText).map(m => s"$Plans${m.group(1)}" -> one).toVector
        case _ => Vector.empty
      }
      val drafted = root ++ parts.foldLeft(Drafted.empty)(_ ++ _._1) ++
        milestones.foldLeft(Drafted.empty)(_ ++ checked(_))
      (drafted, (named ++ now).toMap)
    }

    // done.md: its preamble and loose paragraphs one section, each entry and `##` section a
    // milestone done.
    def done(src: Prepared): Drafted = {
      val (pre, sections) = src.lines.sections((0, src.lines.size), 2)
      val (items, rest) = src.lines.items(pre._1, pre._2, itemTitle(DoneItem, _))
      val container = checked(draft(src, src.self, "section", None, title(src), roadmapRoot, rest))
      (items ++ sections).foldLeft(container) { case (drafted, (name, r)) =>
        val key = unique(s"done-${slug(name)}")
        drafted ++ checked(draft(src, key, "milestone", Some("done"), name, roadmapRoot, Vector(r)))
      }
    }

    val (roadmapDrafts, planParents) =
      prepared.find(_.path == Roadmap).fold((Drafted.empty, Map.empty[String, String]))(roadmap)

    val rest = prepared.filterNot(_.path == Roadmap).map { src =>
      val whole = (0, src.lines.size)
      def plain(kind: String, parent: Option[String]): Drafted =
        document(src, src.self, kind, None, title(src), parent, whole, 2, false, _ => "section")
      src.path match {
        case Done => done(src)
        case p if p == Mechanisms + Index => plain("section", roadmapRoot)
        case p if p.startsWith(Mechanisms) =>
          plain("mechanism", roots.get(Mechanisms + Index).orElse(roadmapRoot))
        case p if p == Backlog + Index => plain("section", roadmapRoot)
        case p if p.startsWith(Backlog) =>
          plain("note", roots.get(Backlog + Index).orElse(roadmapRoot))
        case p if p.startsWith(Plans) =>
          document(
            src,
            src.self,
            "plan",
            None,
            title(src),
            planParents.get(p).orElse(roadmapRoot),
            whole,
            2,
            true,
            heading => if (Stage.matches(heading)) "stage" else "section"
          )
        case _ => plain("note", roadmapRoot)
      }
    }
    roadmapDrafts ++ rest.foldLeft(Drafted.empty)(_ ++ _)
  }

  /** The plans `roadmap` names outside its [[Closed]] sections, as paths under the checkout. */
  def openPlans(roadmap: String): Vector[String] = {
    val lines = new Lines(roadmap)
    val (pre, sections) = lines.sections((0, lines.size), 2)
    val open = pre +: sections.collect { case (h, r) if !Closed(h) => r }
    PlanRef
      .findAllMatchIn(joined(lines.all, open))
      .map(m => s"$Plans${m.group(1)}")
      .toVector
      .distinct
  }

  /** `text` joined from `ranges` of `lines`: each range's lines, less its blank lines at either
    * end, the ranges a blank line apart.
    */
  def joined(lines: Vector[String], ranges: Vector[(Int, Int)]): String =
    ranges
      .map((a, b) =>
        lines.slice(a, b).mkString("\n").replaceFirst("^(?:[ \t]*\n)+", "").stripTrailing
      )
      .filter(_.nonEmpty)
      .mkString("\n\n")

  /** `s` as a key part: lowercase ASCII letters and digits, runs of anything else one `-`. */
  def slug(s: String): String = {
    val dashed =
      s.toLowerCase.map(c => if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) c else '-')
    val s1 = dashed.replaceAll("-+", "-").stripPrefix("-").stripSuffix("-")
    if (s1.isEmpty) "x" else s1
  }

  private def clip(s: String, most: Int): String = s.take(most).stripSuffix("-")

  private def rank(path: String): Int =
    if (path == Roadmap) 0
    else if (path == Done) 1
    else if (path.startsWith(Mechanisms)) 2
    else if (path.startsWith("roadmap/")) 3
    else if (path.startsWith(Backlog)) 4
    else 5

  private def fileKey(path: String): String = {
    val name = path.split('/').lastOption.getOrElse(path).stripSuffix(".md")
    path match {
      case Roadmap => "roadmap"
      case Done => "done"
      case p if p == Mechanisms + Index => "mechanisms"
      case p if p.startsWith(Mechanisms) => s"mechanism-${slug(name)}"
      case p if p == Backlog + Index => "backlog"
      case p if p.startsWith(Plans) => s"plan-${slug(name)}"
      case _ => s"note-${slug(name)}"
    }
  }

  private def itemTitle(item: Regex, line: String): Option[String] =
    item
      .findPrefixMatchOf(line)
      .map(_.group(1).trim)
      .filterNot(_.endsWith(":"))

  private def title(src: Prepared): String =
    src.lines.all.indices
      .collectFirst(Function.unlift(i => src.lines.heading(i, 1)))
      .getOrElse(src.path.split('/').lastOption.getOrElse(src.path).stripSuffix(".md"))

  private def where(d: Draft): String =
    d.provenance.lines match {
      case Vector((a, b)) => s"${d.provenance.source}:${a + 1}-$b"
      case _ => s"${d.provenance.source} (${d.local})"
    }

  private def dir(path: String): String = path.lastIndexOf('/') match {
    case -1 => ""
    case i => path.take(i + 1)
  }

  private def normal(path: String): String =
    path
      .split('/')
      .foldLeft(Vector.empty[String]) {
        case (done, "" | ".") => done
        case (done, "..") => done.dropRight(1)
        case (done, part) => done :+ part
      }
      .mkString("/")

  /** A source ready to draft: its key, its lines, and its lines with links pointed at
    * artifacts.
    */
  private final case class Prepared(
      path: String,
      self: String,
      lines: Lines,
      rewritten: Vector[String]
  )
}

/** A markdown text's lines, and which of them are inside a fenced code block. */
private[importer] final class Lines(text: String) {

  val all: Vector[String] = text.split("\n", -1).toVector

  def size: Int = all.size

  /** Whether line `i` is a fence or inside one. */
  val fenced: Vector[Boolean] =
    all
      .foldLeft((Vector.empty[Boolean], Option.empty[String])) { case ((done, open), line) =>
        val t = line.dropWhile(_ == ' ')
        val marker = Seq("```", "~~~").find(t.startsWith)
        (open, marker) match {
          case (None, Some(m)) => (done :+ true, Some(m))
          case (Some(m), _) if t.startsWith(m) => (done :+ true, None)
          case (o, _) => (done :+ o.nonEmpty, o)
        }
      }
      ._1

  /** Line `i`'s heading text, when it is a heading of `level` outside a fence. */
  def heading(i: Int, level: Int): Option[String] = {
    val mark = "#" * level + " "
    all
      .lift(i)
      .filter(l => !fenced.lift(i).getOrElse(true) && l.startsWith(mark))
      .map(_.drop(mark.length).trim)
  }

  /** Whether every line of `range` is blank. */
  def blank(range: (Int, Int)): Boolean = all.slice(range._1, range._2).forall(_.isBlank)

  /** `range` cut at its `level` headings: the lines before the first, and each heading's text
    * with its lines, its heading line first.
    */
  def sections(range: (Int, Int), level: Int): ((Int, Int), Vector[(String, (Int, Int))]) = {
    val (from, until) = range
    val at = (from until until).flatMap(i => heading(i, level).map(i -> _)).toVector
    val ends = at.drop(1).map(_._1) :+ until
    (
      (from, at.headOption.fold(until)(_._1)),
      at.zip(ends).map { case ((i, h), end) => h -> (i, end) }
    )
  }

  /** The items in lines `[from, until)`, each from a line `start` names to the next line
    * outside a fence that is neither blank nor indented, with its name; and the ranges between
    * them.
    */
  def items(
      from: Int,
      until: Int,
      start: String => Option[String]
  ): (Vector[(String, (Int, Int))], Vector[(Int, Int)]) = {
    def named(i: Int): Option[String] =
      if (fenced.lift(i).getOrElse(true)) None else all.lift(i).flatMap(start)
    def flush(j: Int): Boolean =
      !fenced.lift(j).getOrElse(false) &&
        all.lift(j).exists(l => !l.isBlank && !l.startsWith(" ") && !l.startsWith("\t"))
    @scala.annotation.tailrec
    def scan(i: Int, found: Vector[(String, (Int, Int))]): Vector[(String, (Int, Int))] =
      if (i >= until) found
      else
        named(i) match {
          case Some(name) =>
            val end = (i + 1 until until).find(flush).getOrElse(until)
            scan(end, found :+ (name -> (i, end)))
          case None => scan(i + 1, found)
        }
    val found = scan(from, Vector.empty)
    val gaps = (from +: found.map(_._2._2))
      .zip(found.map(_._2._1) :+ until)
      .filter((a, b) => a < b)
    (found, gaps)
  }
}
