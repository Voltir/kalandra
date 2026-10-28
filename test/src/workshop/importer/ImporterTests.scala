package workshop.importer

import grit.projects.artifact.{Artifact, Body, Project}
import grit.projects.name.{KindName, StateName}
import grit.projects.render.ProjectMarkdown

import utest.*

object ImporterTests extends TestSuite {

  import Fixture.{imported, key}

  private def artifact(local: String): Option[Artifact] =
    imported.project.artifacts.find(_.head.key == key(local))

  private def heads(p: Project) =
    p.artifacts.map(a =>
      (a.head.key, a.head.kind, a.head.state, a.head.title, a.head.parent, a.head.cites)
    )

  /** What the store holds after the import's writes: its normal form, each written by someone. */
  private def stored(p: Project): Project = {
    val normal =
      ProjectMarkdown.read(ProjectMarkdown.render(p)).fold(b => sys.error(b.toString), identity)
    Project
      .of(
        normal.key,
        normal.pin,
        normal.artifacts.map(a => a.copy(head = a.head.copy(by = Some("someone"))))
      )
      .fold(sys.error, identity)
  }

  private val empty: Project =
    Project.of(Fixture.project, Fixture.pin, Vector.empty).fold(sys.error, identity)

  /** `p` with the artifact `local` held in `state`, its other artifacts as they are. */
  private def restated(p: Project, local: String, state: String): Project = {
    val to = StateName.of(state).fold(sys.error, identity)
    Project
      .of(
        p.key,
        p.pin,
        p.artifacts
          .map(a => if (a.head.key == key(local)) a.copy(head = a.head.copy(state = to)) else a)
      )
      .fold(sys.error, identity)
  }

