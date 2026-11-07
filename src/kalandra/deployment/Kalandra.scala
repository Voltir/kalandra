package kalandra.deployment

import java.time.ZoneOffset

import scala.concurrent.duration.DurationInt

import grit.core.files.Formats
import grit.core.identity.{Account, Identities, Realm, Vouching}
import grit.core.message.Tokens
import grit.core.model.{Assignment, ModelId, ModelRef, Policy, Upstream}
import grit.core.period.LifecycleSettings
import grit.core.persona.Persona
import grit.core.place.{Directory, Place}
import grit.core.speech.Speaking
import grit.core.spend.{Budget, DailyCap}
import grit.core.visibility.{
  Cleared,
  Compartments,
  DeclaredLabel,
  Grant,
  Group,
  GroupName,
  Integrity,
  Label,
  Labelled,
  Level,
  Party,
  RoomLabels,
  Visibility
}
import grit.kit.deployment.{Assembly, Deployment, Offer, Offered, Placer, Rounds}
import grit.projects.method.{Example, Method}
import grit.projects.plugin.Projects

import kalandra.edge.ClaudeCodeEdge
import kalandra.method.GritMethod

/** kalandra: grit's own projects, kept by the projects plugin (ADR 0049) under
  * [[GritMethod]], for one person, its [[Owner]], working in one room, the grit
  * checkout's directory. Its turns run on [[Models]] through Anthropic's API, each message
  * placed by Jev, a day's spend capped at [[DailyLimit]].
  */
object Kalandra {

  /** The one account: `workshop:local/nick`. */
  val Owner: Account.Sourced =
    // A literal Account.of accepts. Stored, so kept from the deployment's earlier name: workshop.
    Account
      .of("workshop", "local/nick")
      .fold(why => throw new IllegalStateException(why), identity)

  /** The realm its owner's account is in, `workshop:local`, which its edge alone vouches for. */
  val Local: Realm =
    // A literal Realm.of accepts. Stored, so kept from the deployment's earlier name: workshop.
    Realm.of("workshop", "local").fold(why => throw new IllegalStateException(why), identity)

  /** What its room is labelled, and its owner cleared for: internal. */
  val Kept: DeclaredLabel = Label.at(Level.Internal)

  /** How far what its owner says in its room is believed: a team member's word,
    * the integrity the edge gives every command it runs.
    */
  val Believed: Integrity = Integrity.Member

  /** The room: the grit checkout `checkout`, under `fs`. */
  def room(checkout: Directory): Place = Place.of(checkout)

  /** Its rooms' labels: [[room]] declared at [[Kept]], every other room
    * unmapped, so a command there reads public rows alone.
    */
  def rooms(checkout: Directory): Either[String, RoomLabels] =
    RoomLabels
      .of(Vector(room(checkout) -> Kept), Labelled.Unmapped(Label.Public))
      .left
      .map(p => s"the room ${p.written} is labelled twice")

  /** Turns' model: Claude Sonnet 5.5 through Anthropic's API, a reply at most 4,096 tokens, at
    * the model's own effort.
    */
  val TurnModel: Assignment = Assignment(direct("anthropic/claude-sonnet-5-5"), 4_096, None)

  /** The summary's model, and the nightly dream's: Claude Haiku 5.5 through Anthropic's API,
    * the least a model reading others' text may be, 4,096 tokens, no effort asked.
    */
  val SummaryModel: Assignment = Assignment(direct("anthropic/claude-haiku-5-5"), 4_096, None)

  /** The search query's model: Claude Haiku 5.5, 1,024 tokens, as short as a query is. */
  val QueryModel: Assignment = Assignment(direct("anthropic/claude-haiku-5-5"), 1_024, None)

  /** The model closing a period grit only heard: Claude Haiku 5.5, 4,096 tokens. */
  val HeardModel: Assignment = Assignment(direct("anthropic/claude-haiku-5-5"), 4_096, None)

