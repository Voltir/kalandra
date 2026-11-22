package kalandra.method

import grit.core.id.AgentType
import grit.projects.artifact.Pin
import grit.projects.artifact.{Artifact, ArtifactHead, Body, Project, Rank}
import grit.projects.method.{
  Declared,
  Example,
  Holders,
  Method,
  MethodBench,
  MethodJson,
  Migrated,
  Mover,
  Nesting,
  Remap
}
import grit.projects.name.{
  ArtifactKey,
  ArtifactRef,
  CheckName,
  Handle,
  KindName,
  ProjectKey,
  RoleName,
  StateName
}

import kalandra.method.MethodFiles.filed
import utest.*

object GritMethodTests extends TestSuite {

  private def right[A](e: Either[String, A]): A =
    e.fold(why => throw new java.lang.AssertionError(why), identity)

  private def version(v: Int): Method = right(filed(v)).method

  /** Each test artifact's number, by its handle. */
  private val Numbers: Map[String, Int] =
    Map("m" -> 1, "s" -> 2, "n" -> 3, "c" -> 4, "b" -> 5, "a" -> 6)

  private def key(local: String): ArtifactKey =
    right(ArtifactKey.read(s"p/${Numbers.getOrElse(local, 0)}"))

  private def head(local: String, kind: String, state: String, parent: Option[String] = None) =
    ArtifactHead(
      key(local),
      Some(right(Handle.of(local))),
      right(KindName.of(kind)),
      right(StateName.of(state)),
      local,
      parent.map(key),
      Rank.Middle,
      Vector.empty,
      None
    )

  private def project(m: Method, as: Artifact*): Project =
    right(
      Project.of(
        right(ProjectKey.of("p")),
        right(Pin.of(m.name, m.version, m.digest)),
        as.toVector
      )
    )