  val tests = Tests {
    test(
      "the import's files read back after a render with the same heads, and a second render changes nothing"
    ) {
      imported.provenance.values.map(_.source).toSet ==>
        Fixture.sources.map(_.path).toSet - ".local/backlog/empty.md"
      val once = ProjectMarkdown
        .read(ProjectMarkdown.render(imported.project))
        .fold(b => sys.error(b.toString), identity)
      heads(once) ==> heads(imported.project)
      ProjectMarkdown.read(ProjectMarkdown.render(once)) ==> Right(once)
    }

    test(
      "the roadmap's items are milestones in their section's state, now when Now names them, and done.md's entries done"
    ) {
      val states = imported.project.artifacts
        .filter(a => KindName.value(a.head.kind) == "milestone")
        .map(a => a.head.key.local -> StateName.value(a.head.state))
        .toMap
      states ==> Map(
        "m3" -> "now",
        "m4" -> "later",
        "email-edge" -> "later",
        "tooling" -> "parallel",
        "slack-joins" -> "next",
        "done-m2-storage-closed" -> "done",
        "done-m1-the-turn" -> "done",
        "done-m1-5-the-contract" -> "done",
        "done-e1-recipes-closed-2026-09-20" -> "done"
      )
    }

    test(
      "mechanisms, notes, a plan's stages and sections take their kinds, each under its index or milestone"
    ) {
      val placed = Vector(
        "roadmap",
        "roadmap-now",
        "roadmap-update-protocol",
        "done",
        "mechanisms",
        "mechanism-assembly",
        "backlog",
        "note-email-edge",
        "plan-m3",
        "plan-m3-shared-design",
        "plan-m3-s1-documents"
      ).map { l =>
        l -> artifact(l).map(a => (KindName.value(a.head.kind), a.head.parent.map(_.local)))
      }
      placed ==> Vector(
        "roadmap" -> Some(("section", None)),
        "roadmap-now" -> Some(("section", Some("roadmap"))),
        "roadmap-update-protocol" -> Some(("section", Some("roadmap"))),
        "done" -> Some(("section", Some("roadmap"))),
        "mechanisms" -> Some(("section", Some("roadmap"))),
        "mechanism-assembly" -> Some(("mechanism", Some("mechanisms"))),
        "backlog" -> Some(("section", Some("roadmap"))),
        "note-email-edge" -> Some(("note", Some("backlog"))),
        "plan-m3" -> Some(("plan", Some("m3"))),
        "plan-m3-shared-design" -> Some(("section", Some("plan-m3"))),
        "plan-m3-s1-documents" -> Some(("stage", Some("plan-m3")))
      )
    }

    test(
      "a link to an imported file refers to its artifact, a code span naming one is a cite, and a link to a closed plan stays as written"
    ) {
      val assembly = artifact("mechanism-assembly")
      assembly.map(a => Body.cites(a.body)) ==> Some(Vector(key("mechanism-edges")))
      assert(assembly.exists(a => Body.markdown(a.body).contains("(../../.local/plans/m1.md)")))
      artifact("note-slack-joins").map(_.head.cites) ==> Some(Vector(key("mechanism-edges")))
      artifact("slack-joins").map(_.head.cites) ==> Some(Vector(key("note-slack-joins")))
      artifact("roadmap").map(a => Body.cites(a.body)) ==> Some(Vector(key("mechanisms")))
    }

    test("a file over the stored cap splits at its ## headings, and a part still over it at ###") {
      def children(local: String) =
        imported.project.artifacts.filter(_.head.parent.contains(key(local))).map(_.head.key.local)
      (children("note-big"), children("note-big-part-two")) ==> (
        Vector("note-big-part-one", "note-big-part-two"),
        Vector("note-big-part-two-two-a", "note-big-part-two-two-b")
      )
    }

    test("a file that is no artifact is reported with why, and the rest imported") {
      imported.failed ==> Vector(".local/backlog/empty.md:1-2" -> "a body is blank")
    }

    test(
      "each source line is in exactly one artifact, and its text is that artifact's body under normalise"
    ) {
      Fidelity.problems(Fixture.sources, imported, imported.project) ==> Vector.empty
      val edited = imported.project.artifacts.map { a =>
        if (a.head.key == key("note-email-edge"))
          a.copy(body = Body.read("# Email edge\n\nMail, not an edge.").fold(sys.error, identity))
        else a
      }
      val changed = Project.of(Fixture.project, Fixture.pin, edited).fold(sys.error, identity)
      Fidelity.problems(Fixture.sources, imported, changed) ==> Vector(
        "grit/note-email-edge: its stored body is not .local/backlog/email-edge.md's text under normalise"
      )
    }

    test("the open plans are those the roadmap names outside Done and the Update Protocol") {
      Importer.openPlans(Fixture.Roadmap) ==> Vector(".local/plans/m3.md")
    }

    test(
      "a first import keeps each parent before its children, a cite cycle's first artifact twice, then moves each to its state"
    ) {
      val writes =
        Sync.writes(imported.project, empty, Fixture.method).fold(sys.error, identity).writes
      val keeps = writes.collect { case Write.Keep(k, args) => (k, args) }
      val order = keeps.map(_._1).zipWithIndex.groupMap(_._1)(_._2)
      val parentLater = keeps.zipWithIndex.collect {
        case ((k, args), i)
            if args.obj
              .get("parent")
              .exists(p => order.get(key(p.str.drop(5))).forall(_.min > i)) =>
          k
      }
      parentLater ==> Vector.empty
      val assembly = keeps.filter(_._1 == key("mechanism-assembly")).map(_._2)
      assembly.map(a => Body.read(a("body").str).map(Body.cites)) ==> Vector(
        Right(Vector.empty),
        Right(Vector(key("mechanism-edges")))
      )
      writes.collect { case Write.Move(k, to) => k.local -> StateName.value(to) }.toSet ==> Set(
        "m3" -> "now",
        "tooling" -> "parallel",
        "slack-joins" -> "next",
        "done-m2-storage-closed" -> "done",
        "done-m1-the-turn" -> "done",
        "done-m1-5-the-contract" -> "done",
        "done-e1-recipes-closed-2026-09-20" -> "done"
      )
    }

    test(
      "a second import of the same tree writes nothing, and a changed source rewrites its artifact alone"
    ) {
      val held = stored(imported.project)
      Sync.writes(imported.project, held, Fixture.method).map(_.writes) ==> Right(Vector.empty)
      val changed = Fixture.sources.map(s =>
        if (s.path == ".local/backlog/email-edge.md")
          s.copy(text = "# Email edge\n\nMail as an edge, later.\n")
        else s
      )
      val again = Importer
        .of(Fixture.project, Fixture.pin, Fixture.method, changed)
        .fold(b => sys.error(b.toString), identity)
      Sync
        .writes(again.project, held, Fixture.method)
        .map(_.writes.map {
          case Write.Keep(k, _) => s"keep ${k.local}"
          case Write.Move(k, _) => s"move ${k.local}"
        }) ==> Right(Vector("keep note-email-edge"))
    }

    test("a re-import leaves an existing stage's stored state alone") {
      val held = restated(stored(imported.project), "plan-m3-s1-documents", "building")
      Sync.writes(imported.project, held, Fixture.method).map(_.writes) ==> Right(Vector.empty)
    }

    test("a new artifact takes its state from the source") {
      val held = stored(imported.project)
      val without = Project
        .of(held.key, held.pin, held.artifacts.filterNot(_.head.key == key("slack-joins")))
        .fold(sys.error, identity)
      Sync
        .writes(imported.project, without, Fixture.method)
        .map(_.writes.collect {
          case Write.Move(k, to) if k == key("slack-joins") => StateName.value(to)
        }) ==> Right(Vector("next"))
    }

    test("a milestone whose roadmap section changed is moved") {
      val held = restated(stored(imported.project), "slack-joins", "later")
      Sync
        .writes(imported.project, held, Fixture.method)
        .map(_.writes.collect {
          case Write.Move(k, to) if k == key("slack-joins") => StateName.value(to)
        }) ==> Right(Vector("next"))
    }

    test(
      "a milestone whose roadmap state has no move from its stored state is reported, not a stop"
    ) {
      val held = restated(stored(imported.project), "slack-joins", "done")
      Sync.writes(imported.project, held, Fixture.method).map { s =>
        (
          s.writes.collect { case Write.Move(k, _) if k == key("slack-joins") => k },
          s.unmoved
        )
      } ==> Right(
        (
          Vector.empty,
          Vector(
            s"move ${key("slack-joins").value} to next" -> "no moves take a milestone from done to next"
          )
        )
      )
    }

    test("verify accepts a stage advanced in the store") {
      val held = restated(stored(imported.project), "plan-m3-s1-documents", "building")
      Fidelity.problems(Fixture.sources, imported, held) ==> Vector.empty
    }

    test("verify still compares a milestone's stored state") {
      val held = restated(stored(imported.project), "slack-joins", "done")
      Fidelity.problems(Fixture.sources, imported, held) ==> Vector(
        s"${key("slack-joins").value}: its stored head differs"
      )
    }
  }
}