  /** Which model does each job; every pair is reached at Anthropic's API, so no OpenRouter key
    * is read.
    */
  val Models: Policy = Policy.one(TurnModel, SummaryModel, QueryModel, HeardModel)

  /** A turn's window: 32,000 tokens, which holds a triage conversation and bounds what a
    * window's first call writes to the cache (about 8¢ at Sonnet's write rate).
    */
  val TurnBudget: Tokens = Tokens(32_000)

  /** A turn's model calls: 12, enough for triage to read the store short and then act. */
  val TurnCalls: Int = 12

  /** The input a turn's request may reach, as its provider counts it: the window
    * ([[TurnBudget]]) plus 40,000 tokens, the growth a turn was allowed over its first request
    * before grit's limit became absolute (grit 8c5bb1c3). Once a reply's input plus the largest
    * growth of a round so far meets it, the turn's next call is its last with tools, and it is
    * told so; a turn can pass it by one round's results (`TurnLoop.Rounds`).
    */
  val TurnLimit: Tokens = TurnBudget + Tokens(40_000)

  /** The most a day spends on model calls: $1. */
  val DailyLimit: DailyCap =
    DailyCap.of("1").fold(why => throw new IllegalStateException(why), identity)

  /** [[rooms]], [[Owner]] and whoever runs its chat here cleared for [[Kept]], and so are the
    * models [[Models]] pins and Jev, which places each message.
    */
  def visibility(checkout: Directory): Either[String, Visibility] =
    for {
      compartments <- Compartments
        .of(Vector.empty)
        .left
        .map(c => s"$c is declared twice")
      labels <- rooms(checkout)
      owners <- GroupName.of("owners")
      v <- Visibility
        .of(
          compartments,
          labels,
          // Account.Local stands for whoever runs `kalandra chat` on this machine, a stopgap
          // until a chat asks as its own person (artifact grit/note-chat-asker-is-a-person).
          Vector(Group(owners, Set(Owner, Account.Local))),
          Vector(Grant(owners, Kept)),
          clearances = cleared.map(Cleared(_, Kept))
        )
        .left
        .map(_.toString)
    } yield v

  /** The parties a turn sends the room's content to: each pair [[Models]] pins, and Jev. */
  private def cleared: Vector[Party] =
    (Models.ladders
      .flatMap(_.rungs)
      .flatMap(r => Party.of(r.assignment.ref)) :+ Party.Service(Placer.JevService)).distinct

  /** `model` at Anthropic's API. */
  private def direct(model: String): ModelRef =
    ModelRef(
      ModelId.of(model).getOrElse(throw new IllegalStateException(s"no model id: $model")),
      Some(Upstream.AnthropicApi)
    )

  /** kalandra over the checkout `checkout`. */
  def deployment(checkout: Directory): Either[String, Deployment] =
    for {
      rounds <- Rounds.of(TurnCalls).flatMap(_.within(TurnLimit))
      methods <- GritMethod.all.left.map(_.mkString("; "))
      projects <- Projects
        .of(methods.map((m: Method) => (m, Vector.empty[Example])), GritMethod.Name)
        .left
        .map(_.toString)
      visible <- visibility(checkout)
      persona <- Persona.of("Kalandra")
      identities <- Identities
        .of(Vector(Vouching(ClaudeCodeEdge.Attester, Local)), Set.empty)
        .left
        .map(_.toString)
      made <- Deployment
        .of(
          edges = Vector(new ClaudeCodeEdge(Owner, Local, room(checkout), Believed)),
          plugins = Vector(projects),
          policy = Models,
          offer = Offer(Offered.Read, rounds),
          assembly = Assembly.Linear(TurnBudget),
          placer = Placer.Jev,
          lifecycle = LifecycleSettings.Default,
          budget = Budget(ZoneOffset.UTC, Some(DailyLimit)),
          speaking = Speaking.Off,
          sweep = 30.seconds,
          persona = persona,
          visibility = visible,
          identities = identities,
          formats = Formats(_ => Vector.empty)
        )
        .left
        .map(_.message)
    } yield made
}
