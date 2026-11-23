#!/usr/bin/env bash
# The proof, before a deploy, that this checkout's commit deploys over the live one and serves
# through grit's commands edge with credentials: it restores a copy of grit_kalandra, makes a
# copy of the live checkout (a clone at its commit, with its jar) serving the copy on a second
# port, joins two checkouts of a throwaway repository to it, and runs `bin/kalandra deploy` there:
# with the command line's credential missing and the sessions' digest not configured, stopped by a
# data file that would drop a row's subject; failing after the data file is kept, and rolled back
# to the copy as it began; failing so, with a reverse that raises, and rolled back by restoring its
# dump, then with that restore failing; stopped by a project its re-key refuses, before any re-key
# is sent; refused a record over the proof's budget, and given --began; whole, re-keying every
# project of the old form, its serve's start making grit's index of a session's agents; refused a
# brief the running serve refuses; and again, with nothing to rewrite, over a budget lowered to see
# it say so, its start quiet about the index. It then drives the deployed serve the way the command
# line, sessions, agents, the join and Claude Code will, and stops what it started and drops the
# copy.
#
#   ops/edge-proof.sh
#
# Run from this checkout, committed, after `bin/kalandra build`. It reads grit_kalandra once (a
# pg_dump, as ops/backup.sh does) and never writes it; it restores the dump into grit_kalandra_e7,
# where it cancels every pending or enqueued DBOS workflow so nothing resumes, and, once live is
# past the place rename, runs the reverse, so the deploys have a data file to keep and roll back.
# Everything it serves is on 127.0.0.1:7318, with keys that are no keys, so nothing it serves can
# spend; the deploys run with a HOME of the proof's own, holding credentials of its own, as live
# holds its, each with its digest in the copy's .env, never printed: the first deploy makes the
# command line's again and appends both digests, and the rest keep them. The copy's .env names
# one other machine, laptop, by a credential of the proof's own, which every deploy leaves as it is.
#
# Into the proof's directory, ${KALANDRA_PROOF_DIR:-$HOME/.cache/kalandra-e7}, it saves the jar it
# proved (serve.jar), the join script that jar serves (join.sh), grit's forward rewrite at the
# commit the jar is built against (machine-from-worker.sql), this checkout's reverse
# (worker-from-machine.sql), this checkout's claim-session.sql, whose statements must be grit's
# claim-session.sql's at that commit, and its reverse (claim-session-reverse.sql), each with its
# sha256, a fresh join's output (join.txt), the whole
# deploy's dry-run counts (forward-dry.txt), what its re-key did (rekey-report.txt: counts, seconds,
# refusals, what is left in the old form, how far each artifact is believed, five samples) and
# each check's line (proof.txt), which names the
# commit proved: `bin/kalandra deploy` runs those exact files, and only that commit.
#
# It calls no model. Every call of the installed Claude Code (its --version, the fresh join's
# `claude mcp add-json`, `claude mcp get`, the undo's `claude mcp remove`) runs with a HOME of the
# proof's own, no login and a key that is no key, a model's base URL and every proxy naming
# 127.0.0.1:7320, which logs and refuses all it is sent, and must be sent nothing. Its MCP client
# connects through the join's headersHelper by `claude mcp get`, which calls no model, in a joined
# checkout: its entry is the one the join wrote but for its URL, a forwarder on 127.0.0.1:7319
# (ops/forward.py) that checks each request's Authorization header is the sessions' credential's
# (by its digest), never logging its value. A real Claude Code turn is ops/harness-check.sh's, run
# by hand; the proof prints one line when the installed claude or the join script is not the one
# that check last passed under.
#
# It must take at most proof_budget seconds, so that with the deploy's own budget
# (ops/deploy.sh) a proof and the deploy after it take five minutes at most. It prints each
# stage's seconds, fails itself at the first stage boundary past its budget, naming its three
# slowest stages, and its pass record says how long it took, which the deploy refuses over the
# budget. KALANDRA_PROOF_BUDGET may set a budget lower than proof_budget, never higher.
#
# Refused, exit 1, before anything is started: KALANDRA_LISTEN names 7317, the checkout has
# changes, there is no jar, the jar is not built against the grit build.mill pins (a release's
# commit, its tag's in grit's checkout; a snapshot's, its marker's), grit's checkout has no
# machine-from-worker.sql or claim-session.sql at that commit, or ops/claim-session.sql's ALTER
# statements are not that claim-session.sql's, the live checkout has no jar, grit_kalandra_e7 or
# grit_kalandra_e7_join exists, something listens on 7318, 7319 or 7320, or claude is not on
# PATH. Prints a line a check, and `edge-proof: passed`, exit 0, only when every check held. Every process it starts,
# and every one a deploy starts, runs in a directory under its own, and is stopped by its PID only
# while that PID still runs there; the copies are dropped, however it ends.
set -euo pipefail

# The proof's budget, in seconds; ops/deploy.sh reads it here, at the commit proved.
proof_budget=240
t_began="${EPOCHREALTIME/,/.}"

wt="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$wt/out/assembly.dest/out.jar"
compose="${GRIT_COMPOSE_DIR:-$HOME/Projects/grit}"
grit_checkout="${KALANDRA_CHECKOUT:-$HOME/Projects/grit}"
proof="${KALANDRA_PROOF_DIR:-$HOME/.cache/kalandra-e7}"
# The live checkout, which is cloned and whose jar is copied, never written.
live="${KALANDRA_LIVE:-$HOME/Projects/kalandra}"
restored=grit_kalandra_e7
joindb=grit_kalandra_e7_join
listen=127.0.0.1:7318
forward=127.0.0.1:7319
outside=127.0.0.1:7320
edge="http://$listen"
# Where ops/harness-check.sh records the claude and the join script it last passed under.
harness_record="${XDG_STATE_HOME:-$HOME/.local/state}/kalandra-harness-check/passed.txt"
[[ $# == 0 ]] || { echo "usage: ops/edge-proof.sh" >&2; exit 64; }
budget="$proof_budget"
if [[ -n "${KALANDRA_PROOF_BUDGET:-}" ]]; then
  [[ "$KALANDRA_PROOF_BUDGET" =~ ^[0-9]+$ && "$KALANDRA_PROOF_BUDGET" -le "$proof_budget" ]] ||
    { echo "edge-proof: KALANDRA_PROOF_BUDGET may lower the budget of $proof_budget s, not raise it" >&2; exit 64; }
  budget="$KALANDRA_PROOF_BUDGET"
fi

pg() { docker compose --project-directory "$compose" exec -T postgres "$@"; }
sql() { pg psql -U grit -d "$restored" -At -v ON_ERROR_STOP=1 -c "$1"; }
listening() { ss -Hltn "sport = :$1" 2>/dev/null | grep -q .; }
sha() { sha256sum <"$1" | cut -d' ' -f1; }
# How many stored places, in every table the rename rewrites, are under the key $1.
under() {
  sql "SELECT (SELECT count(*) FROM grit.places WHERE path[1] = '$1') + (SELECT count(*) FROM grit.sessions WHERE place[1] = '$1') + (SELECT count(*) FROM grit.documents WHERE place[1] = '$1') + (SELECT count(*) FROM grit.files WHERE place[1] = '$1') + (SELECT count(*) FROM grit.piece_sets WHERE place[1] = '$1') + (SELECT count(*) FROM grit.visibility_changes WHERE change ->> 'room' LIKE '$1:%') + (SELECT count(*) FROM grit.session_events WHERE shape ->> 'worktree' LIKE '$1:%')"
}

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
git -C "$wt" diff --quiet HEAD -- || refuse "the checkout has changes: deploy runs only a commit that was proved, so commit first"
[[ -f "$jar" ]] || refuse "no jar at $jar: run bin/kalandra build first"
version="$(sed -n 's/^ *def gritVersion = "\(.*\)"$/\1/p' "$wt/build.mill")"
# The grit commit the jar must be built against: a release's tag's, in grit's checkout; a
# snapshot's marker's.
if [[ "$version" == *-SNAPSHOT ]]; then
  marker="$HOME/.ivy2/local/dev.grit/.publish-local-$version"
  at="$(cat "$marker" 2>/dev/null)" || refuse "no marker for grit $version at $marker"
else
  at="$(git -C "$grit_checkout" rev-parse --verify -q "refs/tags/v$version^{commit}")" ||
    refuse "grit's checkout $grit_checkout has no tag v$version"
fi
# The jar is an executable assembly, a launcher before its zip, which unzip warns of; Python's
# zipfile reads past it.
built="$(python3 -I -c 'import sys, zipfile; print(zipfile.ZipFile(sys.argv[1]).read("kalandra/grit.txt").decode().strip())' "$jar" 2>/dev/null)" ||
  refuse "the jar carries no kalandra/grit.txt"
[[ "$built" == "$version at $at" ]] || refuse "the jar is built against grit $built, not $version at $at: build again"
forward_sql="$(git -C "$grit_checkout" show "$at:core/dbos/it/resources/machine-from-worker.sql")" ||
  refuse "grit's checkout has no machine-from-worker.sql at $at"
# A file's ALTER statements, one a line, its comments dropped and its whitespace made one space.
alters() { sed 's/--.*$//' | tr '\n' ' ' | tr -s ' ' | tr ';' '\n' | sed 's/^ //; s/ $//' | grep '^ALTER TABLE ' || true; }
grit_claims_sql="$(git -C "$grit_checkout" show "$at:core/dbos/it/resources/claim-session.sql")" ||
  refuse "grit's checkout has no claim-session.sql at $at"
[[ -n "$(alters <<<"$grit_claims_sql")" && "$(alters <<<"$grit_claims_sql")" == "$(alters <"$wt/ops/claim-session.sql")" ]] ||
  refuse "ops/claim-session.sql's ALTER statements are not grit's claim-session.sql's at $at"
[[ -f "$live/out/assembly.dest/out.jar" ]] || refuse "no live jar at $live/out/assembly.dest/out.jar"
for d in "$restored" "$joindb"; do
  if [[ -n "$(pg psql -U grit -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname = '$d'")" ]]; then
    refuse "$d exists: a proof may be running"
  fi
done
listening 7318 && refuse "something listens on 7318"
listening 7319 && refuse "something listens on 7319"
listening 7320 && refuse "something listens on 7320"
command -v claude >/dev/null || refuse "claude is not on PATH"

work="$(cd "$(mktemp -d)" && pwd -P)"
# Every process the proof starts runs in a directory under $work, and so does every one a deploy
# starts (its run, under the deploys' HOME; serve, in the checkout's copy), so a pid's cwd shows
# it is the proof's own; `started` holds each the proof started until it is waited for.
mkdir -p "$work/run"
started=()
copied=0

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
# The pids running in a directory under the work directory but this shell: a deploy's run and the
# serve it started, which are no children of the proof's.
strays() {
  local p pid
  for p in /proc/[0-9]*; do
    pid="${p#/proc/}"
    [[ "$pid" == "$$" || "$pid" == "${BASHPID-}" ]] && continue
    if ours "$pid"; then echo "$pid"; fi
  done
  return 0
}

cleanup() {
  local pid d
  for pid in "${started[@]}"; do stop "$pid"; done
  for pid in $(strays); do stop "$pid"; done
  if [[ "$copied" == 1 ]]; then
    for d in $restored $joindb $(pg psql -U grit -d postgres -At -c "SELECT datname FROM pg_database WHERE datname LIKE '${restored}\\_%'" 2>/dev/null); do
      pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS $d WITH (FORCE)" >/dev/null || true
    done
  fi
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

# Seconds, to a tenth, as the deploy counts them.
now() { local t="$EPOCHREALTIME"; echo "${t/,/.}"; }
since() { awk -v a="$1" -v b="${2:-$(now)}" 'BEGIN { printf "%.1f", b - a }'; }
# Each stage ended, as "<seconds> <name>"; the one running, and when it began.
stages=()
stage_name=refusals
stage_at="$t_began"
# stage <name>: ends the stage running, printing its seconds, and ends the proof, failed, when it
# is past its budget; then starts the stage <name>.
stage() {
  local t took
  t="$(now)"
  stages+=("$(since "$stage_at" "$t") $stage_name")
  say "time  $stage_name took ${stages[-1]%% *} s"
  stage_name="$1" stage_at="$t"
  took="$(since "$t_began" "$t")"
  awk -v a="$took" -v b="$budget" 'BEGIN { exit !(a > b) }' || return 0
  say "edge-proof: over its $budget s budget at $took s; its three slowest stages: $(printf '%s\n' "${stages[@]}" | sort -rn | head -n 3 |
    awk '{ s = $1; $1 = ""; printf "%s%s %s s", (NR > 1 ? ", " : ""), substr($0, 2), s }')"
  exit 1
}

proved="$(git -C "$wt" rev-parse HEAD)"
say "edge-proof: $(date -u +%Y-%m-%dT%H:%M:%SZ), checkout $wt at $(git -C "$wt" rev-parse --short HEAD)"
say "commit $proved"
say "grit  the jar is built against grit $built"
keep_file "$jar" serve.jar
printf '%s\n' "$forward_sql" >"$work/machine-from-worker.sql"
keep_file "$work/machine-from-worker.sql" machine-from-worker.sql
keep_file "$wt/ops/worker-from-machine.sql" worker-from-machine.sql
keep_file "$wt/ops/claim-session.sql" claim-session.sql
keep_file "$wt/ops/claim-session-reverse.sql" claim-session-reverse.sql

stage copy
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
cancelled="$(sql "WITH c AS (UPDATE dbos.workflow_status SET status = 'CANCELLED' WHERE status IN ('PENDING', 'ENQUEUED') RETURNING 1) SELECT count(*) FROM c")"
say "copy  $cancelled pending or enqueued workflows cancelled in the copy"
machine_before="$(under machine)"
# Once live is past the rename, the copy is put back before it by the reverse, so that the deploys
# below have a data file to keep, stop at and roll back, as the deploy that renamed live had.
if [[ "$machine_before" != 0 && "$(under worker)" == 0 ]]; then
  pg psql -U grit -d "$restored" -q -v ON_ERROR_STOP=1 -At -f - -c COMMIT <"$wt/ops/worker-from-machine.sql" >/dev/null
  holds $? "live is past the rename, $machine_before places under machine: the copy is put back before it by worker-from-machine.sql"
  machine_before="$(under machine)"
fi
[[ "$machine_before" == 0 ]]; holds $? "no place is under machine before the rename, so the reverse is exact ($machine_before)"
# How many of the two columns claim-session.sql adds grit.claims has: none while live is before
# it, when each deploy below runs it as it runs the rename; both once live is past it, when it
# has nothing to rewrite (the copy is not put back before it, as the live jar reads the columns).
claims_columns() { sql "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'grit' AND table_name = 'claims' AND column_name IN ('session_harness', 'session_local')"; }
case "$(claims_columns)" in
  0) claims_pending=1 ;;
  2) claims_pending=0 ;;
  *) claims_pending=x ;;
