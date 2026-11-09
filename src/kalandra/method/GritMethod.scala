package kalandra.method

import grit.core.id.Sha256
import grit.projects.artifact.{Artifact, ArtifactHead, Body, Project}
import grit.projects.method.{
  ArtifactKind,
  Example,
  Method,
  RoleView,
  Transition,
  TransitionAction,
  TransitionCheck,
  TransitionChecks
}
import grit.projects.name.{
  ArtifactKey,
  KindName,
  LocalKey,
  MethodName,
  Pin,
  ProjectKey,
  RoleName,
  StateName
}

/** How grit itself is built, as a method (ADR 0049), in two versions.
  *
  * Version 1: a `milestone` holds `stage`s and closes only once each has ended; a `stage` is
  * planned with a `## Goal` before it is built, reviewed, and merged or dropped; an `idea` is
  * parked until it is folded into other work or dropped; a `mechanism` describes a part of grit
  * until it is retired. A `coder` is shown the artifact and what it cites two links deep, of
  * every kind but ideas; a `manager` every kind, one link deep.
  *
  * Version 2, what grit's planning markdown imports into: a `milestone` sits `later`, `next`,
  * `now` or `parallel` (beside the milestones, never blocking them), moving freely between them,
  * and ends `done` once its children have ended, or `dropped`; a `plan` is `open` until it is
  * `closed` (its children ended) or `dropped`; a `stage` of a plan is planned, built, reviewed,
  * and merged or dropped, with no section required of it; a `section`, a part of a longer
  * artifact, is `current` until `retired`; a `mechanism` as in version 1; a `note`, a loose idea
  * or finding, is `open` until `folded` into other work or `dropped`. A `coder` is shown every
  * kind but notes, two links deep; a `manager` every kind, one link deep.
  *
  * Version 3, version 2 with its `stage` named `checkpoint`, the process's word, and a
  * `backlog` kind: an item owed by the milestone it is parented to (which cannot end `done`
  * while it is open), `open` until `taken` into work, `folded` into other work, or `dropped`; a
  * `note` keeps records and references. A `coder` is shown what it was in version 2 and always
  * carries the coder rules (`roadmap-coder-rules`); a `manager` every kind, one link deep, always
  * carrying the update protocol (`roadmap-update-protocol`); a `reviewer` every kind, two links
  * deep.
  *
  * Version 4, version 3 and an `amendment`: an edit proposed to a section's standing text
  * (`kalandra.amend`), `proposed`, then `benched` once it holds every section its kind lists
  * (Diff, Moments, Cue, Lesson, Confidence, Observable), then `adopted` or `rejected` only once
  * decided so ([[AmendmentChecks]]), or `dropped` from either before. A `manager` and a
  * `reviewer` are shown amendments too, a `coder` none; a `grit-coder` (grit's own coding turn)
  * is shown what a coder is and always carries the grit coder's standing section
  * (`roadmap-grit-coder`).
  */
object GritMethod {

  /** `grit`: the method's name, the deployment's default. */
  val Name: MethodName = name(MethodName.of("grit"))

  /** `amendment`: version 4's kind of an edit proposed to a section's standing text. */
  val Amendment: KindName = kind(KindName.of("amendment"))

  /** `adopted`: the state an amendment is adopted in, which only a person moves it to. */
  val Adopted: StateName = state(StateName.of("adopted"))

  /** Version 1, which the project `restore-check` holds to. */
  def v1: Either[Vector[String], Method] = method(1, declaredV1)

  /** Version 2, which grit's planning tree was imported into. */
  def v2: Either[Vector[String], Method] = method(2, declaredV2)

  /** Version 3, which the project `grit` holds to until it is migrated. */
  def v3: Either[Vector[String], Method] = method(3, declaredV3)

  /** Version 4, the latest: what a new project holds to. */
  def v4: Either[Vector[String], Method] = method(4, declaredV4)

  /** Every version, oldest first, or every reason one is not a method. */
  def all: Either[Vector[String], Vector[Method]] =
    Vector(v1, v2, v3, v4).partitionMap(identity) match {
      case (Vector(), methods) => Right(methods)
      case (why, _) => Left(why.flatten)
    }

