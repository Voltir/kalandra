package workshop.edge

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

import grit.core.clock.Clock
import grit.core.edge.{Asked, EdgeRefusal, EdgeStores, RealmSource, ServedEdge, Variable}
import grit.core.id.{AttesterName, EdgeName}
import grit.core.identity.{Account, Realm, Standing}
import grit.core.place.Place
import grit.core.store.StoreError
import grit.core.visibility.Integrity

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

/** The edge Claude Code sessions and their person reach the projects plugin's commands through:
  * HTTP on `WORKSHOP_LISTEN` (`host:port` on a loopback address; default [[ClaudeCodeEdge.Listen]]),
  * each command run as [[Routes]] says, as `owner` in `room`, believed at `believed`. It is
  * also the attester of `realm`, whose one account, `owner`, it says is a full member: a person
  * is linked to `owner` as it opens, or it does not open. It hears no conversation and delivers
  * no reply.
  */
final class ClaudeCodeEdge(owner: Account, realm: Realm, room: Place, believed: Integrity)
    extends ServedEdge {

  val name: EdgeName = ClaudeCodeEdge.Name

  // WORKSHOP_LISTEN has a default, so no variable is needed.
  val needs: Vector[Variable] = Vector.empty

  val asks: ServedEdge.Asks = ServedEdge.Asks.Never

  override val attester: Option[AttesterName] = Some(ClaudeCodeEdge.Attester)

  def open(
      stores: EdgeStores^,
      env: Map[String, String],
      clock: Clock^,
      log: String => Unit
  ): Either[EdgeRefusal, ServedEdge.Open^{stores, clock, log, caps.any}] = {
    val source = new ClaudeCodeEdge.Source(owner, realm)
    for {
      at <- ClaudeCodeEdge.listening(env)
      _ <- stores.attesting
        .before(source, owner)
        .left
        .map(why => EdgeRefusal.Failed(s"${Account.written(owner)} is not vouched: $why"))
      routes = new Routes(stores.commands, owner, room, believed, clock, ClaudeCodeEdge.briefs(env))
      server <- scala.util
        .Try(HttpServer.create(at, 0))
        .toEither
        .left
        .map(e => EdgeRefusal.Refused(s"cannot listen on ${ClaudeCodeEdge.shown(at)}: $e"))
    } yield {
      // The server, a Java object, holds `routes` (the commands and the clock) where capture
      // checking no longer tracks them, with no caps.unsafe asked for. Sound because only `open`
      // hands it the handler, before it starts, and `close` stops it once, through an
      // AtomicBoolean; its handler threads hold only immutable values (the `PluginCommands` and
      // `Clock` capabilities, both shared, the room's settings and the briefs' directory; two
      // writes of one brief each move a whole file into place), so no tracked effect is
      // observed through it outside this edge's Open.
      server.createContext("/", new ClaudeCodeEdge.Handler(routes))
      server.start()
      log(s"claude-code: serving commands on http://${ClaudeCodeEdge.shown(at)}")
      new ClaudeCodeEdge.Serving(server, stores, source)
    }
  }
}

object ClaudeCodeEdge {

  /** `claude-code`. */
  val Name: EdgeName = EdgeName("claude-code")

  /** `workshop`: the attester this edge is. */
  val Attester: AttesterName = AttesterName("workshop")

  /** The variable naming where it listens: `WORKSHOP_LISTEN`. */
  val ListenVar: String = "WORKSHOP_LISTEN"

  /** `127.0.0.1:7317`. */
  val Listen: String = "127.0.0.1:7317"

  /** Where `env` says to listen, or why not: not `host:port`, or a host that is no loopback
    * address.
    */
  def listening(env: Map[String, String]): Either[EdgeRefusal, InetSocketAddress] = {
    val raw = env.getOrElse(ListenVar, Listen).trim
    val malformed = EdgeRefusal.Malformed(Variable(ListenVar), "host:port on a loopback address")
    raw.lastIndexOf(':') match {
      case -1 => Left(malformed)
      case i =>
        (for {
          port <- raw.drop(i + 1).toIntOption.filter(p => p > 0 && p < 65536)
          host <- scala.util.Try(InetAddress.getByName(raw.take(i))).toOption
          if host.isLoopbackAddress
        } yield new InetSocketAddress(host, port)).toRight(malformed)
    }
  }

  /** The variable naming the brief files' directory: `WORKSHOP_BRIEFS`. */
  val BriefsVar: String = "WORKSHOP_BRIEFS"

  /** The brief files in the directory `env` names, by default
    * `$HOME/.local/state/workshop/briefs`.
    */
  def briefs(env: Map[String, String]): BriefFiles =
    new BriefFiles(
      env
        .get(BriefsVar)
        .filter(_.trim.nonEmpty)
        .fold(Paths.get(env.getOrElse("HOME", ""), ".local", "state", "workshop", "briefs"))(
          Paths.get(_)
        )
    )

  private def shown(at: InetSocketAddress): String =
    s"${at.getAddress.getHostAddress}:${at.getPort}"

  /** The realm's source: `owner` a full member with no email, every other account outside. */
  private final class Source(owner: Account, realm: Realm) extends RealmSource {
    def ask(account: Account): Asked =
      Asked.Said(if (account == owner) Standing.Full(None) else Standing.Outside)
    def all(of: Realm): Either[Asked.Unreached, Map[Account, Standing]] =
      Right(if (of == realm) Map(owner -> Standing.Full(None)) else Map.empty)
  }

  /** Each request read whole, answered by `routes` as plain text. */
  private final class Handler(routes: Routes^) extends HttpHandler {
    def handle(exchange: HttpExchange): Unit =
      try {
        val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        val reply = routes.answer(exchange.getRequestMethod, exchange.getRequestURI.getPath, body)
        val bytes = reply.text.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty) -1 else bytes.length.toLong)
        if (bytes.nonEmpty) exchange.getResponseBody.write(bytes)
      } finally exchange.close()
  }

  /** The edge while served: `server` until [[close]]. */
  private final class Serving(server: HttpServer, stores: EdgeStores^, source: Source)
      extends ServedEdge.Open {

    // No conversation is heard here, so there is no reply to deliver.
    def deliver(): Either[StoreError, Int] = Right(0)

    override def attest(): Either[StoreError, Int] = stores.attesting.round(source)

    private val stopped = new AtomicBoolean(false)

    def close(): Unit = if (stopped.compareAndSet(false, true)) server.stop(1)
  }
}