esac
[[ "$claims_pending" != x ]]; holds $? "grit.claims has both or neither of claim-session.sql's columns ($(claims_columns)): claim-session.sql pending $claims_pending"
# The index of a session's agents by their parent (grit's schema.sql), which a start of a build
# that has it makes and every later start leaves as it is: whether the copy has it before any
# deploy, as live does.
parent_index() { sql "SELECT count(*) FROM pg_indexes WHERE schemaname = 'grit' AND tablename = 'sessions' AND indexname = 'idx_sessions_parent'"; }
say "fact  idx_sessions_parent in the copy before any deploy: $(parent_index)"
# What a rollback that reverses every data file it kept says of them, the last kept first.
claims_reversed_first=""
[[ "$claims_pending" != 1 ]] || claims_reversed_first="claim-session.sql reversed, "
reversed="${claims_reversed_first}machine-from-worker.sql reversed"
# Every rewritten table's place, by primary key, kept in the copy to compare after each rollback.
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
# The rows whose place differs from the copy's before any deploy, of those that were there then.
changed_q="SELECT b.tbl || ' ' || b.k FROM e7proof.before b LEFT JOIN e7proof.placed p ON p.tbl = b.tbl AND p.k = b.k WHERE p.v IS DISTINCT FROM b.v ORDER BY 1"

stage "join script"
# ---- 2. The proved jar's join script, from a serve of it on an empty database; serve refuses to
# start without either digest, or with an entry of KALANDRA_MACHINES not <machine>:<digest>.
mkdir -p "$work/home" "$work/cred"
( umask 077
  for c in a b wrong laptop; do head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' >"$work/cred/$c"; done
  printf 'Authorization: Bearer %s\n' "$(<"$work/cred/wrong")" >"$work/cred/wrong.header" )
d() { tr -d '\n' <"$1" | sha256sum | cut -d' ' -f1; }
wrong="$work/cred/wrong"
pg psql -U grit -d postgres -q -c "CREATE DATABASE $joindb" >/dev/null
serve_env=(env -i PATH=/usr/bin:/bin HOME="$work/home" KALANDRA_CHECKOUT="$grit_checkout"
  GRIT_DATABASE_URL="jdbc:postgresql://localhost:5432/$joindb" KALANDRA_LISTEN="$listen"
  ANTHROPIC_API_KEY=edge-proof-no-key JEV_API_KEY=edge-proof-no-key)
for missing in KALANDRA_OWNER_DIGEST KALANDRA_SESSIONS_DIGEST; do
  digests=(KALANDRA_OWNER_DIGEST="$(d "$work/cred/a")" KALANDRA_SESSIONS_DIGEST="$(d "$work/cred/b")")
  digests=("${digests[@]/#$missing=*/}")
  rc=0
  (cd "$work/run" && exec timeout 120 "${serve_env[@]}" ${digests[@]} "$wt/bin/kalandra" serve) >"$work/refused.log" 2>&1 || rc=$?
  [[ "$rc" == 1 ]] && grep -qxF "[kalandra] $missing is not set: the SHA-256 digest, 64 lowercase hex, of $([[ $missing == *OWNER* ]] && echo "the command line's" || echo "the sessions'") credential" "$work/refused.log"
  holds $? "serve without $missing exits 1, its log naming the variable: $(tail -1 "$work/refused.log")"
done
# An entry written as a variable is, `=` for `:`, which the refusal names by its number alone.
rc=0
(cd "$work/run" && exec timeout 120 "${serve_env[@]}" KALANDRA_OWNER_DIGEST="$(d "$work/cred/a")" KALANDRA_SESSIONS_DIGEST="$(d "$work/cred/b")" \
  KALANDRA_MACHINES="laptop=$(d "$work/cred/laptop")" "$wt/bin/kalandra" serve) >"$work/refused.log" 2>&1 || rc=$?
[[ "$rc" == 1 ]] && grep -qxF "[kalandra] KALANDRA_MACHINES: entry 1 is not <machine>:<digest>" "$work/refused.log" &&
  ! grep -qF "$(d "$work/cred/laptop")" "$work/refused.log"
holds $? "serve with a KALANDRA_MACHINES entry not <machine>:<digest> exits 1, its log naming the variable, not the digest: $(tail -1 "$work/refused.log")"
# serve_up <pid> <log>: 0 once the log says it serves, 1 when the pid ends or 120 s pass first.
serve_up() {
  for _ in $(seq 1 240); do
    if grep -q "commands: serving on http://$listen" "$2"; then return 0; fi
    kill -0 "$1" 2>/dev/null || return 1
    sleep 0.5
  done
  return 1
}
(cd "$work/run" && exec "${serve_env[@]}" KALANDRA_OWNER_DIGEST="$(d "$work/cred/a")" KALANDRA_SESSIONS_DIGEST="$(d "$work/cred/b")" \
  "$wt/bin/kalandra" serve) >"$work/join-serve.log" 2>&1 &
started+=("$!")
join_pid="$!"
serve_up "$join_pid" "$work/join-serve.log"; up=$?
holds "$up" "the proved jar serves an empty database: commands: serving on $edge"
curl -q -fsS --noproxy '*' --max-time 10 -H @<(printf 'Authorization: Bearer %s\n' "$(<"$work/cred/b")") "$edge/join/claude-code" >"$work/join.sh"
holds $? "  and serves its join script to a credential it takes"
stop "$join_pid"
pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS $joindb WITH (FORCE)" >/dev/null
keep_file "$work/join.sh" join.sh
if [[ "$up" != 0 ]]; then
  tail -30 "$work/join-serve.log" >&2
  exit 1
fi
# Every call of the installed claude runs in this environment: a HOME of the proof's own, no
# login, a key that is no key, and a model's base URL and every proxy the sink on 7320, which
# logs and refuses all it is sent (started in step 10, and asked in step 12 whether it was sent
# anything).
real_claude="$(dirname "$(command -v claude)")"
mkdir -p "$work/jhome2"
no_model=(env -i PATH="$real_claude:/usr/bin:/bin" HOME="$work/jhome2" ANTHROPIC_API_KEY=edge-proof-no-key
  ANTHROPIC_BASE_URL="http://$outside" HTTPS_PROXY="http://$outside" HTTP_PROXY="http://$outside"
  https_proxy="http://$outside" http_proxy="http://$outside" NO_PROXY=127.0.0.1 no_proxy=127.0.0.1
  CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1)
# What ops/harness-check.sh last passed under, beside the claude installed and the join this jar
# serves: one line when either differs, which says to run it by hand; the proof passes either way.
installed="$(cd "$work/run" && "${no_model[@]}" claude --version 2>/dev/null | head -n 1)"
r_claude="$(sed -n 's/^claude //p' "$harness_record" 2>/dev/null | head -n 1)"
r_join="$(sed -n 's/^join //p' "$harness_record" 2>/dev/null | head -n 1)"
if [[ ! -f "$harness_record" ]]; then
  say "note  ops/harness-check.sh has no pass recorded in $harness_record: run it by hand"
elif [[ "$r_claude" != "$installed" || "$r_join" != "$(sha "$work/join.sh")" ]]; then
  say "note  ops/harness-check.sh last passed under claude ${r_claude:-?} and join ${r_join:0:12}; installed are claude $installed and join $(sha "$work/join.sh" | head -c 12): run it by hand"
else
  say "harn  ops/harness-check.sh last passed under claude $installed and this join"
fi

stage "live copy"
# ---- 3. The live checkout's copy: a clone at its commit, its jar, keys that are no keys; serving
# the copy on 7318, and two checkouts of a repository joined to it with its own join script. A
# deploy's HOME is $dhome, where `claude` is a stand-in that keeps local-scope MCP entries in
# ~/.claude.json, keyed by the repository, as Claude Code does.
clone="$work/live"
git clone -q "$live" "$clone"
old_head="$(git -C "$clone" rev-parse HEAD)"
old_branch="$(git -C "$clone" symbolic-ref -q --short HEAD)"
mkdir -p "$clone/out/assembly.dest"
cp -- "$live/out/assembly.dest/out.jar" "$clone/out/assembly.dest/out.jar"
old_jar="$(sha "$clone/out/assembly.dest/out.jar")"
say "live  $clone, a clone of $live at $old_head on $old_branch, its jar $old_jar"
git -C "$clone" merge-base --is-ancestor "$old_head" "$proved"; holds $? "the proved commit descends from the live checkout's"
dhome="$work/dhome"
mkdir -p "$work/shim" "$dhome"
# The credentials, as live has them: the command line's and the sessions', each with its digest in
# the copy's .env, which the old serve needs to start.
own="$dhome/.config/kalandra/credential"; ses="$dhome/.config/grit/credential"
( umask 077
  mkdir -p "$(dirname "$own")" "$(dirname "$ses")"
  for c in "$own" "$ses"; do head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' >"$c"; done
  printf 'ANTHROPIC_API_KEY=edge-proof-no-key\nJEV_API_KEY=edge-proof-no-key\nKALANDRA_OWNER_DIGEST=%s\nKALANDRA_SESSIONS_DIGEST=%s\nKALANDRA_MACHINES=laptop:%s\n' \
    "$(d "$own")" "$(d "$ses")" "$(d "$work/cred/laptop")" >"$clone/.env" )
