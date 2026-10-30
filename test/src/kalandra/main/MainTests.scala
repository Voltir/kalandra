package kalandra.main

import grit.core.place.Directory
import grit.kit.environment.Secrets
import grit.kit.run.KitFailure

import kalandra.deployment.Kalandra
import utest.*

object MainTests extends TestSuite {

  private val keys = Map(
    "ANTHROPIC_API_KEY" -> "a",
    "JEV_API_KEY" -> "j",
    "OPENROUTER_API_KEY" -> "o"
  )

  val tests = Tests {
    test("the kit is given Anthropic's and Jev's keys, never OpenRouter's") {
      Main.environment(keys).map(_.keySet & keys.keySet) ==> Right(
        Set("ANTHROPIC_API_KEY", "JEV_API_KEY")
      )
    }

    test(
      "a command that runs turns refuses an environment without Anthropic's key, rather than answer with the stub"
    ) {
      (
        Main
          .keyed(keys - "ANTHROPIC_API_KEY")
          .left
          .map(_.startsWith("ANTHROPIC_API_KEY is not set")),
        Main.keyed(keys + ("ANTHROPIC_API_KEY" -> " ")).isLeft,
        Main.keyed(keys).isRight
      ) ==> (Left(true), true, true)
    }

    test("a missing Jev key is refused, and the refusal says where kalandra reads its keys") {
      val dir = Directory.of("/home/someone/grit").fold(sys.error, identity)
      val kalandra = Kalandra.deployment(dir).fold(sys.error, identity)
      val env = Main
        .environment(keys - "JEV_API_KEY" + ("GRIT_DATABASE_URL" -> "jdbc:postgresql://h/k"))
        .fold(sys.error, identity)
      Secrets
        .of(env, kalandra)
        .left
        .map(r => Main.said(KitFailure.Unconfigured(r)))
        .left
        .map(m => (m.contains("JEV_API_KEY"), m.endsWith(Main.KeysHint))) ==> Left((true, true))
    }
  }
}
