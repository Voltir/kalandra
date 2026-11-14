package kalandra.deployment

import grit.core.identity.Account
import grit.core.message.Tokens
import grit.core.model.{ModelId, ModelRef, Upstream}
import grit.core.place.{Directory, Place}
import grit.core.spend.DailyCap
import grit.core.tool.ToolName
import grit.core.visibility.{Label, Labelled, Party, Room}
import grit.kit.deployment.{Assembly, DeploymentVersion, Offered, Placer}
import grit.projects.name.{KindName, StateName}
import grit.projects.plugin.{Projects, TurnCommands}

import kalandra.method.GritMethod
import utest.*

object KalandraTests extends TestSuite {

  private val checkout: Directory =
    Directory.of("/home/someone/grit").fold(sys.error, identity)

  private lazy val kalandra = Kalandra.deployment(checkout).fold(sys.error, identity)

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

    test(
      "serve's turns may not move an amendment to adopted, and its plugin is built with that guard"
    ) {
      // A turn's refused move is the plugin's (grit's ProjectCommandsTests); a Tx cannot be opened
      // outside grit, so what is pinned here is that serve's plugin holds the guard, a state
      // Projects.of refuses unless the method has it.
      (
        Kalandra.ServeTurns,
        Projects
          .of(
            GritMethod.declared.fold(e => sys.error(e.mkString), identity),
            GritMethod.Name,
            Kalandra.ServeTurns
          )
          .isRight
      ) ==> (
        TurnCommands.Shipped(
          Set(
            KindName
              .of("amendment")
              .fold(sys.error, identity) -> StateName.of("adopted").fold(sys.error, identity)
          )
        ),
        true
      )
    }

    test(
      "a turn is offered the projects plugin's reads, keep, move and new, and not withdraw, migrate, claim or release"
    ) {
      kalandra.plugins
        .flatMap(_.commands)
        .filter(_.offeredToTurns)
        .map(c => ToolName.value(c.spec.name))
        .toSet ==> Set(
        "project_new",
        "project_keep",
        "project_move",
        "project_show",
        "project_history",
        "project_list",
        "project_find",
        "project_export",
        "project_stale",
        "project_brief"
      )
    }
  }
}