  /** Every version with the worked examples it is declared with (version 4's, of an
    * amendment's moves; none before it), oldest first, or every reason one is not a method.
    */
  def declared: Either[Vector[String], Vector[(Method, Vector[Example])]] =
    for {
      methods <- all
      worked <- amendments.left.map(Vector(_))
    } yield methods.map(m => m -> (if (m.version == 4) worked else Vector.empty))

  private type Parts = (Vector[ArtifactKind], Vector[Transition], Vector[RoleView])

  /** The checks the versions' transitions may name: grit's and kalandra's. */
  private val Compiled: Vector[TransitionCheck] = TransitionChecks.all ++ AmendmentChecks.all

  private def method(
      version: Int,
      declared: Either[String, Parts]
  ): Either[Vector[String], Method] =
    for {
      parts <- declared.left.map(Vector(_))
      method <- Method
        .of(Name, version, parts._1, parts._2, parts._3, Compiled)
        .left
        .map(_.map(_.toString))
    } yield method

  private def move(
      kind: KindName,
      from: StateName,
      to: StateName,
      checks: TransitionCheck*
  ): Transition =
    Transition(kind, from, to, checks.map(_.name).toVector, TransitionAction.Nothing)

  private def declaredV1: Either[String, Parts] =
    for {
      milestone <- KindName.of("milestone")
      stage <- KindName.of("stage")
      idea <- KindName.of("idea")
      mechanism <- KindName.of("mechanism")
      planned <- StateName.of("planned")
      open <- StateName.of("open")
      closed <- StateName.of("closed")
      building <- StateName.of("building")
      review <- StateName.of("review")
      merged <- StateName.of("merged")
      dropped <- StateName.of("dropped")
      parked <- StateName.of("parked")
      folded <- StateName.of("folded")
      current <- StateName.of("current")
      retired <- StateName.of("retired")
      coder <- RoleName.of("coder")
      manager <- RoleName.of("manager")
      coding <- RoleView.of(coder, Set(milestone, stage, mechanism), 2)
      managing <- RoleView.of(
        manager,
        Set(milestone, stage, idea, mechanism),
        1
      )
    } yield {
      val kinds = Vector(
        ArtifactKind(
          milestone,
          planned,
          Vector(open, closed),
          Set(closed),
          Vector.empty
        ),
        ArtifactKind(
          stage,
          planned,
          Vector(building, review, merged, dropped),
          Set(merged, dropped),
          Vector("Goal")
        ),
        ArtifactKind(
          idea,
          parked,
          Vector(folded, dropped),
          Set(folded, dropped),
          Vector.empty
        ),
        ArtifactKind(
          mechanism,
          current,
          Vector(retired),
          Set(retired),
          Vector.empty
        )
      )
      val transitions = Vector(
        move(milestone, planned, open),
        move(milestone, open, closed, TransitionChecks.ChildrenEnded),
        move(stage, planned, building, TransitionChecks.SectionsPresent),
        move(stage, building, review),
        move(stage, review, building),
        move(stage, review, merged),
        move(stage, planned, dropped),
        move(stage, building, dropped),
        move(idea, parked, folded),
        move(idea, parked, dropped),
        move(mechanism, current, retired)
      )
      (kinds, transitions, Vector(coding, managing))
    }

  /** Version 2's kinds and transitions, its `stage` named `work`: the planning tree versions 2
    * and 3 share.
    */
  private final case class Planning(
      milestone: KindName,
      plan: KindName,
      work: KindName,
      section: KindName,
      mechanism: KindName,
      note: KindName,
      kinds: Vector[ArtifactKind],
      transitions: Vector[Transition]
  )

