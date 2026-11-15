#!/usr/bin/env bash
# The proof, before the switch, that this checkout's serve works through grit's commands edge with
# credentials, and that the stored key's rename and its rollback hold: it restores a copy of
# grit_kalandra, renames its places from `worker` to `machine` as the switch will, serves it on a
# second port with two throwaway credentials, drives it the way the command line, sessions,
# agents, the join and Claude Code will, then rolls the copy back and serves it from the live
# 0.2.0 jar, and stops what it started and drops the copy.
#
#   ops/edge-proof.sh
#
# Run from this checkout after `bin/kalandra build`. It reads grit_kalandra once (a pg_dump, as
# ops/backup.sh does) and never writes it; it restores the dump into grit_kalandra_e7, where it
# cancels every pending or enqueued DBOS workflow so nothing resumes, and serves that copy on
# 127.0.0.1:7318 from this checkout's jar, with keys that are no keys, so nothing it serves can
# spend, and with an empty HOME, which must stay empty: serve writes no files. Its two credentials
# are made in its own work directory, as the switch makes the real ones, and never printed.
#
# Into the proof's directory, ${KALANDRA_PROOF_DIR:-$HOME/.cache/kalandra-e7}, it saves the jar it
# proved (serve.jar), the join script that jar serves (join.sh), grit's forward rewrite at the
# commit the jar is built against (machine-from-worker.sql), this checkout's reverse
# (worker-from-machine.sql), each with its sha256, the join's output (join.txt), the forward dry
# run's counts (forward-dry.txt) and each check's line (proof.txt): the switch installs and runs
# those exact files.
#
# It runs Claude Code once (`claude -p`, Haiku, 3 turns at most) in the credential-joined
# temporary repository, its MCP entry the one the join wrote, with its headersHelper, pointed by
# a --strict-mcp-config file at a forwarder on 127.0.0.1:7319 that checks an Authorization header
# is the sessions' credential's (by its digest) and never logs its value. That run needs the real
# HOME to log in: the transcript directory it makes under ~/.claude/projects/ is deleted.
#
# Refused, exit 1, before anything is started: grit_kalandra_e7 exists, something listens on
# 7318 or 7319, KALANDRA_LISTEN names 7317, there is no jar, or the jar is not built against the
# grit snapshot's marker. Prints a line a check, and `edge-proof: passed`, exit 0, only when every
# check held. Every process it starts runs in a directory of its own and is stopped by its PID,
# only while that PID still runs there and has not been waited for; the copy is dropped, however
# it ends.
set -euo pipefail

