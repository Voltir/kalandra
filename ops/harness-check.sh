#!/usr/bin/env bash
# The one check that calls a model, run by hand, never by a deploy or the proof: a real Claude
# Code turn (`claude -p`, Haiku, 3 turns at most) calls a kalandra tool through the MCP entry the
# join writes, with its headersHelper. Run it when the join script changes, or when `claude
# --version` is not the one it last passed under; ops/edge-proof.sh prints a line saying so.
#
#   ops/harness-check.sh
#
# Run from a checkout after `bin/kalandra build`. It serves that jar on an empty database,
# grit_kalandra_harness, at 127.0.0.1:7318, with credentials of its own and keys that are no keys
# (serve calls no model here); keeps a project, harness-check, holding one milestone; joins a
# throwaway repository with the join script serve serves, through the installed claude with a
# HOME of its own; then runs `claude -p`, with the real HOME so that it is logged in, its MCP
# config the joined entry pointed at a forwarder on 127.0.0.1:7319 (ops/forward.py), which checks
# each request bore the sessions' credential, by its digest, never logging its value.
#
# Costs about a third of a cent, which it prints as Claude Code reports it. On a pass it records
# the claude and the join script it passed under in
# ${XDG_STATE_HOME:-~/.local/state}/kalandra-harness-check/passed.txt, which the proof reads.
# Refused, exit 1, before anything is started: something listens on 7318 or 7319,
# grit_kalandra_harness exists, there is no jar or no claude. Exit 0 and `harness-check: passed`
# only when every check held. It stops what it started by its pid, only while that pid runs in
# its own directory, drops the database and deletes the transcript directory the run makes under
# ~/.claude/projects/, however it ends.
set -euo pipefail

