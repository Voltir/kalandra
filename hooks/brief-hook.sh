#!/usr/bin/env bash
# The Claude Code hook that puts a claimed brief in a session's or a subagent's context.
#
#   brief-hook.sh                  as a SessionStart or SubagentStart hook: the event's JSON on
#                                  stdin; prints the hook JSON whose additionalContext is the brief
#   brief-hook.sh --slot [type]    the slot a claim's `for` names for this directory: the
#                                  session's, or the subagent type's
#
# The slot is hooks/slot.sh's, for the event's cwd and, in a subagent, its agent type. Its brief
# is ${KALANDRA_BRIEFS:-$HOME/.local/state/kalandra/briefs}/<slot, each / written __>.md, the file
# the edge writes when a claim for that slot is done.
#
# It needs bash, jq, sha1sum and timeout; never a JVM or the network. Any failure (no such file,
# a read over a second, stdin that is no hook event, a tool missing) exits 0 and prints nothing,
# so the session starts as it would without the hook.

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
  # The `x` keeps the file's trailing newlines, which $(...) would drop.
  brief="$(timeout 1 cat -- "$briefs/${name//\//__}.md" && printf x)" || exit 0
  brief="${brief%x}"
  [[ -n "$brief" ]] || exit 0
  jq -n --arg e "$event" --arg c "$brief" \
    '{hookSpecificOutput: {hookEventName: $e, additionalContext: $c}}'
} 2>/dev/null
exit 0