  private def planning(workName: String): Either[String, Planning] =
    for {
      milestone <- KindName.of("milestone")
      plan <- KindName.of("plan")
      work <- KindName.of(workName)
      section <- KindName.of("section")
      mechanism <- KindName.of("mechanism")
      note <- KindName.of("note")
      later <- StateName.of("later")
      next <- StateName.of("next")
      now <- StateName.of("now")
      parallel <- StateName.of("parallel")
      done <- StateName.of("done")
      open <- StateName.of("open")
      closed <- StateName.of("closed")
      planned <- StateName.of("planned")
      building <- StateName.of("building")
      review <- StateName.of("review")
      merged <- StateName.of("merged")
      dropped <- StateName.of("dropped")
      folded <- StateName.of("folded")
      current <- StateName.of("current")
      retired <- StateName.of("retired")
    } yield {
      val unended = Vector(later, next, now, parallel)
      val kinds = Vector(
        ArtifactKind(
          milestone,
          later,
          Vector(next, now, parallel, done, dropped),
          Set(done, dropped),
          Vector.empty
        ),
        ArtifactKind(plan, open, Vector(closed, dropped), Set(closed, dropped), Vector.empty),
        ArtifactKind(
          work,
          planned,
          Vector(building, review, merged, dropped),
          Set(merged, dropped),
          Vector.empty
        ),
        ArtifactKind(section, current, Vector(retired), Set(retired), Vector.empty),
        ArtifactKind(mechanism, current, Vector(retired), Set(retired), Vector.empty),
        ArtifactKind(note, open, Vector(folded, dropped), Set(folded, dropped), Vector.empty)
      )
      val milestones =
        (for { a <- unended; b <- unended if a != b } yield move(milestone, a, b)) ++
          unended.map(move(milestone, _, done, TransitionChecks.ChildrenEnded)) ++
          unended.map(move(milestone, _, dropped))
      val transitions = milestones ++ Vector(
        move(plan, open, closed, TransitionChecks.ChildrenEnded),
        move(plan, open, dropped),
        move(work, planned, building),
        move(work, building, review),
        move(work, review, building),
        move(work, review, merged),
        move(work, planned, dropped),
        move(work, building, dropped),
        move(section, current, retired),
        move(mechanism, current, retired),
        move(note, open, folded),
        move(note, open, dropped)
      )
      Planning(milestone, plan, work, section, mechanism, note, kinds, transitions)
    }

  private def declaredV2: Either[String, Parts] =
    for {
      p <- planning("stage")
      coder <- RoleName.of("coder")
      manager <- RoleName.of("manager")
      coding <- RoleView.of(coder, Set(p.milestone, p.plan, p.work, p.section, p.mechanism), 2)
      managing <- RoleView.of(
        manager,
        Set(p.milestone, p.plan, p.work, p.section, p.mechanism, p.note),
        1
      )
    } yield (p.kinds, p.transitions, Vector(coding, managing))

  private def declaredV3: Either[String, Parts] =
    for {
      p <- planning("checkpoint")
      backlog <- KindName.of("backlog")
      open <- StateName.of("open")
      taken <- StateName.of("taken")
      folded <- StateName.of("folded")
      dropped <- StateName.of("dropped")
      coder <- RoleName.of("coder")
      manager <- RoleName.of("manager")
      reviewer <- RoleName.of("reviewer")
      coderRules <- LocalKey.of("roadmap-coder-rules")
      updateProtocol <- LocalKey.of("roadmap-update-protocol")
      every = Set(p.milestone, p.plan, p.work, p.section, p.mechanism, p.note, backlog)
      coding <- RoleView.of(
        coder,
        Set(p.milestone, p.plan, p.work, p.section, p.mechanism),
        2,
        Set(coderRules)
      )
      managing <- RoleView.of(manager, every, 1, Set(updateProtocol))
      reviewing <- RoleView.of(reviewer, every, 2)
    } yield (
      p.kinds :+ ArtifactKind(
        backlog,
        open,
        Vector(taken, folded, dropped),
        Set(taken, folded, dropped),
        Vector.empty
      ),
      p.transitions ++ Vector(taken, folded, dropped).map(move(backlog, open, _)),
      Vector(coding, managing, reviewing)
    )

  private def declaredV4: Either[String, Parts] =
    for {
      v3 <- declaredV3
      views: Vector[RoleView] = v3._3
      amendment <- KindName.of("amendment")
      proposed <- StateName.of("proposed")
      benched <- StateName.of("benched")
      adopted <- StateName.of("adopted")
      rejected <- StateName.of("rejected")
      dropped <- StateName.of("dropped")
      coder <- RoleName.of("coder")
      gritCoder <- RoleName.of("grit-coder")
      standing <- LocalKey.of("roadmap-grit-coder")
      coding <- views.find(_.role == coder).toRight("version 3 has no coder's view")
      grit <- RoleView.of(gritCoder, coding.kinds, coding.depth, Set(standing))
      widened <- views
        .map(v =>
          if (v.role == coder) Right(v)
          else RoleView.of(v.role, v.kinds + amendment, v.depth, v.carried)
        )
        .partitionMap(identity) match {
        case (Vector(), done) => Right(done)
        case (why, _) => Left(why.mkString("; "))
      }
    } yield (
      v3._1 :+ ArtifactKind(
        amendment,
        proposed,
        Vector(benched, adopted, rejected, dropped),
        Set(adopted, rejected, dropped),
        AmendmentSections
      ),
      v3._2 ++ Vector(
        move(amendment, proposed, benched, TransitionChecks.SectionsPresent),
        move(amendment, benched, adopted, AmendmentChecks.DecidedAdopt),
        move(amendment, benched, rejected, AmendmentChecks.DecidedReject),
        move(amendment, proposed, dropped),
        move(amendment, benched, dropped)
      ),
      widened :+ grit
    )

