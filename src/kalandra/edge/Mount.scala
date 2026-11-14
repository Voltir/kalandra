package kalandra.edge

import grit.claudecode.ClaudeCode
import grit.commands.caller.{Caller, Callers}
import grit.commands.edge.CommandsEdge
import grit.commands.worker.WorkerName
import grit.core.id.{AttesterName, EdgeName}
import grit.core.identity.{Account, Realm}
import grit.core.place.Place
import grit.core.visibility.Integrity
import grit.edge.http.{Hosts, Listen}
import grit.mcp.wire.ServerInfo

/** How kalandra mounts grit's commands edge (ADR 0062): where it listens, who its requests are
  * from, its own commands and wait ([[OwnCommands]]), and Claude Code's adapter.
  */
object Mount {

  /** `commands`: the edge's name, as serve's log and a refusal say it. */
  val Name: EdgeName = EdgeName("commands")

  /** `local`: the worker every request is from until requests carry credentials, named for the
    * credential it stands in for. It is part of every claim holder (`local/<dir>-<8 hex>`) and
    * worktree place the edge gives, so changing it orphans every live claim: it changes only in
    * a commit that re-makes them.
    */
  val Worker: WorkerName =
    // A literal WorkerName.of accepts.
    WorkerName.of("local").fold(why => throw new IllegalStateException(why), identity)

  /** `workshop`: the attester the edge is for its realm. */
  // Stored, so kept from the deployment's earlier name: workshop.
  val Attester: AttesterName = AttesterName("workshop")

  /** The variable naming where serve listens: `KALANDRA_LISTEN`. */
  val ListenVar: String = "KALANDRA_LISTEN"

  /** `127.0.0.1:7317`: where serve listens when [[ListenVar]] is unset. */
  val DefaultListen: String = "127.0.0.1:7317"

  /** What the MCP server says of itself: `kalandra`, version `2`, and that a project artifact is
    * not a claude.ai Artifact, which a client offering both would otherwise confuse.
    */
  val Server: ServerInfo = ServerInfo(
    "kalandra",
    "2",
    Some(
      "kalandra keeps grit's projects. An artifact here is a project artifact, a versioned " +
        "record of a grit project, not a claude.ai Artifact."
    )
  )

  /** Where `env` says to listen, [[DefaultListen]] when [[ListenVar]] is unset; or why not, as
    * [[Listen.parse]] says, after the variable's name.
    */
  def listening(env: Map[String, String]): Either[String, Listen] =
    Listen.parse(env.getOrElse(ListenVar, DefaultListen).trim).left.map(why => s"$ListenVar: $why")

  /** The commands edge [[Name]] at `at` in `room`: every request with no credential from `owner`,
    * believed at `believed`, on [[Worker]], and one carrying a credential refused; `owner`
    * vouched a full member of `realm` by [[Attester]]; serving [[Server]], [[OwnCommands.all]],
    * [[OwnCommands.reaching]] and Claude Code's adapter. Or why not, as [[CommandsEdge.of]]
    * refuses: `owner` outside `realm`, or `at` not loopback.
    */
  def edge(
      at: Listen,
      room: Place,
      owner: Account,
      believed: Integrity,
      realm: Realm
  ): Either[String, CommandsEdge] =
    CommandsEdge.of(
      Name,
      at,
      Hosts.loopback,
      room,
      realm,
      Attester,
      Callers.owner(Caller(owner, believed, Worker)),
      Server,
      OwnCommands.all,
      Some(OwnCommands.reaching),
      Vector(ClaudeCode.adapter)
    )
}
