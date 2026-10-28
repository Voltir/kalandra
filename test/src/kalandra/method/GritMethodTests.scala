package kalandra.method

import grit.projects.name.{KindName, StateName}

import utest.*

object GritMethodTests extends TestSuite {

  val tests = Tests {
    test("version 1 keeps the digest the project restore-check pinned") {
      GritMethod.v1.map(_.digest) ==>
        Right("1c8e4adb20a0b3b044f5b165c638033a1a93dc83bfc98b97b0c6d26180c3de75")
    }

    test(
      "version 2 is the latest, its milestone sitting later, next, now or parallel and ending done or dropped"
    ) {
      val methods = GritMethod.all.fold(why => sys.error(why.mkString("; ")), identity)
      methods.map(_.version) ==> Vector(1, 2)
      val v2 = methods.maxBy(_.version)
      val milestone =
        v2.kinds.find(k => KindName.value(k.name) == "milestone").getOrElse(sys.error("none"))
      (milestone.all.map(StateName.value), milestone.ends.map(StateName.value)) ==> (
        Vector("later", "next", "now", "parallel", "done", "dropped"),
        Set("done", "dropped")
      )
      v2.kinds.map(k => KindName.value(k.name)).toSet ==>
        Set("milestone", "plan", "stage", "section", "mechanism", "note")
    }
  }
}
