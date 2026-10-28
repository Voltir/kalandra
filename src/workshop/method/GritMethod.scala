package workshop.method

import grit.projects.method.{
  ArtifactKind,
  Method,
  RoleView,
  Transition,
  TransitionAction,
  TransitionCheck,
  TransitionChecks
}
import grit.projects.name.{KindName, MethodName, RoleName, StateName}

/** How grit itself is built, as a method (ADR 0049): a `milestone` holds
  * `stage`s and closes only once each has ended; a `stage` is planned with a
  * `## Goal` before it is built, reviewed, and merged or dropped; an `idea` is
  * parked until it is folded into other work or dropped; a `mechanism`
  * describes a part of grit until it is retired. A `coder` is shown the
  * artifact and what it cites two links deep, of every kind but ideas; a
  * `manager` every kind, one link deep.
  */
object GritMethod {

  /** `grit`: the method's name, the deployment's default. */
  val Name: MethodName = name(MethodName.of("grit"))

  /** Version 1. */
  val Version: Int = 1

  /** The method, or every reason its data is not one. */
  def of: Either[Vector[String], Method] =
    for {
      parts <- declared.left.map(Vector(_))
      method <- Method
        .of(Name, Version, parts._1, parts._2, parts._3, TransitionChecks.all)
        .left
        .map(_.map(_.toString))
    } yield method

  private def declared: Either[
    String,
    (Vector[ArtifactKind], Vector[Transition], Vector[RoleView])
  ] =
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
      def move(
          kind: KindName,
          from: StateName,
          to: StateName,
          checks: TransitionCheck*
      ) =
        Transition(
          kind,
          from,
          to,
          checks.map(_.name).toVector,
          TransitionAction.Nothing
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

  // A literal the name's constructor accepts.
  private def name(n: Either[String, MethodName]): MethodName =
    n.fold(why => throw new IllegalStateException(why), identity)
}
