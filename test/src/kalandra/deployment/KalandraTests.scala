package kalandra.deployment

import grit.core.place.{Directory, Place}
import grit.core.visibility.{Labelled, Room}

import utest.*

object KalandraTests extends TestSuite {

  private val checkout: Directory =
    Directory.of("/home/someone/grit").fold(sys.error, identity)

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
      "the deployment labels its room as its rooms do, and its owner alone is cleared for it"
    ) {
      val visible =
        Kalandra.deployment(checkout).fold(sys.error, identity).visibility
      visible.rooms.label(Room(Kalandra.room(checkout), None)) ==> Labelled
        .Mapped(Kalandra.Kept)
      visible.grants.map(g =>
        (visible.groups.filter(_.name == g.group).map(_.accounts), g.label)
      ) ==>
        Vector((Vector(Set(Kalandra.Owner)), Kalandra.Kept))
    }
  }
}
