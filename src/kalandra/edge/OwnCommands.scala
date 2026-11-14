package kalandra.edge

import grit.commands.declare.{HoldingWatch, OwnCommand, Ran}
import grit.core.edge.{CommandError, WatchFor}
import grit.core.id.{DocKey, DocumentVersionId}
import grit.core.plugin.CommandOutcome
import grit.core.store.StoreError
import grit.core.tool.ToolName
import grit.core.visibility.Integrity
import grit.projects.artifact.{Artifact, Body}
import grit.projects.name.{ArtifactKey, ProjectKey, StateName}
import grit.projects.plugin.Projects
import grit.projects.render.ProjectMarkdown

import kalandra.amend.{AdoptRefusal, Adoption, Candidate, Diff, StandingSection}
import kalandra.method.GritMethod

/** kalandra's own commands at grit's commands edge, and the wait its `/wait` reads besides a
  * commit's. Each asks a caller believed at [[Integrity.Member]] at least, and [[adopt]] at
  * [[Integrity.Authority]]; the edge answers any other caller 403, running nothing. Each answers as the edge answers every command: done 200,
  * refused 409, [[CommandError.Unread]] 400, [[CommandError.Store]] 500, and a plugin command's
  * own error with its own status.
  */
object OwnCommands {

  // First, as the commands below are built from them as the object starts.
  private val Move = ToolName("project_move")
  private val Batch = ToolName("project_batch")
  private val Show = ToolName("project_show")
  private val Standing = ToolName("project_standing")
  private val Adopt = ToolName("project_adopt")

  private val AdoptedName: String = StateName.value(GritMethod.Adopted)

  /** What follows [[Projects.guardedMove]]'s words when a move to adopted is refused: how a
    * person adopts an amendment.
    */
  val Adopting: String =
    "A person adopts an amendment through project_adopt, which keeps its section's next " +
      "version under the section's cap: run that with the amendment's key."

  /** Why `project_standing` or `project_adopt` given no `key` is unread (400). */
  val KeyAlone: String = "give key, an amendment's or a standing section's, alone"

  /** In place of `project_move`, at `/command` and `/mcp`: a move to `adopted` is refused (409)
    * in [[Projects.guardedMove]]'s words, then [[Adopting]], running nothing, as only
    * [[adopt]] moves an amendment there; any other move is the plugin's, as it was sent.
    */
  val move: OwnCommand = OwnCommand.guarding(
    Move,
    Integrity.Member,
    (ran, args) =>
      if (text(args, "state").contains(AdoptedName))
        Right(CommandOutcome.Refused(adoptedOnly(text(args, "key"))))
      else ran(Move, args)
  )

  /** In place of `project_batch`, at `/command` and `/mcp`: a batch with any step moving an
    * artifact to `adopted` is refused (409) as [[move]] refuses that move, running no step; any
    * other batch is the plugin's, as it was sent.
    */
  val batch: OwnCommand = OwnCommand.guarding(
    Batch,
    Integrity.Member,
    (ran, args) =>
      adopting(args) match {
        case Some(step) => Right(CommandOutcome.Refused(adoptedOnly(text(step, "key"))))
        case None => ran(Batch, args)
      }
  )

  /** `project_standing`, at `/command` alone (`bin/kalandra standing`), keeping nothing: the
    * standing text of the section `key` ([[StandingSection.file]]), or, for an amendment, of its
    * candidate ([[Adoption.candidate]]) over the section as it is now. Unread (400) without
    * `key`; refused (409) as `project_show` refuses the key, or for no standing section or a
    * candidate [[Adoption.candidate]] refuses, in [[AdoptRefusal.message]]'s words; a store
    * failure (500) when `project_show`'s text is no artifact.
    */
  val standing: OwnCommand = OwnCommand.added(
    Standing,
    Integrity.Member,
    (ran, args) =>
      (for {
        key <- keyOf(Standing, args)
        shown <- shownBy(ran, Standing, key)
        text <-
          if (shown._1.head.kind == GritMethod.Amendment)
            candidate(ran, Standing, shown._1).map(_._3.standing.file)
          else
            StandingSection
              .of(shown._1.head.key, shown._1.body)
              .map(_.file)
              .left
              .map(refused)
      } yield Right(CommandOutcome.Done(text))).merge
  )

  /** `project_adopt`, at `/command` alone (`bin/kalandra adopt`), for a caller believed at
    * [[Integrity.Authority]], as adopting sets standing policy: the amendment `key`'s candidate
    * kept as its section's next version, at the version its diff is against, and the amendment
    * moved to `adopted`, in one plugin `project_batch`, which keeps both or neither and checks
    * the move as any; done, it says the section, its standing text's size and its cap, then the
    * batch's answer. Unread (400) without `key`; refused (409), nothing batched, as [[standing]]
    * refuses a candidate, or as the batch refuses; a store failure (500) as [[standing]]'s.
    */
  val adopt: OwnCommand = OwnCommand.added(
    Adopt,
    Integrity.Authority,
    (ran, args) =>
      (for {
        key <- keyOf(Adopt, args)
        amendment <- shownBy(ran, Adopt, key)
        made <- candidate(ran, Adopt, amendment._1)
        (section, version, candidate) = made
        steps = ujson.Arr(
          ujson.Obj(
            "command" -> "keep",
            "key" -> section.head.key.value,
            "title" -> section.head.title,
            "body" -> Body.markdown(candidate.body),
            "version" -> DocumentVersionId.value(version).toDouble
          ),
          ujson.Obj("command" -> "move", "key" -> key, "state" -> AdoptedName)
        )
        done <- ran(
          Batch,
          ujson.Obj("project" -> ProjectKey.value(amendment._1.head.key.project), "steps" -> steps)
        ) match {
          case Right(CommandOutcome.Done(text)) => Right(text)
          case other => Left(other)
        }
      } yield Right(
        CommandOutcome.Done(
          s"Adopted $key: ${section.head.key.value} kept again, its standing text " +
            f"${candidate.standing.size}%,d characters of its cap ${candidate.standing.cap}%,d.\n" +
            done
        )
      )).merge
  )

