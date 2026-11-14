package kalandra.edge

import grit.commands.caller.{Caller, Callers}
import grit.commands.machine.MachineName
import grit.core.id.{EdgeName, Sha256}
import grit.core.identity.{Account, Realm}
import grit.core.place.Place
import grit.core.visibility.Integrity
import grit.edge.http.{Challenge, Listen, Request, Verb}

import utest.*

object MountTests extends TestSuite {

  private val owner = Account.of("workshop", "local/someone").fold(sys.error, identity)
  private val realm = Realm.of("workshop", "local").fold(sys.error, identity)
  private val room = Place.read("fs:/home/someone/grit").fold(sys.error, identity)

  private def at(text: String): Listen = Listen.parse(text).fold(sys.error, identity)

  /** Two throwaway credentials' texts, and an environment holding their digests. */
  private val ownText = "o" * 43
  private val sessionsText = "s" * 43
  private val digests = Map(
    "KALANDRA_OWNER_DIGEST" -> Sha256.text(ownText).hex,
    "KALANDRA_SESSIONS_DIGEST" -> Sha256.text(sessionsText).hex
  )

  private def bearing(text: String): Request =
    Request
      .of(Verb.Post, "/command/project_list", Vector("Authorization" -> s"Bearer $text"), "{}")
      .fold(sys.error, identity)

  private def callers: Callers = Mount.callers(owner, digests).fold(sys.error, identity)

  val tests = Tests {
    test("serve listens on 127.0.0.1:7317 unless KALANDRA_LISTEN says otherwise") {
      (
        Mount.listening(Map.empty).map(_.shown),
        Mount.listening(Map("KALANDRA_LISTEN" -> " 127.0.0.1:7318 ")).map(_.shown),
        Mount.listening(Map("KALANDRA_LISTEN" -> "7318")).isLeft
      ) ==> (Right("127.0.0.1:7317"), Right("127.0.0.1:7318"), true)
    }

    test(
      "the two digests give the owner on the machine local, its command line at authority and its sessions at member"
    ) {
      val local = MachineName.of("local").fold(sys.error, identity)
      (
        callers.from(bearing(ownText)),
        callers.from(bearing(sessionsText)),
        callers.from(bearing("x" * 43))
      ) ==> (
        Right(Caller(owner, Integrity.Authority, local)),
        Right(Caller(owner, Integrity.Member, local)),
        Left(Challenge.Refused)
      )
    }

    test("a digest variable unset or no digest is refused by its name, never its value") {
      val notHex = "KALANDRA_SESSIONS_DIGEST" -> ("Z" * 64)
      (
        Mount.callers(owner, digests - "KALANDRA_OWNER_DIGEST").map(_.accounts),
        Mount.callers(owner, digests - "KALANDRA_SESSIONS_DIGEST").map(_.accounts),
        Mount.callers(owner, digests + notHex).map(_.accounts)
      ) ==> (
        Left(
          "KALANDRA_OWNER_DIGEST is not set: the SHA-256 digest, 64 lowercase hex, of the command line's credential"
        ),
        Left(
          "KALANDRA_SESSIONS_DIGEST is not set: the SHA-256 digest, 64 lowercase hex, of the sessions' credential"
        ),
        Left("KALANDRA_SESSIONS_DIGEST is not a SHA-256 digest, 64 lowercase hex")
      )
    }

    test("one digest given for both credentials is refused") {
      Mount
        .callers(owner, digests + ("KALANDRA_SESSIONS_DIGEST" -> digests("KALANDRA_OWNER_DIGEST")))
        .map(_.accounts) ==> Left(
        "KALANDRA_OWNER_DIGEST and KALANDRA_SESSIONS_DIGEST name one credential: give each its own"
      )
    }

    test("the edge is named commands, and a request with no credential is asked for one") {
      (
        Mount.edge(at("127.0.0.1:7318"), room, callers, realm).map(_.name),
        callers.from(bearing("").without("Authorization"))
      ) ==> (Right(EdgeName("commands")), Left(Challenge.Asked))
    }

    test("an edge on a wildcard address is refused, as a credential would cross it in clear") {
      Mount
        .edge(at("0.0.0.0:7317"), room, callers, realm)
        .map(_.name) ==> Left(
        "a credential crosses the network in clear, so the edge listens on loopback alone, not 0.0.0.0:7317"
      )
    }

    test("a caller outside the realm is refused") {
      val stranger = Account.of("elsewhere", "someone").fold(sys.error, identity)
      val theirs = Mount.callers(stranger, digests).fold(sys.error, identity)
      Mount
        .edge(at("127.0.0.1:7317"), room, theirs, realm)
        .map(_.name) ==> Left("elsewhere:someone is no account of workshop:local/")
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
