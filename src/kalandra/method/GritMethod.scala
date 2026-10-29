package kalandra.method

import grit.projects.method.{
  ArtifactKind,
  Method,
  RoleView,
  Transition,
  TransitionAction,
  TransitionCheck,
  TransitionChecks
}
import grit.projects.name.{KindName, LocalKey, MethodName, RoleName, StateName}

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
  */
object GritMethod {

  /** `grit`: the method's name, the deployment's default. */
  val Name: MethodName = name(MethodName.of("grit"))

  /** Version 1, which the project `restore-check` holds to. */
  def v1: Either[Vector[String], Method] = method(1, declaredV1)

  /** Version 2, which grit's planning tree was imported into. */
  def v2: Either[Vector[String], Method] = method(2, declaredV2)

  /** Version 3, the latest: what a new project holds to. */
  def v3: Either[Vector[String], Method] = method(3, declaredV3)

  /** Every version, oldest first, or every reason one is not a method. */
  def all: Either[Vector[String], Vector[Method]] =
    Vector(v1, v2, v3).partitionMap(identity) match {
      case (Vector(), methods) => Right(methods)
      case (why, _) => Left(why.flatten)
    }

  private type Parts = (Vector[ArtifactKind], Vector[Transition], Vector[RoleView])

  private def method(
      version: Int,
      declared: Either[String, Parts]
  ): Either[Vector[String], Method] =
    for {
      parts <- declared.left.map(Vector(_))
      method <- Method
        .of(Name, version, parts._1, parts._2, parts._3, TransitionChecks.all)
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

  // A literal the name's constructor accepts.
  private def name(n: Either[String, MethodName]): MethodName =
    n.fold(why => throw new IllegalStateException(why), identity)
}