  /** Every own command kalandra's edge is given. */
  val all: Vector[OwnCommand] = Vector(move, batch, standing, adopt)

  /** The wait `/wait` reads from a body naming no commit, `bin/kalandra wait
    * '{"artifact":"<key>","state":["<state>",…]}'`: the artifact in one of the states
    * ([[Projects.reaching]]). None without `artifact`; refused, naming why, for a key no
    * artifact's may be, or `state` not a non-empty list of states' names. Met, it says
    * `<key> is in <states> (version <v>, written <instant>)`.
    */
  val reaching: HoldingWatch = HoldingWatch(
    Set("artifact", "state"),
    body =>
      body.value
        .get("artifact")
        .map(a =>
          for {
            key <- a.strOpt.toRight("artifact is a key").flatMap(ArtifactKey.read)
            names <- body.value
              .get("state")
              .flatMap(_.arrOpt)
              .toRight("state is a list of states' names")
            states <- names.toVector.foldLeft[Either[String, Set[StateName]]](Right(Set.empty)) {
              (acc, n) =>
                acc.flatMap(got =>
                  n.strOpt.toRight(s"$n is no state's name").flatMap(StateName.of).map(got + _)
                )
            }
            watch <- Projects.reaching(key, states)
          } yield watch
        ),
    (watch: WatchFor.Holding, held) => {
      val states = Projects.reached(held).map(StateName.value).toVector.sorted.mkString(" and ")
      s"${DocKey.value(watch.key)} is in $states (version ${held.version}, written ${held.written})"
    }
  )

  /** What a command came to, as an own command answers it. */
  private type Answer = Either[CommandError, CommandOutcome]

  /** Why a move of the artifact `key` names to adopted is refused: [[Projects.guardedMove]]'s
    * words, the plugin's own for a turn's such move, then [[Adopting]]; [[Adopting]] alone when
    * `key` is none or no artifact's key.
    */
  private def adoptedOnly(key: Option[String]): String =
    key
      .flatMap(ArtifactKey.read(_).toOption)
      .fold(Adopting)(k =>
        s"${Projects.guardedMove(k, GritMethod.Amendment, GritMethod.Adopted)} $Adopting"
      )

  /** The first of a `project_batch`'s `steps` that moves an artifact to adopted, if one does. */
  private def adopting(args: ujson.Obj): Option[ujson.Obj] =
    args.value
      .get("steps")
      .flatMap(_.arrOpt)
      .flatMap(_.collectFirst(Function.unlift { (s: ujson.Value) =>
        s.objOpt
          .map(ujson.Obj.from(_))
          .filter(o =>
            text(o, "command").contains("move") && text(o, "state").contains(AdoptedName)
          )
      }))

  private def keyOf(name: ToolName, args: ujson.Obj): Either[Answer, String] =
    text(args, "key").toRight(Left(CommandError.Unread(name, KeyAlone)))

  /** The artifact `key` as `project_show` gives it, read back; or the answer ending `name`: the
    * show's own when it did not show it, a store failure when its text is no artifact.
    */
  private def shownBy(
      ran: Ran,
      name: ToolName,
      key: String
  ): Either[Answer, (Artifact, DocumentVersionId)] =
    ran(Show, ujson.Obj("key" -> key)) match {
      case Right(CommandOutcome.Done(text)) =>
        ProjectMarkdown
          .readShown(text)
          .left
          .map(why =>
            Left(
              CommandError.Store(
                name,
                StoreError.Invalid(s"$key as project_show gave it was not read: $why")
              )
            )
          )
      case other => Left(other)
    }

  /** The section `amendment`'s diff names, as it is now, the version it is at, and the diff's
    * candidate over it; or the answer refusing it.
    */
  private def candidate(
      ran: Ran,
      name: ToolName,
      amendment: Artifact
  ): Either[Answer, (Artifact, DocumentVersionId, Candidate)] =
    for {
      diff <- Diff
        .read(Body.markdown(amendment.body))
        .left
        .map(why => refused(AdoptRefusal.NotADiff(amendment.head.key, why)))
      section <- shownBy(ran, name, diff.section.value)
      made <- Adoption.candidate(diff, section._1, section._2).left.map(refused)
    } yield (section._1, section._2, made)

  private def refused(why: AdoptRefusal): Answer = Right(CommandOutcome.Refused(why.message))

  private def text(args: ujson.Obj, field: String): Option[String] =
    args.value.get(field).flatMap(_.strOpt)
}
