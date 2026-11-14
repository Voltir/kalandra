package kalandra.edge

import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant

import scala.concurrent.duration.*

import grit.core.clock.Clock
import grit.core.document.DocRef
import grit.core.edge.{CommandError, EdgeWatch, PluginCommands, WatchError, WatchFor, Watched}
import grit.core.id.{ClaimHolder, DocumentVersionId}
import grit.core.identity.Account
import grit.core.place.Place
import grit.core.plugin.CommandOutcome
import grit.core.tool.ToolName
import grit.core.visibility.Integrity
import grit.projects.artifact.{Artifact, Body}
import grit.projects.name.{ArtifactKey, KindName, ProjectKey, StateName}
import grit.projects.plugin.Projects
import grit.projects.render.ProjectMarkdown

import kalandra.amend.{AdoptRefusal, Adoption, Candidate, Diff, StandingSection}
import kalandra.capture.Spooled
import kalandra.method.GritMethod

/** What the edge answers one request: an HTTP status, its text, and that text's content type. */
final case class Reply(status: Int, text: String, contentType: String = Reply.PlainText)
    extends caps.Pure

object Reply {

  /** `text/plain; charset=utf-8`. */
  val PlainText: String = "text/plain; charset=utf-8"

  /** `application/json`. */
  val Json: String = "application/json"
}

/** The edge's routes, each request run as `owner` in `room`, believed at `believed`, dated by
  * `clock`. `POST /command/<name>` runs the command `name` with its body, a JSON object, as its
  * arguments (an empty body: none). `POST /mcp` takes one [[Mcp]] message: `initialize`, `ping`,
  * `tools/list` (the commands offered) and `tools/call`, which runs an offered command as
  * `/command/<name>` would, its text the content, an error unless that answered 200. `POST /wait`
  * waits on `watch` as [[Routes.watchOf]] reads its body. Nothing else runs anything.
  *
  * `project_claim`'s `holder` is passed on as [[BriefFiles.slot]] maps it; once the claim is done,
  * `project_brief` for its key and role, at most [[DocRef.BriefChars]] characters (what Claude
  * Code injects from one hook whole, with room for the hook's JSON), is written to that slot's
  * file in `briefs`.
  * `project_release` takes its key alone and is passed on as given; once the release is done,
  * the slot its answer names (`{"released","holder"}` JSON) has its brief and digests deleted
  * ([[BriefFiles.delete]]). Once a
  * `project_keep`, `project_move` or `project_batch` (not a dry one) is done, each live claim on
  * its artifact's project whose slot
  * has a brief file (`project_claims`) has that file written again from `project_brief`, whether
  * or not its brief shows the artifact: a session reads its file only at a start or a compaction,
  * so a brief rewritten mid-session reaches its window at the next one, by design. A file that
  * cannot be written or deleted, a brief or the claims refused, or a release answer naming no
  * slot, is said after the command's text; the command stays done.
  *
  * Two commands are the edge's own, neither offered as a tool, so only a person reaches them
  * (`bin/kalandra standing|adopt`); each takes `key` alone and answers a refusal 409, keeping
  * nothing:
  *   - `project_standing`: a standing section's text ([[kalandra.amend.StandingSection.file]]),
  *     or, for an amendment, its candidate's ([[kalandra.amend.Adoption.candidate]]), the
  *     section it amends shown as it is now;
  *   - `project_adopt`: an amendment's candidate kept as its section's next version, at the
  *     version the diff is against, and the amendment moved to `adopted`, in one
  *     `project_batch`, which refuses both unless its checks hold; brief files then written
  *     again as after a batch.
  *
  * A `project_move` to `adopted`, or a `project_batch` with a step moving to it, is refused 409
  * and runs nothing: an amendment is adopted only through `project_adopt`, under its section's
  * cap. A keep of the section is not refused: that is how a cap is changed.
  *
  * Every request is first checked as coming from this machine, since a web page can send one
  * too (through DNS rebinding, with its own name as `Host`): see [[answer]].
  */