machines_line="KALANDRA_MACHINES=laptop:$(d "$work/cred/laptop")"
cat >"$work/shim/claude" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
f="$HOME/.claude.json"; [ -f "$f" ] || echo '{}' >"$f"
top="$(git rev-parse --show-toplevel)"
put() { jq --arg p "$top" --arg n "$1" --argjson e "$2" '.projects[$p].mcpServers[$n] = $e' "$f" >"$f.t" && mv "$f.t" "$f"; }
# The planted failure, once: while a deploy joins, a key a session writes into the settings, and
# a keep through the serve it started (a note, which sits anywhere under every version of the
# method), once, as its project is new; then the add fails.
if [[ -n "${EDGE_PROOF_PLANT-}" && -e "$EDGE_PROOF_PLANT" && "$1 $2" == "mcp add-json" ]]; then
  rm -f -- "$EDGE_PROOF_PLANT"
  s="$top/.claude/settings.local.json"
  jq '. + {proofMidDeploy: "kept"}' "$s" >"$s.t" && mv "$s.t" "$s"
  if "$EDGE_PROOF_WT/bin/kalandra" new '{"project":"edge-proof-window"}' >/dev/null 2>&1; then
    "$EDGE_PROOF_WT/bin/kalandra" keep '{"project":"edge-proof-window","handle":"m","kind":"note","title":"Window","body":"window-text-1"}' >/dev/null
  fi
  exit 1
fi
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
plant="$work/plant-join"
docker_dir="$(dirname "$(command -v docker)")"
# A deploy's environment, and the copy's serve's: nothing but what the proof names.
denv=(env -i PATH="$work/shim:$docker_dir:/usr/bin:/bin" HOME="$dhome" DOCKER_CONFIG="$HOME/.docker"
  GRIT_COMPOSE_DIR="$compose" KALANDRA_LISTEN="$listen" GRIT_DATABASE_URL="jdbc:postgresql://localhost:5432/$restored"
  KALANDRA_CHECKOUT="$grit_checkout" EDGE_PROOF_WT="$wt" EDGE_PROOF_PLANT="$plant")
rc=0; (cd "$work/run" && exec "${denv[@]}" "$clone/bin/kalandra" start) >"$work/old-start.out" 2>&1 || rc=$?
holds "$rc" "the live checkout's copy starts its serve on the copy: $(tail -1 "$work/old-start.out")"
init() { git init -q "$1" && git -C "$1" -c user.name=proof -c user.email=proof@example.invalid commit -q --allow-empty -m 'edge proof start'; }
repo="$work/repo"
repo_wt="$work/repo-wt"
init "$repo"; init "$work/repo2"
git -C "$repo" worktree add -q "$repo_wt" -b proof-wt
J() { local dir="$1" home="$2"; shift 2; (cd "$dir" && HOME="$home" PATH="$work/shim:/usr/bin:/bin" bash -s -- "$@"); }
entry() { jq -c --arg p "$1" '.projects[$p].mcpServers.kalandra // null' "$2/.claude.json" 2>/dev/null; }
curl -q -fsS --noproxy '*' --max-time 10 -H @<(printf 'Authorization: Bearer %s\n' "$(<"$ses")") "$edge/join/claude-code" >"$work/old-join.sh"
holds $? "the copy's serve serves its join script, $(sha "$work/old-join.sh")"
for w in "$repo" "$repo_wt"; do
  rc=0; J "$w" "$dhome" "$edge" --credential "$ses" --mcp-name kalandra <"$work/old-join.sh" >"$work/join-old.out" 2>&1 || rc=$?
  holds "$rc" "the copy's join in $w exits 0"
done
hook="$repo/.git/grit/claude-code/hook"

# What a rollback must leave as it found: the clone's commit and jar, every file the join writes
# and its MCP entries, but a key a session writes mid-deploy; .env but the digests a deploy adds.
fingerprint() {
  local w f
  echo "head $(git -C "$clone" symbolic-ref -q --short HEAD) $(git -C "$clone" rev-parse HEAD)"
  echo "jar $(sha "$clone/out/assembly.dest/out.jar")"
  echo "env $(grep -vE '^(KALANDRA_(OWNER|SESSIONS)_DIGEST=.*)?$' "$clone/.env" | sha256sum | cut -d' ' -f1)"
  for w in "$repo" "$repo_wt"; do
    echo "settings $w $(jq -S -c 'del(.proofMidDeploy)' "$w/.claude/settings.local.json" | sha256sum | cut -d' ' -f1)"
    echo "mcp $w $(entry "$w" "$dhome")"
  done
  for f in "$hook" "$repo/.git/hooks/post-commit" "$repo/.git/hooks/post-rewrite" "$repo/.git/info/exclude"; do
    echo "file $f $(sha "$f")"
  done
  echo "places changed: $(sql "$changed_q" | tr '\n' ' ')"
  echo "places under machine: $(under machine)"
  echo "claims columns: $(claims_columns)"
}
fingerprint >"$work/began.txt"
say "began $(grep -c . "$work/began.txt") facts of the copy as it began"
mkdir -p "$work/proved"
for f in serve.jar join.sh machine-from-worker.sql worker-from-machine.sql claim-session.sql claim-session-reverse.sql; do
  cp -- "$proof/$f" "$proof/$f.sha256" "$work/proved/"
done
# The deploys read this proof's files and its lines so far, which end as a passed proof's do: a
# deploy runs only a proved commit, and the checks it is proved by follow.
stage_proof() {
  cp -- "$proof/proof.txt" "$work/proved/proof.txt"
  echo "time  edge-proof took $(since "$t_began") s" >>"$work/proved/proof.txt"
  echo "edge-proof: passed" >>"$work/proved/proof.txt"
}
# deploy <name> [VAR=value…]: the deploy, run as a person runs it, into the clone, with any more
# variables given; its exit in $drc, its last line in $dlast, its log in $dlog.
deploy() {
  drc=0
  (cd "$work/run" && exec timeout 900 "${denv[@]}" "${@:2}" "$wt/bin/kalandra" deploy --checkout "$clone" --grit "$repo" "$work/proved") \
    >"$work/deploy-$1.out" 2>&1 || drc=$?
  local full
  full="$(tail -n 1 "$work/deploy-$1.out")"
  dlog="$(sed -n 's/^deploy: log //p' "$work/deploy-$1.out")"
  say "dep   $1: exit $drc: $full"
  dlast="${full%; took *}"
  [[ "$full" =~ \;\ took\ [0-9]+\.[0-9]\ s,\ serve\ down\ [0-9]+\.[0-9]\ s(\ and\ not\ answering)?$ ]]
  holds $? "($1) its last line says the seconds it took and those serve was down"
}
began_as() {
  fingerprint >"$work/now.txt"
  cmp -s "$work/began.txt" "$work/now.txt"
  holds $? "  and the copy is as it began$(diff "$work/began.txt" "$work/now.txt" | grep '^>' | head -3 | tr '\n' ' ')"
}
stage_proof
if [[ "$failed" != 0 ]]; then
  say "edge-proof: $failed checks failed before the deploys"
  exit 1
fi

O() { env -i PATH=/usr/bin:/bin HOME="$dhome" KALANDRA_LISTEN="$listen" "$clone/bin/kalandra" "$@"; }
# The method versions the proved commit holds, as a deploy declares them, oldest first: each the
# copy has declared already is kept, and each it has not is declared (new_methods), which only
# the dump undoes.
expect 0 "the copy's serve lists the method's versions it has declared" O methods '{"name":"grit"}'
declared="$out"
methods_line="" new_methods=()
for f in $(cd "$wt/methods" && ls -- *.json | sort -t@ -k1,1 -k2,2n); do
  v="${f%.json}"
  if grep -q "^$v " <<<"$declared"; then methods_line+=" $v kept"; else methods_line+=" $v declared"; new_methods+=("$v"); fi
done
methods_line="${methods_line# }"
new_said="${new_methods[*]:-no method version}"
say "fact  the deploys declare $new_said: $methods_line"

stage "deploy a"
# ---- 4. (a) A data file that would drop a row's subject stops the deploy: one commit event under
# worker given a shape of 300 bytes, which the rename must take past the bound.
planted="$(sql "WITH e AS (SELECT session, regexp_replace(shape::text, ',\"subject\":\"[^\"\\\\]*\"\\}\$', '') AS head FROM grit.session_events WHERE kind = 'commit' AND shape ->> 'worktree' LIKE 'worker:%' AND shape::text ~ ',\"subject\":\"[^\"\\\\]*\"\\}\$' AND session IN (SELECT session FROM grit.session_events GROUP BY session HAVING max(seq) < 5000) ORDER BY session DESC, seq DESC LIMIT 1) INSERT INTO grit.session_events (session, seq, n, at, kind, shape) SELECT e.session, (SELECT max(seq) + 1 FROM grit.session_events WHERE session = e.session), (SELECT max(n) + 1 FROM grit.session_events WHERE session = e.session), now(), 'commit', (e.head || ',\"subject\":\"' || repeat('x', 300 - octet_length(e.head) - 14) || '\"}')::json FROM e RETURNING session || ' ' || seq || ' ' || octet_length(shape::text)")"
read -r p_session p_seq p_len <<<"$planted"
[[ "$p_len" == 300 ]]; holds $? "a commit event of 300 bytes under worker planted in the copy: $planted"
# The credentials' other two paths: the command line's removed, with its digest, for the deploy to
# make; the sessions' digest removed from .env, its file kept, for the deploy to append. The
# running serve keeps the digests it was started with until the deploy stops it.
old_own="$(d "$own")"
rm -f -- "$own"
sed -i '/^KALANDRA_\(OWNER\|SESSIONS\)_DIGEST=/d' "$clone/.env"
deploy a
[[ "$drc" == 2 && "$dlast" == "deploy: rolled back: machine-from-worker.sql would lose rows (grit.session_events rewritten without their subject 1); its counts are in the log, a person decides; left as it began: kalandra ${old_head:0:12}, jar ${old_jar:0:12}, serve answering on $listen; the credentials it made and the digest lines it added to $clone/.env stay, for the next deploy" ]]
holds $? "(a) the deploy stops at the row that would lose its subject, exit 2"
grep -qxF "deploy: dry  machine-from-worker.sql  grit.session_events rewritten without their subject  1" "$dlog"; holds $? "  its log has the dry run's count"
grep -qxF "deploy: credentials: $own made" "$dlog" &&
  grep -qxF "deploy: credentials: $ses kept; its digest is configured now" "$dlog" &&
  grep -qxF "deploy: credentials: KALANDRA_OWNER_DIGEST appended to $clone/.env" "$dlog" &&
  grep -qxF "deploy: credentials: KALANDRA_SESSIONS_DIGEST appended to $clone/.env" "$dlog"
holds $? "  it made the command line's credential and appended both digests"
[[ -f "$own" && "$(stat -c %a "$own")" == 600 && "$(d "$own")" != "$old_own" ]]; holds $? "  the credential it made is new, and 0600"
[[ "$(grep -cxE 'KALANDRA_(OWNER|SESSIONS)_DIGEST=[0-9a-f]{64}' "$clone/.env")" == 2 ]] &&
  grep -qxF "KALANDRA_OWNER_DIGEST=$(d "$own")" "$clone/.env" && grep -qxF "KALANDRA_SESSIONS_DIGEST=$(d "$ses")" "$clone/.env"
