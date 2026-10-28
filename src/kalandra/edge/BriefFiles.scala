package kalandra.edge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

/** The brief files a session hook reads, in `dir`: one per slot, `<slot>.md` with each `/` of
  * the slot written `__` (a slug part holds no `_`, so no two slots share a file). A file is
  * replaced whole: a reader sees the old brief or the new, never part of one.
  */
final class BriefFiles(dir: Path) {

  /** The file the brief for `slot` is written to. */
  def file(slot: String): Path = dir.resolve(s"${slot.replace("/", "__")}.md")

  /** Writes `text` as `slot`'s brief, creating the directory if need be: to a file of its own
    * beside it first, then moved over the brief at once. Or why not, writing nothing.
    */
  def write(slot: String, text: String): Either[String, Unit] =
    scala.util
      .Try {
        Files.createDirectories(dir)
        val temporary = Files.createTempFile(dir, ".brief-", ".tmp")
        try {
          Files.writeString(temporary, text, StandardCharsets.UTF_8)
          Files.move(
            temporary,
            file(slot),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
          )
          ()
        } finally Files.deleteIfExists(temporary)
      }
      .toEither
      .left
      .map(e => s"cannot write ${file(slot)}: $e")

  /** Deletes `slot`'s brief, if there is one; or why not. */
  def delete(slot: String): Either[String, Unit] =
    scala.util
      .Try(Files.deleteIfExists(file(slot)))
      .toEither
      .map(_ => ())
      .left
      .map(e => s"cannot delete ${file(slot)}: $e")
}

object BriefFiles {

  /** `raw`, an edge's name for a session or agent, as grit takes a slot (`BriefSlot`): per
    * character (code point), `A`-`Z` lower-cased, `a`-`z`, `0`-`9`, `-` and `/` kept, any other
    * written `-`; then each part's leading `-` dropped. So `Explore` is `explore`, and
    * `Coder_Opus/3F2A` `coder-opus/3f2a`. `hooks/brief-hook.sh` keeps this rule too.
    */
  def slot(raw: String): String =
    raw.codePoints.toArray
      .map(c =>
        if (c >= 'A' && c <= 'Z') (c - 'A' + 'a').toChar
        else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '/') c.toChar
        else '-'
      )
      .mkString
      .split("/", -1)
      .map(_.dropWhile(_ == '-'))
      .mkString("/")
}
