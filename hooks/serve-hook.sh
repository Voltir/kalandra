#!/usr/bin/env bash
# The Claude Code SessionStart hook that starts kalandra's serve when none is running, so a
# session's MCP tools find a server.
#
#   serve-hook.sh    as a SessionStart hook: the event's JSON on stdin (unread); prints the hook
#                    JSON whose additionalContext says what it did, or nothing when serve runs
#
# Claude Code connects an HTTP MCP server at session start, retries it three times, then marks it
# failed until `/mcp` reconnects it; neither the model nor a hook can reconnect it. serve takes
# longer to answer than a hook may run (`bin/kalandra start` waits up to 120 s), so the hook
# starts it detached and returns at once: a session whose first connect came before serve
# answered must ask for `/mcp`, and the context says so. serve running but not answering is said
# too, with the command that fixes it; the hook never stops or restarts it.
#
# It needs bash, jq and timeout. Any other failure exits 0 and prints nothing.

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)" || exit 0
kalandra="$root/bin/kalandra"

say() {
  jq -n --arg c "$1" '{hookSpecificOutput: {hookEventName: "SessionStart", additionalContext: $c}}'
}

{
  timeout 1 cat > /dev/null
  status="$(timeout 3 "$kalandra" status)" || exit 0
  case "$status" in
    *"commands answer at"*) exit 0 ;;
    *"serve pids: none"*)
      ( setsid nohup "$kalandra" start > /dev/null 2>&1 < /dev/null & )
      say "kalandra's serve was not running; this session's start hook started it ($kalandra start, log in $root/logs/serve.log). It answers within about a minute. If the kalandra MCP tools fail, ask the user to run /mcp to reconnect them; meanwhile bin/kalandra <command> '<json>' works once serve answers."
      ;;
    *)
      say "kalandra's serve is running but does not answer ($kalandra status). Its MCP tools will fail: run $kalandra restart, then ask the user to run /mcp."
      ;;
  esac
} 2>/dev/null
exit 0