final class Routes(
    commands: PluginCommands,
    watch: EdgeWatch,
    owner: Account,
    room: Place,
    believed: Integrity,
    clock: Clock^,
    briefs: BriefFiles,
    listen: InetSocketAddress
) {

  /** `method` at `path` with `body`, answered: the command's text with 200 when it did what it
    * was asked; its reason with 409 when it refused; 404 for a command no plugin has, or any
    * other path; 405 for a command not posted; 400 for a body that is no JSON object, or
    * arguments the command does not read; 403 for an owner no person is linked to; 500 when the
    * command's store call failed; 503 when the database could not be reached. Only a 200 kept
    * anything. At [[Routes.McpPath]]: a POST answered as [[Mcp]] says, anything else 405. At
    * [[Routes.WaitPath]]: a POST waited on as the owner in the room, blocking for the whole wait,
    * anything else 405; 200 with what met it; 408 when nothing did before it lapsed; 400 for a
    * body [[Routes.watchOf]] does not read; 403 for an owner no person is linked to; 503 when
    * [[EdgeWatch.Most]] watches are under way, or the database could not be read.
    *
    * First, 403, running nothing, when `origin` (the `Origin` header) is given and is not
    * `http://` a loopback host (`127.0.0.1`, `localhost`, `[::1]`) at any port, or when `host`
    * (the `Host` header) is not `listen`'s address and port or `localhost` and its port. A
    * request with no `Origin` (curl, `bin/kalandra`, an MCP client) passes on its `Host`.
    */
  def answer(
      method: String,
      path: String,
      origin: Option[String],
      host: Option[String],
      body: String
  ): Reply =
    if (!origin.forall(Routes.loopbackOrigin) || !host.exists(local))
      Reply(403, "refused: only a request from this machine is answered")
    else if (path == Routes.McpPath) {
      if (method == "POST") mcp(body)
      else Reply(405, s"post a JSON-RPC message; $method runs nothing")
    } else if (path == Routes.WaitPath) {
      if (method == "POST") waited(body)
      else Reply(405, s"post a watch; $method waits on nothing")
    } else
      path.stripPrefix(Routes.Command) match {
        case name if name == path || name.isEmpty || name.contains('/') =>
          Reply(404, s"no route $path; commands are posted to ${Routes.Command}<name>")
        case _ if method != "POST" => Reply(405, s"post a command; $method runs nothing")
        case raw =>
          (ToolName.of(raw), arguments(body)) match {
            case (Left(_), _) => Reply(404, s"no command is named $raw")
            case (_, Left(why)) => Reply(400, why)
            case (Right(name), Right(args)) => routed(name, args)
          }
      }

  /** Whether `host`, a `Host` header, names `listen`: its address or `localhost`, and its port. */
  private def local(host: String): Boolean = {
    val port = listen.getPort
    val address = listen.getAddress.getHostAddress
    val named = if (address.contains(':')) s"[$address]" else address
    Set(s"$named:$port", s"localhost:$port").contains(host.trim.toLowerCase(java.util.Locale.ROOT))
  }

  /** One MCP message, answered; a call to a command not offered runs nothing. */
  private def mcp(body: String): Reply = Mcp.read(body) match {
    case Left(error) => error
    case Right(Mcp.Message.Notice) => Reply(202, "")
    case Right(Mcp.Message.Request(id, method, params)) =>
      method match {
        case "initialize" => Mcp.initialized(id, params)
        case "ping" => Mcp.result(id, ujson.Obj())
        case "tools/list" => Mcp.result(id, Mcp.tools(commands.offered.map(_.schema)))
        case "tools/call" =>
          Mcp
            .call(params)
            .filterOrElse(
              c => commands.offered.exists(_.schema.name == ToolName.value(c._1)),
              s"no tool is named ${params.value.get("name").flatMap(_.strOpt).getOrElse("")}"
            ) match {
            case Left(why) => Mcp.failed(id, Mcp.InvalidParams, why)
            case Right((name, args)) => Mcp.called(id, routed(name, args))
          }
        case other => Mcp.failed(id, Mcp.MethodNotFound, s"no method is named $other")
      }
  }

  /** The watch `body` asks for, waited on as the owner in the room. */
  private def waited(body: String): Reply =
    arguments(body).flatMap(Routes.watchOf(_, clock.now())) match {
      case Left(why) => Reply(400, why)
      case Right((what, within)) =>
        Routes.watched(what, within, watch.until(owner, room, what, within))
    }

  /** `name` run with `args`, keeping the brief file of a claim or release that is done. */
  private def routed(name: ToolName, args: ujson.Obj): Reply = ToolName.value(name) match {
    case Routes.Claim =>
      val slot = text(args, "holder").map(BriefFiles.slot)
      val claimed = ran(name, slot.fold(args)(s => replaced(args, "holder", Some(s))))
      (claimed.status, slot, text(args, "key"), text(args, "role")) match {
        case (200, Some(s), Some(key), Some(role)) =>
          briefed(s, key, role)
            .fold(
              why => Reply(200, s"${claimed.text}\nNo brief file was written: $why"),
              _ => claimed
            )
        case _ => claimed
      }
    case Routes.Release =>
      val released = ran(name, args)
      if (released.status != 200) released
      else
        Routes.releasedSlot(released.text) match {
          case None =>
            Reply(200, s"${released.text}\nThe brief file stays: the release named no slot")
          case Some((key, slot)) =>
            briefs
              .delete(slot)
              .fold(
                why => Reply(200, s"Released $key.\nThe brief file stays: $why"),
                _ => Reply(200, s"Released $key.")
              )
        }
    case Routes.Move if text(args, "state").contains(Routes.Adopted) =>
      Reply(409, Routes.adoptedOnly(text(args, "key")))
    case Routes.Batch if Routes.adopts(args).nonEmpty =>
      Reply(409, Routes.adoptedOnly(Routes.adopts(args).flatten))
    case Routes.Keep | Routes.Move =>
      val changed = ran(name, args)
      text(args, "key").flatMap(ArtifactKey.read(_).toOption) match {
        case Some(key) if changed.status == 200 =>
          rewritten(key.project).fold(changed)(why => Reply(200, s"${changed.text}\n$why"))
        case _ => changed
      }
    case Routes.Batch => batched(args)
    case Routes.Standing => standing(args)
    case Routes.Adopt => adopted(args)
    case _ => ran(name, args)
  }

  /** `project_batch` run with `args`, then, when it was done and not dry, its project's brief
    * files written again.
    */
  private def batched(args: ujson.Obj): Reply = {
    val done = ran(Routes.Batching, args)
    val dry = args.value.get("dry").flatMap(_.boolOpt).getOrElse(false)
    text(args, "project").flatMap(ProjectKey.of(_).toOption) match {
      case Some(project) if done.status == 200 && !dry =>
        rewritten(project).fold(done)(why => Reply(200, s"${done.text}\n$why"))
      case _ => done
    }
  }

  /** `project_standing`: the standing text of the section `key`, or of an amendment's
    * candidate.
    */
  private def standing(args: ujson.Obj): Reply =
    (for {
      key <- text(args, "key").toRight(Reply(400, Routes.KeyAlone))
      shown <- this.shown(key)
      text <-
        if (KindName.value(shown._1.head.kind) == Routes.Amendment)
          candidate(shown._1).map(_._3.standing.file)
        else
          StandingSection
            .of(shown._1.head.key, shown._1.body)
            .map(_.file)
            .left
            .map(r => Reply(409, r.message))
    } yield Reply(200, text)).merge

  /** `project_adopt`: the amendment `key`'s candidate kept, and the amendment adopted, in one
    * batch.
    */
  private def adopted(args: ujson.Obj): Reply =
    (for {
      key <- text(args, "key").toRight(Reply(400, Routes.KeyAlone))
      amendment <- shown(key)
      made <- candidate(amendment._1)
      (section, version, candidate) = made
      steps = ujson.Arr(
        ujson.Obj(
          "command" -> "keep",
          "key" -> section.head.key.value,
          "title" -> section.head.title,
          "body" -> Body.markdown(candidate.body),
          "version" -> DocumentVersionId.value(version).toDouble
        ),
        ujson.Obj("command" -> "move", "key" -> key, "state" -> Routes.Adopted)
      )
      done = batched(
        ujson.Obj("project" -> ProjectKey.value(amendment._1.head.key.project), "steps" -> steps)
      )
      _ <- Either.cond(done.status == 200, (), done)
    } yield Reply(
      200,
      s"Adopted $key: ${section.head.key.value} kept again, its standing text " +
        f"${candidate.standing.size}%,d characters of its cap ${candidate.standing.cap}%,d.\n" +
        done.text
    )).merge

  /** The section `amendment`'s diff names, as it is now, the version it is at, and the diff's
    * candidate over it; or the reply refusing it.
    */
  private def candidate(
      amendment: Artifact
  ): Either[Reply, (Artifact, DocumentVersionId, Candidate)] =
    for {
      diff <- Diff
        .read(Body.markdown(amendment.body))
        .left
        .map(why => Reply(409, AdoptRefusal.NotADiff(amendment.head.key, why).message))
      section <- shown(diff.section.value)
      made <- Adoption
        .candidate(diff, section._1, section._2)
        .left
        .map(r => Reply(409, r.message))
    } yield (section._1, section._2, made)

  /** The artifact `key` as `project_show` gives it, read back, or the reply refusing it. */
  private def shown(key: String): Either[Reply, (Artifact, DocumentVersionId)] = {
    val answered = ran(Routes.Show, ujson.Obj("key" -> key))
    if (answered.status != 200) Left(answered)
    else
      ProjectMarkdown
        .readShown(answered.text)
        .left
        .map(why => Reply(500, s"$key as project_show gave it was not read: $why"))
  }

  /** Writes again the brief file of each live claim on `project` whose slot has one; what failed,
    * if anything did.
    */
  private def rewritten(project: ProjectKey): Option[String] = {
    val claims = ran(Routes.Claims, ujson.Obj("project" -> ProjectKey.value(project)))
    val failed =
      if (claims.status != 200) Vector(s"the claims were not read: ${claims.text}")
      else
        Routes.held(claims.text) match {
          case None => Vector("the claims' answer was not read")
          case Some(held) =>
            held.filter(h => Files.exists(briefs.file(h.slot))).flatMap { h =>
              briefed(h.slot, h.key, h.role).left.toOption.map(why => s"${h.slot}: $why")
            }
        }
    Option.when(failed.nonEmpty)(s"Brief files not written again: ${failed.mkString("; ")}")
  }

  /** `project_brief` for `key` and `role`, at most [[DocRef.BriefChars]] characters, written
    * as `slot`'s brief; or why not: the brief refused, or the file not written.
    */
  private def briefed(slot: String, key: String, role: String): Either[String, Unit] = {
    val brief =
      ran(Routes.Brief, ujson.Obj("key" -> key, "role" -> role, "chars" -> DocRef.BriefChars))
    if (brief.status == 200) briefs.write(slot, brief.text) else Left(brief.text)
  }

  private def ran(name: ToolName, args: ujson.Obj): Reply =
    Routes.replied(commands.run(owner, room, believed, name, args, clock.now()))

  private def text(args: ujson.Obj, field: String): Option[String] =
    args.value.get(field).flatMap(_.strOpt)

  /** `args` with `field` set to `to`, or left out when `to` is none; `args` itself unchanged. */
  private def replaced(args: ujson.Obj, field: String, to: Option[String]): ujson.Obj =
    ujson.Obj.from(
      args.value.toSeq.filterNot(_._1 == field) ++ to.map(t => field -> (ujson.Str(t): ujson.Value))
    )

  private def arguments(body: String): Either[String, ujson.Obj] =
    if (body.trim.isEmpty) Right(ujson.Obj())
    else
      scala.util.Try(ujson.read(body)).toOption match {
        case Some(o: ujson.Obj) => Right(o)
        case _ => Left("the body is no JSON object of the command's arguments")
      }
}