holds $? "  and the copy's .env holds each digest once, the credential's"
[[ "$(grep -c '^KALANDRA_MACHINES=' "$clone/.env")" == 1 ]] && grep -qxF "$machines_line" "$clone/.env"
holds $? "  and its KALANDRA_MACHINES line once, as it was"
expect 0 "  the old serve, rolled back, takes the credential the deploy made" O list '{"project":"grit","state":"now"}'
# The patterns read from the credentials' files, never put on a command line.
grep -qF -f "$own" -f "$ses" "$dlog" "$work/deploy-a.out"; [[ $? == 1 ]]; holds $? "  and neither log holds either credential"
began_as
sql "DELETE FROM grit.session_events WHERE session = $p_session AND seq = $p_seq" >/dev/null

stage "deploy b"
# ---- 5. (b) A failure after the data file is kept and the new serve is up: the join's MCP add
# fails, after a keep through the new serve and a key a session writes into the settings.
touch "$plant"
stage_proof
deploy b
[[ "$drc" == 2 && "$dlast" == "deploy: rolled back: the join in $repo exited 1: grit claude-code: claude mcp add failed"*"; left as it began: kalandra ${old_head:0:12}, jar ${old_jar:0:12}, $reversed, joins as before in 1 checkouts, serve answering on $listen" ]]
holds $? "(b) the deploy rolls back from a failed join, exit 2"
grep -qxF "deploy: machine-from-worker.sql: kept" "$dlog"; holds $? "  after it kept the data file"
if [[ "$claims_pending" == 1 ]]; then
  grep -qxF "deploy: claim-session.sql: kept" "$dlog" && grep -qxF "deploy: rollback: claim-session-reverse.sql kept" "$dlog"
  holds $? "  and claim-session.sql, which the rollback reversed"
fi
[[ ! -e "$plant" ]]; holds $? "  the plant fired"
[[ "$(jq -r '.proofMidDeploy' "$repo/.claude/settings.local.json")" == kept ]]; holds $? "  the key written mid-deploy is still in the settings"
say "fact  (b) $(grep -m 1 '^deploy: preflight: ' "$dlog" || echo "deploy: preflight said nothing of the brief")"
grep -qxF "deploy: credentials: $own kept; its digest is configured already" "$dlog" &&
  grep -qxF "deploy: credentials: $ses kept; its digest is configured already" "$dlog" &&
  [[ "$(grep -cxE 'KALANDRA_(OWNER|SESSIONS)_DIGEST=[0-9a-f]{64}' "$clone/.env")" == 2 ]]
holds $? "  it keeps the credentials (a) made and appended, and appends no digest again"
began_as
# The old serve knows no handle: it finds what the new serve kept by the key it is stored under.
expect 0 "the copy's serve, rolled back, shows what the new serve kept in its window, by the key it is stored under" O show '{"key":"edge-proof-window/1"}'
grep -q "window-text-1" <<<"$out"; holds $? "  as it kept it"

stage "deploys e, f"
# ---- 5b. (e) A reverse that raises: one session event naming its worktree under machine in a form
# the reverse refuses, and the join's failure after the keep; the rollback restores the dump into
# <copy>_restoring, then renames the copy aside and the restored one into its name. (f) The same,
# with a <copy>_restoring held open, so the restore fails before any rename.
psw() { pg psql -U grit -q -v ON_ERROR_STOP=1 -d "$restored" "$@"; }
plant_raise() {
  sql "INSERT INTO grit.session_events (session, seq, n, at, kind, shape) SELECT x.s, (SELECT max(seq) + 1 FROM grit.session_events WHERE session = x.s), (SELECT max(n) + 1 FROM grit.session_events WHERE session = x.s), now(), 'read', '{\"worktree\" : \"machine:local/edge-proof-plant\"}'::json FROM (SELECT session AS s FROM grit.session_events GROUP BY session HAVING max(seq) < 5000 ORDER BY session DESC LIMIT 1) x RETURNING session || ' ' || seq"
}
read -r r_session r_seq <<<"$(plant_raise)"
[[ -n "$r_seq" ]]; holds $? "a session event the reverse refuses planted in the copy: $r_session $r_seq"
touch "$plant"
stage_proof
deploy e
aside="$(sed -n "s/.*kept as \(${restored}_rolled_back_[0-9]*\), for a person.*/\1/p" <<<"$dlast")"
[[ "$drc" == 2 && "$dlast" == "deploy: rolled back: the join in $repo exited 1: grit claude-code: claude mcp add failed"*"; left as it began: kalandra ${old_head:0:12}, jar ${old_jar:0:12}, ${claims_reversed_first}$restored restored from the dump taken before the deploy (the database as the deploy left it is kept as $aside, for a person to drop once satisfied), joins as before in 1 checkouts, serve answering on $listen" ]]
holds $? "(e) the deploy rolls back by restoring the dump, exit 2, naming the aside database $aside"
grep -q "^deploy: rollback: worker-from-machine.sql raised: .*ERROR: " "$dlog"; holds $? "  after the reverse raised a server error"
[[ -n "$aside" && "$(pg psql -U grit -d postgres -At -c "SELECT count(*) FROM pg_database WHERE datname IN ('$aside', '${restored}_restoring')")" == 1 ]]
holds $? "  the aside database exists, and no ${restored}_restoring is left"
[[ "$(pg psql -U grit -d "$aside" -At -c "SELECT count(*) FROM grit.sessions WHERE place[1] = 'machine'")" != 0 ]]; holds $? "  the aside database holds the copy as the deploy left it, under machine"
[[ "$(sql "SELECT count(*) FROM grit.session_events WHERE session = $r_session AND seq = $r_seq")" == 1 && "$(sql "SELECT count(*) FROM grit.sessions WHERE place[1] = 'machine'")" == 0 ]]
holds $? "  the copy's name holds the dump's content: the planted event, no session under machine"
expect 0 "  the old jar answers on the restored copy" O show '{"key":"edge-proof-window/1"}'
sql "DELETE FROM grit.session_events WHERE session = $r_session AND seq = $r_seq" >/dev/null
began_as
pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS $aside WITH (FORCE)" >/dev/null

read -r r_session r_seq <<<"$(plant_raise)"
pg psql -U grit -d postgres -q -c "CREATE DATABASE ${restored}_restoring" >/dev/null
(cd "$work/run" && exec timeout 600 docker compose --project-directory "$compose" exec -T postgres \
  psql -U grit -d "${restored}_restoring" -q -c 'SELECT pg_sleep(600)') >/dev/null 2>&1 &
started+=("$!")
holder="$!"
for _ in $(seq 1 40); do
  [[ "$(pg psql -U grit -d postgres -At -c "SELECT count(*) FROM pg_stat_activity WHERE datname = '${restored}_restoring'")" == 0 ]] || break
  sleep 0.25
done
touch "$plant"
stage_proof
deploy f
[[ "$drc" == 3 && "$dlast" == "deploy: rollback incomplete: worker-from-machine.sql raised: "*", and making ${restored}_restoring for the restore failed: "*"; $restored is untouched, as the deploy left it, with machine-from-worker.sql kept and not reversed; ${restored}_restoring is left as it was, not the deploy's; serve is stopped, with the old code and jar in place; a person must put $restored right (the dump from before the deploy is "*"), then start serve: bin/kalandra start in $clone" ]]
holds $? "(f) a restore that fails before any rename ends rollback incomplete, exit 3"
[[ "$(sql "SELECT count(*) FROM grit.sessions WHERE place[1] = 'machine'")" != 0 && -z "$(pg psql -U grit -d postgres -At -c "SELECT datname FROM pg_database WHERE datname LIKE '${restored}\\_rolled\\_back\\_%'")" ]]
holds $? "  the copy's name is untouched, as the deploy left it, and nothing was renamed aside"
[[ "$(git -C "$clone" rev-parse HEAD) $(sha "$clone/out/assembly.dest/out.jar")" == "$old_head $old_jar" ]]; holds $? "  the old code and jar are in place"
! listening 7318; holds $? "  and serve is stopped: nothing listens on 7318"
[[ -e "$dhome/.local/state/kalandra-deploy/running" ]]; holds $? "  the next deploy is refused until a person clears it"
# A person's recovery, as the line says: the copy put right by hand, then serve started.
pg psql -U grit -d postgres -q -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${restored}_restoring'" >/dev/null
stop "$holder"
pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS ${restored}_restoring" >/dev/null
sql "DELETE FROM grit.session_events WHERE session = $r_session AND seq = $r_seq" >/dev/null
psw -f - -c COMMIT <"$proof/worker-from-machine.sql" >/dev/null; holds $? "  the reverse, run by hand once the plant is gone, exits 0"
rm -f -- "$dhome/.local/state/kalandra-deploy/running"
rc=0; (cd "$work/run" && exec "${denv[@]}" "$clone/bin/kalandra" start) >"$work/old-start.out" 2>&1 || rc=$?
holds "$rc" "  the old serve starts again"
rc=0; J "$repo" "$dhome" "$edge" --credential "$ses" --mcp-name kalandra <"$work/old-join.sh" >"$work/join-old.out" 2>&1 || rc=$?
holds "$rc" "  and the copy's join again in $repo exits 0"
began_as

stage "deploy g"
# ---- 5c. (g) A project that cannot be re-keyed: an artifact of a project other than grit (whose
# brief the deploy's preflight asks for) citing one the store does not hold. The deploy's survey
# says so, so no re-key is sent. When a method version was declared before it, which the old serve
# cannot read, the rollback restores the dump, and the old serve then lists no such version;
# otherwise it reverses the data files it kept. Once live holds no artifact of the old form, no
# re-key can be refused, and (g) is not run.
old_form="$(sql "SELECT count(*) FROM grit.documents WHERE plugin = 'projects' AND key NOT LIKE 'grit/%' AND superseded_at IS NULL AND data ? 'head' AND NOT data -> 'head' ? 'id'")"
if [[ "$old_form" == 0 ]]; then
  say "skip  (g): the copy holds no artifact of the old form outside grit, so no re-key can be refused"
