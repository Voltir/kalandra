#!/usr/bin/env bash
# The Claude Code hook that spools a session's events for serve to record in grit's session
# record, where the Claude Code edge reads them (kalandra.capture.Ingest).
#
#   spool-hook.sh            as a SessionStart, PostToolUse, PostToolUseFailure, Stop, PostCompact,
#                            SessionEnd, SubagentStart or SubagentStop hook: the event's JSON on stdin
#   spool-hook.sh --commit   as a git post-commit hook: the commit just made, spooled for the
#                            Claude Code session whose Bash made it ($CLAUDE_CODE_SESSION_ID);
#                            nothing outside one, nor while a rebase is in progress
#   spool-hook.sh --rewrite rebase
#                            as a git post-rewrite hook: each commit a rebase left, once, in
#                            order, spooled as --commit spools one. `--rewrite amend` spools
#                            nothing: post-commit already spooled the amended commit.
#
# A commit spooled before a rebase or an amend rewrote it stays as spooled: the old hash names a
# commit a reader may still `git show`, and the record is append-only.
#
# Each event is one line appended with one printf to
# ${KALANDRA_SPOOL:-$HOME/.local/state/kalandra/spool}/<session_id>.jsonl:
#
#   {"s":session,"a":agent (a subagent's only),"n":ordinal,"k":kind,"w":worktree,"d":shape,"t":text,
#    "h":slot (a start's only)}
#
# - `k` is the harness's own name: the event's, or for a tool event the tool's (Read, Edit, Write,
#   MultiEdit, NotebookEdit, Bash, and scout's MCP tools, mcp__scout__*; any other tool spools
#   nothing); `commit` for --commit. serve maps it to grit's words, a scout query to a read.
# - `n` is the time in microseconds times 1000 plus this process's id modulo 1000, written as a
#   string because it passes the integers a JSON reader's double holds exactly: grit knows an
#   event recorded again by it, so two events of one session given the same `n` (two hooks in
#   one microsecond whose process ids agree modulo 1000) keep only one, and the other is dropped
#   silently.
# - `w` is the worktree the event ran in: `git rev-parse --show-toplevel` in its cwd, else the cwd.
# - `d` is what the event was, never its content: a path relative to the cwd (outside it, its
#   file name alone), whether the tool succeeded, a Read's bytes, a Bash command's program alone;
#   for a scout query, what it `named` (the first of its symbols, or its suite, target or pattern,
#   cut to 120 characters), its `tool`, and its answer's UTF-8 `bytes` (0 for none).
# - `h`, on a SessionStart or SubagentStart only, is the slot the session works as, hooks/slot.sh's
#   for the event's own cwd (not `w`) and, in a subagent, its agent type: the brief hook's slot,
#   whose claims the session's work keeps from lapsing. Without slot.sh, no line has one.
# - `t` is the text of a Stop (its last reply) or a PostCompact (its summary), cut to 2,000
#   characters; a Stop with none spools nothing. Such a line can pass 4 KB, past which an append
#   is no longer one write, so one that meets a concurrent append can be torn; serve drops a line
#   it cannot read, and says so.
#
# Bounds: a session's file past 2 MB takes no more lines (grit logs no more than 5,000 events a
# session), and each SessionStart keeps the spool's newest 100 session files, so that while serve
# is down the spool stays under about 200 MB.
#
# It needs bash 5 (EPOCHREALTIME), jq, git, stat, timeout and coreutils; never a JVM or the
# network. Any failure (stdin that is no hook event, an id no file name may be, a tool missing)
# exits 0 and spools nothing, so the session runs as it would without the hook.

spool="${KALANDRA_SPOOL:-${HOME:-}/.local/state/kalandra/spool}"

# slot(): the slot for a directory, and an agent type in it.
. "$(dirname -- "${BASH_SOURCE[0]}")/slot.sh" 2>/dev/null
cap=$((2 * 1024 * 1024))
keep=100

# The worktree the directory $1 is in, else $1.
where() {
  git -C "$1" rev-parse --show-toplevel 2>/dev/null || printf '%s\n' "$1"
}

# This event's ordinal: microseconds, times 1000, plus the process id modulo 1000.
ordinal() {
  local us="${EPOCHREALTIME//[!0-9]/}"
  [[ -n "$us" ]] || return 1
  printf '%s\n' "$((10#$us * 1000 + BASHPID % 1000))"
}

# Whether $1 may name a spool file: letters, digits, `.`, `_` and `-`, not leading with `.`.
named() {
  [[ "$1" =~ ^[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}$ ]]
}

# The line $2 appended to session $1's file, unless the file is past the cap.
append() {
  local file="$spool/$1.jsonl" size
  mkdir -p -- "$spool" || return 1
  if [[ -e "$file" ]]; then
    size="$(stat -c %s -- "$file")" || return 1
    ((size > cap)) && return 0
  fi
  printf '%s\n' "$2" >>"$file"
}

# The spool's session files past the newest $keep deleted.
prune() {
  local old
  old="$(cd -- "$spool" && ls -t -- *.jsonl 2>/dev/null | tail -n +$((keep + 1)))" || return 0
  [[ -n "$old" ]] || return 0
  (cd -- "$spool" && xargs -r rm -f -- <<<"$old")
}

# The commit $2 spooled for session $1.
commit() {
  local subject n line
  subject="$(git log -1 --format=%s "$2")" || return 1
  n="$(ordinal)" || return 1
  line="$(jq -cn --arg s "$1" --arg n "$n" --arg w "$(where "$PWD")" \
    --arg sha "$2" --arg subject "$subject" \
    '{s: $s, n: $n, k: "commit", w: $w, d: {sha: $sha, subject: $subject[:100]}}')" || return 1
  append "$1" "$line"
}