  val tests = Tests {
    test("each version's file is the method its project pinned, with its worked examples whole") {
      // Versions 1 to 5 are the pins grit_kalandra's projects held or hold (restore-check
      // version 1, grit the rest); 6 is the pin it is declared with, and 7 the next. The digest leaves the
      // examples out, so their count is pinned beside it.
      (
        MethodFiles.Versions.map(v =>
          filed(v).map(d =>
            (
              d.method.name,
              d.method.version,
              MethodBench.failures(d.method, d.examples),
              d.examples.size,
              d.method.digest
            )
          )
        ),
        MethodFiles.text(MethodFiles.Versions.size + 1).isLeft
      ) ==> (
        Vector(
          "1c8e4adb20a0b3b044f5b165c638033a1a93dc83bfc98b97b0c6d26180c3de75",
          "b08f7ac5f0a18cdfffcec83468e48295d3f2e1bb11dc49cf31b8869457c80a1f",
          "cdae558df99dfaeb924b1eb863a10004196e1bab2cdb65e90d49a42cdfb74f47",
          "a4324899142bd13487e2442f57fea1be8cd5e6972247bbd177aff000a0cff65d",
          "243738553f2f7368af940d5593ee7c2ac49cc9f3b66eeea85588854e3ba7b7cd",
          "c334283c23b92f97a54dfc76648330355fc15b859be5bcc80f2d7507743097dc",
          "62e5060307938afd04b824a1884b11d3271eed1870a3fe651a91b7cf337881ae"
        ).zip(MethodFiles.Versions.zip(Vector(0, 0, 0, 5, 5, 8, 8)))
          .map { case (pin, (v, examples)) => Right((Grit.Name, v, Vector(), examples, pin)) },
        true
      )
    }

    test(
      "version 6 is version 5 with adopted a person's alone and a checkpoint ready once its plan is whole, and its examples say so"
    ) {
      val declared = filed(6)
      val checkpoint = right(KindName.of("checkpoint"))
      val amendment = right(KindName.of("amendment"))
      // What version 6 leaves as version 5 had it: every other kind, its moves, and the views.
      def unchanged(m: Method) = (
        m.kinds.filterNot(_.name == checkpoint),
        m.transitions.filterNot(t => t.kind == checkpoint || t.kind == amendment),
        m.views,
        m.checks
      )
      def moves(m: Method, kind: KindName) = m.transitions
        .filter(_.kind == kind)
        .map(t => s"${t.from}->${t.to} [${t.checks.mkString(",")}] ${t.movers}")
      // Each example move's outcome, with the kind it moves, the state it moves to, and who.
      def outcomes(d: Declared) = d.examples
        .flatMap(e =>
          e.moves.flatMap(mv =>
            e.start.artifacts
              .find(_.head.key == mv.key)
              .map(a => (a.head.kind, mv.to, mv.by, mv.expect))
          )
        )
        .distinct
      (
        declared.map(d => d.method.kinds.find(_.name == checkpoint).map(k => (k.all, k.sections))),
        declared.map(d => moves(d.method, checkpoint)),
        declared.map(d => moves(d.method, amendment)),
        declared.map(d => unchanged(d.method) == unchanged(version(5))),
        declared.map(outcomes)
      ) ==> (
        Right(
          Some(
            (
              Vector("planned", "ready", "building", "review", "merged", "dropped")
                .map(s => right(StateName.of(s))),
              Vector("Goal", "Signatures", "Reads", "Commits")
            )
          )
        ),
        Right(
          Vector(
            "planned->ready [sections-present] Anyone",
            "ready->building [] Anyone",
            "building->review [] Anyone",
            "review->building [] Anyone",
            "review->merged [children-ended] Anyone",
            "planned->dropped [children-ended] Anyone",
            "ready->dropped [children-ended] Anyone",
            "building->dropped [children-ended] Anyone"
          )
        ),
        Right(
          Vector(
            "proposed->benched [sections-present] Anyone",
            "benched->adopted [decided-adopt] Person",
            "benched->rejected [decided-reject] Anyone",
            "proposed->dropped [] Anyone",
            "benched->dropped [] Anyone"
          )
        ),
        Right(true),
        Right {
          import Example.Expect.{Allowed, RefusedBy, RefusedPersonOnly}
          def by(check: String) = RefusedBy(right(CheckName.of(check)))
          def to(k: KindName, s: String, m: Mover, e: Example.Expect) =
            (k, right(StateName.of(s)), m, e)
          Vector(
            to(amendment, "benched", Mover.Other, by("sections-present")),
            to(amendment, "benched", Mover.Other, Allowed),
            to(amendment, "adopted", Mover.Person, by("decided-adopt")),
            to(amendment, "rejected", Mover.Other, by("decided-reject")),
            to(amendment, "rejected", Mover.Other, Allowed),
            to(amendment, "adopted", Mover.Person, Allowed),
            to(amendment, "adopted", Mover.Other, RefusedPersonOnly),
            to(checkpoint, "ready", Mover.Other, by("sections-present")),
            to(checkpoint, "ready", Mover.Other, Allowed),
            to(checkpoint, "building", Mover.Other, Allowed),
            to(checkpoint, "review", Mover.Other, Allowed),
            to(checkpoint, "merged", Mover.Other, Allowed)
          )
        }
      )
    }

    test("project_methods gives each version back as its file, but for the file's last newline") {
      // project_methods answers ujson.write(MethodJson.written(...), indent = 2) of what it reads.
      MethodFiles.Versions.map(v =>
        for {
          d <- filed(v)
          file <- MethodFiles.raw(v)
          written = ujson.write(MethodJson.written(d.method, d.examples), indent = 2)
        } yield (v, written == file.stripSuffix("\n"))
      ) ==> MethodFiles.Versions.map(v => Right((v, true)))
    }

    test("every shipped method file's example artifacts and carried names read as handles") {
      MethodFiles.Versions.map(v =>
        filed(v).map(d =>
          d.examples.forall(_.start.artifacts.forall(_.head.handle.isDefined)) &&
            d.method.views.forall(_.carried.forall(h => Handle.of(Handle.value(h)) == Right(h)))
        )
      ) ==> MethodFiles.Versions.map(_ => Right(true))
    }

    test("a version 2 project migrates to version 3, its stages checkpoints, a note re-kinded") {
      val (v2, v3) = (version(2), version(3))
      val m = Artifact(head("m", "milestone", "now"), right(Body.read("M.", ArtifactRef.id)))
      val s = Artifact(
        head("s", "stage", "building", parent = Some("m")),
        right(Body.read("S.", ArtifactRef.id))
      )
      val n = Artifact(
        head("n", "note", "open", parent = Some("m")),
        right(Body.read("N.", ArtifactRef.id))
      )
      v3.migrated(
        project(v2, m, s, n),
        Remap(
          kinds = Map(right(KindName.of("stage")) -> right(KindName.of("checkpoint"))),
          artifacts = Map(key("n") -> right(KindName.of("backlog")))
        )
      ) ==> Right(
        Migrated(
          project(
            v3,
            m,
            Artifact(s.head.copy(kind = right(KindName.of("checkpoint"))), s.body),
            Artifact(n.head.copy(kind = right(KindName.of("backlog"))), n.body)
          ),
          Vector(key("s"), key("n"))
        )
      )
    }

    test(
      "a version 5 project migrates to version 6 unchanged, a planned checkpoint still planned"
    ) {
      val (v5, v6) = (version(5), right(filed(6)).method)
      val as = Vector(
        Artifact(head("m", "milestone", "now"), right(Body.read("M.", ArtifactRef.id))),
        Artifact(
          head("c", "checkpoint", "planned", parent = Some("m")),
          right(Body.read("C.", ArtifactRef.id))
        ),
        Artifact(
          head("b", "checkpoint", "building", parent = Some("m")),
          right(Body.read("B.", ArtifactRef.id))
        ),
        Artifact(head("a", "amendment", "benched"), right(Body.read("A.", ArtifactRef.id)))
      )
      v6.migrated(project(v5, as*), Remap()) ==> Right(Migrated(project(v6, as*), Vector()))
    }

    test("version 7 is version 6 with each kind's nesting and the manager's claims covering") {
      val (v6, v7) = (version(6), version(7))
      def nesting(m: Method) = m.kinds
        .map(k =>
          KindName.value(k.name) -> (k.nesting match {
            case Nesting.Anywhere => "anywhere"
            case Nesting.Root => "root"
            case Nesting.Under(ks) =>
              ks.toVector.map(KindName.value).sorted.mkString("under ", ", ", "")
          })
        )
        .toMap
      def bare(m: Method) = (
        m.kinds.map(_.copy(nesting = Nesting.Anywhere)),
        m.views.map(v => (v.role, v.kinds, v.depth, v.carried)),
        m.transitions,
        m.checks
      )
      (
        nesting(v7),
        v7.views
          .filter(_.covers == grit.projects.method.Cover.Under)
          .map(v => RoleName.value(v.role)),
        bare(v7) == bare(v6)
      ) ==> (
        Map(
          "milestone" -> "under section",
          "plan" -> "under milestone",
          "checkpoint" -> "under milestone, plan",
          "section" -> "anywhere",
          "mechanism" -> "under section",
          "note" -> "anywhere",
          "backlog" -> "under backlog, milestone, section",
          "amendment" -> "root"
        ),
        Vector("manager"),
        true
      )
    }

    test(
      "a version 6 project in version 7's shape migrates unchanged, and a milestone at the root is refused"
    ) {
      val (v6, v7) = (version(6), version(7))
      def made(local: String, kind: String, state: String, parent: Option[String] = None) =
        Artifact(head(local, kind, state, parent), right(Body.read("Text.", ArtifactRef.id)))
      val placed = Vector(
        made("s", "section", "current"),
        made("m", "milestone", "now", parent = Some("s")),
        made("c", "checkpoint", "planned", parent = Some("m")),
        made("n", "note", "open"),
        made("b", "backlog", "open", parent = Some("m")),
        made("a", "amendment", "proposed")
      )
      val rootMilestone =
        placed.map(a => if (a.head.key == key("m")) made("m", "milestone", "now") else a)
      (
        v7.migrated(project(v6, placed*), Remap()),
        v7.migrated(project(v6, rootMilestone*), Remap())
      ) ==> (
        Right(Migrated(project(v7, placed*), Vector())),
        Left("p/1 m, of kind milestone, cannot sit at the project's root: it may sit under section")
      )
    }

    test(
      "the draft of version 8 is version 7 with coder held by coder agents and manager by a session, its examples replayed"
    ) {
      val (v7, drafted) = (version(7), MethodFiles.drafted(8))
      def holders(m: Method) = m.views.map(v =>
        RoleName.value(v.role) -> (v.holders match {
          case Holders.Anyone => "anyone"
          case Holders.Session => "a session"
          case Holders.Agents(ts) =>
            ts.toVector.map(AgentType.value).sorted.mkString("agents ", ", ", "")
        })
      )
      def bare(m: Method) = (
        m.name,
        m.kinds,
        m.transitions,
        m.views.map(v => (v.role, v.kinds, v.depth, v.carried, v.covers)),
        m.checks
      )
      (
        drafted.map(d =>
          (
            d.method.version,
            holders(d.method),
            bare(d.method) == bare(v7),
            MethodBench.failures(d.method, d.examples),
            d.examples == right(filed(7)).examples,
            d.method.digest != v7.digest
          )
        ),
        for {
          d <- drafted
          file <- MethodFiles.rawDraft(8)
        } yield ujson.write(MethodJson.written(d.method, d.examples), indent = 2) == file
          .stripSuffix("\n")
      ) ==> (
        Right(
          (
            8,
            Vector(
              "coder" -> "agents coder-haiku, coder-opus",
              "manager" -> "a session",
              "reviewer" -> "anyone",
              "grit-coder" -> "anyone"
            ),
            true,
            Vector(),
            true,
            true
          )
        ),
        Right(true)
      )
    }
  }
}
