package kalandra.method

import grit.projects.artifact.{Artifact, ArtifactHead, Body, Project}
import grit.projects.method.{Method, MethodBench, Migrated, Remap}
import grit.projects.name.{ArtifactKey, KindName, Pin, ProjectKey, StateName}

import kalandra.method.MethodFiles.filed
import utest.*

object GritMethodTests extends TestSuite {

  private def right[A](e: Either[String, A]): A =
    e.fold(why => throw new java.lang.AssertionError(why), identity)

  private def version(v: Int): Method = right(filed(v)).method

  private def head(local: String, kind: String, state: String, parent: Option[String] = None) =
    ArtifactHead(
      right(ArtifactKey.read(s"p/$local")),
      right(KindName.of(kind)),
      right(StateName.of(state)),
      local,
      parent.map(p => right(ArtifactKey.read(s"p/$p"))),
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
    test(
      "each version's file reads, is that version of grit, its examples come out as they say, and it keeps its pin"
    ) {
      // Versions 1 to 5 are the pins grit_kalandra's projects held or hold (restore-check
      // version 1, grit the rest); 6 is the pin it is declared with.
      MethodFiles.Versions.map(v =>
        filed(v).map(d =>
          (
            d.method.name,
            d.method.version,
            MethodBench.failures(d.method, d.examples),
            d.method.digest
          )
        )
      ) ==> Vector(
        "1c8e4adb20a0b3b044f5b165c638033a1a93dc83bfc98b97b0c6d26180c3de75",
        "b08f7ac5f0a18cdfffcec83468e48295d3f2e1bb11dc49cf31b8869457c80a1f",
        "cdae558df99dfaeb924b1eb863a10004196e1bab2cdb65e90d49a42cdfb74f47",
        "a4324899142bd13487e2442f57fea1be8cd5e6972247bbd177aff000a0cff65d",
        "243738553f2f7368af940d5593ee7c2ac49cc9f3b66eeea85588854e3ba7b7cd",
        "c334283c23b92f97a54dfc76648330355fc15b859be5bcc80f2d7507743097dc"
      ).zip(MethodFiles.Versions).map((pin, v) => Right((Grit.Name, v, Vector(), pin)))
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
      (
        declared.map(d => MethodBench.failures(d.method, d.examples)),
        declared.map(d => d.method.kinds.find(_.name == checkpoint).map(k => (k.all, k.sections))),
        declared.map(d => moves(d.method, checkpoint)),
        declared.map(d => moves(d.method, amendment)),
        declared.map(d => unchanged(d.method) == unchanged(version(5))),
        declared.map(_.examples.flatMap(_.moves).map(_.expect).distinct.map(_.toString).sorted)
      ) ==> (
        Right(Vector()),
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
            "benched->adopted [decided-adopt] People",
            "benched->rejected [decided-reject] Anyone",
            "proposed->dropped [] Anyone",
            "benched->dropped [] Anyone"
          )
        ),
        Right(true),
        Right(
          Vector(
            "Allowed",
            "RefusedBy(decided-adopt)",
            "RefusedBy(decided-reject)",
            "RefusedBy(sections-present)",
            "RefusedPersonOnly"
          )
        )
      )
    }

    test(
      "a version 5 project migrates to version 6 unchanged, a planned checkpoint still planned"
    ) {
      val (v5, v6) = (version(5), right(filed(6)).method)
      val as = Vector(
        Artifact(head("m", "milestone", "now"), right(Body.read("M."))),
        Artifact(head("c", "checkpoint", "planned", parent = Some("m")), right(Body.read("C."))),
        Artifact(head("b", "checkpoint", "building", parent = Some("m")), right(Body.read("B."))),
        Artifact(head("a", "amendment", "benched"), right(Body.read("A.")))
      )
      v6.migrated(project(v5, as*), Remap()) ==> Right(Migrated(project(v6, as*), Vector()))
    }
  }
}
