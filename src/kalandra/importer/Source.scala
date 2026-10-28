package kalandra.importer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A markdown file of grit's planning tree: its path under the checkout (`roadmap/ROADMAP.md`),
  * and its text.
  */
final case class Source(path: String, text: String) extends caps.Pure {

  /** Its text's SHA-256, 64 lowercase hex digits. */
  def sha256: String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString
}

/** Where an artifact's text came from: lines `[from, until)` of each range of `source` (counted
  * from 0, split at `\n`), joined with a blank line between ranges, as `original`; and `text`,
  * `original` with each markdown link to an imported file pointed at its artifact.
  */
final case class Provenance(
    source: String,
    lines: Vector[(Int, Int)],
    original: String,
    text: String
) extends caps.Pure