object Routes {

  /** `/command/`: where a command is posted, its name after. */
  val Command: String = "/command/"

  /** Whether `origin`, an `Origin` header, is `http://` a loopback host at a port, or none. */
  private def loopbackOrigin(origin: String): Boolean =
    LoopbackOrigin.matches(origin.trim.toLowerCase(java.util.Locale.ROOT))

  private val LoopbackOrigin = raw"http://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]{1,5})?".r

  /** `/mcp`: where MCP messages are posted. */
  val McpPath: String = "/mcp"

  /** `/wait`: where a watch is posted. */
  val WaitPath: String = "/wait"

  /** 5 minutes: how long a watch waits when its body names no `within`; a client gone meanwhile
    * holds its place among [[EdgeWatch.Most]] until then.
    */
  val Within: FiniteDuration = 5.minutes

  /** What a watch's body asks for and how long it waits ([[Within]] unless `within` names
    * seconds; [[EdgeWatch.bounded]] clamps it), or why not:
    *   - `{"artifact": "<project>/<artifact>", "state": ["<state>", …]}`: the artifact in one of
    *     the states ([[Projects.reaching]]);
    *   - `{"commit": {"worktree": "<absolute path>"}}` or `{"commit": {"slot": "<holder>"}}`: a
    *     commit made in the worktree, or by a session working as the holder, at or after `since`
    *     (an ISO-8601 instant), by default `now`.
    */
  private[edge] def watchOf(
      args: ujson.Obj,
      now: Instant
  ): Either[String, (WatchFor, FiniteDuration)] = {
    val o = args.value
    val within: Either[String, FiniteDuration] = o.get("within") match {
      case None => Right(Within)
      case Some(w) =>
        w.numOpt
          .filter(s => s >= 0 && s.isWhole)
          .map(_.toLong.seconds)
          .toRight("within is whole seconds, 0 or more")
    }
    val what: Either[String, WatchFor] = (o.get("artifact"), o.get("commit")) match {
      case (Some(a), None) =>
        for {
          key <- a.strOpt.toRight("artifact is a key").flatMap(ArtifactKey.read)
          names <- o.get("state").flatMap(_.arrOpt).toRight("state is a list of states' names")
          states <- names.toVector.foldLeft[Either[String, Set[StateName]]](Right(Set.empty)) {
            (acc, n) =>
              acc.flatMap(got =>
                n.strOpt.toRight(s"$n is no state's name").flatMap(StateName.of).map(got + _)
              )
          }
          watch <- Projects.reaching(key, states)
        } yield watch
      case (None, Some(c)) =>
        for {
          by <- c.objOpt.map(_.toMap) match {
            case Some(m) if m.keySet == Set("worktree") =>
              m("worktree").strOpt
                .flatMap(Spooled.worktree)
                .map(WatchFor.Committer.At(_))
                .toRight("worktree is an absolute path")
            case Some(m) if m.keySet == Set("slot") =>
              m("slot").strOpt
                .toRight("slot is a holder's name")
                .flatMap(ClaimHolder.of)
                .map(WatchFor.Committer.As(_))
            case _ => Left("commit names a worktree or a slot")
          }
          since <- o.get("since") match {
            case None => Right(now)
            case Some(s) =>
              s.strOpt
                .flatMap(t => scala.util.Try(Instant.parse(t)).toOption)
                .toRight("since is an ISO-8601 instant")
          }
        } yield WatchFor.Committed(by, since)
      case _ => Left("a watch names an artifact or a commit, one of them")
    }
    what.flatMap(w => within.map(w -> _))
  }

