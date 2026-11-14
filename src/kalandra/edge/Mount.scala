package kalandra.edge

import grit.claudecode.ClaudeCode
import grit.commands.caller.{Caller, Callers}
import grit.commands.edge.CommandsEdge
import grit.commands.machine.MachineName
import grit.core.id.{AttesterName, EdgeName, Sha256}
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

  /** `local`: the machine every credential kalandra configures is on. It is part of every claim
    * holder (`local/<dir>-<8 hex>`) and worktree place the edge gives, so changing it orphans
    * every live claim: it changes only in a commit that re-makes them.
    */
  val Machine: MachineName =
    // A literal MachineName.of accepts.
    MachineName.of("local").fold(why => throw new IllegalStateException(why), identity)

  /** Authority: how far the owner's own command line is believed. */
  val CommandLine: Integrity = Integrity.Authority

  /** Member: how far the sessions the owner starts are believed, so what they keep is the
    * owner's at that integrity.
    */
  val Sessions: Integrity = Integrity.Member

  /** `KALANDRA_OWNER_DIGEST`: the variable holding the SHA-256 digest, 64 lowercase hex, of the
    * command line's credential; never its text.
    */
  val OwnerDigestVar: String = "KALANDRA_OWNER_DIGEST"

  /** `KALANDRA_SESSIONS_DIGEST`: the variable holding the SHA-256 digest, 64 lowercase hex, of
    * the sessions' credential; never its text.
    */
  val SessionsDigestVar: String = "KALANDRA_SESSIONS_DIGEST"

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

  /** The callers `env` names, each `owner` on [[Machine]]: believed at [[CommandLine]] for
    * [[OwnerDigestVar]]'s credential and at [[Sessions]] for [[SessionsDigestVar]]'s. Or why
    * not: a variable unset, or not 64 lowercase hex, said by its name, never its value; or both
    * holding one digest.
    */
  def callers(owner: Account, env: Map[String, String]): Either[String, Callers] = {
    def digest(name: String, whose: String): Either[String, Sha256] =
      env.get(name) match {
        case None =>
          Left(s"$name is not set: the SHA-256 digest, 64 lowercase hex, of $whose credential")
        case Some(text) =>
          Sha256.read(text.trim).toRight(s"$name is not a SHA-256 digest, 64 lowercase hex")
      }
    for {
      own <- digest(OwnerDigestVar, "the command line's")
      sessions <- digest(SessionsDigestVar, "the sessions'")
      _ <- Either.cond(
        own != sessions,
        (),
        s"$OwnerDigestVar and $SessionsDigestVar name one credential: give each its own"
      )
      made <- Callers.of(
        Callers.Token(own, Caller(owner, CommandLine, Machine)),
        Callers.Token(sessions, Caller(owner, Sessions, Machine))
      )
    } yield made
  }

  /** The commands edge [[Name]] at `at` in `room`, each request from the caller of the credential
    * it bears ([[callers]]), every caller vouched a full member of `realm` by [[Attester]];
    * serving [[Server]], [[OwnCommands.all]], [[OwnCommands.reaching]] and Claude Code's adapter.
    * Or why not, as [[CommandsEdge.of]] refuses: a caller outside `realm`, or `at` not loopback.
    */
  def edge(
      at: Listen,
      room: Place,
      callers: Callers,
      realm: Realm
  ): Either[String, CommandsEdge] =
    CommandsEdge.of(
      Name,
      at,
      Hosts.loopback,
      room,
      realm,
      Attester,
      callers,
      Server,
      OwnCommands.all,
      Some(OwnCommands.reaching),
      Vector(ClaudeCode.adapter)
    )
}
