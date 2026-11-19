package kalandra.deployment

import grit.commands.caller.Callers
import grit.core.id.{EdgeName, Sha256}
import grit.core.identity.Account
import grit.core.message.Tokens
import grit.core.model.{ModelId, ModelRef, Upstream}
import grit.core.place.{Directory, Place}
import grit.core.spend.DailyCap
import grit.core.tool.ToolName
import grit.core.visibility.{Label, Labelled, Party, Room}
import grit.edge.http.Listen
import grit.kit.deployment.{Assembly, DeploymentVersion, Offered, Placer}

import kalandra.edge.{Mount, OwnCommands}
import utest.*

object KalandraTests extends TestSuite {

  /** Where a deployment built in a test listens: serve's default address. */
  val Listening: Listen = Listen.parse("127.0.0.1:7317").fold(sys.error, identity)

  /** Who a deployment built in a test takes requests from: the owner, by two throwaway digests. */
  val Calling: Callers = Mount
    .callers(
      Kalandra.Owner,
      Map(
        Mount.OwnerDigestVar -> Sha256.text("o" * 43).hex,
        Mount.SessionsDigestVar -> Sha256.text("s" * 43).hex
      )
    )
    .fold(sys.error, identity)

  private val checkout: Directory =
    Directory.of("/home/someone/grit").fold(sys.error, identity)

  private lazy val kalandra =
    Kalandra.deployment(checkout, Listening, Calling).fold(sys.error, identity)

  private def direct(model: String): ModelRef =
    ModelRef(ModelId.of(model).getOrElse(sys.error(model)), Some(Upstream.AnthropicApi))

  private val evidence: Directory =
    Directory.of("/home/someone/.cache/grit-propose").fold(sys.error, identity)

  private lazy val proposer = Kalandra.proposing(checkout, evidence).fold(sys.error, identity)

