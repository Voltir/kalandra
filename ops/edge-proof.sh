#!/usr/bin/env bash
# The proof, before the switch, that this checkout's serve works through grit's commands edge:
# it serves a restored copy of grit_kalandra on a second port and drives it the way sessions,
# agents and ops scripts will, then stops what it started and drops the copy.
#
#   ops/edge-proof.sh [--record-mcp]
#
# Run from this checkout after `./mill show assembly`. It reads grit_kalandra once (a pg_dump, as
# ops/backup.sh does) and never writes it; it restores the dump into grit_kalandra_restore, where
# it cancels every pending or enqueued DBOS workflow so nothing resumes, and serves that copy on
# 127.0.0.1:7318 from this checkout's jar, with keys that are no keys, so nothing it serves can
# spend, and with an empty HOME, which must stay empty: serve writes no files.
#
# Into the proof's directory, ${KALANDRA_PROOF_DIR:-$HOME/.cache/kalandra-e4c}, it saves the jar
# it proved (serve.jar), its sha256 (serve.jar.sha256), the join script that jar serves (join.sh)
# and its sha256, and each check's line (proof.txt): the switch installs that exact jar.
#
# With --record-mcp it also runs Claude Code once (`claude -p`, Haiku, 3 turns at most) in the
# temporary repository, its MCP client pointed by a temporary --strict-mcp-config file at a
# logging forwarder on 127.0.0.1:7319, and saves the exchange, headers and JSON-RPC bodies with no
# credential, as mcp.jsonl. It needs the real HOME to log in: the transcript directory that run
# makes under ~/.claude/projects/ is deleted, and the entry it leaves in ~/.claude.json is named,
# not edited. No `claude mcp add` is run.
#
# Refused, exit 1, before anything is started: grit_kalandra_restore exists (a restore test may
# be running), something listens on 7318 or 7319, KALANDRA_LISTEN names 7317, or there is no jar.
# Prints a line a check, and `edge-proof: passed`, exit 0, only when every check held. Every
# process it starts is stopped by its PID, and the copy dropped, however it ends.
set -euo pipefail

wt="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$wt/out/assembly.dest/out.jar"
compose="${GRIT_COMPOSE_DIR:-$HOME/Projects/grit}"
grit_checkout="${KALANDRA_CHECKOUT:-$HOME/Projects/grit}"
proof="${KALANDRA_PROOF_DIR:-$HOME/.cache/kalandra-e4c}"
restored=grit_kalandra_restore
listen=127.0.0.1:7318
forward=127.0.0.1:7319
edge="http://$listen"
record=0
for a in "$@"; do
  case "$a" in
    --record-mcp) record=1 ;;
    *) echo "usage: ops/edge-proof.sh [--record-mcp]" >&2; exit 64 ;;
  esac
done

pg() { docker compose --project-directory "$compose" exec -T postgres "$@"; }
sql() { pg psql -U grit -d "$restored" -At -v ON_ERROR_STOP=1 -c "$1"; }
K() { env KALANDRA_LISTEN="$listen" "$wt/bin/kalandra" "$@"; }
listening() { ss -Hltn "sport = :$1" 2>/dev/null | grep -q .; }

