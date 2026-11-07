package kalandra.edge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

import grit.core.session.SessionDigest

/** The brief files a session hook reads, in `dir`, each `/` of a slot written `__` in their
  * names (a slug part holds no `_`, so no two slots share a file): per slot `<slot>.md`, its
  * brief, and beside it two digests of the session working as it, `<slot>.digest.md` for a
  * window that holds what the session last said and `<slot>.digest-said.md` for one that lacks
  * it. A file is replaced whole: a reader sees the old text or the new, never part of one.
  */
final class BriefFiles(dir: Path) {

  /** The file the brief for `slot` is written to. */
  def file(slot: String): Path = named(slot, ".md")

  /** The file `slot`'s digest for `window` is written to. */
  def digest(slot: String, window: SessionDigest.Window): Path = window match {
    case SessionDigest.Window.HoldsLastSaid => named(slot, ".digest.md")
    case SessionDigest.Window.LacksLastSaid => named(slot, ".digest-said.md")
  }

  /** Writes `text` as `slot`'s brief, creating the directory if need be; or why not, writing
    * nothing.
    */
  def write(slot: String, text: String): Either[String, Unit] = replace(file(slot), text)

  /** Writes `of` each window as `slot`'s digest for it, when `slot` has a brief file: whether it
    * had; or why not, when a digest could not be written. A brief released meanwhile may be left
    * with its digests, until the next release or claim of its slot.
    */
  def writeDigests(slot: String, of: SessionDigest.Window => String): Either[String, Boolean] =
    if (!Files.exists(file(slot))) Right(false)
    else
      SessionDigest.Window.values.toVector
        .foldLeft[Either[String, Unit]](Right(()))((done, w) =>
          done.flatMap(_ => replace(digest(slot, w), of(w)))
        )
        .map(_ => true)

  /** Deletes `slot`'s brief and its digests, those there are; or why not. */
  def delete(slot: String): Either[String, Unit] =
    (file(slot) +: SessionDigest.Window.values.toVector.map(digest(slot, _)))
      .foldLeft[Either[String, Unit]](Right(())) { (done, path) =>
        done.flatMap(_ =>
          scala.util
            .Try(Files.deleteIfExists(path))
            .toEither
            .map(_ => ())
            .left
            .map(e => s"cannot delete $path: $e")
        )
      }

  private def named(slot: String, suffix: String): Path =
    dir.resolve(s"${slot.replace("/", "__")}$suffix")

  /** `text` written to `path`, creating the directory if need be: to a file of its own beside it
    * first, then moved over `path` at once. Or why not, writing nothing.
    */
  private def replace(path: Path, text: String): Either[String, Unit] =
    scala.util
      .Try {
        Files.createDirectories(dir)
        val temporary = Files.createTempFile(dir, ".brief-", ".tmp")
        try {
          Files.writeString(temporary, text, StandardCharsets.UTF_8)
          Files.move(
            temporary,
            path,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
          )
          ()
        } finally Files.deleteIfExists(temporary)
      }
      .toEither
      .left
      .map(e => s"cannot write $path: $e")
}

object BriefFiles {

  /** 2,500: the most characters a digest file holds. It is its own hook's output, so a digest
    * costs a compacted or cleared window this much at most beside its brief.
    */
  val DigestChars: Int = 2500

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