  val tests = Tests {
    test(
      "a proposer's turns are offered the coding tools' reads and project_propose alone"
    ) {
      (
        proposer.offer.tools,
        proposer.plugins
          .flatMap(_.commands)
          .filter(_.offeredToTurns)
          .map(c => ToolName.value(c.spec.name))
      ) ==> (Offered.Read, Vector("project_propose"))
    }

    test(
      "a proposer's evidence checkouts, under its evidence root, are rooms at kalandra's label, as the checkout is"
    ) {
      val checkout0 =
        Place.read("fs:/home/someone/.cache/grit-propose/propose-1").fold(sys.error, identity)
      val rooms = proposer.visibility.rooms
      (
        rooms.label(Room(checkout0, None)),
        rooms.label(Room(Kalandra.room(checkout), None)),
        rooms.label(Room(Place.read("fs:/home/someone").fold(sys.error, identity), None))
      ) ==> (
        Labelled.Mapped(Kalandra.Kept),
        Labelled.Mapped(Kalandra.Kept),
        Labelled.Unmapped(Label.Public)
      )
    }

    test(
      "a proposer runs on Claude Opus 5.5 at 8,192 tokens a reply, 20 calls a turn over a 32,000-token window, 3 turns a proposal, under the version proposer, a day at most $6"
    ) {
      (
        proposer.policy.turn.rungs.map(r => (r.assignment.ref, r.assignment.maxTokens)),
        (proposer.offer.rounds.calls, proposer.offer.rounds.limit, proposer.assembly),
        Kalandra.ProposerTurns,
        DeploymentVersion.value(proposer.version),
        proposer.budget.cap,
        proposer.placer
      ) ==> (
        Vector((direct("anthropic/claude-opus-5-5"), 8_192)),
        (20, None, Assembly.Linear(Tokens(32_000))),
        3,
        "proposer",
        DailyCap.of("6").toOption,
        Kalandra.ProposerPlacer
      )
    }

    test("serve's one edge is grit's commands edge, needing no variable") {
      kalandra.edges.map(e => (EdgeName.value(e.name), e.needs)) ==> Vector(("commands", Vector()))
    }

    test("a proposer declares no schedules, no jobs and no edge") {
      (proposer.schedules.size, proposer.jobs.size, proposer.edges.size) ==> (0, 0, 0)
    }

    test(
      "the checkout's room is declared internal, so a command there reads internal rows"
    ) {
      // Tx.writable reads a room's label only where the labeller maps it explicitly; anywhere
      // else a command's transaction reads public rows alone.
      val room = Room(Kalandra.room(checkout), None)
      val rooms = Kalandra.rooms(checkout).fold(sys.error, identity)
      (rooms.declares(room), rooms.label(room)) ==> (
        true,
        Labelled.Mapped(Kalandra.Kept)
      )
      val elsewhere =
        Room(Place.read("fs:/tmp").fold(sys.error, identity), None)
      rooms.declares(elsewhere) ==> false
    }

    test(
      "the deployment labels its room as its rooms do, and its owner and whoever runs its chat here are cleared for it"
    ) {
      val visible = kalandra.visibility
      visible.rooms.label(Room(Kalandra.room(checkout), None)) ==> Labelled
        .Mapped(Kalandra.Kept)
      visible.grants.map(g =>
        (visible.groups.filter(_.name == g.group).map(_.accounts), g.label)
      ) ==>
        Vector((Vector(Set[Account](Kalandra.Owner, Account.Local)), Kalandra.Kept))
    }

    test(
      "turns run on Claude Sonnet 5.5 and every other role on Claude Haiku 5.5, each through Anthropic's API"
    ) {
      kalandra.policy.ladders.map(_.rungs.map(_.assignment.ref)) ==> Vector(
        Vector(direct("anthropic/claude-sonnet-5-5")),
        Vector(direct("anthropic/claude-haiku-5-5")),
        Vector(direct("anthropic/claude-haiku-5-5")),
        Vector(direct("anthropic/claude-haiku-5-5")),
        Vector(direct("anthropic/claude-haiku-5-5"))
      )
    }

    test(
      "both models and Jev are cleared for the room's label, so a turn in the room may call them"
    ) {
      val clearances = kalandra.visibility.clearances
      Vector(
        Party.of(direct("anthropic/claude-sonnet-5-5")),
        Party.of(direct("anthropic/claude-haiku-5-5")),
        Some(Party.Service(Placer.JevService))
      ).map(_.map(clearances.of)) ==> Vector.fill(3)(Some(Kalandra.Kept))
    }

    test(
      "Jev places each message, a day spends at most $1, and a window holds 32,000 tokens"
    ) {
      (kalandra.placer, kalandra.budget.cap, kalandra.assembly) ==> (
        Placer.Jev,
        DailyCap.of("1").toOption,
        Assembly.Linear(Tokens(32_000))
      )
    }

    test(
      "a turn makes at most 12 model calls, its input bounded by its pin, not by the deployment"
    ) {
      (kalandra.offer.rounds.calls, kalandra.offer.rounds.limit) ==> (12, None)
    }

    test("a turn is offered the plugin commands a session may run") {
      kalandra.plugins
        .flatMap(_.commands)
        .filter(_.offeredToTurns)
        .map(c => ToolName.value(c.spec.name))
        .toSet ==> Set(
        "project_new",
        "project_keep",
        "project_edit",
        "project_move",
        "project_reorder",
        "project_split",
        "project_show",
        "project_history",
        "project_list",
        "project_find",
        "project_export",
        "project_stale",
        "project_brief"
      )
    }

    test("no plugin command has the name of a command kalandra's edge adds") {
      val plugins = kalandra.plugins.flatMap(_.commands).map(c => ToolName.value(c.spec.name)).toSet
      OwnCommands.all.map(o => ToolName.value(o.name)).filter(plugins) ==> Vector()
    }

    test("the one phrase ops/deploy.sh greps for an already-declared method is part of Projects.DeclaredAlready") {
      val greps = DeployScript.alreadyDeclaredGreps
      (greps.size, greps.forall(grit.projects.plugin.Projects.DeclaredAlready.contains)) ==> (1, true)
    }

    test("the survey answer ops/deploy.sh reads as nothing left to re-key is Projects.NothingToRekey") {
      DeployScript.nothingToRekey ==> Some(grit.projects.plugin.Projects.NothingToRekey)
    }
  }
}

/** `ops/deploy.sh`'s text, read from the checkout the tests run in, for what it greps grit's reply for. */
object DeployScript {

  /** The text of `ops/deploy.sh`, found in the working directory or one of its parents. */
  lazy val text: String = Iterator
    .iterate(java.nio.file.Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .map(_.resolve("ops/deploy.sh"))
    .find(java.nio.file.Files.exists(_))
    .map(java.nio.file.Files.readString(_))
    .getOrElse(sys.error("ops/deploy.sh not found above the working directory"))

  /** The answer the script reads as no project being left to re-key: its `rekey_none`. */
  def nothingToRekey: Option[String] =
    """(?m)^rekey_none="([^"]+)"$""".r.findFirstMatchIn(text).map(_.group(1))

  /** The phrases `== *"…"*` the script matches with, that name a declared method's reply. */
  def alreadyDeclaredGreps: Vector[String] =
    """== \*"([^"]+)"\*""".r
      .findAllMatchIn(text)
      .map(_.group(1))
      .filter(_.contains("declared already"))
      .toVector
}