  /** What a watch for `what`, waiting `within`, answers when it came to `came`. */
  private def watched(
      what: WatchFor,
      within: FiniteDuration,
      came: Either[WatchError, Watched]
  ): Reply = (what, came) match {
    case (h: WatchFor.Holding, Right(held: Watched.Held)) =>
      val states = Projects.reached(held).map(StateName.value).toVector.sorted.mkString(" and ")
      Reply(
        200,
        s"${grit.core.id.DocKey.value(h.key)} is in $states (version ${held.version}, written ${held.written})"
      )
    case (_, Right(Watched.Committed(session, at, shape))) =>
      val shown =
        scala.util.Try(ujson.read(grit.core.session.Event.Shape.value(shape)).obj).toOption
      def field(k: String) = shown.flatMap(_.get(k)).flatMap(_.strOpt)
      val commit = (field("sha").map(_.take(12)) ++ field("subject")).mkString(" ")
      val in = field(grit.core.session.Event.Shape.Worktree).fold("")(w => s" in $w")
      Reply(200, s"committed $commit$in, on session ${session.local} at $at")
    case (_, Right(Watched.Lapsed)) =>
      Reply(408, s"nothing met the watch within ${EdgeWatch.bounded(within).toSeconds}s")
    case (_, Right(other)) => Reply(500, s"the watch was met by what it did not ask for: $other")
    case (_, Left(WatchError.Busy)) =>
      Reply(503, s"${EdgeWatch.Most} watches are under way already: wait for one to end")
    case (_, Left(WatchError.NotVouched(by))) =>
      Reply(403, s"no person is linked to ${Account.written(by)}")
    case (_, Left(WatchError.Unavailable(cause))) =>
      Reply(503, s"the database is unavailable: $cause")
  }

