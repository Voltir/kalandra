package kalandra.main

import grit.core.edge.Console
import grit.core.place.Directory
import grit.kit.environment.Secrets
import grit.kit.run.KitFailure

import kalandra.deployment.{Kalandra, KalandraTests}
import utest.*

object MainTests extends TestSuite {

  private val keys = Map(
    "ANTHROPIC_API_KEY" -> "a",
    "JEV_API_KEY" -> "j",
    "OPENROUTER_API_KEY" -> "o"
  )

  private def dir(s: String): Directory = Directory.of(s).fold(sys.error, identity)

  /** An exported root artifact's file: its path and its text, as `project_export` writes it. */
  private def exported(id: String, handle: String, kind: String, state: String): (String, String) =
    s"${id.drop(id.indexOf('/') + 1)}.md" ->
      s"""---
         |id: $id
         |handle: $handle
         |kind: $kind
         |state: $state
         |title: "T"
         |parent: null
         |rank: n
         |cites: []
         |by: "workshop:local/nick"
         |---
         |
         |Its body.
         |""".stripMargin

  val tests = Tests {
    test(
      "every amendment, an ended one among them, is written as exported at its handle, and nothing else"
    ) {
      val index = "project.md" ->
        s"""---
           |key: m17
           |method: grit
           |version: 4
           |digest: ${"0" * 64}
           |---
           |""".stripMargin
      val rejected = exported("m17/2", "amend-a", "amendment", "rejected")
      val proposed = exported("m17/3", "amend-b", "amendment", "proposed")
      Amendments.of(
        Vector(index, exported("m17/1", "note", "note", "open"), proposed, rejected)
      ) ==>
        Right(Vector("amend-a.md" -> rejected._2, "amend-b.md" -> proposed._2))
    }

    test("amendments written again leave no file of an amendment whose handle changed") {
      val into = java.nio.file.Files.createTempDirectory("amendments")
      java.nio.file.Files.writeString(into.resolve("notes.txt"), "kept")
      Amendments.written(into, Vector("amend-a.md" -> "a", "amend-b.md" -> "b"))
      (
        Amendments.written(into, Vector("amend-c.md" -> "a", "amend-b.md" -> "b2")),
        scala.util
          .Using(java.nio.file.Files.list(into))(
            _.toArray.toVector.map(_.toString.split('/').last).sorted
          )
          .toOption
      ) ==> (Right(2), Some(Vector("amend-b.md", "amend-c.md", "notes.txt")))
    }

    test("an export writes each file at its path, nested ones in their directories") {
      val into = java.nio.file.Files.createTempDirectory("export").resolve("m17")
      val files = Vector("project.md" -> "index", "3.md" -> "root", "3/1.md" -> "child")
      (
        Export.written(into, files),
        files.map((path, _) =>
          scala.util.Try(java.nio.file.Files.readString(into.resolve(path))).toOption
        )
      ) ==> (Right(3), Vector(Some("index"), Some("root"), Some("child")))
    }

    test(
      "an export into a directory holding a file, or into a file, is refused, naming it, and writes nothing"
    ) {
      val into = java.nio.file.Files.createTempDirectory("export")
      val file = java.nio.file.Files.writeString(into.resolve("12.md"), "an earlier export's")
      (
        Export.written(into, Vector("project.md" -> "index")),
        Export.written(file, Vector("project.md" -> "index")),
        java.nio.file.Files.exists(into.resolve("project.md"))
      ) ==> (
        Left(s"$into is neither new nor an empty directory: kalandra export writes only into one"),
        Left(s"$file is neither new nor an empty directory: kalandra export writes only into one"),
        false
      )
    }

    test(
      "a proposal, dry or not, runs only on an engine this process starts, refused while serve holds it, naming bin/kalandra stop"
    ) {
      (
        Proposer.admit(Console.Runner.Here),
        Proposer.admit(Console.Runner.Elsewhere(None)).left.map(_.contains("`bin/kalandra stop`"))
      ) ==> (Right(()), Left(true))
    }

    test("work refuses a directory outside the evidence root, and a sibling whose name it begins") {
      val root = dir("/home/someone/.cache/grit-propose")
      Vector(
        "/home/someone/.cache/grit-propose/propose-1",
        "/home/someone/grit",
        "/home/someone/.cache/grit-propose-old"
      )
        .map(d => Proposer.within(root, dir(d)).isRight) ==> Vector(true, false, false)
    }

    test("the kit is given Anthropic's, Jev's and OpenRouter's keys") {
      Main.environment(keys).map(_.keySet & keys.keySet) ==> Right(
        Set("ANTHROPIC_API_KEY", "JEV_API_KEY", "OPENROUTER_API_KEY")
      )
    }

    test("kalandra opens grit_kalandra, and a database whose name starts grit_kalandra_") {
      def opened(url: String) =
        Main.environment(keys + ("GRIT_DATABASE_URL" -> url)).map(_.get("GRIT_DATABASE_URL"))
      (
        Main.environment(keys).map(_.get("GRIT_DATABASE_URL")),
        opened("jdbc:postgresql://localhost:5432/grit_kalandra_e7"),
        opened("jdbc:postgresql://h/grit_kalandra_e7?currentSchema=grit")
      ) ==> (
        Right(Some("jdbc:postgresql://localhost:5432/grit_kalandra")),
        Right(Some("jdbc:postgresql://localhost:5432/grit_kalandra_e7")),
        Right(Some("jdbc:postgresql://h/grit_kalandra_e7?currentSchema=grit"))
      )
    }

    test("any other database is refused by its name alone, the refusal stating the rule") {
      def refusal(url: String) =
        Main.environment(keys + ("GRIT_DATABASE_URL" -> url)).left.toOption
      def rule(name: String) = Some(
        s"GRIT_DATABASE_URL names $name; kalandra opens grit_kalandra, or a database whose name starts grit_kalandra_"
      )
      (
        refusal("jdbc:postgresql://localhost:5432/grit"),
        refusal("jdbc:postgresql://localhost:5432/grit_kalandrax"),
        refusal("jdbc:postgresql://localhost:5432/kalandra"),
        refusal("jdbc:postgresql://localhost:5432/"),
        refusal("jdbc:postgresql://localhost:5432/grit?options=grit_kalandra_e7")
      ) ==> (rule("grit"), rule("grit_kalandrax"), rule("kalandra"), rule(""), rule("grit"))
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
      val kalandra = Kalandra
        .deployment(dir, KalandraTests.Own, KalandraTests.Listening, KalandraTests.Calling)
        .fold(sys.error, identity)
      val env = Main
        .environment(
          keys - "JEV_API_KEY" + ("GRIT_DATABASE_URL" -> "jdbc:postgresql://h/grit_kalandra_k")
        )
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
