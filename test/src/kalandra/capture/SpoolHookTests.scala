package kalandra.capture

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import kalandra.hooks.HookRun
import utest.*

/** `hooks/spool-hook.sh`, run as Claude Code runs it (a hook event's JSON on stdin) and as git
  * runs it after a commit.
  */
object SpoolHookTests extends TestSuite {

  private val Session = "faafcbf0-3592-4c99-8f14-21889a88798e"

  /** A git repository, and the directory under it a session runs in. */
  private def worktree(): (Path, Path) = {
    val root = Files.createTempDirectory("worktree").toRealPath()
    val made = new ProcessBuilder("git", "init", "-q", root.toString).start().waitFor()
    assert(made == 0)
    val sub = Files.createDirectories(root.resolve("src"))
    (root, sub)
  }

  private def hook(
      stdin: String,
      spool: Path,
      cwd: Path,
      env: Map[String, String] = Map.empty,
      args: Vector[String] = Vector.empty
  ): HookRun.Ran =
    HookRun.run(
      HookRun.script("spool-hook.sh"),
      stdin,
      cwd,
      // A test run from a Claude Code session or its subagent inherits these; a test sets them.
      Map(
        "KALANDRA_SPOOL" -> spool.toString,
        "CLAUDE_CODE_SESSION_ID" -> "",
        "CLAUDE_CODE_CHILD_SESSION" -> ""
      ) ++ env,
      args*
    )

  /** An event's stdin: the common fields, `fields` and, in a subagent, its `agent_id`. */
  private def event(
      name: String,
      cwd: Path,
      fields: (String, ujson.Value)*
  ): String =
    ujson.write(
      ujson.Obj.from(
        Vector[(String, ujson.Value)](
          "session_id" -> Session,
          "transcript_path" -> s"/home/someone/.claude/projects/x/$Session.jsonl",
          "cwd" -> cwd.toString,
          "hook_event_name" -> name
        ) ++ fields
      )
    )

  private def tool(
      name: String,
      cwd: Path,
      tool: String,
      input: ujson.Obj,
      more: (String, ujson.Value)*
  ): String =
    event(
      name,
      cwd,
      (Vector[(String, ujson.Value)]("tool_name" -> tool, "tool_input" -> input) ++ more)*
    )

  /** The slot `hooks/brief-hook.sh --slot` prints in `cwd`, for the agent type `type` if any. */
  private def slot(cwd: Path, `type`: String = ""): String =
    HookRun
      .run(
        HookRun.script("brief-hook.sh"),
        "",
        cwd,
        Map.empty,
        (Vector("--slot") ++ Option.when(`type`.nonEmpty)(`type`))*
      )
      .out
      .stripSuffix("\n")

  /** The spool's lines for the session, each without its `n`; and the `n`s. */
  private def spooled(spool: Path): (Vector[ujson.Value], Vector[Long]) = {
    val file = spool.resolve(s"$Session.jsonl")
    val lines =
      if (Files.exists(file)) Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toVector
      else Vector.empty
    val read = lines.map(ujson.read(_))
    (
      read.map(v => { val o = v.obj.clone(); o.remove("n"); ujson.Obj.from(o) }),
      read.map(_("n").str.toLong)
    )
  }

