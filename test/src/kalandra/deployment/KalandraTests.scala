package kalandra.deployment

import grit.core.identity.Account
import grit.core.message.Tokens
import grit.core.model.{ModelId, ModelRef, Upstream}
import grit.core.place.{Directory, Place}
import grit.core.spend.DailyCap
import grit.core.tool.ToolName
import grit.core.visibility.{Labelled, Party, Room}
import grit.kit.deployment.{Assembly, Placer}

import utest.*

object KalandraTests extends TestSuite {

  private val checkout: Directory =
    Directory.of("/home/someone/grit").fold(sys.error, identity)

  private lazy val kalandra = Kalandra.deployment(checkout).fold(sys.error, identity)

  private def direct(model: String): ModelRef =
    ModelRef(ModelId.of(model).getOrElse(sys.error(model)), Some(Upstream.AnthropicApi))

  val tests = Tests {
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
      "a turn makes at most 12 model calls, and its input may reach 200,000 tokens before it is told to act"
    ) {
      (kalandra.offer.rounds.calls, kalandra.offer.rounds.limit) ==> (12, Some(Tokens(200_000)))
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
