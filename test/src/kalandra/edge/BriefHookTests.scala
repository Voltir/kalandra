package kalandra.edge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import kalandra.hooks.HookRun
import utest.*

/** `hooks/brief-hook.sh`, run as Claude Code runs it: a hook event's JSON on stdin. */
object BriefHookTests extends TestSuite {

  private type Ran = HookRun.Ran

  /** The hook run with `args` in `cwd`, `stdin` its input, reading briefs from `briefs`. */
  private def hook(stdin: String, briefs: Path, cwd: Path, args: String*): Ran =
    HookRun.run(
      HookRun.script("brief-hook.sh"),
      stdin,
      cwd,
      Map("KALANDRA_BRIEFS" -> briefs.toString),
      args*
    )

  private def sha1(s: String): String =
    MessageDigest
      .getInstance("SHA-1")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** A SessionStart event's stdin as Claude Code gives it for `source`. */
  private def sessionStart(cwd: Path, source: String): String =
    ujson.write(
      ujson.Obj(
        "session_id" -> "faafcbf0-3592-4c99-8f14-21889a88798e",
        "transcript_path" -> "/home/someone/.claude/projects/x/faafcbf0.jsonl",
        "cwd" -> cwd.toString,
        "hook_event_name" -> "SessionStart",
        "source" -> source
      )
    )

  /** A SubagentStart event's stdin for an agent of type `agentType`. */
  private def subagentStart(cwd: Path, agentType: String): String =
    ujson.write(
      ujson.Obj(
        "session_id" -> "faafcbf0-3592-4c99-8f14-21889a88798e",
        "transcript_path" -> "/home/someone/.claude/projects/x/faafcbf0.jsonl",
        "cwd" -> cwd.toString,
        "prompt_id" -> "4b3c22a9-9d87-4c3f-b8f4-d6d6085be7c4",
        "agent_id" -> "a068ac626e56ee78e",
        "agent_type" -> agentType,
        "hook_event_name" -> "SubagentStart"
      )
    )

  /** The hook's output read as the event and the context it injects; none for no hook JSON. */
  private def injected(ran: Ran): Option[(String, String)] =
    scala.util.Try {
      val output = ujson.read(ran.out)("hookSpecificOutput")
      (output("hookEventName").str, output("additionalContext").str)
    }.toOption

  /** A fresh briefs directory, and a fresh directory a session runs in. */
  private def dirs(): (Path, Path) =
    (Files.createTempDirectory("briefs"), Files.createTempDirectory("session"))

  /** `text` written as the brief for `slot` in `briefs`, as the edge writes it. */
  private def brief(briefs: Path, slot: String, text: String): Unit =
    new BriefFiles(briefs).write(slot, text).fold(sys.error, identity)

  val tests = Tests {
    test("SessionStart of every source injects the brief of the slot sha1(cwd)") {
      val (briefs, cwd) = dirs()
      brief(briefs, sha1(cwd.toString), "# the session's brief\n")
      brief(briefs, s"coder-haiku/${sha1(cwd.toString)}", "# a coder's brief\n")
      Vector("startup", "resume", "clear", "compact", "fork").map(source =>
        injected(hook(sessionStart(cwd, source), briefs, cwd))
      ) ==> Vector.fill(5)(Some("SessionStart" -> "# the session's brief\n"))
    }

    test(
      "SubagentStart injects the brief of the slot BriefFiles names for its agent type and cwd"
    ) {
      val (briefs, cwd) = dirs()
      brief(briefs, sha1(cwd.toString), "# the session's brief\n")
      val types = Vector("coder-haiku", "Explore", "Coder_Opus", "a.b:c", "_x", "KKb", "a😀b")
      types.foreach(t => brief(briefs, BriefFiles.slot(s"$t/${sha1(cwd.toString)}"), s"# $t\n"))
      types.map(t => injected(hook(subagentStart(cwd, t), briefs, cwd))) ==>
        types.map(t => Some("SubagentStart" -> s"# $t\n"))
    }

    test("--slot prints the slot a claim's `for` names, for the directory it runs in") {
      val (briefs, cwd) = dirs()
      Vector(
        hook("", briefs, cwd, "--slot").out,
        hook("", briefs, cwd, "--slot", "Coder_Opus").out
      ) ==> Vector(
        sha1(cwd.toString) + "\n",
        BriefFiles.slot(s"Coder_Opus/${sha1(cwd.toString)}") + "\n"
      )
    }

    test("no brief file, another event or unreadable stdin: exit 0 and print nothing") {
      val (briefs, cwd) = dirs()
      brief(briefs, sha1(cwd.toString), "# the session's brief\n")
      val other = Files.createTempDirectory("elsewhere")
      Vector(
        sessionStart(other, "startup"),
        subagentStart(cwd, "explore"),
        """{"hook_event_name":"UserPromptSubmit","cwd":"""" + cwd + """","prompt":"hi"}""",
        "{not json",
        ""
      ).map(stdin => hook(stdin, briefs, cwd)).map(r => (r.status, r.out, r.err)) ==>
        Vector.fill(5)((0, "", ""))
    }

    test("a brief file cat cannot read within a second: exit 0, nothing printed, within 3 s") {
      val (briefs, cwd) = dirs()
      Files.createDirectories(briefs)
      val fifo = new BriefFiles(briefs).file(sha1(cwd.toString))
      val made = new ProcessBuilder("mkfifo", fifo.toString).start().waitFor()
      made ==> 0
      val ran = hook(sessionStart(cwd, "startup"), briefs, cwd)
      (ran.status, ran.out, ran.err, ran.millis < 3000) ==> ((0, "", "", true))
    }
  }
}
