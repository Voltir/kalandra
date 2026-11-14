package kalandra.edge

import grit.commands.worker.WorkerName
import grit.core.id.EdgeName
import grit.core.identity.{Account, Realm}
import grit.core.place.Place
import grit.core.visibility.Integrity
import grit.edge.http.Listen

import utest.*

object MountTests extends TestSuite {

  private val owner = Account.of("workshop", "local/someone").fold(sys.error, identity)
  private val realm = Realm.of("workshop", "local").fold(sys.error, identity)
  private val room = Place.read("fs:/home/someone/grit").fold(sys.error, identity)

  private def at(text: String): Listen = Listen.parse(text).fold(sys.error, identity)

  val tests = Tests {
    test("serve listens on 127.0.0.1:7317 unless KALANDRA_LISTEN says otherwise") {
      (
        Mount.listening(Map.empty).map(_.shown),
        Mount.listening(Map("KALANDRA_LISTEN" -> " 127.0.0.1:7318 ")).map(_.shown),
        Mount.listening(Map("KALANDRA_LISTEN" -> "7318")).isLeft
      ) ==> (Right("127.0.0.1:7317"), Right("127.0.0.1:7318"), true)
    }

    test("the edge is named commands, for the owner's requests from the worker local") {
      (
        Mount.edge(at("127.0.0.1:7318"), room, owner, Integrity.Member, realm).map(_.name),
        WorkerName.value(Mount.Worker)
      ) ==> (Right(EdgeName("commands")), "local")
    }

    test(
      "an edge on a wildcard address is refused, as any process reaching it would be the owner"
    ) {
      Mount
        .edge(at("0.0.0.0:7317"), room, owner, Integrity.Member, realm)
        .left
        .map(_.contains("listens on loopback alone")) ==> Left(true)
    }

    test("an owner outside the realm is refused") {
      val stranger = Account.of("elsewhere", "someone").fold(sys.error, identity)
      Mount
        .edge(at("127.0.0.1:7317"), room, stranger, Integrity.Member, realm)
        .left
        .map(_.contains("is no account of workshop:local")) ==> Left(true)
    }

    test(
      "the MCP server is kalandra, telling a client a project artifact is no claude.ai Artifact"
    ) {
      (
        Mount.Server.name,
        Mount.Server.instructions.exists(_.contains("not a claude.ai Artifact"))
      ) ==>
        ("kalandra", true)
    }
  }
}
