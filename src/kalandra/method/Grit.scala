package kalandra.method

import grit.projects.method.{TransitionCheck, TransitionChecks}
import grit.projects.name.{KindName, MethodName, StateName}

/** How grit itself is built, as a method (ADR 0049): the names kalandra's code reads of it, and
  * the checks its versions may name. Its versions are the files `methods/grit@<version>.json`,
  * each declared into the store through `project_declare` before a project may hold to it.
  */
object Grit {

  /** `grit`: the method's name, the deployment's default. */
  val Name: MethodName = known(MethodName.of("grit"))

  /** `amendment`: the kind, from version 4, of an edit proposed to a section's standing text. */
  val Amendment: KindName = known(KindName.of("amendment"))

  /** `adopted`: the state `project_adopt` moves an amendment to. */
  val Adopted: StateName = known(StateName.of("adopted"))

  /** The checks the versions' transitions may name: grit's and kalandra's. */
  val Checks: Vector[TransitionCheck] = TransitionChecks.all ++ AmendmentChecks.all

  // Each a literal its reader accepts.
  private def known[A](n: Either[String, A]): A =
    n.fold(why => throw new IllegalStateException(why), identity)
}
