package kalandra.edge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import grit.core.session.SessionDigest

import kalandra.hooks.HookRun
import utest.*

/** `hooks/brief-hook.sh`, run as Claude Code runs it: a hook event's JSON on stdin. */
object BriefHookTests extends TestSuite {

  private type Ran = HookRun.Ran

  private val Session = "faafcbf0-3592-4c99-8f14-21889a88798e"

  /** The hook run with `args` in `cwd`, `stdin` its input, reading briefs from `briefs`, with no
    * `CLAUDE_CODE_SESSION_ID` (a test run from a Claude Code session would inherit its own).
    */
  private def hook(stdin: String, briefs: Path, cwd: Path, args: String*): Ran =
    hookIn("", stdin, briefs, cwd, args*)

  /** [[hook]] in the Bash of the session `session`, its `CLAUDE_CODE_SESSION_ID`. */
  private def hookIn(session: String, stdin: String, briefs: Path, cwd: Path, args: String*): Ran =
    HookRun.run(
      HookRun.script("brief-hook.sh"),
      stdin,
      cwd,
      Map("KALANDRA_BRIEFS" -> briefs.toString, "CLAUDE_CODE_SESSION_ID" -> session),
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
        "session_id" -> Session,
        "transcript_path" -> "/home/someone/.claude/projects/x/faafcbf0.jsonl",
        "cwd" -> cwd.toString,
        "hook_event_name" -> "SessionStart",
        "source" -> source
      )
    )

  /** A SubagentStart event's stdin for an agent of type `agentType`, started by the session
    * `session` on its prompt `prompt` as the agent `agent`.
    */
  private def subagentStart(
      cwd: Path,
      agentType: String,
      session: String = Session,
      prompt: String = "4b3c22a9-9d87-4c3f-b8f4-d6d6085be7c4",
      agent: String = "a068ac626e56ee78e"
  ): String =
    ujson.write(
      ujson.Obj(
        "session_id" -> session,
        "transcript_path" -> s"/home/someone/.claude/projects/x/$session.jsonl",
        "cwd" -> cwd.toString,
        "prompt_id" -> prompt,
        "agent_id" -> agent,
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
      "--digest injects compact's digest, clear's digest with what was said, and nothing else; the brief stays alone"
    ) {
      val (briefs, cwd) = dirs()
      val slot = sha1(cwd.toString)
      brief(briefs, slot, "# the brief\n")
      new BriefFiles(briefs)
        .writeDigests(slot, w => s"## digest for $w")
        .fold(sys.error, identity) ==> true
      val sources = Vector("compact", "clear", "startup", "resume")
      def ran(args: String*) =
        sources.map(s => injected(hook(sessionStart(cwd, s), briefs, cwd, args*)).map(_._2))
      val (alone, fresh) = (ran(), ran("--digest"))
      val old =
        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 25 * 3600000L)
      SessionDigest.Window.values.foreach(w =>
        Files.setLastModifiedTime(new BriefFiles(briefs).digest(slot, w), old)
      )
      (alone, fresh, ran("--digest")) ==> (
        Vector.fill(4)(Some("# the brief\n")),
        Vector(
          Some("## digest for HoldsLastSaid"),
          Some("## digest for LacksLastSaid"),
          None,
          None
        ),
        Vector.fill(4)(None)
      )
    }

    test(
      "SubagentStart injects the brief of the slot BriefFiles names for its agent type, cwd and session"
    ) {
      val (briefs, cwd) = dirs()
      brief(briefs, sha1(cwd.toString), "# the session's brief\n")
      val types = Vector("coder-haiku", "Explore", "Coder_Opus", "a.b:c", "_x", "KKb", "a😀b")
      types.foreach(t =>
        brief(briefs, BriefFiles.slot(s"$t/${sha1(cwd.toString)}/$Session"), s"# $t\n")
      )
      types.map(t => injected(hook(subagentStart(cwd, t), briefs, cwd))) ==>
        types.map(t => Some("SubagentStart" -> s"# $t\n"))
    }

    test("two sessions in one cwd: each one's subagent of a type gets its own session's brief") {
      val (briefs, cwd) = dirs()
      val other = "0d6c1f4e-77a2-4b0e-9c51-3e8f2a9b6d10"
      Vector(Session, other).foreach(s =>
        brief(briefs, s"coder-opus/${sha1(cwd.toString)}/$s", s"# $s's coder\n")
      )
      Vector(Session, other).map(s =>
        injected(hook(subagentStart(cwd, "coder-opus", session = s), briefs, cwd)).map(_._2)
      ) ==> Vector(Some(s"# $Session's coder\n"), Some(s"# $other's coder\n"))
    }

    test("a resumed session's subagent, on a later prompt, gets the brief it got before") {
      val (briefs, cwd) = dirs()
      brief(briefs, s"coder-opus/${sha1(cwd.toString)}/$Session", "# the coder's brief\n")
      Vector(
        subagentStart(cwd, "coder-opus"),
        subagentStart(
          cwd,
          "coder-opus",
          prompt = "9e0f6a1b-2c3d-4e5f-8a7b-6c5d4e3f2a1b",
          agent = "b17f00d5e1a2c3d4"
        )
      ).map(stdin => injected(hook(stdin, briefs, cwd)).map(_._2)) ==>
        Vector.fill(2)(Some("# the coder's brief\n"))
    }

    test("--slot prints the slot a claim's `holder` names, for the directory it runs in") {
      val (briefs, cwd) = dirs()
      Vector(
        hook("", briefs, cwd, "--slot").out,
        hookIn(Session, "", briefs, cwd, "--slot", "Coder_Opus").out
      ) ==> Vector(
        sha1(cwd.toString) + "\n",
        BriefFiles.slot(s"Coder_Opus/${sha1(cwd.toString)}/$Session") + "\n"
      )
    }

    test(
      "--slot <type> in a session's Bash prints the slot whose brief that session's subagent gets"
    ) {
      val (briefs, cwd) = dirs()
      val other = "0d6c1f4e-77a2-4b0e-9c51-3e8f2a9b6d10"
      Vector(Session, other).foreach(s =>
        brief(briefs, hookIn(s, "", briefs, cwd, "--slot", "coder-opus").out.trim, s"# $s\n")
      )
      Vector(Session, other).map(s =>
        injected(hook(subagentStart(cwd, "coder-opus", session = s), briefs, cwd)).map(_._2)
      ) ==> Vector(Some(s"# $Session\n"), Some(s"# $other\n"))
    }

    test("--slot <type> with no CLAUDE_CODE_SESSION_ID exits 2 and says why, printing no slot") {
      val (briefs, cwd) = dirs()
      val ran = hook("", briefs, cwd, "--slot", "coder-opus")
      (ran.status, ran.out, ran.err) ==> (
        (
          2,
          "",
          "brief-hook.sh --slot coder-opus: CLAUDE_CODE_SESSION_ID is not set; run it in the " +
            "Bash of the Claude Code session that starts the agent\n"
        )
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