# ---- 0. Refusals, before anything is started.
refuse() { echo "edge-proof: $*" >&2; exit 1; }
[[ "${KALANDRA_LISTEN:-}" != *:7317 ]] || refuse "KALANDRA_LISTEN names 7317, the live serve's port"
[[ -f "$jar" ]] || refuse "no jar at $jar: run ./mill show assembly first"
if [[ -n "$(pg psql -U grit -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname = '$restored'")" ]]; then
  refuse "$restored exists: a restore test may be running"
fi
listening 7318 && refuse "something listens on 7318"
listening 7319 && refuse "something listens on 7319"

work="$(mktemp -d)"
started=()
copied=0
transcripts=""
claude_projects_before=""

cleanup() {
  local pid
  for pid in "${started[@]}"; do
    # Only a process this script started, still in this checkout or the work directory.
    if [[ -d "/proc/$pid" ]]; then
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
    fi
  done
  if [[ "$copied" == 1 ]]; then
    pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS $restored WITH (FORCE)" >/dev/null || true
  fi
  if [[ -n "$transcripts" && -d "$transcripts" ]]; then rm -rf -- "$transcripts"; fi
  rm -rf -- "$work"
}
trap cleanup EXIT
touch "$work/start"
sleep 1

mkdir -p "$proof"
: >"$proof/proof.txt"
failed=0
say() { echo "$*" | tee -a "$proof/proof.txt"; }
pass() { say "ok    $*"; }
fail() { say "FAIL  $*"; failed=$((failed + 1)); }
holds() { if [[ "$1" == 0 ]]; then pass "$2"; else fail "$2"; fi; }
# expect <exit wanted> <name> <command…>: the command's exit, its output in $out.
expect() {
  local want="$1" name="$2" rc=0
  shift 2
  out="$("$@" 2>&1)" || rc=$?
  if [[ "$rc" == "$want" ]]; then pass "$name (exit $rc)"; else fail "$name (exit $rc, wanted $want): $(head -c 300 <<<"$out")"; fi
}

say "edge-proof: $(date -u +%Y-%m-%dT%H:%M:%SZ), checkout $wt at $(git -C "$wt" rev-parse --short HEAD)$(git -C "$wt" diff --quiet HEAD -- || echo ' with changes')"
cp "$jar" "$proof/serve.jar"
(cd "$proof" && sha256sum serve.jar >serve.jar.sha256)
say "jar   $(cut -d' ' -f1 "$proof/serve.jar.sha256")  serve.jar"

# ---- 1. The copy, its pending workflows cancelled.
pg pg_dump -U grit -Fc grit_kalandra >"$work/live.dump"
pg pg_restore --list <"$work/live.dump" >/dev/null
pg psql -U grit -d postgres -q -c "CREATE DATABASE $restored" >/dev/null
copied=1
pg pg_restore -U grit -d "$restored" --no-owner --exit-on-error <"$work/live.dump"
pending="$(sql "WITH c AS (UPDATE dbos.workflow_status SET status = 'CANCELLED' WHERE status IN ('PENDING', 'ENQUEUED') RETURNING 1) SELECT count(*) FROM c")"
say "copy  $restored restored from one dump of grit_kalandra; $pending pending or enqueued workflows cancelled there"

# ---- 2. No stored row names the old edge.
edge_counts="$(pg psql -U grit -d "$restored" -At -v ON_ERROR_STOP=1 <<'SQL'
SELECT format('SELECT %L || '' '' || count(*) FROM %I.%I WHERE %I::text = ''claude-code''',
              table_name || '.' || column_name, table_schema, table_name, column_name)
FROM information_schema.columns
WHERE table_schema = 'grit' AND column_name LIKE '%edge%'
  AND data_type IN ('text', 'character varying')
ORDER BY 1
\gexec
SQL
)"
say "edge  columns naming an edge, rows holding claude-code: $(tr '\n' ',' <<<"$edge_counts" | sed 's/,$//')"
if awk '$2 != 0 { bad = 1 } END { exit bad }' <<<"$edge_counts"; then
  pass "no stored row names the edge claude-code"
else
  fail "a stored row names the edge claude-code"
fi

# ---- 3. serve on the copy, with no keys and an empty HOME.
mkdir -p "$work/home"
env -i PATH=/usr/bin:/bin HOME="$work/home" KALANDRA_CHECKOUT="$grit_checkout" \
  GRIT_DATABASE_URL="jdbc:postgresql://localhost:5432/$restored" KALANDRA_LISTEN="$listen" \
  ANTHROPIC_API_KEY=edge-proof-no-key JEV_API_KEY=edge-proof-no-key \
  "$wt/bin/kalandra" serve >"$work/serve.log" 2>&1 &
started+=("$!")
serve_pid="$!"
up=1
for _ in $(seq 1 240); do
  if grep -q "commands: serving on http://$listen" "$work/serve.log"; then up=0; break; fi
  kill -0 "$serve_pid" 2>/dev/null || break
  sleep 0.5
done
holds "$up" "serve logs commands: serving on http://$listen"
if [[ "$up" != 0 ]]; then
  tail -30 "$work/serve.log" >&2
  exit 1
fi