  val tests = Tests {

    test("each event spools one line: its harness's kind, its shape, and the worktree it ran in") {
      val (root, cwd) = worktree()
      val spool = Files.createTempDirectory("spool")
      val stdins = Vector(
        event("SessionStart", cwd, "source" -> "startup"),
        tool(
          "PostToolUse",
          cwd,
          "Read",
          ujson.Obj("file_path" -> s"$cwd/A.scala"),
          "tool_response" -> ujson.Obj("file" -> ujson.Obj("content" -> "héllo"))
        ),
        tool("PostToolUse", cwd, "Edit", ujson.Obj("file_path" -> "/etc/elsewhere/B.scala")),
        tool("PostToolUse", cwd, "Bash", ujson.Obj("command" -> "FOO=secret /usr/bin/git status")),
        tool(
          "PostToolUseFailure",
          cwd,
          "Bash",
          ujson.Obj("command" -> "npm test"),
          "error" -> "Exit code 1"
        ),
        event("SubagentStart", cwd, "agent_id" -> "a068ac6", "agent_type" -> "Explore"),
        tool(
          "PostToolUse",
          cwd,
          "Write",
          ujson.Obj("file_path" -> s"$cwd/C.scala"),
          "agent_id" -> "a068ac6",
          "agent_type" -> "Explore"
        ),
        event("SubagentStop", cwd, "agent_id" -> "a068ac6", "agent_type" -> "Explore"),
        event("Stop", cwd, "last_assistant_message" -> "x" * 2500),
        event("PostCompact", cwd, "trigger" -> "manual", "compact_summary" -> "the work so far"),
        event("SessionEnd", cwd, "reason" -> "other")
      )
      val ran = stdins.map(hook(_, spool, cwd))
      val (lines, ns) = spooled(spool)
      val w = ujson.Str(root.toString)
      def line(k: String, d: ujson.Obj, more: (String, ujson.Value)*): ujson.Value =
        ujson.Obj.from(
          Vector[(String, ujson.Value)]("s" -> Session, "k" -> k, "w" -> w, "d" -> d) ++ more
        )
      assert(
        ran.map(r => (r.status, r.out, r.err)) == Vector.fill(stdins.size)((0, "", "")),
        lines == Vector(
          line("SessionStart", ujson.Obj("source" -> "startup"), "h" -> slot(cwd)),
          line("Read", ujson.Obj("path" -> "src/A.scala", "ok" -> true, "bytes" -> 6)),
          line("Edit", ujson.Obj("path" -> "B.scala", "ok" -> true)),
          line("Bash", ujson.Obj("program" -> "git", "ok" -> true)),
          line("Bash", ujson.Obj("program" -> "npm", "ok" -> false)),
          line(
            "SubagentStart",
            ujson.Obj("type" -> "Explore"),
            "a" -> "a068ac6",
            "h" -> slot(cwd, "Explore")
          ),
          line("Write", ujson.Obj("path" -> "src/C.scala", "ok" -> true), "a" -> "a068ac6"),
          line("SubagentStop", ujson.Obj("type" -> "Explore"), "a" -> "a068ac6"),
          line("Stop", ujson.Obj(), "t" -> "x" * 2000),
          line("PostCompact", ujson.Obj("trigger" -> "manual"), "t" -> "the work so far"),
          line("SessionEnd", ujson.Obj("reason" -> "other"))
        ),
        ns.forall(_ > 1_700_000_000_000_000_000L),
        ns.distinct.size == ns.size
      )
    }

    test("a scout query spools a read: what it named, its tool, and its answer's bytes") {
      val (root, cwd) = worktree()
      val spool = Files.createTempDirectory("spool")
      def answered(text: String): (String, ujson.Value) =
        "tool_response" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> text))
      val stdins = Vector(
        tool(
          "PostToolUse",
          cwd,
          "mcp__scout__show",
          ujson.Obj("symbols" -> ujson.Arr("Ledger.post", "Ledger"), "body" -> ujson.Arr("post")),
          answered("héllo")
        ),
        tool("PostToolUse", cwd, "mcp__scout__tests", ujson.Obj("suite" -> "S" * 200)),
        tool(
          "PostToolUseFailure",
          cwd,
          "mcp__scout__file",
          ujson.Obj("target" -> "Ledger.scala"),
          "error" -> "no such file"
        ),
        tool(
          "PostToolUse",
          cwd,
          "mcp__scout__find",
          ujson.Obj("pattern" -> "*Shelf*"),
          "tool_response" -> "two"
        )
      )
      val ran = stdins.map(hook(_, spool, cwd))
      val (lines, _) = spooled(spool)
      def line(tool: String, named: String, ok: Boolean, bytes: Int): ujson.Value =
        ujson.Obj(
          "s" -> Session,
          "k" -> s"mcp__scout__$tool",
          "w" -> root.toString,
          "d" -> ujson.Obj("named" -> named, "tool" -> tool, "ok" -> ok, "bytes" -> bytes)
        )
      assert(
        ran.map(_.status) == Vector.fill(stdins.size)(0),
        lines == Vector(
          line("show", "Ledger.post", true, 6),
          line("tests", "S" * 120, true, 0),
          line("file", "Ledger.scala", false, 0),
          line("find", "*Shelf*", true, 3)
        )
      )
    }

    test("a start line's `h` is the slot brief-hook.sh --slot gives in the event's own cwd") {
      val (root, cwd) = worktree()
      val spool = Files.createTempDirectory("spool")
      hook(event("SessionStart", cwd, "source" -> "startup"), spool, cwd)
      hook(
        event("SubagentStart", cwd, "agent_id" -> "a1", "agent_type" -> "coder-opus"),
        spool,
        root
      )
      val (lines, _) = spooled(spool)
      val h = lines.map(_.obj.get("h").map(_.str))
      assert(
        h == Vector(Some(slot(cwd)), Some(slot(cwd, "coder-opus"))),
        slot(cwd) != slot(root)
      )
    }

    test("a commit in a Claude Code session spools its sha and subject; outside one, nothing") {
      val (root, cwd) = worktree()
      val spool = Files.createTempDirectory("spool")
      def git(args: String*): String = {
        val p = new ProcessBuilder((Vector("git", "-C", root.toString) ++ args).asJava)
          .redirectErrorStream(true)
          .start()
        val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        p.waitFor()
        out.trim
      }
      val _ = git(
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@example.com",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "the first"
      )
      val sha = git("rev-parse", "HEAD")
      val outside = hook("", spool, cwd, args = Vector("--commit"))
      val noneSpooled = spooled(spool)._1
      val inside =
        hook("", spool, cwd, Map("CLAUDE_CODE_SESSION_ID" -> Session), Vector("--commit"))
      assert(
        (outside.status, inside.status, inside.err) == ((0, 0, "")),
        noneSpooled == Vector.empty,
        spooled(spool)._1 == Vector(
          ujson.Obj(
            "s" -> Session,
            "k" -> "commit",
            "w" -> root.toString,
            "d" -> ujson.Obj("sha" -> sha, "subject" -> "the first")
          )
        )
      )
    }

    test(
      "a rebase spools each commit it leaves once, as it finally is; an amend spools the amended commit once"
    ) {
      val (root, _) = worktree()
      val spool = Files.createTempDirectory("spool")
      val hooks = Files.createTempDirectory("hooks")
      val script = HookRun.script("spool-hook.sh").toAbsolutePath
      Vector("post-commit" -> "--commit", "post-rewrite" -> "--rewrite").foreach((name, arg) => {
        val _ = Files.writeString(
          hooks.resolve(name),
          s"#!/usr/bin/env bash\nexec bash '$script' $arg \"$$@\"\n"
        )
        hooks.resolve(name).toFile.setExecutable(true)
      })
      def git(args: String*): String = {
        val b = new ProcessBuilder(
          (Vector(
            "git",
            "-C",
            root.toString,
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@example.com",
            "-c",
            s"core.hooksPath=$hooks"
          ) ++ args).asJava
        ).redirectErrorStream(true)
        b.environment().put("CLAUDE_CODE_SESSION_ID", Session)
        b.environment().remove("CLAUDE_CODE_CHILD_SESSION")
        b.environment().put("KALANDRA_SPOOL", spool.toString)
        b.environment().put("GIT_SEQUENCE_EDITOR", "true")
        val p = b.start()
        val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        assert(p.waitFor() == 0)
        out.trim
      }
      def commit(subject: String, file: String): String = {
        val _ = Files.writeString(root.resolve(file), subject)
        val _ = git("add", file)
        val _ = git("commit", "-q", "-m", subject)
        git("rev-parse", "--short=12", "HEAD")
      }
      val base = commit("the base", "base")
      val a = commit("the first", "a")
      val b = commit("the second", "b")
      val fix = commit("fixup! the first", "a2")
      val _ = git("rebase", "-q", "-i", "--autosquash", "--force-rebase", base)
      val rebased =
        git("log", "--format=%h %s", "--abbrev=12", s"$base..HEAD").linesIterator.toVector
      val _ = git("commit", "-q", "--amend", "-m", "the second, amended")
      val amended = git("rev-parse", "--short=12", "HEAD")
      val recorded = spooled(spool)._1.map(v => (v("d")("sha").str.take(12), v("d")("subject").str))
      recorded ==> Vector(base, a, b, fix).zip(
        Vector("the base", "the first", "the second", "fixup! the first")
      ) ++ rebased.reverse.map(l => (l.take(12), l.drop(13))) :+ (amended, "the second, amended")
    }

    test(
      "a path is relative to its own worktree, named when not the event's, and cut from the left past 120"
    ) {
      val (root, cwd) = worktree()
      val (other, _) = worktree()
      val spool = Files.createTempDirectory("spool")
      val deep = Files.createDirectories(other.resolve("d" * 60).resolve("e" * 60))
      val stdins = Vector(
        tool("PostToolUse", cwd, "Edit", ujson.Obj("file_path" -> s"$cwd/A.scala")),
        tool(
          "PostToolUse",
          cwd,
          "Read",
          ujson.Obj("file_path" -> s"$other/src/B.scala"),
          "tool_response" -> ujson.Obj("file" -> ujson.Obj("content" -> "b"))
        ),
        tool("PostToolUse", cwd, "Write", ujson.Obj("file_path" -> s"$deep/C.scala"))
      )
      stdins.foreach(hook(_, spool, cwd))
      val tail = s"${"d" * 60}/${"e" * 60}/C.scala".takeRight(120)
      spooled(spool)._1.map(_("d")) ==> Vector(
        ujson.Obj("path" -> "src/A.scala", "ok" -> true),
        ujson.Obj(
          "path" -> "src/B.scala",
          "ok" -> true,
          "bytes" -> 1,
          "worktree" -> other.toString
        ),
        ujson.Obj("path" -> tail, "ok" -> true, "worktree" -> other.toString)
      )
      assert(root != other)
    }

    test("a commit made in a subagent, a child session, is marked `child`") {
      val (root, cwd) = worktree()
      val spool = Files.createTempDirectory("spool")
      val made = new ProcessBuilder(
        "git",
        "-C",
        root.toString,
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@example.com",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "by an agent"
      ).start().waitFor()
      val env = Map("CLAUDE_CODE_SESSION_ID" -> Session, "CLAUDE_CODE_CHILD_SESSION" -> "1")
      val _ = hook("", spool, cwd, env, Vector("--commit"))
      spooled(spool)._1.map(v => (v("d").obj.get("child"), v("d")("subject").str)) ==>
        Vector((Some(ujson.True), "by an agent"))
      assert(made == 0)
    }

    test(
      "a session's file past 2 MB takes no more lines; SessionStart keeps the newest 100 files"
    ) {
      val (_, cwd) = worktree()
      val spool = Files.createTempDirectory("spool")
      val full = spool.resolve(s"$Session.jsonl")
      Files.writeString(full, "x" * (2 * 1024 * 1024 + 1))
      val _ = hook(tool("PostToolUse", cwd, "Bash", ujson.Obj("command" -> "ls")), spool, cwd)
      val sizeAfter = Files.size(full)
      Files.delete(full)
      (1 to 105).foreach { i =>
        val f = spool.resolve(f"old-$i%03d.jsonl")
        Files.writeString(f, "{}\n")
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1_000_000L * i))
      }
      Files.writeString(spool.resolve("old-000.ingesting"), "{}\n")
      val _ = hook(event("SessionStart", cwd, "source" -> "startup"), spool, cwd)
      val left = Files.list(spool).iterator().asScala.map(_.getFileName.toString).toSet
      assert(
        sizeAfter == 2 * 1024 * 1024 + 1,
        left.size == 101,
        left.contains(s"$Session.jsonl"),
        left.contains("old-000.ingesting"),
        !left.contains("old-005.jsonl"),
        !left.contains("old-006.jsonl"),
        left.contains("old-007.jsonl")
      )
    }

    test("no event, another event or tool, an empty Stop, or an id no file may have: nothing") {
      val (_, cwd) = worktree()
      val spool = Files.createTempDirectory("spool")
      val stdins = Vector(
        "",
        "{not json",
        event("UserPromptSubmit", cwd, "prompt" -> "hi"),
        tool("PostToolUse", cwd, "WebFetch", ujson.Obj("url" -> "https://example.com")),
        event("Stop", cwd, "last_assistant_message" -> ""),
        event("SessionStart", cwd, "source" -> "startup").replace(Session, "../escape"),
        event("SessionStart", cwd, "source" -> "startup").replace(Session, "")
      )
      val ran = stdins.map(hook(_, spool, cwd))
      assert(
        ran.map(r => (r.status, r.out, r.err)) == Vector.fill(stdins.size)((0, "", "")),
        Files.list(spool).count() == 0L
      )
    }
  }
}
