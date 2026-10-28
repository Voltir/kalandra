package workshop.deployment

import java.time.ZoneOffset

import scala.concurrent.duration.DurationInt

import grit.core.files.Formats
import grit.core.identity.{Account, Identities, Realm, Vouching}
import grit.core.message.Tokens
import grit.core.period.LifecycleSettings
import grit.core.persona.Persona
import grit.core.place.{Directory, Place}
import grit.core.speech.Speaking
import grit.core.spend.Budget
import grit.core.visibility.{
  Compartments,
  DeclaredLabel,
  Grant,
  Group,
  GroupName,
  Integrity,
  Label,
  Labelled,
  Level,
  RoomLabels,
  Visibility
}
import grit.kit.deployment.{Assembly, Deployment, Offer, Offered, Placer, Rounds, Seed}
import grit.projects.plugin.Projects

import workshop.edge.ClaudeCodeEdge
import workshop.method.GritMethod

/** workshop: grit's own projects, kept by the projects plugin (ADR 0049) under
  * [[GritMethod]], for one person, its [[Owner]], working in one room, the grit
  * checkout's directory. Its conversations call no model: it reads no model's
  * key, so a turn would get the stub.
  */
object Workshop {

  /** The one account: `workshop:local/nick`. */
  val Owner: Account.Sourced =
    // A literal Account.of accepts.
    Account
      .of("workshop", "local/nick")
      .fold(why => throw new IllegalStateException(why), identity)

  /** The realm its owner's account is in, `workshop:local`, which its edge alone vouches for. */
  val Local: Realm =
    // A literal Realm.of accepts.
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

  /** [[rooms]], and [[Owner]] alone cleared for [[Kept]]. */
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
          Vector(Group(owners, Set(Owner))),
          Vector(Grant(owners, Kept))
        )
        .left
        .map(_.toString)
    } yield v

  /** workshop over the checkout `checkout`. */
  def deployment(checkout: Directory): Either[String, Deployment] =
    for {
      catalog <- Seed.catalog
      rounds <- Rounds.of(4)
      method <- GritMethod.of.left.map(_.mkString("; "))
      projects <- Projects
        .of(Vector(method), GritMethod.Name)
        .left
        .map(_.toString)
      visible <- visibility(checkout)
      persona <- Persona.of("Workshop")
      identities <- Identities
        .of(Vector(Vouching(ClaudeCodeEdge.Attester, Local)), Set.empty)
        .left
        .map(_.toString)
      made <- Deployment
        .of(
          edges = Vector(new ClaudeCodeEdge(Owner, Local, room(checkout), Believed)),
          plugins = Vector(projects),
          policy = catalog.policy,
          offer = Offer(Offered.Read, rounds),
          assembly = Assembly.Linear(Tokens(8_000)),
          placer = Placer.Off(
            "workshop's room is worked in through commands, not conversations"
          ),
          lifecycle = LifecycleSettings.Default,
          budget = Budget(ZoneOffset.UTC, None),
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