wt="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$wt/out/assembly.dest/out.jar"
compose="${GRIT_COMPOSE_DIR:-$HOME/Projects/grit}"
record="${XDG_STATE_HOME:-$HOME/.local/state}/kalandra-harness-check/passed.txt"
db=grit_kalandra_harness
listen=127.0.0.1:7318
forward=127.0.0.1:7319
edge="http://$listen"
[[ $# == 0 ]] || { echo "usage: ops/harness-check.sh" >&2; exit 64; }

pg() { docker compose --project-directory "$compose" exec -T postgres "$@"; }
listening() { ss -Hltn "sport = :$1" 2>/dev/null | grep -q .; }
refuse() { echo "harness-check: $*" >&2; exit 1; }
unset GRIT_EDGE GRIT_HOOK_STARTED GRIT_HOOK_SELF CLAUDE_PROJECT_DIR CLAUDE_CODE_SESSION_ID \
  KALANDRA_CREDENTIAL GRIT_DATABASE_URL http_proxy HTTP_PROXY https_proxy HTTPS_PROXY all_proxy ALL_PROXY
[[ -f "$jar" ]] || refuse "no jar at $jar: run bin/kalandra build first"
command -v claude >/dev/null || refuse "claude is not on PATH"
listening 7318 && refuse "something listens on 7318"
listening 7319 && refuse "something listens on 7319"
[[ -z "$(pg psql -U grit -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname = '$db'")" ]] ||
  refuse "$db exists: a check may be running"

work="$(cd "$(mktemp -d)" && pwd -P)"
mkdir -p "$work/run" "$work/home" "$work/jhome" "$work/cred"
started=()
made_db=0
transcripts=""
ours() { [[ "$(readlink "/proc/$1/cwd" 2>/dev/null)" == "$work"/* ]]; }
stop() {
  ours "$1" || return 0
  kill "$1" 2>/dev/null || true
  for _ in $(seq 1 60); do ours "$1" || break; sleep 0.5; done
  if ours "$1"; then kill -9 "$1" 2>/dev/null || true; fi
  wait "$1" 2>/dev/null || true
}
cleanup() {
  local pid
  for pid in "${started[@]}"; do stop "$pid"; done
  if [[ "$made_db" == 1 ]]; then pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS $db WITH (FORCE)" >/dev/null || true; fi
  if [[ -n "$transcripts" && -d "$transcripts" ]]; then rm -rf -- "$transcripts"; fi
  rm -rf -- "$work"
}
trap cleanup EXIT

failed=0
pass() { echo "ok    $*"; }
fail() { echo "FAIL  $*"; failed=$((failed + 1)); }
holds() { if [[ "$1" == 0 ]]; then pass "$2"; else fail "$2"; fi; }
sha() { sha256sum <"$1" | cut -d' ' -f1; }
d() { tr -d '\n' <"$1" | sha256sum | cut -d' ' -f1; }
installed="$(claude --version | head -n 1)"
echo "harness-check: $(date -u +%Y-%m-%dT%H:%M:%SZ), checkout $wt, claude $installed"

# ---- Serve on an empty database, and what the tool call will list.
( umask 077
  for c in owner sessions; do head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' >"$work/cred/$c"; done )
ses="$work/cred/sessions"
pg psql -U grit -d postgres -q -c "CREATE DATABASE $db" >/dev/null
made_db=1
(cd "$work/run" && exec env -i PATH=/usr/bin:/bin HOME="$work/home" KALANDRA_CHECKOUT="$HOME/Projects/grit" \
  GRIT_DATABASE_URL="jdbc:postgresql://localhost:5432/$db" KALANDRA_LISTEN="$listen" \
  ANTHROPIC_API_KEY=harness-check-no-key JEV_API_KEY=harness-check-no-key \
  KALANDRA_OWNER_DIGEST="$(d "$work/cred/owner")" KALANDRA_SESSIONS_DIGEST="$(d "$ses")" \
  "$wt/bin/kalandra" serve) >"$work/serve.log" 2>&1 &
started+=("$!")
up=1
for _ in $(seq 1 240); do
  if grep -q "commands: serving on $edge" "$work/serve.log"; then up=0; break; fi
  kill -0 "${started[0]}" 2>/dev/null || break
  sleep 0.5
done
holds "$up" "the jar serves an empty database on $edge"
if [[ "$up" != 0 ]]; then tail -20 "$work/serve.log" >&2; exit 1; fi
set +e
K() { env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$work/cred/owner" "$wt/bin/kalandra" "$@" >/dev/null; }
K new '{"project":"harness-check"}' &&
  K keep '{"key":"harness-check/m","kind":"milestone","title":"Harness check","body":"Kept by ops/harness-check.sh."}'
holds $? "the project harness-check is kept, holding one milestone"

# ---- The join, through the installed claude, in a repository of its own.
curl -q -fsS --noproxy '*' --max-time 10 -H @<(printf 'Authorization: Bearer %s\n' "$(<"$ses")") "$edge/join/claude-code" >"$work/join.sh"
holds $? "serve serves its join script"
repo="$work/repo"
git init -q "$repo" && git -C "$repo" -c user.name=check -c user.email=check@example.invalid commit -q --allow-empty -m 'harness check'
real_claude="$(dirname "$(command -v claude)")"
rc=0; (cd "$repo" && HOME="$work/jhome" PATH="$real_claude:/usr/bin:/bin" bash -s -- "$edge" --credential "$ses" --mcp-name kalandra <"$work/join.sh") >"$work/join.out" 2>&1 || rc=$?
holds "$rc" "the join exits 0 through the installed claude"
entry="$(jq -c --arg p "$repo" '.projects[$p].mcpServers.kalandra // null' "$work/jhome/.claude.json" 2>/dev/null)"
[[ "$(jq -r '.headersHelper // empty' <<<"$entry")" == "'$repo/.git/grit/claude-code/hook' header" ]]
holds $? "  its MCP entry has the hook's header as its headersHelper"

# ---- The turn: its MCP client through the forwarder, which checks each request's credential.
(cd "$work/run" && exec python3 -I "$wt/ops/forward.py" 7319 7318 "$work/mcp.jsonl" "$(d "$ses")") >"$work/forward.log" 2>&1 &
started+=("$!")
for _ in $(seq 1 20); do listening 7319 && break; sleep 0.25; done
jq -n --argjson e "$entry" --arg u "http://$forward/mcp" '{mcpServers: {kalandra: ($e + {url: $u})}}' >"$work/mcp-config.json"
transcripts="$HOME/.claude/projects/$(sed 's/[^A-Za-z0-9]/-/g' <<<"$repo")"
[[ ! -e "$transcripts" ]] || { fail "a transcript directory $transcripts was there before the run"; transcripts=""; }
rc=0
(cd "$repo" && timeout 180 claude -p 'Call the kalandra tool project_list with project harness-check, then say how many artifacts it lists.' \
  --model haiku --max-turns 3 --strict-mcp-config --mcp-config "$work/mcp-config.json" \
  --allowedTools mcp__kalandra__project_list --setting-sources project,local \
  --output-format json </dev/null) >"$work/claude.json" 2>"$work/claude.err" || rc=$?
holds "$rc" "claude -p ran with the join's MCP entry, through the forwarder"
cost="$(jq -r '.total_cost_usd // empty' "$work/claude.json" 2>/dev/null)"
cost="${cost:+$(awk -v c="$cost" 'BEGIN { printf "%.2f", c * 100 }')¢}"
echo "cost  claude -p: ${cost:-unknown} as Claude Code reports it, $(jq -r '"\(.num_turns // "?") turns, \(.usage.input_tokens // "?") in, \(.usage.output_tokens // "?") out"' "$work/claude.json" 2>/dev/null)"
echo "said  $(jq -r '.result // empty' "$work/claude.json" 2>/dev/null | head -c 200 | tr '\n' ' ')"
if [[ -s "$work/mcp.jsonl" ]]; then
  echo "mcp   in order (rpc status credential): $(jq -r '"\(.rpc) \(.status) \(.credential)"' "$work/mcp.jsonl" | tr '\n' ',' | sed 's/,$//')"
  jq -e -s 'length > 0 and all(.credential == "the sessions'"'"'")' "$work/mcp.jsonl" >/dev/null
  holds $? "every MCP request bore the sessions' credential"
  jq -e -s 'any(.rpc == "tools/call" and .status == 200)' "$work/mcp.jsonl" >/dev/null
  holds $? "tools/call answered 200"
else
  fail "the forwarder recorded no exchange"
fi

if [[ "$failed" == 0 ]]; then
  mkdir -p "$(dirname "$record")"
  printf 'claude %s\njoin %s\nat %s\ncost %s\n' "$installed" "$(sha "$work/join.sh")" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${cost:-unknown}" >"$record"
  echo "harness-check: passed, recorded in $record"
else
  echo "harness-check: $failed checks failed"
  tail -5 "$work/claude.err" >&2
  exit 1
fi