  private val Claim = "project_claim"
  private[edge] val Release = "project_release"
  private val Keep = "project_keep"
  private val Move = "project_move"
  private val Batch = "project_batch"
  private val Brief = ToolName("project_brief")
  private val Claims = ToolName("project_claims")
  private val Show = ToolName("project_show")
  private val Batching = ToolName("project_batch")

  /** `project_standing` and `project_adopt`: the edge's own commands, which no plugin has. */
  private[edge] val Standing = "project_standing"
  private[edge] val Adopt = "project_adopt"

  /** `amendment`: the kind whose diff a standing section is amended by. */
  private val Amendment = "amendment"

  /** `adopted`: the state only `project_adopt` moves an amendment to. */
  private val Adopted = StateName.value(GritMethod.Adopted)

  /** Why a move of the artifact `key` names to adopted is refused: [[Projects.guardedMove]]'s
    * wording, the plugin's own for a turn's such move, then [[Adopting]]; [[Adopting]] alone
    * when `key` is none or no artifact's key.
    */
  private def adoptedOnly(key: Option[String]): String =
    key
      .flatMap(ArtifactKey.read(_).toOption)
      .fold(Adopting)(k =>
        s"${Projects.guardedMove(k, GritMethod.Amendment, GritMethod.Adopted)} $Adopting"
      )