# Whether a rebase is in progress in this worktree.
rebasing() {
  local merge apply
  merge="$(git rev-parse --git-path rebase-merge)" && apply="$(git rev-parse --git-path rebase-apply)" &&
    [[ -d "$merge" || -d "$apply" ]]
}

if [[ "${1-}" == "--commit" ]]; then
  {
    session="${CLAUDE_CODE_SESSION_ID-}"
    named "$session" || exit 0
    rebasing && exit 0
    sha="$(git rev-parse HEAD)" || exit 0
    commit "$session" "$sha"
  } 2>/dev/null
  exit 0
fi

if [[ "${1-}" == "--rewrite" ]]; then
  {
    session="${CLAUDE_CODE_SESSION_ID-}"
    named "$session" || exit 0
    [[ "${2-}" == "rebase" ]] || exit 0
    # Each line is `<old sha> <new sha> [extra]`; a squash or fixup maps several to one new sha.
    declare -A seen=()
    while read -r _ sha _; do
      [[ "$sha" =~ ^[0-9a-f]{40,64}$ && -z "${seen[$sha]-}" ]] || continue
      seen[$sha]=1
      commit "$session" "$sha" || exit 0
    done < <(timeout 1 cat)
  } 2>/dev/null
  exit 0
fi

{
  input="$(timeout 1 cat)" || exit 0
  # One per line: tab is whitespace to `read`, so an empty agent id would shift the cwd.
  ids="$(jq -r '(.session_id // "" | tostring), (.agent_id // "" | tostring), (.cwd // "" | tostring),
    (.hook_event_name // "" | tostring), (.agent_type // "" | tostring)' <<<"$input")" || exit 0
  mapfile -t ids <<<"$ids"
  session="${ids[0]-}" agent="${ids[1]-}" cwd="${ids[2]-}" event="${ids[3]-}" type="${ids[4]-}"
  named "$session" || exit 0
  [[ -z "$agent" ]] || named "$agent" || exit 0
  [[ -n "$cwd" ]] || exit 0
  n="$(ordinal)" || exit 0
  h=""
  if declare -F slot >/dev/null; then
    case "$event" in
      SessionStart) h="$(slot "$cwd")" || h="" ;;
      SubagentStart) [[ -z "$type" ]] || h="$(slot "$cwd" "$type")" || h="" ;;
    esac
  fi
  line="$(jq -c --arg n "$n" --arg w "$(where "$cwd")" --arg h "$h" '
    def cut($k): if type == "string" then .[:$k] else "" end;
    def rel($cwd):
      if type != "string" then ""
      elif startswith($cwd + "/") then .[($cwd | length) + 1:]
      else split("/") | last
      end | .[:120];
    def answer:
      if type == "string" then .
      elif type == "array" then map(select(type == "object") | .text | select(type == "string")) | join("")
      elif type == "object" and (.content | type) == "array" then .content | answer
      else ""
      end;
    def program:
      cut(400) | split(" ") | map(select(length > 0 and (contains("=") | not)))
      | (.[0] // "") | split("/") | last | .[:40];
    .hook_event_name as $e
    | (if $e == "PostToolUse" or $e == "PostToolUseFailure" then .tool_name else $e end) as $k
    | ($e != "PostToolUseFailure") as $ok
    | .cwd as $cwd
    | (if $k == "SessionStart" then {source: (.source | cut(20))}
       elif $k == "SubagentStart" or $k == "SubagentStop" then {type: (.agent_type | cut(60))}
       elif $k == "Read" then
         {path: (.tool_input.file_path | rel($cwd)), ok: $ok,
          bytes: (.tool_response.file.content // "" | if type == "string" then utf8bytelength else 0 end)}
       elif $k == "Edit" or $k == "Write" or $k == "MultiEdit" or $k == "NotebookEdit" then
         {path: ((.tool_input.file_path // .tool_input.notebook_path) | rel($cwd)), ok: $ok}
       elif $k == "Bash" then {program: (.tool_input.command | program), ok: $ok}
       elif ($k | type) == "string" and ($k | startswith("mcp__scout__")) then
         {named: (.tool_input
                  | [(.symbols | if type == "array" then .[0] else null end), .suite, .target, .pattern]
                  | map(select(type == "string")) | (.[0] // "") | .[:120]),
          tool: ($k | ltrimstr("mcp__scout__") | .[:40]), ok: $ok,
          bytes: (.tool_response | answer | utf8bytelength)}
       elif $k == "Stop" then {}
       elif $k == "PostCompact" then {trigger: (.trigger | cut(20))}
       elif $k == "SessionEnd" then {reason: (.reason | cut(40))}
       else null
       end) as $d
    | (if $k == "Stop" then .last_assistant_message | cut(2000)
       elif $k == "PostCompact" then .compact_summary | cut(2000)
       else null
       end) as $t
    | select($d != null and $t != "")
    | {s: .session_id}
      + (if (.agent_id // "") != "" then {a: .agent_id} else {} end)
      + {n: $n, k: $k, w: $w, d: $d}
      + (if $t != null then {t: $t} else {} end)
      + (if $h != "" then {h: $h} else {} end)
  ' <<<"$input")" || exit 0
  [[ -n "$line" ]] || exit 0
  append "$session" "$line" || exit 0
  [[ "$event" == "SessionStart" ]] && prune
} 2>/dev/null
exit 0