wt="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$wt/out/assembly.dest/out.jar"
compose="${GRIT_COMPOSE_DIR:-$HOME/Projects/grit}"
grit_checkout="${KALANDRA_CHECKOUT:-$HOME/Projects/grit}"
proof="${KALANDRA_PROOF_DIR:-$HOME/.cache/kalandra-e7}"
# The live checkout, whose jar and bin/kalandra alone are read (copied), and the 0.2.0 join script
# E4c's proof saved, which the live sessions' join was made with.
live="${KALANDRA_LIVE:-$HOME/Projects/kalandra}"
e4c="${KALANDRA_E4C_DIR:-$HOME/.cache/kalandra-e4c}"
java="${GRIT_JDK_HOME:-/home/nickchilders/.jdks/jdk-26.0.2}/bin/java"
restored=grit_kalandra_e7
listen=127.0.0.1:7318
forward=127.0.0.1:7319
edge="http://$listen"
[[ $# == 0 ]] || { echo "usage: ops/edge-proof.sh" >&2; exit 64; }

pg() { docker compose --project-directory "$compose" exec -T postgres "$@"; }
sql() { pg psql -U grit -d "$restored" -At -v ON_ERROR_STOP=1 -c "$1"; }
# The switch's psql, on the copy: `pg` in ops/SWITCH.md.
psw() { pg psql -U grit -q -v ON_ERROR_STOP=1 -d "$restored" "$@"; }
listening() { ss -Hltn "sport = :$1" 2>/dev/null | grep -q .; }
# How many stored places, in every table the rename rewrites, are under the key $1.
under() {
  sql "SELECT (SELECT count(*) FROM grit.places WHERE path[1] = '$1') + (SELECT count(*) FROM grit.sessions WHERE place[1] = '$1') + (SELECT count(*) FROM grit.documents WHERE place[1] = '$1') + (SELECT count(*) FROM grit.files WHERE place[1] = '$1') + (SELECT count(*) FROM grit.piece_sets WHERE place[1] = '$1') + (SELECT count(*) FROM grit.visibility_changes WHERE change ->> 'room' LIKE '$1:%') + (SELECT count(*) FROM grit.session_events WHERE shape ->> 'worktree' LIKE '$1:%')"
}

# The two queries the switch runs before the kept rename, verbatim in ops/SWITCH.md (checked
# below): the pending workflows that name a place under `worker`, and the session events the
# rename drops a subject from or deletes, which the switch saves first.
pending_q="SELECT count(*) FROM dbos.workflow_status w WHERE w.status IN ('PENDING','ENQUEUED') AND (w.inputs LIKE '%worker:%' OR EXISTS (SELECT 1 FROM dbos.operation_outputs o WHERE o.workflow_uuid = w.workflow_uuid AND o.output LIKE '%worker:%'))"
dropped_q="SELECT session, seq, kind, shape FROM grit.session_events WHERE shape ->> 'worktree' LIKE 'worker:%' AND octet_length(shape::text) >= 300 ORDER BY session, seq"
connected_q="SELECT count(*) FROM pg_stat_activity WHERE datname='grit_kalandra' AND pid <> pg_backend_pid()"

# ---- 0. Refusals, before anything is started.
refuse() { echo "edge-proof: $*" >&2; exit 1; }
[[ "${KALANDRA_LISTEN:-}" != *:7317 ]] || refuse "KALANDRA_LISTEN names 7317, the live serve's port"
# What else the hook, bin/kalandra or curl read that could send a step elsewhere, or pick a
# credential the proof did not name; the proof names each one it needs itself:
#   GRIT_EDGE               the installed hook's edge (a credentialed hook refuses another)
#   GRIT_HOOK_STARTED/SELF  the hook's watchdog marks: set, a hook skips its bound and runs as SELF
#   CLAUDE_PROJECT_DIR      the hook's directory: set, the proof's events and commit name it
#   CLAUDE_CODE_SESSION_ID  a session: bin/kalandra then sends the sessions' credential
#   KALANDRA_CREDENTIAL     the command line's credential file
#   GRIT_DATABASE_URL       bin/kalandra start's and work's database (the serves here get env -i)
#   *_proxy, *_PROXY        a proxy every curl to the proof's serve would go through
unset GRIT_EDGE GRIT_HOOK_STARTED GRIT_HOOK_SELF CLAUDE_PROJECT_DIR CLAUDE_CODE_SESSION_ID \
  KALANDRA_CREDENTIAL GRIT_DATABASE_URL http_proxy HTTP_PROXY https_proxy HTTPS_PROXY all_proxy ALL_PROXY
[[ -f "$jar" ]] || refuse "no jar at $jar: run bin/kalandra build first"
version="$(sed -n 's/^ *def gritVersion = "\(.*\)"$/\1/p' "$wt/build.mill")"
marker="$HOME/.ivy2/local/dev.grit/.publish-local-$version"
at="$(cat "$marker" 2>/dev/null)" || refuse "no marker for grit $version at $marker"
# The jar is an executable assembly, a launcher before its zip, which unzip warns of; Python's
# zipfile reads past it.
built="$(python3 -I -c 'import sys, zipfile; print(zipfile.ZipFile(sys.argv[1]).read("kalandra/grit.txt").decode().strip())' "$jar" 2>/dev/null)" ||
  refuse "the jar carries no kalandra/grit.txt"
[[ "$built" == "$version at $at" ]] || refuse "the jar is built against grit $built, the marker names $at: build again"
forward_sql="$(git -C "$grit_checkout" show "$at:core/dbos/it/resources/machine-from-worker.sql")" ||
  refuse "grit's checkout has no machine-from-worker.sql at $at"
[[ -f "$e4c/join.sh" ]] || refuse "no 0.2.0 join script at $e4c/join.sh"
if [[ -n "$(pg psql -U grit -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname = '$restored'")" ]]; then
  refuse "$restored exists: a proof may be running"
fi
listening 7318 && refuse "something listens on 7318"
listening 7319 && refuse "something listens on 7319"

work="$(cd "$(mktemp -d)" && pwd -P)"
# Every process the proof starts runs in a directory under $work, so a pid's cwd shows it is
# the proof's own; `started` holds each until it is waited for.
mkdir -p "$work/run"
started=()
copied=0
transcripts=""

# ours <pid>: the process is alive and runs in a directory under the work directory.
ours() { [[ "$(readlink "/proc/$1/cwd" 2>/dev/null)" == "$work"/* ]]; }
# forget <pid>: waited for, so no longer the proof's to stop (its pid may be another's now).
forget() {
  local p kept=()
  for p in "${started[@]}"; do [[ "$p" == "$1" ]] || kept+=("$p"); done
  started=("${kept[@]}")
}
# stop <pid>: SIGTERM, up to 30 s, then SIGKILL, each only while the pid is still ours; then waited.
stop() {
  ours "$1" || { forget "$1"; return 0; }
  kill "$1" 2>/dev/null || true
  for _ in $(seq 1 60); do ours "$1" || break; sleep 0.5; done
  if ours "$1"; then kill -9 "$1" 2>/dev/null || true; fi
  wait "$1" 2>/dev/null || true
  forget "$1"
}

cleanup() {
  local pid
  for pid in "${started[@]}"; do stop "$pid"; done
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
keep_file() { cp "$1" "$proof/$2" && (cd "$proof" && sha256sum "$2" >"$2.sha256") && say "file  $(cut -d' ' -f1 "$proof/$2.sha256")  $2"; }

say "edge-proof: $(date -u +%Y-%m-%dT%H:%M:%SZ), checkout $wt at $(git -C "$wt" rev-parse --short HEAD)$(git -C "$wt" diff --quiet HEAD -- || echo ' with changes')"
say "grit  the jar is built against grit $built"
keep_file "$jar" serve.jar
printf '%s\n' "$forward_sql" >"$work/machine-from-worker.sql"
keep_file "$work/machine-from-worker.sql" machine-from-worker.sql
keep_file "$wt/ops/worker-from-machine.sql" worker-from-machine.sql
F="$proof/machine-from-worker.sql"
R="$proof/worker-from-machine.sql"
# The switch runs these queries as the proof does.
for q in "$pending_q" "$dropped_q" "$connected_q"; do
  grep -qF -- "$q" "$wt/ops/SWITCH.md" || refuse "ops/SWITCH.md does not run the proof's query: $q"
done
pass "ops/SWITCH.md runs the proof's pending-workflow, saved-rows and connections queries verbatim"

# ---- 1. The copy, and what it holds before anything changes it.
pg pg_dump -U grit -Fc grit_kalandra >"$work/live.dump"
pg pg_restore --list <"$work/live.dump" >/dev/null
pg psql -U grit -d postgres -q -c "CREATE DATABASE $restored" >/dev/null
copied=1
pg pg_restore -U grit -d "$restored" --no-owner --exit-on-error <"$work/live.dump"
say "copy  $restored restored from one dump of grit_kalandra"
# From here a failed check is counted, not an exit.
set +e
say "copy  workflows by status: $(sql "SELECT string_agg(status || ' ' || n, ', ' ORDER BY status) FROM (SELECT status, count(*) n FROM dbos.workflow_status GROUP BY status) s")"
pending_named="$(sql "$pending_q")"
say "pend  pending or enqueued workflows naming worker: $pending_named (of $(sql "SELECT count(*) FROM dbos.workflow_status WHERE status IN ('PENDING','ENQUEUED')") pending or enqueued)"
[[ "$pending_named" == 0 ]]; holds $? "no pending workflow names a place under worker"
# The query is shown to see DBOS's stored encoding: one workflow, its recorded inputs or step
# outputs holding a place as grit wrote it, made pending on the copy, is counted, then put back.
plant="$(sql "SELECT w.workflow_uuid FROM dbos.workflow_status w WHERE w.status NOT IN ('PENDING','ENQUEUED') AND (w.inputs LIKE '%worker:%' OR EXISTS (SELECT 1 FROM dbos.operation_outputs o WHERE o.workflow_uuid = w.workflow_uuid AND o.output LIKE '%worker:%')) ORDER BY w.created_at DESC LIMIT 1")"
if [[ -n "$plant" ]]; then
  was="$(sql "SELECT status FROM dbos.workflow_status WHERE workflow_uuid = '$plant'")"
  where="$(sql "SELECT CASE WHEN inputs LIKE '%worker:%' THEN 'its inputs' ELSE 'a step''s output' END FROM dbos.workflow_status WHERE workflow_uuid = '$plant'")"
  sql "UPDATE dbos.workflow_status SET status = 'PENDING' WHERE workflow_uuid = '$plant'" >/dev/null
  seen="$(sql "$pending_q")"
  sql "UPDATE dbos.workflow_status SET status = '$was' WHERE workflow_uuid = '$plant'" >/dev/null
  [[ "$seen" == 1 ]]; holds $? "the pending-workflow query counts a planted one ($was, made pending), a place under worker in $where as DBOS stored it: $seen"
else
  # None records one: a workflow recording a place under fs is given one under worker in its
  # place, in DBOS's own encoding, made pending, counted, then put back.
  say "fact  workflows in the copy by name: $(sql "SELECT string_agg(name || ' ' || n, ', ' ORDER BY name) FROM (SELECT name, count(*) n FROM dbos.workflow_status GROUP BY name) s")"
  say "fact  recorded steps naming a place under fs: $(sql "SELECT count(*) FROM dbos.operation_outputs WHERE output LIKE '%fs:/%'"), workflows' inputs: $(sql "SELECT count(*) FROM dbos.workflow_status WHERE inputs LIKE '%fs:/%'")"
  plant="$(sql "SELECT o.workflow_uuid || ' ' || o.function_id FROM dbos.operation_outputs o JOIN dbos.workflow_status w USING (workflow_uuid) WHERE w.status NOT IN ('PENDING','ENQUEUED') AND o.output LIKE '%fs:/%' ORDER BY w.created_at DESC LIMIT 1")"
  if [[ -n "$plant" ]]; then
    read -r pw pf <<<"$plant"
    sql "CREATE TABLE e7plant AS SELECT w.status, o.output FROM dbos.workflow_status w JOIN dbos.operation_outputs o USING (workflow_uuid) WHERE o.workflow_uuid = '$pw' AND o.function_id = $pf" >/dev/null
    sql "UPDATE dbos.operation_outputs SET output = replace(output, 'fs:/', 'worker:local/') WHERE workflow_uuid = '$pw' AND function_id = $pf" >/dev/null
    sql "UPDATE dbos.workflow_status SET status = 'PENDING' WHERE workflow_uuid = '$pw'" >/dev/null
    seen="$(sql "$pending_q")"
    sql "UPDATE dbos.operation_outputs o SET output = p.output FROM e7plant p WHERE o.workflow_uuid = '$pw' AND o.function_id = $pf" >/dev/null
    sql "UPDATE dbos.workflow_status w SET status = p.status FROM e7plant p WHERE w.workflow_uuid = '$pw'" >/dev/null
    sql "DROP TABLE e7plant" >/dev/null
    [[ "$seen" == 1 ]]; holds $? "the pending-workflow query counts a planted one: a step's recorded place under fs given one under worker, in DBOS's stored encoding, its workflow made pending: $seen"
  else
    fail "no workflow in the copy records a place, so the pending-workflow query cannot be shown to see one"
  fi
fi
[[ "$(sql "$pending_q")" == 0 ]]; holds $? "  and put back, the query counts 0 again"
cancelled="$(sql "WITH c AS (UPDATE dbos.workflow_status SET status = 'CANCELLED' WHERE status IN ('PENDING', 'ENQUEUED') RETURNING 1) SELECT count(*) FROM c")"
say "copy  $cancelled pending or enqueued workflows cancelled in the copy"
machine_before="$(under machine)"
[[ "$machine_before" == 0 ]]; holds $? "no place is under machine before the rename, so the reverse is exact ($machine_before)"
# Every rewritten table's place, by primary key, kept in the copy to compare after the rollback.
pg psql -U grit -d "$restored" -q -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE SCHEMA e7proof;
CREATE VIEW e7proof.placed AS
      SELECT 'grit.places' AS tbl, id::text AS k, array_to_string(path, E'\x1f') AS v FROM grit.places
UNION ALL SELECT 'grit.sessions', id::text, array_to_string(place, E'\x1f') FROM grit.sessions
UNION ALL SELECT 'grit.documents', version::text, array_to_string(place, E'\x1f') FROM grit.documents
UNION ALL SELECT 'grit.files', id::text, array_to_string(place, E'\x1f') FROM grit.files
UNION ALL SELECT 'grit.piece_sets', id::text, array_to_string(place, E'\x1f') FROM grit.piece_sets
UNION ALL SELECT 'grit.visibility_changes', id::text, change::text FROM grit.visibility_changes
UNION ALL SELECT 'grit.session_events', session || '/' || seq, shape::text FROM grit.session_events;
CREATE TABLE e7proof.before AS SELECT * FROM e7proof.placed;
SQL
say "hash  $(sql "SELECT string_agg(tbl || ' ' || n || ' rows ' || left(h, 12), ', ' ORDER BY tbl) FROM (SELECT tbl, count(*) n, md5(string_agg(k || '=' || v, ',' ORDER BY k)) h FROM e7proof.before GROUP BY tbl) s")"
claims_q="SELECT plugin, subject, holder, role FROM grit.claims ORDER BY plugin, subject, holder"
sql "$claims_q" >"$work/claims-before.txt"
say "claim $(wc -l <"$work/claims-before.txt") claims in the copy"
# What the switch saves before the kept run: the rows the rename drops a subject from or deletes.
psw -c "\copy ($dropped_q) TO STDOUT WITH CSV HEADER" >"$work/dropped.csv"
saved=$(($(wc -l <"$work/dropped.csv") - 1))
say "save  $saved session events the rename drops a subject from or deletes, saved as the switch saves them"
tail -n +2 "$work/dropped.csv" | cut -d, -f1-3 | sed 's/^/save    session,seq,kind /' | tee -a "$proof/proof.txt"

# ---- 2. The rename on the copy, as the switch runs it.
rc=0; psw -f - <"$F" >"$work/dry.txt" 2>&1 || rc=$?
holds "$rc" "the forward dry run exits 0"
cp "$work/dry.txt" "$proof/forward-dry.txt"
sed 's/^/dry     /' "$work/dry.txt" | tee -a "$proof/proof.txt"
# A count in psql's aligned table, by its row's name; zeros: every count in the table is 0.
count() { awk -F'|' -v w="$2" '{ a = $1; gsub(/^ +| +$/, "", a); b = $2; gsub(/ /, "", b); if (a == w) print b }' "$1"; }
zeros() { awk -F'|' 'NF == 2 { b = $2; gsub(/ /, "", b); if (b ~ /^[0-9]+$/) { seen = 1; if (b != 0) bad = 1 } } END { exit (bad || !seen) }' "$1"; }
deleted="$(count "$work/dry.txt" 'grit.session_events deleted, too long to rewrite')"
subjectless="$(count "$work/dry.txt" 'grit.session_events rewritten without their subject')"
[[ "$deleted" == 0 ]]; holds $? "the dry run deletes no event ($deleted)"
[[ "$saved" == "$((subjectless + deleted))" ]]; holds $? "the saved rows are the dry run's subjectless and deleted events ($saved = $subjectless + $deleted)"
w_left="$(under worker)"
[[ "$w_left" != 0 ]]; holds $? "the dry run rolled back: $w_left places under worker remain"
if [[ "$deleted" != 0 ]]; then
  say "edge-proof: stopped: the rename would delete $deleted events"
  exit 1
fi
rc=0; psw -f - -c COMMIT <"$F" >"$work/kept.txt" 2>&1 || rc=$?
holds "$rc" "the forward run, kept, exits 0"
cmp -s <(grep -v '^COMMIT' "$work/kept.txt") "$work/dry.txt"; holds $? "  and its counts are the dry run's"
rc=0; psw -f - <"$F" >"$work/again.txt" 2>&1 || rc=$?
zeros "$work/again.txt" && [[ "$rc" == 0 ]]
holds $? "a second forward run counts 0 everywhere"
left_worker="$(under worker)"
[[ "$left_worker" == 0 ]]; holds $? "no place under worker remains ($left_worker)"

# ---- 3. serve on the copy, with no keys, an empty HOME and two throwaway credentials, made as
# the switch makes the real ones.
mkdir -p "$work/home" "$work/cred"
( umask 077
  head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' >"$work/cred/owner"
  head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' >"$work/cred/sessions"
  head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' >"$work/cred/wrong"
  printf 'Authorization: Bearer %s\n' "$(<"$work/cred/wrong")" >"$work/cred/wrong.header" )
d() { tr -d '\n' <"$1" | sha256sum | cut -d' ' -f1; }
own="$work/cred/owner"; ses="$work/cred/sessions"; wrong="$work/cred/wrong"
[[ "$(stat -c %a "$own" "$ses" | sort -u)" == 600 ]]; holds $? "the credentials are mode 600"
serve_env=(env -i PATH=/usr/bin:/bin HOME="$work/home" KALANDRA_CHECKOUT="$grit_checkout"
  GRIT_DATABASE_URL="jdbc:postgresql://localhost:5432/$restored" KALANDRA_LISTEN="$listen"
  ANTHROPIC_API_KEY=edge-proof-no-key JEV_API_KEY=edge-proof-no-key)
for missing in KALANDRA_OWNER_DIGEST KALANDRA_SESSIONS_DIGEST; do
  digests=(KALANDRA_OWNER_DIGEST="$(d "$own")" KALANDRA_SESSIONS_DIGEST="$(d "$ses")")
  digests=("${digests[@]/#$missing=*/}")
  rc=0
  (cd "$work/run" && exec timeout 120 "${serve_env[@]}" ${digests[@]} "$wt/bin/kalandra" serve) >"$work/refused.log" 2>&1 || rc=$?
  [[ "$rc" == 1 ]] && grep -qxF "[kalandra] $missing is not set: the SHA-256 digest, 64 lowercase hex, of $([[ $missing == *OWNER* ]] && echo "the command line's" || echo "the sessions'") credential" "$work/refused.log"
  holds $? "serve without $missing exits 1, its log naming the variable: $(tail -1 "$work/refused.log")"
done
(cd "$work/run" && exec "${serve_env[@]}" KALANDRA_OWNER_DIGEST="$(d "$own")" KALANDRA_SESSIONS_DIGEST="$(d "$ses")" \
  "$wt/bin/kalandra" serve) >"$work/serve.log" 2>&1 &
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
grep -qxF "[kalandra] grit $version at $at" "$work/serve.log"; holds $? "serve logs [kalandra] grit $version at $at"

# ---- 4. 401 everywhere, with no credential and with a wrong one; nothing kept.
kept_q="SELECT (SELECT count(*) FROM grit.documents) || '/' || (SELECT count(*) FROM grit.sessions) || '/' || (SELECT count(*) FROM grit.session_events) || '/' || (SELECT count(*) FROM grit.claims)"
kept_before="$(sql "$kept_q")"
routes=(
  "POST /command/project_keep {\"key\":\"edge-proof/x\",\"kind\":\"note\",\"title\":\"x\",\"body\":\"x\"}"
  "POST /mcp {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
  "POST /wait {\"artifact\":\"edge-proof/x\",\"state\":[\"open\"],\"within\":1}"
  "GET /holder?harness=claude-code&dir=/tmp/x -"
  "GET /brief?harness=claude-code&session=s&dir=/tmp/x&start=fresh -"
  "POST /events {\"harness\":\"claude-code\",\"session\":\"s\"}"
  "GET /join/claude-code -"
  "GET /nowhere -"
)
codes=""
for r in "${routes[@]}"; do
  read -r verb path body <<<"$r"
  for who in none wrong; do
    hdr=(); [[ "$who" == wrong ]] && hdr=(-H "@$work/cred/wrong.header")
    data=(); [[ "$body" != - ]] && data=(--data-binary "$body")
    c="$(curl -q -s --noproxy '*' --max-time 10 -o /dev/null -w '%{http_code}' -X "$verb" "${hdr[@]}" "${data[@]}" "$edge$path")"
    codes+="$verb $path $who $c"$'\n'
  done
done
say "401   $(awk '{print $4}' <<<"${codes%$'\n'}" | sort | uniq -c | tr -s ' ' | tr '\n' ',' | sed 's/,$//')"
! grep -qv ' 401$' <<<"${codes%$'\n'}"; holds $? "every route answers 401 to no credential and to a wrong one${codes:+: $(grep -v ' 401$' <<<"${codes%$'\n'}" | tr '\n' ';')}"
[[ "$(sql "$kept_q")" == "$kept_before" ]]; holds $? "  and nothing was kept ($kept_before documents/sessions/events/claims)"
asked="$(curl -q -s --noproxy '*' --max-time 10 -X POST -d '{}' "$edge/command/project_list")"
[[ "$asked" == "refused: this edge needs a credential: send Authorization: Bearer <your credential>" ]]
holds $? "  with no credential the edge says: $asked"

# ---- 5. Each credential gets its caller.
K() { env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$own" "$wt/bin/kalandra" "$@"; }
mkdir -p "$work/shome/.config/grit"
(umask 077; cp "$ses" "$work/shome/.config/grit/credential")
# A session: CLAUDE_CODE_SESSION_ID set, the command line's KALANDRA_CREDENTIAL inherited, which
# the session's marker overrides.
S() { env HOME="$work/shome" CLAUDE_CODE_SESSION_ID=proof-k1 KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$own" "$wt/bin/kalandra" "$@"; }
level() { sql "SELECT l.level FROM grit.documents d JOIN grit.integrity_labels l ON l.id = d.integrity_id WHERE d.key = '$1' AND d.superseded_at IS NULL"; }

expect 0 "list grit's now" K list '{"project":"grit","state":"now"}'
expect 0 "show grit/plan-harness-edge" K show '{"key":"grit/plan-harness-edge"}'
rc=0
K standing '{"key":"grit/roadmap-grit-coder"}' >"$work/standing.md" 2>"$work/standing.err" || rc=$?
grep -q '^grit/roadmap-grit-coder at version [0-9]\+$' "$work/standing.err" || rc=1
holds "$rc" "standing of grit/roadmap-grit-coder through the command line's credential, its version on stderr"
expect 0 "new project edge-proof" K new '{"project":"edge-proof"}'
expect 0 "keep milestone edge-proof/m" K keep '{"key":"edge-proof/m","kind":"milestone","title":"Edge proof","body":"Kept by ops/edge-proof.sh in a restored copy."}'
expect 0 "keep note edge-proof/c" K keep '{"key":"edge-proof/c","kind":"note","title":"Cited note","body":"cited-text-1","parent":"edge-proof/m"}'
note() { printf '{"key":"edge-proof/n","kind":"note","title":"Proof note","body":"%s See [the cited](artifact:edge-proof/c).","parent":"edge-proof/m","cites":["edge-proof/c"]}' "$1"; }
expect 0 "keep note edge-proof/n citing edge-proof/c" K keep "$(note note-text-1)"
expect 0 "a keep by the command line" K keep '{"key":"edge-proof/by-owner","kind":"note","title":"Owner","body":"kept by the command line","parent":"edge-proof/m"}'
expect 0 "a keep by a session" S keep '{"key":"edge-proof/by-session","kind":"note","title":"Session","body":"kept by a session","parent":"edge-proof/m"}'
key_of="$(sql "SELECT key FROM grit.documents WHERE key LIKE '%edge-proof/by-owner' AND superseded_at IS NULL")"
say "fact  a document's key column holds $key_of"
lo="$(level "$key_of")"; ls_="$(level "${key_of%by-owner}by-session")"
[[ "$lo" == 3 ]]; holds $? "the command line's keep is recorded at integrity level 3, authority ($lo)"
[[ "$ls_" == 2 ]]; holds $? "the session's keep, KALANDRA_CREDENTIAL inherited, is recorded at level 2, member ($ls_)"
before="$(K show '{"key":"edge-proof/n"}')"
expect 1 "a move to adopted is refused" K move '{"key":"edge-proof/n","state":"adopted"}'
grep -q project_adopt <<<"$out"; holds $? "  and the refusal names project_adopt"
expect 1 "a batch with a step to adopted is refused" K batch '{"project":"edge-proof","steps":[{"command":"move","key":"edge-proof/n","state":"adopted"}]}'
grep -q project_adopt <<<"$out"; holds $? "  and the refusal names project_adopt"
[[ "$(K show '{"key":"edge-proof/n"}')" == "$before" ]]; holds $? "edge-proof/n unchanged after both"
state="$(sed -n 's/^state: //p' <<<"$before" | head -1)"
expect 0 "wait for edge-proof/n in $state" K wait "{\"artifact\":\"edge-proof/n\",\"state\":[\"$state\"],\"within\":5}"
expect 3 "wait for edge-proof/n in folded lapses" K wait '{"artifact":"edge-proof/n","state":["folded"],"within":2}'
expect 1 "a wait with a key it does not read is refused" K wait '{"artifact":"edge-proof/n","state":["open"],"slot":"x"}'
grep -q "reads no slot" <<<"$out"; holds $? "  and names the key"
repo="$work/repo"
H="$(K holder "{\"dir\":\"$repo\"}")" || H=""
[[ "$H" =~ ^local/repo-[0-9a-f]{8}$ ]]; holds $? "holder for the repository is local/repo-<8 hex>: $H"
expect 0 "claim edge-proof/n as manager for $H" K claim "{\"key\":\"edge-proof/n\",\"role\":\"manager\",\"holder\":\"$H\"}"
expect 0 "export edge-proof through CommandClient" K export edge-proof "$work/export"
n="$(find "$work/export" -type f | wc -l)"
[[ "$n" -ge 3 ]]; holds $? "  $n export files"
expect 0 "a session's export through CommandClient" S export edge-proof "$work/export-s"
# The lines a person reads (grit/note-e7-bin-kalandra-lines), exactly.
gone="$work/none/credential"
expect 78 "a missing credential file sends nothing" env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$gone" "$wt/bin/kalandra" list '{"project":"edge-proof"}'
[[ "$out" == "kalandra: cannot read a credential in $gone; nothing was sent: ask whoever runs serve for this credential and put it in $gone" ]]
holds $? "  and says: $out"
expect 78 "a session with no sessions' credential sends nothing, though KALANDRA_CREDENTIAL is set" env HOME="$work/none" CLAUDE_CODE_SESSION_ID=proof-k2 KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$own" "$wt/bin/kalandra" list '{"project":"edge-proof"}'
grep -qF "in $work/none/.config/grit/credential;" <<<"$out"; holds $? "  and names the sessions' file"
expect 1 "a credential serve does not know is refused" env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$wrong" "$wt/bin/kalandra" list '{"project":"edge-proof"}'
[[ "$out" == "refused: the credential sent is not one this edge knows"$'\n'"kalandra: serve at $listen refused the credential in $wrong (401) for project_list; ask whoever runs serve for this credential and put it in $wrong" ]]
holds $? "  and says the edge's line, then: $(tail -1 <<<"$out")"
expect 1 "the jar's command with a credential serve does not know is refused" env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$wrong" "$wt/bin/kalandra" export edge-proof "$work/export-w"
[[ "$out" == *"[kalandra] serve refused the credential in $wrong (401) for project_export; ask whoever runs serve for this credential and put it in $wrong" ]]
holds $? "  and says: $(tail -1 <<<"$out")"

# ---- 6. The lower credential is refused adopt, and reaches the other own commands.
expect 1 "a session's adopt is refused" S adopt '{"key":"edge-proof/n"}'
say "fact  the session's adopt answered: $(head -1 <<<"$out")"
grep -q "answered 403" <<<"$out" && grep -q "project_adopt" <<<"$out" && grep -q "authority" <<<"$out"
holds $? "  403, naming project_adopt and authority"
expect 1 "the command line's adopt reaches the command, which refuses a note as no amendment" K adopt '{"key":"edge-proof/n"}'
grep -q "answered 409" <<<"$out"; holds $? "  409, the command's own refusal: $(head -1 <<<"$out")"
expect 1 "a session's move to adopted reaches the guard" S move '{"key":"edge-proof/n","state":"adopted"}'
grep -q "answered 409" <<<"$out" && grep -q project_adopt <<<"$out"; holds $? "  409 in the guard's words"
expect 0 "a session's standing reaches the command" S standing '{"key":"grit/roadmap-grit-coder"}'
[[ "$(K show '{"key":"edge-proof/n"}')" == "$before" ]]; holds $? "edge-proof/n unchanged after the adopts"

# ---- 7. The join: 0.2.0's, then the re-join with --credential over it, beside a fresh join; then
# the hook. `claude` on PATH is a stand-in that keeps local-scope MCP entries in HOME's
# ~/.claude.json, keyed by the repository, as Claude Code does.
mkdir -p "$work/shim" "$work/jhome" "$work/jhome2"
cat >"$work/shim/claude" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
f="$HOME/.claude.json"; [ -f "$f" ] || echo '{}' >"$f"
top="$(git rev-parse --show-toplevel)"
put() { jq --arg p "$top" --arg n "$1" --argjson e "$2" '.projects[$p].mcpServers[$n] = $e' "$f" >"$f.t" && mv "$f.t" "$f"; }
case "$1 $2" in
  "mcp remove") n="${!#}"
    jq -e --arg p "$top" --arg n "$n" '.projects[$p].mcpServers[$n]' "$f" >/dev/null || exit 1
    jq --arg p "$top" --arg n "$n" 'del(.projects[$p].mcpServers[$n])' "$f" >"$f.t" && mv "$f.t" "$f" ;;
  "mcp add") put "${@: -2:1}" "$(jq -nc --arg u "${!#}" '{type: "http", url: $u}')" ;;
  "mcp add-json") put "${@: -2:1}" "${!#}" ;;
  *) exit 1 ;;
esac
SH
chmod +x "$work/shim/claude"
init() { git init -q "$1" && git -C "$1" -c user.name=proof -c user.email=proof@example.invalid commit -q --allow-empty -m 'edge proof start'; }
init "$repo"; init "$work/repo2"
J() { local dir="$1" home="$2"; shift 2; (cd "$dir" && HOME="$home" PATH="$work/shim:/usr/bin:/bin" bash -s -- "$@"); }
entry() { jq -c --arg p "$1" '.projects[$p].mcpServers.kalandra // null' "$2/.claude.json" 2>/dev/null; }
rc=0; J "$repo" "$work/jhome" "$edge" --mcp-name kalandra <"$e4c/join.sh" >"$work/join-old.out" 2>&1 || rc=$?
holds "$rc" "0.2.0's join exits 0"
old_entry="$(entry "$repo" "$work/jhome")"
say "fact  0.2.0's join left the MCP entry $old_entry"
curl -q -fsS --noproxy '*' --max-time 10 -H @<(printf 'Authorization: Bearer %s\n' "$(<"$ses")") "$edge/join/claude-code" >"$work/join.sh"
keep_file "$work/join.sh" join.sh
rc=0; J "$repo" "$work/jhome" "$edge" --credential "$ses" --mcp-name kalandra <"$work/join.sh" >"$work/join.out" 2>&1 || rc=$?
holds "$rc" "the re-join with --credential over 0.2.0's exits 0"
sed "s|$work|<work>|g" "$work/join.out" >"$proof/join.txt"
sed 's/^/join    /' "$proof/join.txt" | tee -a "$proof/proof.txt"
grep -qxF "  credential $ses, sent with every request" "$work/join.out"; holds $? "  it reports the credential"
grep -qxF "  mcp       kalandra at $edge/mcp, local scope, replacing kalandra at $edge/mcp" "$work/join.out"; holds $? "  it replaces 0.2.0's MCP entry"
hook="$repo/.git/grit/claude-code/hook"
new_entry="$(entry "$repo" "$work/jhome")"
[[ "$new_entry" == "$(jq -nc --arg u "$edge/mcp" --arg h "'$hook' header" '{type: "http", url: $u, headersHelper: $h}')" ]]
holds $? "  the MCP entry is $edge/mcp with the hook's header as its headersHelper"
rc=0; J "$work/repo2" "$work/jhome2" "$edge" --credential "$ses" --mcp-name kalandra <"$work/join.sh" >"$work/join2.out" 2>&1 || rc=$?
holds "$rc" "a fresh join with --credential exits 0"
norm() { sed "s|$2|<repo>|g" "$1"; }
cmp -s <(norm "$repo/.claude/settings.local.json" "$repo") <(norm "$work/repo2/.claude/settings.local.json" "$work/repo2") &&
  cmp -s <(norm "$hook" "$repo") <(norm "$work/repo2/.git/grit/claude-code/hook" "$work/repo2") &&
  [[ "$(sed "s|$repo|<repo>|g" <<<"$new_entry")" == "$(sed "s|$work/repo2|<repo>|g" <<<"$(entry "$work/repo2" "$work/jhome2")")" ]] &&
  cmp -s <(norm "$repo/.git/hooks/post-commit" "$repo") <(norm "$work/repo2/.git/hooks/post-commit" "$work/repo2")
holds $? "  the re-joined repository's settings, hook, git hooks and MCP entry are a fresh join's"
handlers="$(jq --arg h "'$hook'" '[.hooks[][].hooks[] | select(.command | startswith($h))] | length' "$repo/.claude/settings.local.json")"
[[ "$handlers" == "$(jq '[.hooks[][].hooks[]] | length' "$repo/.claude/settings.local.json")" ]]; holds $? "  every handler in the settings is the hook's ($handlers)"

payload() { jq -nc --arg s "$1" --arg cwd "$repo" --arg e "$2" '{session_id: $s, transcript_path: "/dev/null", cwd: $cwd, hook_event_name: $e}'; }
fire() { CLAUDE_PROJECT_DIR="$repo" PATH=/usr/bin:/bin "$hook" "$@"; }
start="$(payload proof-s1 SessionStart | jq -c '. + {source: "startup"}')"
ctx="$(fire brief <<<"$start" | jq -r '.hookSpecificOutput.additionalContext // empty')" || ctx=""
grep -q "Proof note" <<<"$ctx" && grep -q "note-text-1" <<<"$ctx" && grep -q "cited-text-1" <<<"$ctx"
holds $? "the hook's brief at SessionStart holds the claimed note and what it cites"
expect 0 "keep edge-proof/n with a changed body" K keep "$(note note-text-2)"
ctx="$(fire brief <<<"$start" | jq -r '.hookSpecificOutput.additionalContext // empty')" || ctx=""
grep -q "note-text-2" <<<"$ctx" && ! grep -q "note-text-1" <<<"$ctx"; holds $? "  and the next brief shows the keep"
op="$(fire opening <<<"$start" | jq -r '.hookSpecificOutput.additionalContext // empty')" || op=""
[[ "$(head -1 <<<"$op")" == *"$H"* ]]; holds $? "the hook's opening names the holder first: $(head -1 <<<"$op")"
printed="'$hook' holder TYPE --dir '$repo' --session 'proof-s1'"
grep -qxF "grit: an agent this session starts has its own claim holder. This prints it, with the agent's type in place of TYPE: $printed" <<<"$op"
holds $? "the opening prints the agent holder command"
AH="$(fire holder coder-proof --dir "$repo" --session proof-s1 2>/dev/null)" || AH=""
KH="$(K holder "{\"dir\":\"$repo\",\"type\":\"coder-proof\",\"session\":\"proof-s1\"}")" || KH=""
[[ -n "$AH" && "$AH" == "$KH" && "$AH" == "$H"/coder-proof/* ]]
holds $? "bin/kalandra holder with dir, type and session is the hook's holder coder-proof: $AH"
expect 0 "keep checkpoint edge-proof/w" K keep '{"key":"edge-proof/w","kind":"checkpoint","title":"Agent checkpoint","body":"## Goal\n\nagent-text-1","parent":"edge-proof/m"}'
expect 0 "claim edge-proof/w as coder for $AH" K claim "{\"key\":\"edge-proof/w\",\"role\":\"coder\",\"holder\":\"$AH\"}"
sub="$(payload proof-s1 SubagentStart | jq -c '. + {agent_id: "proof-a1", agent_type: "coder-proof"}')"
actx="$(fire brief <<<"$sub" | jq -r '.hookSpecificOutput.additionalContext // empty')" || actx=""
grep -q "agent-text-1" <<<"$actx" && ! grep -q "note-text-2" <<<"$actx"
holds $? "the hook's brief at SubagentStart holds the agent's claimed checkpoint, not the session's note"
rc=0; fire event <<<"$start" || rc=$?; holds "$rc" "the hook posts the start"
read_="$(payload proof-s1 PostToolUse | jq -c --arg f "$repo/README" '. + {tool_name: "Read", tool_input: {file_path: $f}, tool_response: {type: "text", file: {filePath: $f, content: "hello\n", numLines: 1, startLine: 1, totalLines: 1}}}')"
rc=0; fire event <<<"$read_" || rc=$?; holds "$rc" "the hook posts a read"
stop_="$(payload proof-s1 Stop | jq -c '. + {last_assistant_message: "The proof session is done.", stop_hook_active: false}')"
rc=0; fire event <<<"$stop_" || rc=$?; holds "$rc" "the hook posts a closing"
commit_under() {
  (cd "$work/run" && exec env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$own" "$wt/bin/kalandra" wait \
    "{\"commit\":$1,\"within\":30}") >"$work/wait.out" 2>&1 &
  local wait_pid="$!" rc=0
  started+=("$wait_pid")
  sleep 2
  CLAUDE_CODE_SESSION_ID=proof-s1 PATH=/usr/bin:/bin git -C "$repo" -c user.name=proof \
    -c user.email=proof@example.invalid commit -q --allow-empty -m "$2"
  local sha; sha="$(git -C "$repo" rev-parse HEAD)"
  wait "$wait_pid" || rc=$?
  forget "$wait_pid"
  grep -q "^committed ${sha:0:12} $2" "$work/wait.out" || rc=1
  return "$rc"
}
commit_under "{\"holder\":\"$H\"}" 'edge proof by holder'; holds $? "a commit wait by the holder $H is met by the commit"
commit_under "{\"worktree\":\"$repo\"}" 'edge proof by worktree'; holds $? "a commit wait by the worktree is met by the commit"
counts="$(sql "SELECT s.holder, array_to_string(s.place, '/'), count(e.seq), string_agg(DISTINCT e.kind, ',' ORDER BY e.kind) FROM grit.sessions s JOIN grit.session_events e ON e.session = s.id WHERE s.harness = 'claude-code' AND s.local = 'proof-s1' GROUP BY s.holder, s.place")"
say "count proof-s1 in the copy: holder|place|events|kinds = $counts"
IFS='|' read -r sh sp se sk <<<"$counts"
[[ "$sh" == "$H" && "$sp" == "machine/local$repo" && "${se:-0}" -ge 5 && "$sk" == "closing,commit,read,started" ]]
holds $? "the session proof-s1 is recorded as $H at machine:local$repo, with its start, read, closing and commits"
wt_shape="$(sql "SELECT e.shape ->> 'worktree' FROM grit.sessions s JOIN grit.session_events e ON e.session = s.id WHERE s.local = 'proof-s1' AND e.kind = 'commit' LIMIT 1")"
[[ "$wt_shape" == "machine:local$repo" ]]; holds $? "  and its commit's shape names the worktree machine:local$repo"
hdr="$(fire header 2>/dev/null)"
jq -e 'type == "object" and keys == ["Authorization"] and (.Authorization | test("^Bearer [A-Za-z0-9_-]{43}$"))' <<<"$hdr" >/dev/null
holds $? "the hook's header prints one Authorization object of a bearer credential (its value not printed)"
hdr=""
foreign="$(GRIT_EDGE=http://127.0.0.1:7399 fire brief <<<"$start" 2>&1 >/dev/null)"
[[ "$foreign" == "grit claude-code hook: GRIT_EDGE names http://127.0.0.1:7399, but this checkout's credential is sent only to the edge it was joined to, $edge; it did nothing" ]]
holds $? "a foreign GRIT_EDGE makes the hook do nothing, saying so"

# ---- 8. A real Claude Code run, its MCP client through the join's headersHelper, by a forwarder
# that checks each request's credential by its digest and never logs it.
cat >"$work/forward.py" <<'PY'
import hashlib, http.client, http.server, json, sys, threading
listen_port, target_port, log_path, want = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
lock = threading.Lock()
class Forward(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def handle_one(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        auth = self.headers.get_all("Authorization") or []
        bearer = auth[0][7:] if len(auth) == 1 and auth[0].startswith("Bearer ") else None
        credential = ("none" if not auth else "the sessions'" if bearer is not None
                      and hashlib.sha256(bearer.encode()).hexdigest() == want else "another")
        c = http.client.HTTPConnection("127.0.0.1", target_port, timeout=60)
        sent = {k: v for k, v in self.headers.items() if k.lower() != "connection"}
        c.request(self.command, self.path, body=raw or None, headers=sent)
        r = c.getresponse()
        answer = r.read()
        try:
            method = json.loads(raw).get("method", "-") if raw else "-"
        except (ValueError, AttributeError):
            method = "?"
        with lock, open(log_path, "a") as f:
            f.write(json.dumps({"method": self.command, "path": self.path, "rpc": method,
                                "credential": credential, "status": r.status}) + "\n")
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
(cd "$work/run" && exec python3 -I "$work/forward.py" 7319 7318 "$work/mcp.jsonl" "$(d "$ses")") >"$work/forward.log" 2>&1 &
started+=("$!")
for _ in $(seq 1 20); do listening 7319 && break; sleep 0.25; done
jq -n --argjson e "$new_entry" --arg u "http://$forward/mcp" '{mcpServers: {kalandra: ($e + {url: $u})}}' >"$work/mcp-config.json"
transcripts="$HOME/.claude/projects/$(sed 's/[^A-Za-z0-9]/-/g' <<<"$repo")"
[[ ! -e "$transcripts" ]] || { fail "a transcript directory $transcripts was there before the run"; transcripts=""; }
rc=0
(cd "$repo" && timeout 180 claude -p 'Call the kalandra tool project_list with project edge-proof, then say how many artifacts it lists.' \
  --model haiku --max-turns 3 --strict-mcp-config --mcp-config "$work/mcp-config.json" \
  --allowedTools mcp__kalandra__project_list --setting-sources project,local \
  --output-format json </dev/null) >"$work/claude.json" 2>"$work/claude.err" || rc=$?
holds "$rc" "claude -p ran with the join's MCP entry, through the forwarder"
say "cost  claude -p: $(jq -r '"\(.total_cost_usd // "?") USD as Claude Code reports it, \(.num_turns // "?") turns, \(.usage.input_tokens // "?") in, \(.usage.output_tokens // "?") out"' "$work/claude.json" 2>/dev/null || echo unknown)"
say "said  $(jq -r '.result // empty' "$work/claude.json" 2>/dev/null | head -c 200 | tr '\n' ' ')"
if [[ -f "$work/mcp.jsonl" ]]; then
  cp "$work/mcp.jsonl" "$proof/mcp.jsonl"
  say "mcp   in order (rpc status credential): $(jq -r '"\(.rpc) \(.status) \(.credential)"' "$work/mcp.jsonl" | tr '\n' ',' | sed 's/,$//')"
  jq -e -s 'length > 0 and all(.credential == "the sessions'"'"'")' "$work/mcp.jsonl" >/dev/null
  holds $? "every MCP request bore the sessions' credential"
  jq -e -s 'any(.rpc == "tools/call" and .status == 200)' "$work/mcp.jsonl" >/dev/null
  holds $? "tools/call answered 200"
else
  fail "the forwarder recorded no exchange"
fi
s_place="$(sql "SELECT count(*) FROM grit.sessions WHERE harness = 'claude-code' AND place = ARRAY['machine','local'] || string_to_array(ltrim('$repo', '/'), '/') AND local NOT LIKE 'proof-%'")"
say "fact  sessions the real run's hooks recorded at machine:local$repo: $s_place"
if [[ -d "$transcripts" ]]; then rm -rf -- "$transcripts"; say "clean deleted the run's transcripts, $transcripts"; fi
transcripts=""

# ---- 9. The join's undo, the line it printed run as printed; the claims.
undo_line="$(sed -n 's/^Undo: //p' "$work/join.out")"
[[ "$undo_line" == "curl -fsS -H @<(printf 'Authorization: Bearer %s' \"\$(<'$ses')\") $edge/join/claude-code | bash -s -- --undo --mcp-name kalandra" ]]
holds $? "the join's printed undo fetches the script with the sessions' credential, by its file"
# pipefail, so a refused fetch fails the run rather than piping nothing to a bash that exits 0.
rc=0; (cd "$repo" && HOME="$work/jhome" PATH="$work/shim:/usr/bin:/bin" bash -o pipefail -c "$undo_line") >"$work/undo.out" 2>&1 || rc=$?
holds "$rc" "the printed undo, run as printed, exits 0"
[[ ! -e "$hook" && ! -e "$repo/.git/hooks/post-commit" && ! -e "$repo/.git/hooks/post-rewrite" ]]
holds $? "  the hook and its git hooks are gone"
[[ "$(jq '[.hooks // {} | .[][].hooks[]] | length' "$repo/.claude/settings.local.json")" == 0 && "$(entry "$repo" "$work/jhome")" == null ]]
holds $? "  no handler and no MCP entry is left"
[[ -z "$(find "$work/home" -mindepth 1 -print -quit)" ]]; holds $? "serve's HOME is still empty"
sql "$claims_q" | grep -v '|edge-proof/' >"$work/claims-after.txt"
cmp -s <(grep -v '|edge-proof/' "$work/claims-before.txt") "$work/claims-after.txt"; holds $? "every claim but the proof's is as it was ($(wc -l <"$work/claims-after.txt"))"
written="$(find "$wt" -newer "$work/start" -type f -not -path "$wt/out/*" -not -path "$wt/logs/*" -not -path "$wt/.git/*")"
[[ -z "$written" ]]; holds $? "nothing written in the checkout${written:+: $written}"

# ---- 10. The rollback: serve stopped, the reverse rewrite, the old jar serving the copy.
ours "$serve_pid"; holds $? "serve still runs in the proof's directory, pid $serve_pid"
stop "$serve_pid"
! kill -0 "$serve_pid" 2>/dev/null; holds $? "serve stopped by its pid"
rc=0; psw -f - <"$R" >"$work/rdry.txt" 2>&1 || rc=$?
holds "$rc" "the reverse dry run exits 0"
sed 's/^/rdry    /' "$work/rdry.txt" | tee -a "$proof/proof.txt"
m_left="$(under machine)"
[[ "$m_left" != 0 ]]; holds $? "  and rolled back: $m_left places under machine remain"
rc=0; psw -f - -c COMMIT <"$R" >"$work/rkept.txt" 2>&1 || rc=$?
holds "$rc" "the reverse run, kept, exits 0"
cmp -s <(grep -v '^COMMIT' "$work/rkept.txt") "$work/rdry.txt"; holds $? "  and its counts are its dry run's"
rc=0; psw -f - <"$R" >"$work/ragain.txt" 2>&1 || rc=$?
zeros "$work/ragain.txt" && [[ "$rc" == 0 ]]
holds $? "a second reverse run counts 0 everywhere"
changed="$(sql "SELECT b.tbl || ' ' || b.k FROM e7proof.before b LEFT JOIN e7proof.placed p ON p.tbl = b.tbl AND p.k = b.k WHERE p.v IS DISTINCT FROM b.v ORDER BY 1")"
expected="$(tail -n +2 "$work/dropped.csv" | awk -F, '{ print "grit.session_events " $1 "/" $2 }' | sort)"
[[ "$(sort <<<"$changed")" == "$expected" ]]
holds $? "every place as it was before the rename, but the $saved counted drops ($(grep -c . <<<"$changed") rows differ)"
old="$work/old"
mkdir -p "$old/home" "$old/logs" "$old/bin" "$old/out/assembly.dest"
old_up=1
if cp -- "$live/out/assembly.dest/out.jar" "$old/out/assembly.dest/out.jar" 2>/dev/null &&
  cp -- "$live/bin/kalandra" "$old/bin/kalandra"; then
  chmod a-w "$old/out/assembly.dest/out.jar"
  say "old   $(sha256sum "$old/out/assembly.dest/out.jar" | cut -d' ' -f1)  the live checkout's jar, copied, with its bin/kalandra"
  (cd "$old" && exec env -i PATH=/usr/bin:/bin HOME="$old/home" KALANDRA_CHECKOUT="$grit_checkout" \
    GRIT_DATABASE_URL="jdbc:postgresql://localhost:5432/$restored" KALANDRA_LISTEN="$listen" KALANDRA_LOGS="$old/logs" \
    ANTHROPIC_API_KEY=edge-proof-no-key JEV_API_KEY=edge-proof-no-key \
    "$java" --sun-misc-unsafe-memory-access=allow -jar "$old/out/assembly.dest/out.jar" serve) >"$work/old-serve.log" 2>&1 &
  started+=("$!")
  old_pid="$!"
  for _ in $(seq 1 240); do
    if grep -q "commands: serving on http://$listen" "$work/old-serve.log"; then old_up=0; break; fi
    kill -0 "$old_pid" 2>/dev/null || break
    sleep 0.5
  done
fi
holds "$old_up" "the old jar serves the copy: commands: serving on http://$listen"
if [[ "$old_up" == 0 ]]; then
  O() { env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" "$old/bin/kalandra" "$@"; }
  expect 0 "old: list edge-proof, with no credential" O list '{"project":"edge-proof"}'
  grep -q "edge-proof/n" <<<"$out" && grep -q "edge-proof/w" <<<"$out" && grep -q "edge-proof/by-session" <<<"$out"
  holds $? "  and it lists what the new serve kept"
  expect 0 "old: show edge-proof/n" O show '{"key":"edge-proof/n"}'
  grep -q "note-text-2" <<<"$out"; holds $? "  as the new serve kept it last"
  expect 0 "old: claims of edge-proof" O claims '{"project":"edge-proof"}'
  grep -qF "$H" <<<"$out" && grep -qF "$AH" <<<"$out"; holds $? "  and they name the new serve's holders $H and $AH"
  OH="$(O holder "{\"dir\":\"$repo\"}")" || OH=""
  [[ "$OH" == "$H" ]]; holds $? "old: the repository's holder is the new serve's, $OH"
  old_s="$(sql "SELECT array_to_string(place, '/') FROM grit.sessions WHERE local = 'proof-s1'")"
  [[ "$old_s" == "worker/local$repo" ]]; holds $? "the new serve's session is under worker again: $old_s"
  rc=0; J "$repo" "$work/jhome" "$edge" --mcp-name kalandra <"$e4c/join.sh" >"$work/join-back.out" 2>&1 || rc=$?
  holds "$rc" "0.2.0's join again, against the rolled-back serve, exits 0"
  [[ "$(entry "$repo" "$work/jhome")" == "$old_entry" ]]; holds $? "  and its MCP entry is the one 0.2.0's join first left"
  hb="$(payload proof-s3 SessionStart | jq -c '. + {source: "startup"}' | CLAUDE_PROJECT_DIR="$repo" PATH=/usr/bin:/bin "$hook" brief | jq -r '.hookSpecificOutput.additionalContext // empty')" || hb=""
  grep -q "note-text-2" <<<"$hb"; holds $? "  and its hook's brief, from the old serve, holds the claimed note"
  ours "$old_pid"; holds $? "the old serve still runs in its directory, pid $old_pid"
  stop "$old_pid"
  ! kill -0 "$old_pid" 2>/dev/null; holds $? "the old serve stopped by its pid"
  [[ -z "$(find "$old/home" -mindepth 1 -print -quit)" ]]; holds $? "the old serve's HOME is still empty"
fi

say "total $(grep -c '^ok ' "$proof/proof.txt") checks passed, $failed failed"
if [[ "$failed" == 0 ]]; then
  say "edge-proof: passed"
else
  say "edge-proof: $failed checks failed"
  echo "serve's log ends:" >&2
  tail -20 "$work/serve.log" >&2
  if [[ -f "$work/old-serve.log" ]]; then
    echo "the old serve's log ends:" >&2
    tail -20 "$work/old-serve.log" >&2
  fi
  exit 1
fi
