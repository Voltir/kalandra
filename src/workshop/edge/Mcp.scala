package workshop.edge

import grit.core.provider.ToolSchema
import grit.core.tool.ToolName

/** The Model Context Protocol as the edge speaks it at [[Routes.McpPath]]: JSON-RPC 2.0, one message
  * per POST, each answered by one JSON reply (streamable HTTP with no stream and no session).
  */
object Mcp {

  /** The protocol versions it answers in, oldest first; a client asking for another is
    * answered in the last.
    */
  val Versions: Vector[String] = Vector("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

  /** One message read from a POST's body. */
  enum Message {

    /** A request: `method` with `params`, answered to `id`. */
    case Request(id: ujson.Value, method: String, params: ujson.Obj)

    /** A notification, or a client's response: nothing is answered but 202. */
    case Notice
  }

  /** `body` as one message, or the JSON-RPC error that answers it: a parse error for no JSON, an
    * invalid request for anything else that is no one message (a batch among them).
    */
  def read(body: String): Either[Reply, Message] =
    scala.util.Try(ujson.read(body)).toOption match {
      case None => Left(failed(ujson.Null, ParseError, "the body is no JSON", 400))
      case Some(o: ujson.Obj) =>
        (o.value.get("method").flatMap(_.strOpt), o.value.get("id"), o.value.get("params")) match {
          case (None, Some(_), None) if o.value.contains("result") || o.value.contains("error") =>
            Right(Message.Notice)
          case (Some(_), None, _) => Right(Message.Notice)
          case (Some(m), Some(id), None) => Right(Message.Request(id, m, ujson.Obj()))
          case (Some(m), Some(id), Some(p: ujson.Obj)) => Right(Message.Request(id, m, p))
          case _ => Left(invalid)
        }
      case Some(_) => Left(invalid)
    }

  /** The answer to `initialize` asking for `params`' protocol version. */
  def initialized(id: ujson.Value, params: ujson.Obj): Reply = {
    val asked = params.value.get("protocolVersion").flatMap(_.strOpt)
    result(
      id,
      ujson.Obj(
        "protocolVersion" -> asked.filter(Versions.contains).getOrElse(Versions.last),
        "capabilities" -> ujson.Obj("tools" -> ujson.Obj("listChanged" -> false)),
        "serverInfo" -> ujson.Obj("name" -> "workshop", "version" -> "1")
      )
    )
  }

  /** `tools/list`'s result for `offered`: each command a tool, its parameters its input schema. */
  def tools(offered: Vector[ToolSchema]): ujson.Value =
    ujson.Obj(
      "tools" -> ujson.Arr.from(
        offered.map(t =>
          ujson.Obj(
            "name" -> t.name,
            "description" -> t.description,
            "inputSchema" -> t.parameters
          )
        )
      )
    )

  /** The command a `tools/call` names, and its arguments; or why it names none. */
  def call(params: ujson.Obj): Either[String, (ToolName, ujson.Obj)] =
    for {
      raw <- params.value.get("name").flatMap(_.strOpt).toRight("tools/call names no tool")
      name <- ToolName.of(raw).left.map(_ => s"no tool is named $raw")
      args <- params.value.get("arguments") match {
        case None => Right(ujson.Obj())
        case Some(a: ujson.Obj) => Right(a)
        case Some(_) => Left("a tool's arguments are a JSON object")
      }
    } yield (name, args)

  /** `tools/call`'s answer to `id`, from what the edge answered the command: its text the
    * content, an error unless the command did what it was asked (200).
    */
  def called(id: ujson.Value, reply: Reply): Reply =
    result(
      id,
      ujson.Obj(
        "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> reply.text)),
        "isError" -> (reply.status != 200)
      )
    )

  /** `value` as the result of the request `id`. */
  def result(id: ujson.Value, value: ujson.Value): Reply =
    Reply(
      200,
      ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "result" -> value)),
      Reply.Json
    )

  /** The error `code` with `message`, answering the request `id`. */
  def failed(id: ujson.Value, code: Int, message: String, status: Int = 200): Reply =
    Reply(
      status,
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> id,
          "error" -> ujson.Obj("code" -> code, "message" -> message)
        )
      ),
      Reply.Json
    )

  /** JSON-RPC's error codes. */
  val ParseError: Int = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int = -32602

  private def invalid: Reply =
    failed(ujson.Null, InvalidRequest, "the body is no one JSON-RPC 2.0 message", 400)

}
