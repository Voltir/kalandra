package kalandra.method

import grit.projects.artifact.{Artifact, ArtifactHead, Body, Project}
import grit.projects.method.{Method, Migrated, Remap}
import grit.projects.name.{ArtifactKey, KindName, LocalKey, Pin, ProjectKey, RoleName, StateName}

import utest.*

object GritMethodTests extends TestSuite {

  private def right[A](e: Either[String, A]): A =
    e.fold(why => throw new java.lang.AssertionError(why), identity)

  private val methods: Vector[Method] =
    GritMethod.all.fold(why => sys.error(why.mkString("; ")), identity)

  private def version(v: Int): Method =
    methods.find(_.version == v).getOrElse(sys.error(s"no version $v"))

  private def kinds(m: Method): Set[String] = m.kinds.map(k => KindName.value(k.name)).toSet

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

  val tests = Tests {
    test("version 1 keeps the digest the project restore-check pinned") {
      GritMethod.v1.map(_.digest) ==>
        Right("1c8e4adb20a0b3b044f5b165c638033a1a93dc83bfc98b97b0c6d26180c3de75")
    }

    test("version 2 keeps the digest the project grit pinned") {
      // The pin grit_kalandra's project grit holds; its brief headers show b08f7ac5f0a1.
      GritMethod.v2.map(_.digest) ==>
        Right("b08f7ac5f0a18cdfffcec83468e48295d3f2e1bb11dc49cf31b8869457c80a1f")
    }

    test("version 2's milestone sits later, next, now or parallel and ends done or dropped") {
      val milestone =
        version(2).kinds
          .find(k => KindName.value(k.name) == "milestone")
          .getOrElse(sys.error("none"))
      (milestone.all.map(StateName.value), milestone.ends.map(StateName.value)) ==> (
        Vector("later", "next", "now", "parallel", "done", "dropped"),
        Set("done", "dropped")
      )
      kinds(version(2)) ==> Set("milestone", "plan", "stage", "section", "mechanism", "note")
    }

    test(
      "version 3 is the latest: stage is checkpoint, and a backlog item is taken, folded or dropped"
    ) {
      methods.map(_.version) ==> Vector(1, 2, 3)
      val v3 = version(3)
      kinds(v3) ==> Set(
        "milestone",
        "plan",
        "checkpoint",
        "section",
        "mechanism",
        "note",
        "backlog"
      )
      val backlog =
        v3.kinds.find(k => KindName.value(k.name) == "backlog").getOrElse(sys.error("none"))
      (backlog.all.map(StateName.value), backlog.ends.map(StateName.value)) ==> (
        Vector("open", "taken", "folded", "dropped"),
        Set("taken", "folded", "dropped")
      )
    }

    test(
      "version 3's views: the coder and manager carry their rules, the reviewer sees every kind"
    ) {
      version(3).views
        .map(v =>
          (
            RoleName.value(v.role),
            v.depth,
            v.kinds.map(KindName.value),
            v.carried.map(LocalKey.value)
          )
        )
        .sortBy(_._1) ==> Vector(
        (
          "coder",
          2,
          Set("milestone", "plan", "checkpoint", "section", "mechanism"),
          Set("roadmap-coder-rules")
        ),
        ("manager", 1, kinds(version(3)), Set("roadmap-update-protocol")),
        ("reviewer", 2, kinds(version(3)), Set[String]())
      )
    }

    test("a version 3 milestone cannot end done while it parents an open backlog item") {
      val artifacts = Vector(
        Artifact(head("m", "milestone", "now"), right(Body.read("M."))),
        Artifact(head("b", "backlog", "open", parent = Some("m")), right(Body.read("B.")))
      )
      version(3)
        .move(artifacts, right(ArtifactKey.read("p/m")), right(StateName.of("done")))
        .left
        .map(_.toString) ==> Left(
        "Unheld(ArtifactHead(ArtifactKey(p,m),milestone,now,m,None,Vector(),None),done," +
          "Vector((children-ended,not ended yet: p/b (open))))"
      )
    }

    test("a version 2 project migrates to version 3, its stages checkpoints, a note re-kinded") {
      val v2 = version(2)
      val v3 = version(3)
      def project(m: Method, as: Artifact*): Project =
        right(
          Project.of(
            right(ProjectKey.of("p")),
            right(Pin.of(m.name, m.version, m.digest)),
            as.toVector
          )
        )
      val m = Artifact(head("m", "milestone", "now"), right(Body.read("M.")))
      val s = Artifact(head("s", "stage", "building", parent = Some("m")), right(Body.read("S.")))
      val n = Artifact(head("n", "note", "open", parent = Some("m")), right(Body.read("N.")))
      v3.migrated(
        project(v2, m, s, n),
        Remap(
          kinds = Map(right(KindName.of("stage")) -> right(KindName.of("checkpoint"))),
          artifacts = Map(right(ArtifactKey.read("p/n")) -> right(KindName.of("backlog")))
        )
      ) ==> Right(
        Migrated(
          project(
            v3,
            m,
            Artifact(s.head.copy(kind = right(KindName.of("checkpoint"))), s.body),
            Artifact(n.head.copy(kind = right(KindName.of("backlog"))), n.body)
          ),
          Vector(right(ArtifactKey.read("p/n")), right(ArtifactKey.read("p/s")))
        )
      )
    }
  }
}