# ---- 4. bin/kalandra against it. From here a failed check is counted, not an exit.
set +e
expect 0 "list grit's now" K list '{"project":"grit","state":"now"}'
expect 0 "show grit/plan-harness-edge" K show '{"key":"grit/plan-harness-edge"}'
rc=0
K standing '{"key":"grit/roadmap-grit-coder"}' >"$work/standing.md" 2>"$work/standing.err" || rc=$?
grep -q '^grit/roadmap-grit-coder at version [0-9]\+$' "$work/standing.err" || rc=1
holds "$rc" "standing of grit/roadmap-grit-coder, its version on stderr"
expect 0 "new project edge-proof" K new '{"project":"edge-proof"}'
expect 0 "keep milestone edge-proof/m" K keep '{"key":"edge-proof/m","kind":"milestone","title":"Edge proof","body":"Kept by ops/edge-proof.sh in a restored copy."}'
expect 0 "keep note edge-proof/c" K keep '{"key":"edge-proof/c","kind":"note","title":"Cited note","body":"cited-text-1","parent":"edge-proof/m"}'
note() { printf '{"key":"edge-proof/n","kind":"note","title":"Proof note","body":"%s See [the cited](artifact:edge-proof/c).","parent":"edge-proof/m","cites":["edge-proof/c"]}' "$1"; }
expect 0 "keep note edge-proof/n citing edge-proof/c" K keep "$(note note-text-1)"
before="$(K show '{"key":"edge-proof/n"}')"
expect 1 "a move to adopted is refused" K move '{"key":"edge-proof/n","state":"adopted"}'
grep -q project_adopt <<<"$out"; holds $? "  and the refusal names project_adopt"
expect 1 "a batch with a step to adopted is refused" K batch '{"project":"edge-proof","steps":[{"command":"move","key":"edge-proof/n","state":"adopted"}]}'
grep -q project_adopt <<<"$out"; holds $? "  and the refusal names project_adopt"
[[ "$(K show '{"key":"edge-proof/n"}')" == "$before" ]]; holds $? "edge-proof/n unchanged after both"
state="$(sed -n 's/^state: //p' <<<"$before" | head -1)"
expect 0 "wait for edge-proof/n in $state" K wait "{\"artifact\":\"edge-proof/n\",\"state\":[\"$state\"],\"within\":5}"
expect 3 "wait for edge-proof/n in folded lapses" K wait '{"artifact":"edge-proof/n","state":["folded"],"within":2}'
expect 1 "a commit wait naming a slot is refused" K wait '{"commit":{"slot":"x"}}'
grep -q "a worktree or a holder" <<<"$out"; holds $? "  and says a commit names a worktree or a holder"
expect 1 "a wait with a key it does not read is refused" K wait '{"artifact":"edge-proof/n","state":["open"],"slot":"x"}'
grep -q "reads no slot" <<<"$out"; holds $? "  and names the key"
repo="$work/repo"
H="$(K holder "{\"dir\":\"$repo\"}")" || H=""
[[ "$H" =~ ^local/repo-[0-9a-f]{8}$ ]]; holds $? "holder for the repository is local/repo-<8 hex>: $H"
expect 64 "slot is gone" K slot
expect 0 "claim edge-proof/n as manager for $H" K claim "{\"key\":\"edge-proof/n\",\"role\":\"manager\",\"holder\":\"$H\"}"
brief() {
  curl -fsS --max-time 10 -G "$edge/brief" --data-urlencode harness=claude-code \
    --data-urlencode session=proof-s0 --data-urlencode "dir=$repo" --data-urlencode start=fresh "$@"
}
b="$(brief)" || b=""
grep -q "note-text-1" <<<"$b" && grep -q "cited-text-1" <<<"$b"; holds $? "/brief after the claim shows the note and what it cites"
expect 0 "keep edge-proof/n with a changed body" K keep "$(note note-text-2)"
b="$(brief)" || b=""
grep -q "note-text-2" <<<"$b" && ! grep -q "note-text-1" <<<"$b"; holds $? "/brief shows the note's new text"
expect 0 "keep only the cited edge-proof/c, changed" K keep '{"key":"edge-proof/c","kind":"note","title":"Cited note","body":"cited-text-2","parent":"edge-proof/m"}'
b="$(brief)" || b=""
grep -q "cited-text-2" <<<"$b" && ! grep -q "cited-text-1" <<<"$b"; holds $? "/brief shows the cited artifact's new text"
expect 0 "export edge-proof" K export edge-proof "$work/export"
n="$(find "$work/export" -type f | wc -l)"
[[ "$n" -ge 3 ]]; holds $? "  $n export files"