else
  read -r planted_cite planted_key <<<"$(sql "WITH p AS (SELECT version FROM grit.documents WHERE plugin = 'projects' AND key NOT LIKE 'grit/%' AND superseded_at IS NULL AND data ? 'head' AND NOT data -> 'head' ? 'id' ORDER BY key LIMIT 1) UPDATE grit.documents d SET data = jsonb_set(d.data, '{head,cites}', (d.data -> 'head' -> 'cites') || to_jsonb(split_part(d.key, '/', 1) || '/edge-proof-gone')) FROM p WHERE d.version = p.version RETURNING d.version, d.key" | head -n 1 | tr '|' ' ')"
  gone="${planted_key%%/*}/edge-proof-gone"
  [[ -n "$planted_cite" ]]; holds $? "$planted_key, of the old form, planted citing $gone in the copy (version $planted_cite)"
  stage_proof
  deploy g
  aside="$(sed -n "s/.*kept as \(${restored}_rolled_back_[0-9]*\), for a person.*/\1/p" <<<"$dlast")"
  refused="deploy: rolled back: project_rekey's survey says a project cannot be re-keyed: "
  if ((${#new_methods[@]} > 0)); then
    [[ "$drc" == 2 && "$dlast" == "$refused"*"it cites $gone, which this transaction reads no artifact under"*"; left as it began: kalandra ${old_head:0:12}, jar ${old_jar:0:12}, $restored restored from the dump taken before the deploy (the database as the deploy left it is kept as $aside, for a person to drop once satisfied), joins as before in 2 checkouts, serve answering on $listen" ]]
    holds $? "(g) a project the survey cannot re-key rolls the deploy back before any re-key, exit 2, restoring the dump"
    grep -qF "deploy: rollback: a method version was declared, which only the dump undoes; restoring the dump" "$dlog" && ! grep -q "^deploy: rekey  " "$dlog"
    holds $? "  for the method version it declared; no re-key was sent"
    expect 0 "  the old serve lists the method's versions" O methods '{"name":"grit"}'
    ! grep -q "^${new_methods[-1]} " <<<"$out"; holds $? "  and ${new_methods[-1]} is not among them: $(tr '\n' ' ' <<<"$out" | head -c 200)"
  else
    [[ "$drc" == 2 && "$dlast" == "$refused"*"it cites $gone, which this transaction reads no artifact under"*"; left as it began: kalandra ${old_head:0:12}, jar ${old_jar:0:12}, $reversed, joins as before in 2 checkouts, serve answering on $listen" ]]
    holds $? "(g) a project the survey cannot re-key rolls the deploy back before any re-key, exit 2, reversing its data files"
    ! grep -q "^deploy: rekey  " "$dlog"; holds $? "  no re-key was sent"
  fi
  [[ -z "$aside" ]] || pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS $aside WITH (FORCE)" >/dev/null
  [[ -n "$planted_cite" ]] && sql "UPDATE grit.documents SET data = data #- '{head,cites,-1}' WHERE version = $planted_cite" >/dev/null
  began_as
fi

stage "deploys c, d"
# ---- 6. A record of a proof over its budget is refused; (c) the whole deploy; (d) again, with
# nothing to rewrite, over a budget lowered to 1 s, which it reports and holds; the deploy's health
# check, alone, writes nothing.
stage_proof
over="$(awk -v b="$proof_budget" 'BEGIN { printf "%.1f", b + 0.1 }')"
sed -i "s/^time  edge-proof took .*/time  edge-proof took $over s/" "$work/proved/proof.txt"
rc=0; out="$(cd "$work/run" && exec timeout 60 "${denv[@]}" "$wt/bin/kalandra" deploy --checkout "$clone" --grit "$repo" "$work/proved" 2>&1)" || rc=$?
[[ "$rc" == 1 && "$out" == "deploy: refused: the proof took $over s, over its $proof_budget s budget; prove again, cutting what made it slow; nothing was changed" ]]
holds $? "a proof that took $over s is refused, exit 1: $(tail -n 1 <<<"$out")"
stage_proof
expect 64 "a deploy given --began, which only the command gives its run, is refused as usage" \
  "${denv[@]}" "$wt/bin/kalandra" deploy --began 1.0 --checkout "$clone" --grit "$repo" "$work/proved"
# Each current artifact of the projects plugin as the copy holds it before the re-key: its key,
# label, how far it is believed, and its head and its body's references, for the report's
# samples and to hold that each is believed as it was.
artifacts_q="SELECT key, classification_id, integrity_id, jsonb_build_object('head', data -> 'head', 'references', data -> 'body' -> 'notes') AS d, data -> 'head' ->> 'id' AS id, data -> 'head' ->> 'handle' AS handle, jsonb_array_length(coalesce(data -> 'body' -> 'notes', '[]'::jsonb)) AS links FROM grit.documents WHERE plugin = 'projects' AND superseded_at IS NULL AND data ? 'head'"
sql "CREATE TABLE e7proof.artifacts_before AS $artifacts_q" >/dev/null
deploy c
X12="${proved:0:12}"
[[ "$drc" == 0 && "$dlast" == "deploy: held: kalandra $X12 with grit $version at ${at:0:12} serving on $listen; $((1 + claims_pending)) data files kept, 2 checkouts joined" ]]
holds $? "(c) the deploy holds, exit 0"
if [[ "$claims_pending" == 1 ]]; then
  grep -qxF "deploy: dry  claim-session.sql  grit.claims session columns added  1" "$dlog" &&
    grep -qxF "deploy: claim-session.sql: kept" "$dlog" && [[ "$(claims_columns)" == 2 ]]
  holds $? "  it kept claim-session.sql, and grit.claims has both its columns"
fi
# A method file's worked examples are outside its digest, so each version declared before is kept.
grep -qxF "deploy: methods: $methods_line" "$dlog"
holds $? "  it keeps each version declared already, under the same digest, and declares $new_said"
sed -n 's/^deploy: \([a-z]* took .*\)/time  (c) \1/p' "$dlog" | tee -a "$proof/proof.txt"
sed -n 's/^deploy: dry  machine-from-worker.sql  //p' "$dlog" >"$proof/forward-dry.txt"
sed 's/^/dry     /' "$proof/forward-dry.txt" | tee -a "$proof/proof.txt"
[[ "$(git -C "$clone" symbolic-ref -q --short HEAD) $(git -C "$clone" rev-parse HEAD)" == "$old_branch $proved" ]]
holds $? "  the copy's $old_branch is at the proved commit, checked out"
[[ "$(sha "$clone/out/assembly.dest/out.jar")" == "$(sha "$proof/serve.jar")" ]]; holds $? "  its jar is the proved jar"
[[ "$(parent_index)" == 1 ]]; holds $? "  its serve's start made idx_sessions_parent on grit.sessions ($(parent_index))"
# ---- 6b. The re-key (c) ran on the restored live dump, written up as rekey-report.txt: what the
# deploy's survey counted, what each re-key answered and how long it took, what is left in the
# old form (none may be), how far each artifact is believed after it (as before), and five
# samples, each artifact's head and references before and after.
report="$proof/rekey-report.txt"
sql "CREATE TABLE e7proof.artifacts_after AS $artifacts_q" >/dev/null
left_old="$(sql "SELECT count(*) FROM e7proof.artifacts_after WHERE id IS NULL")"
believed_changed="$(sql "SELECT count(*) FROM e7proof.artifacts_before b JOIN e7proof.artifacts_after a USING (key, classification_id) WHERE a.integrity_id <> b.integrity_id")"
believed_lower="$(sql "SELECT count(*) FROM e7proof.artifacts_before b JOIN grit.integrity_labels l ON l.id = b.integrity_id WHERE l.level < 3")"
{
  echo "re-key rehearsal: kalandra ${proved:0:12} with grit $built, deploy (c) on $restored, restored from one dump of grit_kalandra at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "counts, the deploy's survey before the re-key (project: artifacts, links in bodies, cites in heads, handles carried as k-<name>):"
  sed -n 's/^deploy: rekey dry  /  /p' "$dlog"
  echo
  echo "what each re-key answered:"
  sed -n 's/^deploy: rekey  /  /p' "$dlog"
  echo
  echo "seconds:"
  sed -n 's/^deploy: rekey: \(.* took .*\)$/  \1/p; s/^deploy: \(rekey took .*\)$/  the stage: \1/p' "$dlog"
  echo "  the whole deploy: $(sed -n 's/^deploy: held: .*; \(took .*\)$/\1/p' "$dlog" | tail -n 1)"
  echo
  echo "refusals: $(grep -m 1 '^deploy: rollback: ' "$dlog" | sed 's/^deploy: rollback: //' || true)"
  grep -q '^deploy: rollback: ' "$dlog" || echo "  none"
  echo
  echo "after the re-key:"
  echo "  $left_old artifacts left in the old form (none may be)"
  echo "  $believed_changed artifacts believed otherwise than before, of $(sql "SELECT count(*) FROM e7proof.artifacts_before") ($believed_lower of them believed below authority before)"
  sed -n 's/^deploy: \(health: .*\)$/  \1/p' "$dlog"
  echo
  echo "five samples, each artifact's head and references, before and after (a carried handle first, if any; then the first by id with a link in its body):"
  samples="$(sql "SELECT key FROM (SELECT key, 0 AS o, 0 AS n FROM e7proof.artifacts_after WHERE handle LIKE 'k-%' UNION ALL SELECT key, 1, regexp_replace(id, '^.*/', '')::int FROM e7proof.artifacts_after WHERE links > 0 AND id IS NOT NULL) s ORDER BY o, n LIMIT 5")"
  for k in $samples; do
    echo
    echo "== $k"
    diff -u --label "$k before" --label "$k after" \
      <(sql "SELECT d FROM e7proof.artifacts_before WHERE key = '$k' LIMIT 1" | jq -S .) \
      <(sql "SELECT d FROM e7proof.artifacts_after WHERE key = '$k' LIMIT 1" | jq -S .)
  done
} >"$report" 2>&1
say "rekey $(sed -n 's/^deploy: \(rekey took .*\)$/\1/p' "$dlog"); the report is $report"
grep -qxE "deploy: rekey: (a second survey lists none|nothing to re-key)" "$dlog"; holds $? "  the deploy re-keyed every project of the old form, and a survey lists none"
[[ "$left_old" == 0 ]]; holds $? "  no artifact is left in the old form ($left_old)"
[[ "$believed_changed" == 0 ]]; holds $? "  every artifact is believed as it was before the re-key ($believed_changed changed)"
grep -qxF "[kalandra] grit $version at $at" "$clone/logs/serve.log"; holds $? "  its serve logs [kalandra] grit $version at $at"
w_left="$(under worker)"; m_now="$(under machine)"
[[ "$w_left" == 0 && "$m_now" != 0 ]]; holds $? "  no place is under worker, $m_now under machine"
grep -qxF "deploy: join    credential $ses, sent with every request" "$dlog"; holds $? "  the join reports the sessions' credential"
grep -qxF "deploy: join    mcp       kalandra at $edge/mcp, local scope, replacing kalandra at $edge/mcp" "$dlog"; holds $? "  and replaces the copy's MCP entry"
for w in "$repo" "$repo_wt"; do
  [[ "$(entry "$w" "$dhome")" == "$(jq -nc --arg u "$edge/mcp" --arg h "'$hook' header" '{type: "http", url: $u, headersHelper: $h}')" ]]
  holds $? "  $w's MCP entry is $edge/mcp with the hook's header as its headersHelper"
done
# A brief the running serve refuses for a store reason, the artifact the health check briefs
# hidden in the copy, refuses the deploy before it changes anything.
hidden="$(sql "WITH h AS (UPDATE grit.documents SET superseded_at = now() WHERE key LIKE '%grit/roadmap-coder-rules' AND superseded_at IS NULL RETURNING version) SELECT string_agg(version::text, ',') FROM h")"
rc=0; out="$(cd "$work/run" && exec timeout 60 "${denv[@]}" "$wt/bin/kalandra" deploy --checkout "$clone" --grit "$repo" "$work/proved" 2>&1)" || rc=$?
[[ -n "$hidden" && "$rc" == 1 && "$out" == "deploy: refused: the running serve answers a brief of grit/roadmap-coder-rules as coder 409: "*"; the health check asks the same of the new serve; nothing was changed" ]]
holds $? "a brief the running serve refuses, its artifact hidden in the copy, refuses the deploy, exit 1: $(tail -n 1 <<<"$out")"
[[ -n "$hidden" ]] && sql "UPDATE grit.documents SET superseded_at = NULL WHERE version IN ($hidden)" >/dev/null
[[ "$(git -C "$clone" rev-parse HEAD)" == "$proved" && ! -e "$dhome/.local/state/kalandra-deploy/running" ]]
holds $? "  and nothing was changed: the copy's code is as deploy c left it, and no run was started"
stage_proof
log_before_d="$(stat -c %s "$clone/logs/serve.log")"
deploy d KALANDRA_DEPLOY_BUDGET=1
[[ "$drc" == 4 && "$dlast" == "deploy: held: kalandra $X12 with grit $version at ${at:0:12} serving on $listen; 0 data files kept, 2 checkouts joined" ]]
holds $? "(d) a second deploy holds over its budget, exit 4"
over_line="$(grep '^deploy: over its' "$dlog")"
[[ "$over_line" =~ ^deploy:\ over\ its\ 1\ s\ budget:\ it\ took\ [0-9]+\.[0-9]\ s\;\ held,\ not\ rolled\ back$ ]] &&
  [[ "$(tail -n 2 "$dlog" | head -n 1)" == "$over_line" ]] && grep -qxF "$over_line" "$work/deploy-d.out"
holds $? "  its log's last line but one, and the command's, says: $over_line"
expect 0 "  serve answers after it, not rolled back" O show '{"key":"edge-proof-window/m"}'
[[ ! -e "$dhome/.local/state/kalandra-deploy/running" ]]; holds $? "  and the next deploy is not refused for it"
grep -qxF "deploy: machine-from-worker.sql: nothing to rewrite" "$dlog" && grep -qxF "deploy: claim-session.sql: nothing to rewrite" "$dlog" &&
  ! grep -q "\.sql: kept" "$dlog"
holds $? "  each data file's dry run counts 0, and nothing is kept"
grep -qxF "deploy: credentials: $own kept; its digest is configured already" "$dlog"; holds $? "  and the credentials are kept as they are"
said_d="$(tail -c +"$((log_before_d + 1))" "$clone/logs/serve.log" | grep -aiE 'idx_sessions_parent|already exists' | head -n 3)"
[[ "$(parent_index)" == 1 && -z "$said_d" ]]
holds $? "  its serve's start, the second of the new build, keeps idx_sessions_parent and logs nothing of it${said_d:+: $said_d}"
grep -q "^deploy: health: a brief of .* answers 200 to the sessions' credential" "$dlog"; holds $? "  its health check asks for a brief"
grep -qxF "deploy: preflight: a brief of grit/roadmap-coder-rules as coder answers 200 to the running serve" "$dlog"
holds $? "  which its preflight asked first of the serve running before it"
# Every row of every table of grit's and DBOS's, by table: its count and a digest of its rows, but
# a heartbeat_at, which serve's engine writes on a timer whatever it is asked (grit.engines).
contents() {
  pg psql -U grit -d "$restored" -At -v ON_ERROR_STOP=1 <<'SQL'
SELECT format('SELECT %L || '' '' || count(*) || '' '' || md5(coalesce(string_agg(md5((to_jsonb(t) - ''heartbeat_at'')::text), '''' ORDER BY md5((to_jsonb(t) - ''heartbeat_at'')::text)), '''')) FROM %I.%I t',
  n.nspname || '.' || c.relname, n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p') AND n.nspname IN ('grit', 'dbos') ORDER BY 1
\gexec
SQL
}
contents >"$work/health-before.txt"
expect 0 "the deploy's health check alone, bin/kalandra deploy --health, passes" "${denv[@]}" "$wt/bin/kalandra" deploy --health
say "fact  deploy --health said: $(tr '\n' ';' <<<"$out")"
contents >"$work/health-after.txt"
[[ -s "$work/health-before.txt" ]] && cmp -s "$work/health-before.txt" "$work/health-after.txt"
holds $? "  and wrote nothing: every row of $(wc -l <"$work/health-before.txt") tables of grit and dbos is as it was$(diff "$work/health-before.txt" "$work/health-after.txt" | grep '^>' | cut -d' ' -f2 | head -5 | tr '\n' ' ' | sed 's/^./, but: &/')"

stage 401s
# ---- 7. 401 everywhere, with no credential and with a wrong one; nothing kept.
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

stage callers
# ---- 8. Each credential gets its caller: the command line's, which deploy (a) made, and the
# sessions'.
K() { env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$own" "$wt/bin/kalandra" "$@"; }
mkdir -p "$work/shome/.config/grit"
(umask 077; cp "$ses" "$work/shome/.config/grit/credential")
# A session: CLAUDE_CODE_SESSION_ID set, the command line's KALANDRA_CREDENTIAL inherited, which
# the session's marker overrides.
S() { env HOME="$work/shome" CLAUDE_CODE_SESSION_ID=proof-k1 KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$own" "$wt/bin/kalandra" "$@"; }
level() { sql "SELECT l.level FROM grit.documents d JOIN grit.integrity_labels l ON l.id = d.integrity_id WHERE d.key = '$1' AND d.superseded_at IS NULL"; }

expect 0 "list grit's now" K list '{"project":"grit","state":"now"}'
expect 0 "show grit/plan-harness-edge" K show '{"key":"grit/plan-harness-edge"}'
# An artifact's id, as project_show heads it.
id_of() { K show "{\"key\":\"$1\",\"head\":true}" | sed -n 's/^id: //p' | head -1; }
# The key an artifact is stored under, which a document's key column holds and a wait watches.
stored_of() { K stored "{\"key\":\"$1\"}"; }
rc=0
K standing '{"key":"grit/roadmap-grit-coder"}' >"$work/standing.md" 2>"$work/standing.err" || rc=$?
coder_id="$(id_of grit/roadmap-grit-coder)"
[[ -n "$coder_id" ]] && grep -qx "$coder_id at version [0-9]\+" "$work/standing.err" || rc=1
holds "$rc" "standing of grit/roadmap-grit-coder through the command line's credential, its id's version on stderr: $(head -c 80 "$work/standing.err")"
expect 0 "new project edge-proof" K new '{"project":"edge-proof"}'
# A milestone sits under a section, as the method's latest version nests it.
expect 0 "keep section edge-proof/s" K keep '{"project":"edge-proof","handle":"s","kind":"section","title":"Edge proof section","body":"Kept by ops/edge-proof.sh in a restored copy."}'
expect 0 "keep milestone edge-proof/m under it" K keep '{"project":"edge-proof","handle":"m","kind":"milestone","title":"Edge proof","body":"Kept by ops/edge-proof.sh in a restored copy.","parent":"edge-proof/s"}'
expect 0 "keep note edge-proof/c" K keep '{"project":"edge-proof","handle":"c","kind":"note","title":"Cited note","body":"cited-text-1","parent":"edge-proof/m"}'
# The proof's note, of body `$1`: a new one by its handle, or with `$2` a key, a new version.
note() { printf '{%s,"kind":"note","title":"Proof note","body":"%s See [the cited](artifact:edge-proof/c).","parent":"edge-proof/m","cites":["edge-proof/c"]}' "${2:-\"project\":\"edge-proof\",\"handle\":\"n\"}" "$1"; }
expect 0 "keep note edge-proof/n citing edge-proof/c" K keep "$(note note-text-1)"
expect 0 "a keep by the command line" K keep '{"project":"edge-proof","handle":"by-owner","kind":"note","title":"Owner","body":"kept by the command line","parent":"edge-proof/m"}'
expect 0 "a keep by a session" S keep '{"project":"edge-proof","handle":"by-session","kind":"note","title":"Session","body":"kept by a session","parent":"edge-proof/m"}'
key_of="$(stored_of edge-proof/by-owner)"
[[ "$key_of" == "$(id_of edge-proof/by-owner)" ]]; holds $? "a new artifact is stored under its id: project_stored answers $key_of"
lo="$(level "$key_of")"; ls_="$(level "$(stored_of edge-proof/by-session)")"
[[ "$lo" == 3 ]]; holds $? "the command line's keep is recorded at integrity level 3, authority ($lo)"
[[ "$ls_" == 2 ]]; holds $? "the session's keep, KALANDRA_CREDENTIAL inherited, is recorded at level 2, member ($ls_)"
before="$(K show '{"key":"edge-proof/n"}')"
# The method's versions, which the deploy declared: each given back as its file, a session's
# declaration refused, and grit migrated to the latest, which edge-proof, new, holds to already.
latest="$(cd "$wt/methods" && ls -- grit@*.json | sed 's/^grit@\(.*\)\.json$/\1/' | sort -n | tail -n 1)"
rc=0
for f in "$wt"/methods/*.json; do
  v="${f##*@}"; v="${v%.json}"
  [[ "$(K methods "{\"name\":\"grit\",\"version\":$v}")" == "$(<"$f")" ]] || rc=1
done
holds "$rc" "project_methods gives each version the deploy declared back as its file in methods/, 1 to $latest"
expect 1 "a session's declaration of a version is refused" S declare "$wt/methods/grit@$latest.json"
grep -q "answered 409" <<<"$out" && grep -q "only by a person running the command themselves" <<<"$out"
holds $? "  409, a declaration being a person's, run themselves: $(head -n 1 <<<"$out" | head -c 160)"
# A migration is refused while an artifact is claimed, and the copy holds live's claims: they are
# set aside for it and put back after, as live migrates once its claims are released.
now_before="$(K list '{"project":"grit","state":"now"}')"
sql "CREATE TABLE grit.claims_set_aside AS SELECT * FROM grit.claims; DELETE FROM grit.claims" >/dev/null
expect 0 "the command line migrates grit to version $latest, nothing claimed" K migrate "{\"project\":\"grit\",\"version\":$latest}"
sql "INSERT INTO grit.claims SELECT * FROM grit.claims_set_aside; DROP TABLE grit.claims_set_aside" >/dev/null
expect 0 "  and grit's now lists" K list '{"project":"grit","state":"now"}'
[[ -n "$now_before" && "$out" == "$now_before" ]]
holds $? "  what it listed before the migration, $(grep -c . <<<"$now_before") lines"
amend="## Diff\n\nd\n\n## Moments\n\nm\n\n## Cue\n\nc\n\n## Lesson\n\nl\n\n## Confidence\n\nc\n\n## Observable\n\no\n\n## Contrast\n\nkept -> amendment.\n\n## Verdict: helped\n\nh\n\n## Decision: adopt\n\n- By: proof.\n- Activation: 1.\n- Checks: 1.\n- Cost: 0.\n- Held-out regression: none."
expect 0 "keep amendment edge-proof/a, decided adopt" K keep "{\"project\":\"edge-proof\",\"handle\":\"a\",\"kind\":\"amendment\",\"title\":\"Proof amendment\",\"body\":\"$amend\"}"
expect 0 "a session benches it" S move '{"key":"edge-proof/a","state":"benched"}'
expect 1 "a session's move of it to adopted is refused" S move '{"key":"edge-proof/a","state":"adopted"}'
grep -q "answered 409" <<<"$out" && grep -q "made only by a person running the command themselves" <<<"$out"
holds $? "  409, saying a person makes it, running the command themselves: $(head -n 1 <<<"$out" | head -c 160)"
expect 0 "the command line's move of it to adopted is made" K move '{"key":"edge-proof/a","state":"adopted"}'
K show '{"key":"edge-proof/a"}' | grep -qx "state: adopted"; holds $? "  and edge-proof/a is adopted"
expect 0 "keep checkpoint edge-proof/k, its plan lacking Commits" K keep '{"project":"edge-proof","handle":"k","kind":"checkpoint","title":"Proof checkpoint","body":"## Goal\n\ng\n\n## Signatures\n\ns\n\n## Reads\n\nr","parent":"edge-proof/m"}'
expect 1 "a session's move of it to ready is refused" S move '{"key":"edge-proof/k","state":"ready"}'
grep -q "sections-present" <<<"$out"; holds $? "  by sections-present"
expect 0 "keep it again with Commits" K keep '{"key":"edge-proof/k","kind":"checkpoint","title":"Proof checkpoint","body":"## Goal\n\ng\n\n## Signatures\n\ns\n\n## Reads\n\nr\n\n## Commits\n\nc","parent":"edge-proof/m"}'
expect 0 "a session moves it to ready" S move '{"key":"edge-proof/k","state":"ready"}'
expect 0 "  and then to building" S move '{"key":"edge-proof/k","state":"building"}'
state="$(sed -n 's/^state: //p' <<<"$before" | head -1)"
# A wait is given an id or a handle, which serve resolves to the key the artifact is stored under:
# a new artifact's id, a re-keyed one's name before.
expect 0 "wait for edge-proof/n, by its handle, in $state" K wait "{\"artifact\":\"edge-proof/n\",\"state\":[\"$state\"],\"within\":5}"
grep -q "^$(stored_of edge-proof/n) is in $state " <<<"$out"; holds $? "  met under the key it is stored under: $(head -c 120 <<<"$out")"
expect 3 "wait for edge-proof/n in folded lapses" K wait "{\"artifact\":\"edge-proof/n\",\"state\":[\"folded\"],\"within\":2}"
coder_state="$(K show '{"key":"grit/roadmap-grit-coder","head":true}' | sed -n 's/^state: //p' | head -1)"
expect 0 "wait for grit/roadmap-grit-coder, re-keyed, by its id, in $coder_state" K wait "{\"artifact\":\"$coder_id\",\"state\":[\"$coder_state\"],\"within\":5}"
grep -q "^grit/roadmap-grit-coder is in $coder_state " <<<"$out"; holds $? "  met under its name before the re-key: $(head -c 120 <<<"$out")"
expect 1 "a wait for an artifact the project does not hold is refused" K wait '{"artifact":"edge-proof/none","state":["open"],"within":2}'
[[ "$(head -n 1 <<<"$out")" == "project edge-proof has no artifact named none"* ]]; holds $? "  in project_stored's words: $(head -n 1 <<<"$out" | head -c 160)"
expect 1 "a wait with a key it does not read is refused" K wait '{"artifact":"edge-proof/n","state":["open"],"slot":"x"}'
grep -q "reads no slot" <<<"$out"; holds $? "  and names the key"
H="$(K holder "{\"dir\":\"$repo\"}")" || H=""
[[ "$H" =~ ^local/repo-[0-9a-f]{8}$ ]]; holds $? "holder for the repository is local/repo-<8 hex>: $H"
expect 0 "claim edge-proof/n as manager for $H" K claim "{\"key\":\"edge-proof/n\",\"role\":\"manager\",\"holder\":\"$H\"}"
# The latest version's rules, on edge-proof, which new pinned to it: a role is held only by the
# holders it names, a lander's claim is one at a time, and a checkpoint enters a state its coder
# is claimed in only with its brief within the budget.
expect 1 "a claim of edge-proof/k as coder for the session's holder $H is refused" K claim "{\"key\":\"edge-proof/k\",\"role\":\"coder\",\"holder\":\"$H\"}"
grep -qF "$H is a session's holder, and the role coder is held only by agents of type" <<<"$out"
holds $? "  coder being held only by coder agents: $(head -n 1 <<<"$out" | head -c 200)"
lander1="$(K holder "{\"dir\":\"$repo\",\"type\":\"lander\",\"session\":\"proof-l1\"}")" || lander1=""
lander2="$(K holder "{\"dir\":\"$repo\",\"type\":\"lander\",\"session\":\"proof-l2\"}")" || lander2=""
[[ "$lander1" == "$H"/lander/* && "$lander2" == "$H"/lander/* && "$lander1" != "$lander2" ]]
holds $? "two lander agents' holders, of two sessions: $lander1, $lander2"
expect 0 "keep mechanism edge-proof/landing under edge-proof/s" K keep '{"project":"edge-proof","handle":"landing","kind":"mechanism","title":"Landing","body":"How main is landed.","parent":"edge-proof/s"}'
expect 0 "claim edge-proof/landing as lander for $lander1" K claim "{\"key\":\"edge-proof/landing\",\"role\":\"lander\",\"holder\":\"$lander1\"}"
expect 1 "a second claim of it as lander, for $lander2, is refused" K claim "{\"key\":\"edge-proof/landing\",\"role\":\"lander\",\"holder\":\"$lander2\"}"
grep -qF "$lander1" <<<"$out"; holds $? "  naming the first's holder: $(head -n 1 <<<"$out" | head -c 200)"
filler="$(printf 'filler %.0s' $(seq 1 1400))"
expect 0 "keep checkpoint edge-proof/big, its coder brief over the budget" K keep "{\"project\":\"edge-proof\",\"handle\":\"big\",\"kind\":\"checkpoint\",\"title\":\"Long checkpoint\",\"body\":\"## Goal\n\n$filler\n\n## Signatures\n\ns\n\n## Reads\n\nr\n\n## Commits\n\nc\",\"parent\":\"edge-proof/m\"}"
grep -q "Its brief as coder would be .* before it moves to ready or building" <<<"$out"
holds $? "  its answer says so: $(grep -o 'Its brief as coder[^.]*' <<<"$out" | head -c 160)"
expect 1 "a session's move of it to ready is refused" S move '{"key":"edge-proof/big","state":"ready"}'
grep -q "the brief of .* as coder would be .* over the 9,500 a brief holds" <<<"$out"
holds $? "  its brief as coder passing the 9,500 a brief holds: $(head -n 1 <<<"$out" | head -c 200)"
expect 0 "export edge-proof through CommandClient" K export edge-proof "$work/export"
n="$(find "$work/export" -type f | wc -l)"
[[ "$n" -ge 3 ]]; holds $? "  $n export files"
find "$work/export" -mindepth 2 -name '*.md' | grep -q .; holds $? "  a child's file is under its parent's number"
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

stage "lower credential"
# ---- 9. The lower credential is refused adopt, and reaches the other own commands.
expect 1 "a session's adopt is refused" S adopt '{"key":"edge-proof/n"}'
say "fact  the session's adopt answered: $(head -1 <<<"$out")"
grep -q "answered 403" <<<"$out" && grep -q "project_adopt" <<<"$out" && grep -q "authority" <<<"$out"
holds $? "  403, naming project_adopt and authority"
expect 1 "the command line's adopt reaches the command, which refuses a note as no amendment" K adopt '{"key":"edge-proof/n"}'
grep -q "answered 409" <<<"$out"; holds $? "  409, the command's own refusal: $(head -1 <<<"$out")"
expect 0 "a session's standing reaches the command" S standing '{"key":"grit/roadmap-grit-coder"}'
# Another machine's sessions, by the credential KALANDRA_MACHINES names: believed as a session.
L() { env -u CLAUDE_CODE_SESSION_ID KALANDRA_LISTEN="$listen" KALANDRA_CREDENTIAL="$work/cred/laptop" "$wt/bin/kalandra" "$@"; }
LH="$(L holder "{\"dir\":\"$repo\"}")" || LH=""
[[ "$LH" =~ ^laptop/repo-[0-9a-f]{8}$ ]]; holds $? "holder for the repository through laptop's credential is laptop/repo-<8 hex>: $LH"
expect 1 "laptop's adopt is refused" L adopt '{"key":"edge-proof/n"}'
grep -q "answered 403" <<<"$out" && grep -q "project_adopt" <<<"$out" && grep -q "authority" <<<"$out"
holds $? "  403, naming project_adopt and authority, as a session's: $(head -1 <<<"$out")"
[[ "$(K show '{"key":"edge-proof/n"}')" == "$before" ]]; holds $? "edge-proof/n unchanged after the adopts"

stage "join and hook"
# ---- 10. The deploy's join beside a fresh join; then the hook.
new_entry="$(entry "$repo" "$dhome")"
# This join's `claude` is the installed Claude Code, in the proof's no-model environment, so the
# CLI call the live join makes (`claude mcp add-json --scope local` of an entry with a
# headersHelper) is run for real. The forwarder, and the sink every proxy names, start first.
(cd "$work/run" && exec python3 -I "$wt/ops/forward.py" 7319 7318 "$work/mcp.jsonl" "$(d "$ses")" 7320 "$work/outside.jsonl") >"$work/forward.log" 2>&1 &
started+=("$!")
for _ in $(seq 1 20); do listening 7319 && listening 7320 && break; sleep 0.25; done
R2() { (cd "$work/repo2" && exec "${no_model[@]}" "$@"); }
rc=0; R2 bash -s -- "$edge" --credential "$ses" --mcp-name kalandra <"$proof/join.sh" >"$work/join2.out" 2>&1 || rc=$?
holds "$rc" "a fresh join with --credential, through the installed claude $(R2 claude --version 2>/dev/null), exits 0"
grep -qxF "  mcp       kalandra at $edge/mcp, local scope" "$work/join2.out"; holds $? "  and the installed claude took its MCP entry"
sed "s|$work|<work>|g" "$work/join2.out" >"$proof/join.txt"
sed 's/^/join    /' "$proof/join.txt" | tee -a "$proof/proof.txt"
norm() { sed "s|$2|<repo>|g" "$1"; }
# A settings file the join makes starts with a $schema key; one it finds (the deployed
# repository's, which an older join made) it leaves without one.
[[ "$(jq -r 'keys_unsorted[0]' "$work/repo2/.claude/settings.local.json")" == '$schema' ]]
holds $? "  the settings file the fresh join made starts with its \$schema key"
settings_of() { jq -S 'del(.proofMidDeploy, ."$schema")' "$1/.claude/settings.local.json" | sed "s|$1|<repo>|g"; }
cmp -s <(settings_of "$repo") <(settings_of "$work/repo2") &&
  cmp -s <(norm "$hook" "$repo") <(norm "$work/repo2/.git/grit/claude-code/hook" "$work/repo2") &&
  [[ "$(sed "s|$repo|<repo>|g" <<<"$new_entry")" == "$(sed "s|$work/repo2|<repo>|g" <<<"$(entry "$work/repo2" "$work/jhome2")")" ]] &&
  cmp -s <(norm "$repo/.git/hooks/post-commit" "$repo") <(norm "$work/repo2/.git/hooks/post-commit" "$work/repo2")
holds $? "  the deployed repository's settings (but the key a session wrote), hook, git hooks and MCP entry are a fresh join's"
handlers="$(jq --arg h "'$hook'" '[.hooks[][].hooks[] | select(.command | startswith($h))] | length' "$repo/.claude/settings.local.json")"
[[ "$handlers" == "$(jq '[.hooks[][].hooks[]] | length' "$repo/.claude/settings.local.json")" ]]; holds $? "  every handler in the settings is the hook's ($handlers)"

payload() { jq -nc --arg s "$1" --arg cwd "$repo" --arg e "$2" '{session_id: $s, transcript_path: "/dev/null", cwd: $cwd, hook_event_name: $e}'; }
fire() { CLAUDE_PROJECT_DIR="$repo" PATH=/usr/bin:/bin "$hook" "$@"; }
start="$(payload proof-s1 SessionStart | jq -c '. + {source: "startup"}')"
ctx="$(fire brief <<<"$start" | jq -r '.hookSpecificOutput.additionalContext // empty')" || ctx=""
grep -q "Proof note" <<<"$ctx" && grep -q "note-text-1" <<<"$ctx" && grep -q "cited-text-1" <<<"$ctx"
holds $? "the hook's brief at SessionStart holds the claimed note and what it cites"
expect 0 "keep edge-proof/n with a changed body" K keep "$(note note-text-2 '"key":"edge-proof/n"')"
ctx="$(fire brief <<<"$start" | jq -r '.hookSpecificOutput.additionalContext // empty')" || ctx=""
grep -q "note-text-2" <<<"$ctx" && ! grep -q "note-text-1" <<<"$ctx"; holds $? "  and the next brief shows the keep"
op="$(fire opening <<<"$start" | jq -r '.hookSpecificOutput.additionalContext // empty')" || op=""
[[ "$(head -1 <<<"$op")" == *"$H"* ]]; holds $? "the hook's opening names the holder first: $(head -1 <<<"$op")"
printed="'$hook' holder TYPE --dir '$repo' --session 'proof-s1'"
grep -qxF "grit: an agent this session starts has its own claim holder. This prints it, with the agent's type in place of TYPE: $printed" <<<"$op"
holds $? "the opening prints the agent holder command"
AH="$(fire holder coder-opus --dir "$repo" --session proof-s1 2>/dev/null)" || AH=""
KH="$(K holder "{\"dir\":\"$repo\",\"type\":\"coder-opus\",\"session\":\"proof-s1\"}")" || KH=""
[[ -n "$AH" && "$AH" == "$KH" && "$AH" == "$H"/coder-opus/* ]]
holds $? "bin/kalandra holder with dir, type and session is the hook's holder coder-opus: $AH"
expect 0 "keep checkpoint edge-proof/w" K keep '{"project":"edge-proof","handle":"w","kind":"checkpoint","title":"Agent checkpoint","body":"## Goal\n\nagent-text-1","parent":"edge-proof/m"}'
expect 0 "a coder-opus agent's holder claims edge-proof/w as coder: $AH" K claim "{\"key\":\"edge-proof/w\",\"role\":\"coder\",\"holder\":\"$AH\"}"
sub="$(payload proof-s1 SubagentStart | jq -c '. + {agent_id: "proof-a1", agent_type: "coder-opus"}')"
actx="$(fire brief <<<"$sub" | jq -r '.hookSpecificOutput.additionalContext // empty')" || actx=""
grep -q "agent-text-1" <<<"$actx" && ! grep -q "note-text-2" <<<"$actx"
holds $? "the hook's brief at SubagentStart holds the agent's claimed checkpoint, not the session's note"
rc=0; fire event <<<"$start" || rc=$?; holds "$rc" "the hook posts the start"
# proof-s1, started, works $H's claim of edge-proof/n: a second session of $H opens with one line
# in place of the brief, naming the command line a person moves the claim with.
start2="$(payload proof-s2 SessionStart | jq -c '. + {source: "startup"}')"
held="$(fire brief <<<"$start2" | jq -r '.hookSpecificOutput.additionalContext // empty')" || held=""
grep -qF "the person who made the claim runs bin/kalandra project_claim themselves, outside any session" <<<"$held"
holds $? "a second session of $H opens with one line naming bin/kalandra project_claim: $(head -c 240 <<<"$held")"
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
# A holder's free claim is taken only by the batch that opens a session: proof-s2, opened while
# proof-s1 works edge-proof/n, does not take it when proof-s1's end frees it; proof-s3's start does.
n_session() { sql "SELECT coalesce(session_local, '-') FROM grit.claims WHERE subject = 'edge-proof/n' AND holder = '$H'"; }
rc=0; fire event <<<"$start2" || rc=$?; holds "$rc" "the hook posts proof-s2's start, proof-s1 working edge-proof/n: $(n_session)"
end1="$(payload proof-s1 SessionEnd | jq -c '. + {reason: "other"}')"
rc=0; fire event <<<"$end1" || rc=$?
[[ "$rc" == 0 && "$(n_session)" == - ]]; holds $? "the hook posts proof-s1's end, which frees edge-proof/n: $(n_session)"
read2="$(payload proof-s2 PostToolUse | jq -c --arg f "$repo/README" '. + {tool_name: "Read", tool_input: {file_path: $f}, tool_response: {type: "text", file: {filePath: $f, content: "hello\n", numLines: 1, startLine: 1, totalLines: 1}}}')"
rc=0; fire event <<<"$read2" || rc=$?
[[ "$rc" == 0 && "$(n_session)" == - ]]; holds $? "  a later read of proof-s2, open already, leaves it free: $(n_session)"
start3="$(payload proof-s3 SessionStart | jq -c '. + {source: "startup"}')"
rc=0; fire event <<<"$start3" || rc=$?
[[ "$rc" == 0 && "$(n_session)" == proof-s3 ]]; holds $? "  proof-s3's start, opening it, takes it: $(n_session)"
hdr="$(fire header 2>/dev/null)"
jq -e 'type == "object" and keys == ["Authorization"] and (.Authorization | test("^Bearer [A-Za-z0-9_-]{43}$"))' <<<"$hdr" >/dev/null
holds $? "the hook's header prints one Authorization object of a bearer credential (its value not printed)"
hdr=""
foreign="$(GRIT_EDGE=http://127.0.0.1:7399 fire brief <<<"$start" 2>&1 >/dev/null)"
[[ "$foreign" == "grit claude-code hook: GRIT_EDGE names http://127.0.0.1:7399, but this checkout's credential is sent only to the edge it was joined to, $edge; it did nothing" ]]
holds $? "a foreign GRIT_EDGE makes the hook do nothing, saying so"

stage "Claude Code's MCP client"
# ---- 11. The installed Claude Code's MCP client, through the join's headersHelper, by a forwarder
# that checks each request's credential by its digest and never logs it; `claude mcp get` calls no
# model, and has no login and a key that is no key, its model's base URL and every proxy the sink
# on 7320, which must be sent nothing. Its entry is the fresh join's, its URL the forwarder's,
# added as kalandra-forwarded by the installed claude; the checkout is trusted, as a person does
# once when Claude Code first asks, which the headersHelper needs.
via="$(jq -c --arg u "http://$forward/mcp" '. + {url: $u}' <<<"$(entry "$work/repo2" "$work/jhome2")")"
[[ "$(jq -r .headersHelper <<<"$via")" == "'$work/repo2/.git/grit/claude-code/hook' header" ]]
holds $? "the forwarded entry keeps the fresh join's headersHelper"
f2="$work/jhome2/.claude.json"
jq --arg p "$work/repo2" '.projects[$p].hasTrustDialogAccepted = true' "$f2" >"$f2.t" && mv "$f2.t" "$f2"
rc=0; (cd "$work/repo2" && exec "${no_model[@]}" timeout 20 claude mcp add-json --scope local kalandra-forwarded "$via") >"$work/mcp-add.out" 2>&1 || rc=$?
holds "$rc" "the installed claude adds the forwarded entry"
rc=0; (cd "$work/repo2" && exec "${no_model[@]}" timeout 20 claude mcp get kalandra-forwarded) >"$work/mcp-get.out" 2>&1 || rc=$?
grep -qF "Status: ✔ Connected" "$work/mcp-get.out" || rc=1
holds "$rc" "claude mcp get, within 20 s, connects through the join's headersHelper: $(grep -m 1 'Status:' "$work/mcp-get.out")"
if [[ -s "$work/mcp.jsonl" ]]; then
  cp "$work/mcp.jsonl" "$proof/mcp.jsonl"
  say "mcp   the forwarder saw, in order (rpc status credential): $(jq -r '"\(.rpc) \(.status) \(.credential)"' "$work/mcp.jsonl" | tr '\n' ',' | sed 's/,$//')"
  jq -e -s 'length > 0 and all(.credential == "the sessions'"'"'")' "$work/mcp.jsonl" >/dev/null
  holds $? "  every request it forwarded bore the sessions' credential"
  jq -e -s 'any((.rpc == "initialize" or .rpc == "server/discover") and .status == 200) and any(.rpc == "tools/list" and .status == 200)' "$work/mcp.jsonl" >/dev/null
  holds $? "  the session opened (server/discover or initialize answered 200), and tools/list answered 200"
else
  fail "the forwarder recorded no exchange"
fi
rc=0; (cd "$work/repo2" && exec "${no_model[@]}" timeout 20 claude mcp remove --scope local kalandra-forwarded) >/dev/null 2>&1 || rc=$?
holds "$rc" "the forwarded entry removed"

stage undo
# ---- 12. The join's undo, the line the deploy's join printed run as printed, in both checkouts;
# what the deploys wrote; the claims.
undo_line="$(sed -n 's/^deploy: join  Undo: //p' "$dlog" | head -n 1)"
[[ "$undo_line" == "curl -fsS -H @<(printf 'Authorization: Bearer %s' \"\$(<'$ses')\") $edge/join/claude-code | bash -s -- --undo --mcp-name kalandra" ]]
holds $? "the join's printed undo fetches the script with the sessions' credential, by its file"
# pipefail, so a refused fetch fails the run rather than piping nothing to a bash that exits 0.
for w in "$repo_wt" "$repo"; do
  rc=0; (cd "$w" && HOME="$dhome" PATH="$work/shim:/usr/bin:/bin" bash -o pipefail -c "$undo_line") >"$work/undo.out" 2>&1 || rc=$?
  holds "$rc" "the printed undo, run as printed in $w, exits 0"
done
[[ ! -e "$hook" && ! -e "$repo/.git/hooks/post-commit" && ! -e "$repo/.git/hooks/post-rewrite" ]]
holds $? "  the hook and its git hooks are gone"
[[ "$(jq '[.hooks // {} | .[][].hooks[]] | length' "$repo/.claude/settings.local.json")" == 0 && "$(entry "$repo" "$dhome")" == null && "$(entry "$repo_wt" "$dhome")" == null ]]
holds $? "  no handler and no MCP entry is left: $(jq -c '[.hooks // {} | .[][].hooks[]] | length' "$repo/.claude/settings.local.json") $(entry "$repo" "$dhome") $(entry "$repo_wt" "$dhome")"
undo2="$(sed -n 's/^Undo: //p' "$work/join2.out")"
rc=0; R2 bash -o pipefail -c "$undo2" >"$work/undo2.out" 2>&1 || rc=$?
[[ "$rc" == 0 && "$(entry "$work/repo2" "$work/jhome2")" == null ]]
holds $? "the fresh join's printed undo, through the installed claude, removes its MCP entry"
[[ ! -e "$work/repo2/.claude" ]]; holds $? "  and the settings file the join made, and the .claude directory it made"
[[ ! -s "$work/outside.jsonl" ]]
holds $? "no call of the installed claude sent anything toward a model or off the machine$([[ ! -s "$work/outside.jsonl" ]] || echo ": $(head -c 300 "$work/outside.jsonl" | tr '\n' ' ')")"
# serve writes no files: what the deploys' HOME holds is the credentials, Claude Code's file the
# stand-in writes, and the deploys' state.
extra="$(cd "$dhome" && find . -type f -not -path ./.config/kalandra/credential -not -path ./.config/grit/credential \
  -not -path ./.claude.json -not -path './.local/state/kalandra-deploy/*' | head -5)"
[[ -z "$extra" ]]; holds $? "the deploys' HOME holds nothing but the credentials, ~/.claude.json and the deploys' runs${extra:+: $extra}"
[[ "$(ls -1 "$dhome/.local/state/kalandra-deploy/runs" | wc -l)" == 3 ]]; holds $? "  of which the three newest are kept"
sql "$claims_q" | grep -v '|edge-proof/' >"$work/claims-after.txt"
cmp -s <(grep -v '|edge-proof/' "$work/claims-before.txt") "$work/claims-after.txt"; holds $? "every claim but the proof's is as it was ($(wc -l <"$work/claims-after.txt"))"
written="$(find "$wt" -newer "$work/start" -type f -not -path "$wt/out/*" -not -path "$wt/logs/*" -not -path "$wt/.git/*")"
[[ -z "$written" ]]; holds $? "nothing written in the checkout${written:+: $written}"

# Stopping what it started and dropping the copies count in the proof's time.
stage cleanup
if [[ "$failed" != 0 ]]; then
  for f in "$work"/deploy-*.out; do
    l="$(sed -n 's/^deploy: log //p' "$f")"
    if [[ -f "$l" ]]; then echo "$(basename "$f")'s log ends:" >&2; tail -20 "$l" >&2; fi
  done
  if [[ -f "$clone/logs/serve.log" ]]; then
    echo "the copy's serve log ends:" >&2
    tail -20 "$clone/logs/serve.log" >&2
  fi
fi
cleanup
trap - EXIT
stage ended
say "time  edge-proof took $(since "$t_began") s, its budget $budget s"
say "total $(grep -c '^ok ' "$proof/proof.txt") checks passed, $failed failed"
if [[ "$failed" == 0 ]]; then
  say "edge-proof: passed"
else
  say "edge-proof: $failed checks failed"
  exit 1
fi
