#!/usr/bin/env bash
# The Claude Code hook that puts a claimed brief in a session's or a subagent's context.
#
#   brief-hook.sh                  as a SessionStart or SubagentStart hook: the event's JSON on
#                                  stdin; prints the hook JSON whose additionalContext is the brief
#   brief-hook.sh --digest         as a second SessionStart hook: the same, its additionalContext
#                                  the slot's digest instead
#   brief-hook.sh --slot [type]    the slot a claim's `for` names for this directory: the
#                                  session's, or the subagent type's
#
# The slot is hooks/slot.sh's, for the event's cwd and, in a subagent, its agent type. Its brief
# is ${KALANDRA_BRIEFS:-$HOME/.local/state/kalandra/briefs}/<slot, each / written __>.md, the file
# the edge writes when a claim for that slot is done (kalandra.edge.BriefFiles).
#
# A window is given its brief at each of its boundaries. With --digest, a SessionStart whose
# source is `compact` gets the slot's digest that leaves out what the session last said, which
# the compacted window holds (<slot>.digest.md), and one whose source is `clear` the digest that
# shows it (<slot>.digest-said.md): the session's work, which the new window lacks, from the
# edge's last pass over the spool. Any other source, and a subagent, get nothing from it. A digest
# last written over a day ago is old work, and is left out. The digest is its own hook's output
# because Claude Code caps each hook's additionalContext at 10,000 characters on its own, and
# injects each hook's beside the others'.
#
# It needs bash, jq, sha1sum, find and timeout; never a JVM or the network. Any failure (no such
# file, a read over a second, stdin that is no hook event, a tool missing) exits 0 and prints
# nothing, so the session starts as it would without the hook.

briefs="${KALANDRA_BRIEFS:-${HOME:-}/.local/state/kalandra/briefs}"

# slot(): the slot for a directory, and an agent type in it.
. "$(dirname -- "${BASH_SOURCE[0]}")/slot.sh" 2>/dev/null || exit 0

if [[ "${1-}" == "--slot" ]]; then
  slot "$PWD" "${2-}"
  exit $?
fi

{
  input="$(timeout 1 cat)" || exit 0
  event="$(jq -r '.hook_event_name // empty' <<<"$input")" || exit 0
  cwd="$(jq -r '.cwd // empty' <<<"$input")" || exit 0
  [[ -n "$cwd" ]] || exit 0
  case "$event" in
    SessionStart) type="" ;;
    SubagentStart)
      type="$(jq -r '.agent_type // empty' <<<"$input")" || exit 0
      [[ -n "$type" ]] || exit 0
      ;;
    *) exit 0 ;;
  esac
  name="$(slot "$cwd" "$type")" || exit 0
  file="$briefs/${name//\//__}.md"
  if [[ "${1-}" == "--digest" ]]; then
    [[ "$event" == SessionStart ]] || exit 0
    case "$(jq -r '.source // empty' <<<"$input")" in
      compact) file="$briefs/${name//\//__}.digest.md" ;;
      clear) file="$briefs/${name//\//__}.digest-said.md" ;;
      *) exit 0 ;;
    esac
    [[ -n "$(find "$file" -maxdepth 0 -mmin -1440 2>/dev/null)" ]] || exit 0
  fi
  # The `x` keeps the file's trailing newlines, which $(...) would drop.
  text="$(timeout 1 cat -- "$file" && printf x)" || exit 0
  text="${text%x}"
  [[ -n "$text" ]] || exit 0
  jq -n --arg e "$event" --arg c "$text" \
    '{hookSpecificOutput: {hookEventName: $e, additionalContext: $c}}'
} 2>/dev/null
exit 0