# ---- 5. The join, in a temporary repository with a temporary HOME and no claude on PATH.
git init -q "$repo"
git -C "$repo" -c user.name=proof -c user.email=proof@example.invalid commit -q --allow-empty -m 'edge proof start'
mkdir -p "$work/old-hooks" "$repo/.claude" "$work/jhome"
printf '#!/usr/bin/env bash\nexit 0\n' >"$work/old-hooks/spool-hook.sh"
chmod +x "$work/old-hooks/spool-hook.sh"
jq -n --arg c "$work/old-hooks/spool-hook.sh" \
  '{hooks: {Stop: [{hooks: [{type: "command", command: $c, timeout: 5}]}]}}' >"$repo/.claude/settings.local.json"
curl -fsS --max-time 10 "$edge/join/claude-code" >"$work/join.sh"
cp "$work/join.sh" "$proof/join.sh"
(cd "$proof" && sha256sum join.sh >join.sh.sha256)
say "join  $(cut -d' ' -f1 "$proof/join.sh.sha256")  join.sh"
rc=0
(cd "$repo" && HOME="$work/jhome" PATH=/usr/bin:/bin bash -s -- "$edge" --mcp-name kalandra \
  --retire "$work/old-hooks" <"$work/join.sh") >"$work/join.out" 2>&1 || rc=$?
holds "$rc" "the join exits 0"
[[ "$(grep -c '^  removed ' "$work/join.out")" == 1 ]]; holds $? "  one removed line, for the planted old handler"
hook="$repo/.git/grit/claude-code/hook"
handlers="$(jq --arg h "'$hook'" '[.hooks[][].hooks[] | select(.command | startswith($h))] | length' "$repo/.claude/settings.local.json")"
[[ "$handlers" == 11 ]]; holds $? "  the settings hold 11 handlers of the hook ($handlers)"
[[ -x "$hook" ]]; holds $? "  the hook is executable"
grep -q '# grit claude-code join' "$repo/.git/hooks/post-commit" && grep -q '# grit claude-code join' "$repo/.git/hooks/post-rewrite"
holds $? "  post-commit and post-rewrite carry the join's marker"
[[ ! -e "$work/jhome/.claude.json" ]]; holds $? "  no ~/.claude.json written (no claude on PATH)"

# ---- 6. A hook fired through the installed path, a brief, events, a commit under a wait.
payload() { jq -nc --arg s "$1" --arg cwd "$repo" --arg e "$2" '{session_id: $s, transcript_path: "/dev/null", cwd: $cwd, hook_event_name: $e}'; }
fire() { CLAUDE_PROJECT_DIR="$repo" PATH=/usr/bin:/bin "$hook" "$@"; }
start="$(payload proof-s1 SessionStart | jq -c '. + {source: "startup"}')"
ctx="$(fire brief <<<"$start" | jq -r '.hookSpecificOutput.additionalContext // empty')" || ctx=""
grep -q "Proof note" <<<"$ctx" && grep -q "note-text-2" <<<"$ctx"; holds $? "the hook's brief at SessionStart holds the claimed note as kept last"
op="$(fire opening <<<"$start" | jq -r '.hookSpecificOutput.additionalContext // empty')" || op=""
[[ "$(head -1 <<<"$op")" == *"$H"* ]]; holds $? "the hook's opening names the holder first"
rc=0; fire event <<<"$start" || rc=$?; holds "$rc" "the hook posts the start"
read="$(payload proof-s1 PostToolUse | jq -c --arg f "$repo/README" '. + {tool_name: "Read", tool_input: {file_path: $f}, tool_response: {type: "text", file: {filePath: $f, content: "hello\n", numLines: 1, startLine: 1, totalLines: 1}}}')"
rc=0; fire event <<<"$read" || rc=$?; holds "$rc" "the hook posts a read"
stop="$(payload proof-s1 Stop | jq -c '. + {last_assistant_message: "The proof session is done.", stop_hook_active: false}')"
rc=0; fire event <<<"$stop" || rc=$?; holds "$rc" "the hook posts a closing"
K wait "{\"commit\":{\"holder\":\"$H\"},\"within\":30}" >"$work/wait.out" 2>&1 &
wait_pid="$!"
started+=("$wait_pid")
sleep 2
CLAUDE_CODE_SESSION_ID=proof-s1 PATH=/usr/bin:/bin git -C "$repo" -c user.name=proof \
  -c user.email=proof@example.invalid commit -q --allow-empty -m 'edge proof'
