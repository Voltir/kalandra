package kalandra.deployment

import java.nio.file.Path
import java.time.ZoneOffset

import scala.concurrent.duration.DurationInt

import grit.commands.caller.Callers
import grit.core.agent.{Subagent, Subagents}
import grit.core.files.Formats
import grit.core.host.{Allowlist, Argv}
import grit.core.id.AgentType
import grit.core.identity.{Account, Identities, Realm, Vouching}
import grit.core.message.{SpendCap, Tokens}
import grit.core.model.{Assignment, Effort, Ladder, ModelId, ModelRef, Policy, Upstream}
import grit.core.period.LifecycleSettings
import grit.core.persona.Persona
import grit.core.place.{Directory, Place, Repository}
import grit.core.speech.Speaking
import grit.core.spend.{Budget, DailyCap}
import grit.core.visibility.{
  Cap,
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
import grit.edge.http.Listen
import grit.kit.deployment.{
  Assembly,
  Deployment,
  DeploymentVersion,
  Offer,
  Offered,
  Placer,
  RepositoryClone,
  Rounds
}
import grit.projects.plugin.{Projects, TurnCommands}

import kalandra.edge.Mount
import kalandra.method.Grit

/** kalandra: grit's own projects, kept by the projects plugin (ADR 0049) under
  * [[Grit]]'s method, for one person, its [[Owner]], working in one room, the grit
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

  /** The room: the grit checkout `checkout`, under `fs`. */
  def room(checkout: Directory): Place = Place.of(checkout)

  /** Its rooms' labels: [[room]] declared at [[Kept]], every other room
    * unmapped, so a command there reads public rows alone.
    */
  def rooms(checkout: Directory): Either[String, RoomLabels] = labelled(
    Vector(room(checkout) -> Kept)
  )

  /** `declared`, every other room unmapped; or the room declared twice. */
  private def labelled(declared: Vector[(Place, DeclaredLabel)]): Either[String, RoomLabels] =
    RoomLabels
      .of(declared, Labelled.Unmapped(Label.Public))
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

  /** Which model does each job of kalandra's own turns; every pair is reached at Anthropic's API. */
  val Models: Policy = Policy.one(TurnModel, SummaryModel, QueryModel, HeardModel)

  /** The repository grit's checkout is a clone of: `grit`. */
  val GritRepository: Repository = named("grit")

  /** The repository kalandra's own checkout is a clone of: `kalandra`. */
  val KalandraRepository: Repository = named("kalandra")

  /** The repositories its coders work in, each a place labelled [[Kept]] whose files are believed
    * at most at member's integrity.
    */
  val Repositories: Vector[Repository] = Vector(GritRepository, KalandraRepository)

  /** The coders' model: GLM 5.3 flash at Z.ai's fp8 upstream through OpenRouter, the bench's pick
    * by cents per passing trial, 8,192 tokens a reply, at medium effort.
    */
  val CoderModel: Assignment = Assignment(
    ModelRef(
      ModelId.of("z-ai/glm-5.3-flash").getOrElse(throw new IllegalStateException("no model id")),
      Some(Upstream.of("z-ai/fp8").getOrElse(throw new IllegalStateException("no upstream")))
    ),
    8_192,
    Some(Effort.Medium)
  )

  /** The specialist's model: Claude Haiku 5.5 through Anthropic's API, 2,048 tokens. */
  val SpecialistModel: Assignment =
    Assignment(direct("anthropic/claude-haiku-5-5"), 2_048, None)

  /** The most one coder's run spends: 15¢. */
  val CoderCap: SpendCap = spendCap("0.15")

  /** The most one specialist's run spends: 2¢. */
  val SpecialistCap: SpendCap = spendCap("0.02")

  /** What a coder may run, besides reading and writing files: `git add` and `git commit` alone
    * of git's commands, and a build, `./mill` with its tasks; in grit, `scripts/check` over the
    * suites or modules it names too. Each runs at most 20 minutes.
    */
  def allowlist(repository: Repository): Allowlist = {
    def argv(program: String, args: String*): Argv =
      Argv.of(program, args*).fold(why => throw new IllegalStateException(why), identity)
    val shared = Vector(
      Allowlist.Rule.Leading(argv("git", "add")),
      Allowlist.Rule.Leading(argv("git", "commit")),
      Allowlist.Rule.Following(argv("./mill"), "the tasks to run, such as a module's compile")
    )
    val checks = Option.when(repository == GritRepository)(
      Allowlist.Rule.Following(argv("scripts/check"), "the suites or modules to check")
    )
    Allowlist
      .of(shared ++ checks, 20.minutes)
      .fold(why => throw new IllegalStateException(why), identity)
  }

  /** The types a turn may start: a coder in each of [[Repositories]], `grit-coder` and
    * `kalandra-coder`, on [[CoderModel]] under [[allowlist]], its brief believed at least as
    * writing out, offered no push; and `specialist`, on [[SpecialistModel]], offered no tool, its
    * brief believed at least at member's integrity.
    */
  val AgentTypes: Vector[Subagent] =
    Repositories.map { repository =>
      subagent(
        s"${repository.name}-coder",
        "Coder",
        CoderModel,
        Subagent.Tools.Coding(Vector.empty, repository, allowlist(repository)),
        CoderCap,
        Integrity.WritesOut
      )
    } :+ subagent(
      "specialist",
      "Specialist",
      SpecialistModel,
      Subagent.Tools.Services(Vector.empty),
      SpecialistCap,
      Integrity.Member
    )

  /** `repository`'s clone at `at`, its working copies under `.claude/worktrees` there. */
  def clone(repository: Repository, at: Directory): RepositoryClone = {
    val path = Path.of(Directory.value(at))
    RepositoryClone(repository, path, path.resolve(".claude").resolve("worktrees"))
  }

  // The names below are literals each `of` accepts: a refusal is a mistake in this file.
  private def named(name: String): Repository =
    Repository.of(name).fold(why => throw new IllegalStateException(why), identity)

  private def spendCap(usd: String): SpendCap =
    SpendCap.of(BigDecimal(usd)).fold(why => throw new IllegalStateException(why), identity)

  private def subagent(
      agent: String,
      persona: String,
      model: Assignment,
      tools: Subagent.Tools,
      cap: SpendCap,
      requires: Integrity
  ): Subagent = (for {
    a <- AgentType.of(agent)
    p <- Persona.of(persona)
    s <- Subagent.of(a, p, Ladder.one(model), tools, cap, requires)
  } yield s).fold(why => throw new IllegalStateException(why), identity)

  /** A turn's window: 32,000 tokens, which holds a triage conversation and bounds what a
    * window's first call writes to the cache (about 8¢ at Sonnet's write rate).
    */
  val TurnBudget: Tokens = Tokens(32_000)

  /** A turn's model calls: 12, enough for triage to read the store short and then act. */
  val TurnCalls: Int = 12

  /** The most a day spends on model calls: $1. */
  val DailyLimit: DailyCap =
    DailyCap.of("1").fold(why => throw new IllegalStateException(why), identity)

  /** [[rooms]], [[Owner]] and whoever runs its chat here cleared for [[Kept]], and so are the
    * models [[Models]] pins and Jev, which places each message.
    */
  def visibility(checkout: Directory): Either[String, Visibility] =
    visible(
      Vector(room(checkout) -> Kept) ++ Repositories.map(_.place -> Kept),
      Models.ladders ++ AgentTypes.map(_.ladder),
      Placer.Jev,
      Repositories.map(r => Cap(r.place, Integrity.Member))
    )

  /** `declared` the rooms' labels, every other room unmapped, `caps` the caps on what is said at
    * their places; [[Owner]] and whoever runs its chat here cleared for [[Kept]], and so are the
    * models `ladders` pin and, when `placer` is Jev, Jev.
    */
  private def visible(
      declared: Vector[(Place, DeclaredLabel)],
      ladders: Vector[Ladder],
      placer: Placer,
      caps: Vector[Cap] = Vector.empty
  ): Either[String, Visibility] =
    for {
      compartments <- Compartments
        .of(Vector.empty)
        .left
        .map(c => s"$c is declared twice")
      labels <- labelled(declared)
      owners <- GroupName.of("owners")
      v <- Visibility
        .of(
          compartments,
          labels,
          // Account.Local stands for whoever runs `kalandra chat` on this machine, a stopgap
          // until a chat asks as its own person (artifact grit/note-chat-asker-is-a-person).
          Vector(Group(owners, Set(Owner, Account.Local))),
          Vector(Grant(owners, Kept)),
          clearances = cleared(ladders, placer).map(Cleared(_, Kept)),
          caps = caps
        )
        .left
        .map(_.toString)
    } yield v

  /** The parties a turn sends the room's content to: each pair `ladders` pin, and Jev when
    * `placer` is.
    */
  private def cleared(ladders: Vector[Ladder], placer: Placer): Vector[Party] =
    (ladders
      .flatMap(_.rungs)
      .flatMap(r => Party.of(r.assignment.ref)) ++
      Option.when(placer == Placer.Jev)(Party.Service(Placer.JevService))).distinct

  /** `model` at Anthropic's API. */
  private def direct(model: String): ModelRef =
    ModelRef(
      ModelId.of(model).getOrElse(throw new IllegalStateException(s"no model id: $model")),
      Some(Upstream.AnthropicApi)
    )

  /** The proposer's placer: none, since a proposal is one text said once in its own session. */
  val ProposerPlacer: Placer = Placer.Off("a proposal is one text said once, in its own session")

  /** The proposer's turns' model: Claude Opus 5.5 through Anthropic's API, a reply at most 8,192
    * tokens, at the model's own effort.
    */
  val ProposerModel: Assignment = Assignment(direct("anthropic/claude-opus-5-5"), 8_192, None)

  /** A proposer's turn's model calls: 20. A search costs a call and few tokens, a `read` of 50 KB
    * (10,000 to 15,000 tokens as the provider counts them) a call and its tokens; when the calls
    * run out the turn ends at its limit, and its task goes on in a fresh turn.
    */
  val ProposerCalls: Int = 20

  /** The most turns one `work` gives a proposal: 3. Each grows from its window, carrying its
    * reads, so what it spends follows how much it reads; [[ProposerLimit]] bounds a day's.
    * `GRIT_WORK_TURNS` is not read: a person raises it here, in a change, not by a variable.
    */
  val ProposerTurns: Int = 3

  /** The most a day of proposals spends on model calls: $6, two or three proposals' worth, counted on the
    * database's one ledger with serve's.
    */
  val ProposerLimit: DailyCap =
    DailyCap.of("6").fold(why => throw new IllegalStateException(why), identity)

  /** The proposer's deployment's own version, `proposer`, so neither it nor serve resumes the
    * other's workflows.
    */
  val ProposerVersion: String = "proposer"

  /** The projects plugin over [[Grit.Checks]], new projects under [[Grit.Name]], its turns
    * offered `turns`, its methods kept at [[Kept]], which every caller in the room reads, and its
    * commands run by a person as `bin/kalandra`'s, so a claim's brief names that command line.
    */
  private def projects(turns: TurnCommands): Either[String, Projects] =
    Projects
      .of(Grit.Checks, Grit.Name, turns, methodsAt = Kept, commandLine = Some("bin/kalandra"))
      .left
      .map(_.toString)

  /** The proposer over the checkout `checkout`: one person's turns, said by `bin/kalandra work` in
    * an evidence checkout under `evidence`, which is a room at [[Kept]] as the checkout is,
    * offered the coding tools' reads ([[Offered.Read]]) and, of the projects plugin,
    * `project_propose` alone, keeping a new amendment ([[Grit.Amendment]]) in its first
    * state. It serves no edge and declares no schedules and no jobs; its turns run on
    * [[ProposerModel]] over a window of [[TurnBudget]], [[ProposerCalls]] calls each, its input held by its pin's
    * bounds, a day's spend capped at [[ProposerLimit]], under [[ProposerVersion]]. Why not: the two directories are one room,
    * or as [[deployment]]'s parts refuse.
    */
  def proposing(checkout: Directory, evidence: Directory): Either[String, Deployment] =
    for {
      rounds <- Rounds.of(ProposerCalls)
      projects <- projects(TurnCommands.Proposing(Grit.Amendment))
      policy = Policy.one(ProposerModel, SummaryModel, QueryModel, HeardModel)
      visible <- visible(
        Vector(room(checkout) -> Kept, Place.of(evidence) -> Kept),
        policy.ladders,
        ProposerPlacer
      )
      persona <- Persona.of("Kalandra")
      version <- DeploymentVersion.of(ProposerVersion).left.map(_.message)
      made <- Deployment
        .of(
          plugins = Vector(projects),
          policy = policy,
          offer = Offer(Offered.Read, rounds),
          assembly = Assembly.Linear(TurnBudget),
          placer = ProposerPlacer,
          lifecycle = LifecycleSettings.Default,
          budget = Budget(ZoneOffset.UTC, Some(ProposerLimit)),
          speaking = Speaking.Off,
          sweep = 30.seconds,
          persona = persona,
          visibility = visible,
          version = version,
          formats = Formats(_ => Vector.empty)
        )
        .left
        .map(_.message)
    } yield made

  /** kalandra over the checkout `checkout`, its one edge grit's commands edge listening at `at`
    * ([[Mount.edge]]) in the checkout's room, each request from `callers`' caller; a turn there may
    * start one of [[AgentTypes]], a coder in [[GritRepository]] working in `checkout` or in
    * [[KalandraRepository]] working in `own`, kalandra's checkout. Why not: as [[Mount.edge]]
    * refuses `at` or a caller, or as the deployment's parts refuse.
    */
  def deployment(
      checkout: Directory,
      own: Directory,
      at: Listen,
      callers: Callers
  ): Either[String, Deployment] =
    for {
      rounds <- Rounds.of(TurnCalls)
      projects <- projects(TurnCommands.Shipped)
      visible <- visibility(checkout)
      persona <- Persona.of("Kalandra")
      identities <- Identities
        .of(Vector(Vouching(Mount.Attester, Local)), Set.empty)
        .left
        .map(_.toString)
      edge <- Mount.edge(at, room(checkout), callers, Local)
      subagents <- Subagents.of(AgentTypes)
      made <- Deployment
        .of(
          edges = Vector(edge),
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
          formats = Formats(_ => Vector.empty),
          subagents = subagents,
          repositories = Vector(clone(GritRepository, checkout), clone(KalandraRepository, own))
        )
        .left
        .map(_.message)
    } yield made
}
