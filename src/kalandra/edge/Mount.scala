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

  /** `local`: the machine serve runs on, which the command line's and the sessions' credentials
    * are on. It is part of every claim holder (`local/<dir>-<8 hex>`) and worktree place the edge
    * gives them, so changing it orphans every live claim: it changes only in a commit that re-makes
    * them.
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

  /** `KALANDRA_MACHINES`: the variable naming the other machines whose sessions serve believes,
    * `<machine>:<digest>` joined by commas, each digest the SHA-256, 64 lowercase hex, of that
    * machine's sessions' credential. Unset or blank names none.
    */
  val MachinesVar: String = "KALANDRA_MACHINES"

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

  /** The callers `env` names, each `owner`: on [[Machine]], believed at [[CommandLine]] for
    * [[OwnerDigestVar]]'s credential and at [[Sessions]] for [[SessionsDigestVar]]'s; and on each
    * machine [[MachinesVar]] names, at [[Sessions]] for that machine's credential. Or why not, by
    * a variable's name and an entry's machine, never a digest's value: a digest variable unset, or
    * not 64 lowercase hex; an entry of [[MachinesVar]] not `<machine>:<digest>`, its machine a
    * name [[MachineName.of]] refuses, [[Machine]] itself, or named twice; or two credentials of
    * one digest.
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
      others <- machines(env.getOrElse(MachinesVar, ""), Set(own, sessions))
      made <- Callers.of(
        Callers.Token(own, Caller(owner, CommandLine, Machine)),
        (Callers.Token(sessions, Caller(owner, Sessions, Machine)) +:
          others.map((machine, d) => Callers.Token(d, Caller(owner, Sessions, machine))))*
      )
    } yield made
  }

  /** [[MachinesVar]]'s entries in `text`, in order, none of their digests in `taken` nor shared;
    * or why not, as [[callers]] says.
    */
  private def machines(
      text: String,
      taken: Set[Sha256]
  ): Either[String, Vector[(MachineName, Sha256)]] =
    if (text.isBlank) Right(Vector.empty)
    else
      text
        .split(",", -1)
        .toVector
        .zipWithIndex
        .foldLeft[Either[String, Vector[(MachineName, Sha256)]]](Right(Vector.empty)) {
          case (Left(why), _) => Left(why)
          case (Right(seen), (entry, at)) =>
            entry.trim.split(":", -1) match {
              case Array(name, hex) =>
                for {
                  machine <- MachineName
                    .of(name)
                    .left
                    .map(why => s"$MachinesVar: entry ${at + 1}: $why")
                  _ <- Either.cond(
                    machine != Machine,
                    (),
                    s"$MachinesVar names $name, this machine, whose sessions $SessionsDigestVar names"
                  )
                  _ <- Either.cond(
                    !seen.exists(_._1 == machine),
                    (),
                    s"$MachinesVar names $name twice"
                  )
                  d <- Sha256
                    .read(hex)
                    .toRight(
                      s"$MachinesVar: $name's digest is not a SHA-256 digest, 64 lowercase hex"
                    )
                  _ <- Either.cond(
                    !taken(d) && !seen.exists(_._2 == d),
                    (),
                    s"$MachinesVar: $name's digest is another credential's: give each its own"
                  )
                } yield seen :+ (machine -> d)
              case _ => Left(s"$MachinesVar: entry ${at + 1} is not <machine>:<digest>")
            }
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