sha="$(git -C "$repo" rev-parse HEAD)"
rc=0; wait "$wait_pid" || rc=$?
grep -q "^committed ${sha:0:12} edge proof" "$work/wait.out" || rc=1
holds "$rc" "the commit wait for $H is met by the commit"
counts="$(sql "SELECT s.holder, count(e.seq), string_agg(DISTINCT e.kind, ',' ORDER BY e.kind) FROM grit.sessions s JOIN grit.session_events e ON e.session = s.id WHERE s.harness = 'claude-code' AND s.local = 'proof-s1' GROUP BY s.holder")"
say "count proof-s1 in the copy: holder|events|kinds = $counts"
IFS='|' read -r sh se sk <<<"$counts"
[[ "$sh" == "$H" && "${se:-0}" -ge 4 && "$sk" == "closing,commit,read,started" ]]
holds $? "the session proof-s1 is recorded as $H with its start, read, closing and commit"
d="$(curl -fsS --max-time 10 -G "$edge/brief" --data-urlencode harness=claude-code --data-urlencode session=proof-s2 \
  --data-urlencode "dir=$repo" --data-urlencode start=fresh --data-urlencode part=digest)" || d=""
[[ -n "$d" ]] && grep -q "edge proof" <<<"$d"; holds $? "a later session's digest shows proof-s1's commit"
t0="$(date +%s%N)"
rc=0; down="$(GRIT_EDGE=http://127.0.0.1:7399 fire brief <<<"$start")" || rc=$?
ms=$(( ($(date +%s%N) - t0) / 1000000 ))
line="$(jq -r '.hookSpecificOutput.additionalContext // empty' <<<"$down" 2>/dev/null)"
[[ "$rc" == 0 && "$ms" -lt 3000 && "$line" == *"did not answer"* && "$(wc -l <<<"$line")" == 1 ]]
holds $? "the edge down: the hook answers in ${ms} ms, under its 3 s bound, with one line of context"

# ---- 7. Claude Code's MCP exchange, recorded (with --record-mcp).
if [[ "$record" == 1 ]]; then
  cat >"$work/forward.py" <<'PY'
import http.client, http.server, json, sys, threading
listen_port, target_port, log_path = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
lock = threading.Lock()
SECRET = {"authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization"}
def headers(items):
    return {k: ("<removed>" if k.lower() in SECRET else v) for k, v in items}
def body(raw):
    text = raw.decode("utf-8", "replace")
    try:
        return json.loads(text)
    except ValueError:
        return text
class Forward(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def handle_one(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        c = http.client.HTTPConnection("127.0.0.1", target_port, timeout=60)
        sent = {k: v for k, v in self.headers.items() if k.lower() not in ("connection",)}
        c.request(self.command, self.path, body=raw or None, headers=sent)
        r = c.getresponse()
        answer = r.read()
        with lock, open(log_path, "a") as f:
            f.write(json.dumps({"request": {"method": self.command, "path": self.path,
                                            "headers": headers(self.headers.items()), "body": body(raw)},
                                "response": {"status": r.status, "headers": headers(r.getheaders()),
                                             "body": body(answer)}}) + "\n")
        self.send_response(r.status)
        for k, v in r.getheaders():
            if k.lower() not in ("content-length", "connection", "transfer-encoding"):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(answer)))
        self.end_headers()
        self.wfile.write(answer)
    do_GET = do_POST = do_DELETE = do_PUT = handle_one
    def log_message(self, *a):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", listen_port), Forward).serve_forever()
