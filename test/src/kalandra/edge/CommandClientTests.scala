package kalandra.edge

import java.nio.file.{Files, Path}

import utest.*

object CommandClientTests extends TestSuite {

  /** A directory of this run's own, holding a credential file of `text` as `credential`. */
  private def holding(text: String): Path = {
    val dir = Files.createTempDirectory("kalandra-client")
    Files.writeString(dir.resolve("credential"), text)
  }

  private def refusal(env: Map[String, String]): Either[String, Unit] =
    CommandClient.of(env).map(_ => ())

  val tests = Tests {
    test("a client is refused without KALANDRA_CREDENTIAL, saying bin/kalandra sets it") {
      refusal(Map.empty) ==> Left(
        "KALANDRA_CREDENTIAL is not set: the path of the file holding the credential to send, " +
          "which bin/kalandra sets"
      )
    }

    test("a missing or empty credential file is refused by its path, and nothing is sent") {
      val empty = holding("\n")
      val missing = empty.resolveSibling("none")
      def said(p: Path) =
        s"cannot read a credential in $p; nothing was sent: ask whoever runs serve for this " +
          s"credential and put it in $p"
      (
        refusal(Map("KALANDRA_CREDENTIAL" -> missing.toString)),
        refusal(Map("KALANDRA_CREDENTIAL" -> empty.toString))
      ) ==> (Left(said(missing)), Left(said(empty)))
    }

    test("a readable credential file gives a client of the serve KALANDRA_LISTEN names") {
      val file = holding("c" * 43 + "\n")
      (
        refusal(Map("KALANDRA_CREDENTIAL" -> file.toString)),
        refusal(Map("KALANDRA_CREDENTIAL" -> file.toString, "KALANDRA_LISTEN" -> "7318")).isLeft
      ) ==> (Right(()), true)
    }
  }
}
