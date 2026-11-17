package kalandra.importer

import grit.projects.method.Method
import grit.projects.name.{ArtifactKey, Pin, ProjectKey}

import kalandra.method.MethodFiles

/** A small planning tree, shaped as grit's: a roadmap, its done list, two mechanisms that cite
  * each other, backlog notes (one blank, one over the stored cap), and one open plan; a closed
  * plan, `m1`, is linked but not among the sources.
  */
object Fixture {

  val Roadmap: String =
    """# grit — Roadmap
      |
      |> Living document. What each mechanism is: [mechanisms](mechanisms/README.md).
      |
      |## Milestones
      |
      |- **Done:** M0 through M2, in [`done.md`](done.md).
      |- **M3 — Projects** [edges]. Its plan: `.local/plans/m3.md`.
      |  The second line of M3.
      |- **M4 — Compaction** later work.
      |
      |**Later, unordered.**
      |- **Email edge** (`email-edge.md`).
      |
      |## Now
      |
      |Building **M3 — Projects** (`.local/plans/m3.md`).
      |
      |## Parallel
      |
      |- [ ] **Tooling**: beside the milestones.
      |
      |## Next
      |
      |- [ ] **Slack joins**: see `.local/backlog/slack-joins.md`.
      |
      |## Done
      |
      |In [`done.md`](done.md); the old plan `.local/plans/m1.md`.
      |
      |## Update Protocol
      |
      |### When to update
      |
      |- **Finishing a step** — delete it.
      |""".stripMargin

  val Done: String =
    """# grit — Done
      |
      |What shipped.
      |
      |- **M2 — Storage: closed** (2026-10-01). Shipped storage.
      |  - a sub point
      |- **M1 — The turn** (2026-09-01).
      |
      |**M1.5: the contract** (2026-09-15). One spec.
      |
      |Pruned at M1's close.
      |
      |## E1 — recipes (closed 2026-09-20)
      |
      |Recipes shipped.
      |""".stripMargin

  val Assembly: String =
    """# Assembly
      |
      |The projection. See [edges](edges.md), and the closed plan [m1](../../.local/plans/m1.md).
      |""".stripMargin

  val Plan: String =
    """# M3 — Projects
      |
      |The plan for M3.
      |
      |## Shared design
      |
      |Artifacts are documents: [assembly](../../roadmap/mechanisms/assembly.md).
      |
      |## S1. Documents
      |
      |**Goal.** Caps.
      |
      |## S2. Commands
      |
      |**Goal.** Commands.
      |""".stripMargin

  /** `n` paragraphs of words, about 100 bytes each. */
  private def words(tag: String, n: Int): String =
    (1 to n)
      .map(i => (1 to 12).map(w => s"$tag$i$w").mkString("Some words ", " ", "."))
      .mkString("\n\n")

  /** Over the stored cap whole, and its second `##` part over it too, each `###` under it. */
  val Big: String =
    s"""# Big
       |
       |Intro.
       |
       |## Part one
       |
       |${words("a", 80)}
       |
       |## Part two
       |
       |### Two a
       |
       |${words("b", 180)}
       |
       |### Two b
       |
       |${words("c", 180)}
       |""".stripMargin

  val sources: Vector[Source] = Vector(
    Source("roadmap/ROADMAP.md", Roadmap),
    Source("roadmap/done.md", Done),
    Source(
      "roadmap/mechanisms/README.md",
      "# Mechanisms\n\n- [Assembly](assembly.md)\n- [Edges](edges.md)\n"
    ),
    Source("roadmap/mechanisms/assembly.md", Assembly),
    Source(
      "roadmap/mechanisms/edges.md",
      "# Edges\n\nEdges reach the engine; [assembly](assembly.md) draws the window.\n"
    ),
    Source(".local/backlog/README.md", "# Backlog\n\nLoose ideas.\n"),
    Source(".local/backlog/email-edge.md", "# Email edge\n\nMail as an edge.\n"),
    Source(
      ".local/backlog/slack-joins.md",
      "# Slack joins\n\nRooms appear at restart; `roadmap/mechanisms/edges.md` says why.\n"
    ),
    Source(".local/backlog/empty.md", "   \n"),
    Source(".local/backlog/big.md", Big),
    Source(".local/plans/m3.md", Plan)
  )

  val project: ProjectKey = ProjectKey.of("grit").fold(sys.error, identity)

  val method: Method = MethodFiles.filed(2).fold(sys.error, _.method)

  val pin: Pin = Pin.of(method.name, method.version, method.digest).fold(sys.error, identity)

  def key(local: String): ArtifactKey = ArtifactKey.of(project, local).fold(sys.error, identity)

  lazy val imported: Imported =
    Importer.of(project, pin, method, sources).fold(bad => sys.error(bad.mkString("; ")), identity)
}