PY
  python3 -I "$work/forward.py" 7319 7318 "$work/mcp.jsonl" >"$work/forward.log" 2>&1 &
  started+=("$!")
  for _ in $(seq 1 20); do listening 7319 && break; sleep 0.25; done
  jq -n --arg u "http://$forward/mcp" '{mcpServers: {kalandra: {type: "http", url: $u}}}' >"$work/mcp-config.json"
  claude_projects_before="$(ls -1 "$HOME/.claude/projects" 2>/dev/null || true)"
  transcripts="$HOME/.claude/projects/$(sed 's/[^A-Za-z0-9]/-/g' <<<"$repo")"
  [[ ! -e "$transcripts" ]] || { fail "a transcript directory $transcripts was there before the run"; transcripts=""; }
  rc=0
  (cd "$repo" && timeout 180 claude -p 'Call the kalandra tool project_list with project edge-proof, then say how many artifacts it lists.' \
    --model haiku --max-turns 3 --strict-mcp-config --mcp-config "$work/mcp-config.json" \
    --allowedTools mcp__kalandra__project_list --setting-sources project,local \
    --output-format json </dev/null) >"$work/claude.json" 2>"$work/claude.err" || rc=$?
  holds "$rc" "claude -p ran against the forwarder"
  say "cost  claude -p: $(jq -r '"\(.total_cost_usd // "?") USD as Claude Code reports it, \(.num_turns // "?") turns"' "$work/claude.json" 2>/dev/null || echo unknown)"
  if [[ -f "$work/mcp.jsonl" ]]; then
    cp "$work/mcp.jsonl" "$proof/mcp.jsonl"
    methods="$(jq -r 'select(.request.body | type == "object") | "\(.request.body.method // "-") \(.response.status)"' "$work/mcp.jsonl" | tr '\n' ',' | sed 's/,$//')"
    say "mcp   in order (method status): $methods"
    jq -e -s '[.[] | select(.request.body.method? == "tools/list" and .response.status == 200)] | length > 0' "$work/mcp.jsonl" >/dev/null
    holds $? "tools/list answered 200"
    jq -e -s '[.[] | select(.request.body.method? == "tools/call" and .response.status == 200)] | length > 0' "$work/mcp.jsonl" >/dev/null
    holds $? "tools/call answered 200"
    if grep -q '"method": "server/discover"' "$work/mcp.jsonl"; then say "fact  Claude Code sent server/discover"; fi
    if grep -q '"method": "initialize"' "$work/mcp.jsonl"; then say "fact  Claude Code sent initialize"; fi
  else
    fail "the forwarder recorded no exchange"
  fi
  after="$(ls -1 "$HOME/.claude/projects" 2>/dev/null || true)"
  others="$(comm -13 <(sort <<<"$claude_projects_before") <(sort <<<"$after") | grep -vxF "$(basename "$transcripts")" || true)"
  [[ -z "$others" ]] || say "note  other new directories under ~/.claude/projects, left alone: $others"
  if [[ -d "$transcripts" ]]; then rm -rf -- "$transcripts"; say "clean deleted the run's transcripts, $transcripts"; fi
  if jq -e --arg p "$repo" '.projects[$p]' "$HOME/.claude.json" >/dev/null 2>&1; then
    say "note  ~/.claude.json holds a projects entry for $repo, left as it is"
  fi
  s2="$(sql "SELECT count(*) FROM grit.sessions WHERE harness = 'claude-code' AND place[array_length(place, 1)] = '$(basename "$repo")'")"
  say "fact  claude-code sessions recorded at the repository: $s2"
fi

# ---- 8. The join's undo.
rc=0
(cd "$repo" && HOME="$work/jhome" PATH=/usr/bin:/bin bash -s -- --undo --mcp-name kalandra <"$work/join.sh") >"$work/undo.out" 2>&1 || rc=$?
holds "$rc" "the undo exits 0"
[[ ! -e "$hook" && ! -e "$repo/.git/hooks/post-commit" && ! -e "$repo/.git/hooks/post-rewrite" ]]
holds $? "  the hook and its git hooks are gone"
[[ "$(jq '[.hooks // {} | .[][].hooks[]] | length' "$repo/.claude/settings.local.json")" == 0 ]]
holds $? "  no handler is left (the retired one is not restored)"

# ---- 9. Nothing written outside.
[[ -z "$(find "$work/home" -mindepth 1 -print -quit)" ]]; holds $? "serve's HOME is still empty"
written="$(find "$wt" -newer "$work/start" -type f -not -path "$wt/out/*" -not -path "$wt/logs/*" -not -path "$wt/.git/*")"
[[ -z "$written" ]]; holds $? "nothing written in the checkout${written:+: $written}"

# ---- 10. The edge stopped, then: what is left listening is checked by the trap's caller.
kill "$serve_pid" 2>/dev/null || true
for _ in $(seq 1 60); do kill -0 "$serve_pid" 2>/dev/null || break; sleep 0.5; done
! kill -0 "$serve_pid" 2>/dev/null; holds $? "serve stopped by its pid"

if [[ "$failed" == 0 ]]; then
  say "edge-proof: passed"
else
  say "edge-proof: $failed checks failed"
  echo "serve's log ends:" >&2
  tail -20 "$work/serve.log" >&2
  exit 1
fi