  /** What follows [[Projects.guardedMove]] when the edge refuses a move to adopted: how a person
    * adopts an amendment.
    */
  val Adopting: String =
    "A person adopts an amendment through project_adopt, which keeps its section's next " +
      "version under the section's cap: run that with the amendment's key."

  private val KeyAlone = "give key, an amendment's or a standing section's, alone"

  /** The first step of `args`, a `project_batch`'s, moving an artifact to [[Adopted]]: the key
    * it names, when it names one; none when no step does.
    */
  private def adopts(args: ujson.Obj): Option[Option[String]] =
    args.value
      .get("steps")
      .flatMap(_.arrOpt)
      .flatMap(_.collectFirst(Function.unlift { (s: ujson.Value) =>
        s.objOpt.collect {
          case o
              if o.get("command").flatMap(_.strOpt).contains("move") &&
                o.get("state").flatMap(_.strOpt).contains(Adopted) =>
            o.get("key").flatMap(_.strOpt)
        }
      }))

  /** A live claim, from the claims' answer: its artifact's key, its role, and the slot it is for. */
  private final case class Held(key: String, role: String, slot: String)

  /** The live claims a `project_claims` answer lists (`{"claims":[{"key","role","holder"}]}`), each
    * slot as [[BriefFiles.slot]] maps it; none for any other text.
    */
  private def held(text: String): Option[Vector[Held]] =
    scala.util
      .Try(ujson.read(text))
      .toOption
      .flatMap(_.objOpt)
      .flatMap(_.get("claims"))
      .flatMap(_.arrOpt)
      .map(
        _.toVector.flatMap(c =>
          for {
            o <- c.objOpt
            key <- o.get("key").flatMap(_.strOpt)
            role <- o.get("role").flatMap(_.strOpt)
            slot <- o.get("holder").flatMap(_.strOpt)
          } yield Held(key, role, BriefFiles.slot(slot))
        )
      )

  /** The key released and the slot it was for, from a release's answer: a JSON object with
    * string `released` and `holder`. None for any other text.
    */
  private def releasedSlot(text: String): Option[(String, String)] =
    scala.util.Try(ujson.read(text)).toOption match {
      case Some(o: ujson.Obj) =>
        for {
          key <- o.value.get("released").flatMap(_.strOpt)
          slot <- o.value.get("holder").flatMap(_.strOpt)
        } yield (key, BriefFiles.slot(slot))
      case _ => None
    }

  private def replied(ran: Either[CommandError, CommandOutcome]): Reply = ran match {
    case Right(CommandOutcome.Done(text)) => Reply(200, text)
    case Right(CommandOutcome.Refused(why)) => Reply(409, why)
    case Left(CommandError.Unknown(name)) =>
      Reply(404, s"no command is named ${ToolName.value(name)}")
    case Left(CommandError.Unread(_, reason)) => Reply(400, reason)
    case Left(CommandError.NotVouched(by)) =>
      Reply(403, s"no person is linked to ${Account.written(by)}")
    case Left(CommandError.Store(_, error)) => Reply(500, s"nothing was kept: $error")
    case Left(CommandError.Unavailable(cause)) => Reply(503, s"the database is unavailable: $cause")
  }
}
