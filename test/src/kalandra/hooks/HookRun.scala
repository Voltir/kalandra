package kalandra.hooks

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

/** kalandra's hook scripts, run as Claude Code and git run them. */
object HookRun {

  /** What one run of a hook did: its exit status, what it printed, and how long it took. */
  final case class Ran(status: Int, out: String, err: String, millis: Long)

  /** The script `name` in this checkout's `hooks/` (`KALANDRA_HOOKS`, which the build sets). */
  def script(name: String): Path =
    Paths.get(sys.env.getOrElse("KALANDRA_HOOKS", "hooks"), name)

  /** `script` run by bash with `args` in `cwd`, `stdin` its input and `env` added to this
    * process's environment; killed after 5 s.
    */
  def run(
      script: Path,
      stdin: String,
      cwd: Path,
      env: Map[String, String],
      args: String*
  ): Ran = {
    val command = new java.util.ArrayList[String]()
    (Vector("bash", script.toString) ++ args).foreach(a => { command.add(a); () })
    val builder = new ProcessBuilder(command).directory(cwd.toFile)
    env.foreach((k, v) => { builder.environment().put(k, v); () })
    val out = Files.createTempFile("hook", ".out")
    val err = Files.createTempFile("hook", ".err")
    builder.redirectOutput(out.toFile).redirectError(err.toFile)
    val started = System.nanoTime()
    val process = builder.start()
    // A hook that exits before reading its stdin closes the pipe; what it then did is the test.
    scala.util.Try {
      process.getOutputStream.write(stdin.getBytes(StandardCharsets.UTF_8))
      process.getOutputStream.close()
    }
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.descendants().forEach(p => { p.destroyForcibly(); () })
      process.destroyForcibly().waitFor()
    }
    val millis = (System.nanoTime() - started) / 1000000
    Ran(process.exitValue(), Files.readString(out), Files.readString(err), millis)
  }
}