  /** The sections an amendment holds before it is benched. */
  private val AmendmentSections: Vector[String] =
    Vector("Diff", "Moments", "Cue", "Lesson", "Confidence", "Observable")

  /** Version 4's worked examples, each one amendment's moves from `proposed`. */
  private def amendments: Either[String, Vector[Example]] = {
    import Example.Expect.{Allowed, RefusedBy}
    val proposal = AmendmentSections.map(s => s"## $s\n\nWhat its $s says.").mkString("\n\n")
    def decided(verb: String, features: Vector[String]): String =
      proposal + "\n\n## Contrast\n\nkept -> amendment -> moment.\n\n## Verdict: confounded\n\n" +
        s"One run each way.\n\n## Decision: $verb\n\n" + features
          .map(f => s"- $f: noted.")
          .mkString("\n")
    for {
      project <- ProjectKey.of("p")
      pin <- Pin.of(Name, 4, Sha256.text("examples"))
      kind <- KindName.of("amendment")
      proposed <- StateName.of("proposed")
      benched <- StateName.of("benched")
      adopted <- StateName.of("adopted")
      rejected <- StateName.of("rejected")
      sections = TransitionChecks.SectionsPresent.name
      adopt = AmendmentChecks.DecidedAdopt.name
      reject = AmendmentChecks.DecidedReject.name
      // Each example one amendment, `amend-<i>`, starting `proposed`, moved in turn.
      example = (i: Int, named: String, body: String, moves: Vector[Example.Move]) =>
        for {
          key <- ArtifactKey.of(project, s"amend-$i")
          read <- Body.read(body)
          head = ArtifactHead(key, kind, proposed, named, None, Vector.empty, None)
          start <- Project.of(project, pin, Vector(Artifact(head, read)))
        } yield Example(named, start, moves.map(m => m.copy(key = key)))
      placeholder <- ArtifactKey.of(project, "amend")
      to = (state: StateName, expect: Example.Expect) => Example.Move(placeholder, state, expect)
      examples <- Vector(
        example(
          0,
          "an amendment lacking a section of its kind is not benched",
          proposal.replace("## Lesson", "## Lessons"),
          Vector(to(benched, RefusedBy(sections)))
        ),
        example(
          1,
          "an undecided amendment is benched, then neither adopted nor rejected",
          proposal,
          Vector(
            to(benched, Allowed),
            to(adopted, RefusedBy(adopt)),
            to(rejected, RefusedBy(reject))
          )
        ),
        example(
          2,
          "an amendment decided reject is rejected, never adopted",
          decided("reject", AmendmentChecks.Features),
          Vector(to(benched, Allowed), to(adopted, RefusedBy(adopt)), to(rejected, Allowed))
        ),
        example(
          3,
          "an amendment decided adopt, each feature named, is adopted",
          decided("adopt", AmendmentChecks.Features),
          Vector(to(benched, Allowed), to(adopted, Allowed))
        ),
        example(
          4,
          "an amendment decided adopt without its cost is not adopted",
          decided("adopt", AmendmentChecks.Features.filterNot(_ == "Cost")),
          Vector(to(benched, Allowed), to(adopted, RefusedBy(adopt)))
        )
      ).partitionMap(identity) match {
        case (Vector(), done) => Right(done)
        case (why, _) => Left(why.mkString("; "))
      }
    } yield examples
  }

  // A literal the name's constructor accepts.
  private def name(n: Either[String, MethodName]): MethodName =
    n.fold(why => throw new IllegalStateException(why), identity)

  private def kind(n: Either[String, KindName]): KindName =
    n.fold(why => throw new IllegalStateException(why), identity)

  private def state(n: Either[String, StateName]): StateName =
    n.fold(why => throw new IllegalStateException(why), identity)
}
