#!/usr/bin/env bash
# The proof, before a deploy, that this checkout's commit deploys over the live one and serves
# through grit's commands edge with credentials: it restores a copy of grit_kalandra, makes a
# copy of the live checkout (a clone at its commit, with its jar) serving the copy on a second
# port, joins two checkouts of a throwaway repository to it, and runs `bin/kalandra deploy` there
# four times: stopped by a data file that would drop a row's subject; failing after the data file
# is kept, and rolled back to the copy as it began; whole; and again, with nothing to rewrite. It
# then drives the deployed serve the way the command line, sessions, agents, the join and Claude
# Code will, and stops what it started and drops the copy.
#
#   ops/edge-proof.sh
#
# Run from this checkout, committed, after `bin/kalandra build`. It reads grit_kalandra once (a
# pg_dump, as ops/backup.sh does) and never writes it; it restores the dump into grit_kalandra_e7,
# where it cancels every pending or enqueued DBOS workflow so nothing resumes. Everything it serves
# is on 127.0.0.1:7318, with keys that are no keys, so nothing it serves can spend; the deploys run
# with a HOME of the proof's own, where they make their own credentials, never printed.
#
# Into the proof's directory, ${KALANDRA_PROOF_DIR:-$HOME/.cache/kalandra-e7}, it saves the jar it
# proved (serve.jar), the join script that jar serves (join.sh), grit's forward rewrite at the
# commit the jar is built against (machine-from-worker.sql), this checkout's reverse
# (worker-from-machine.sql), each with its sha256, a fresh join's output (join.txt), the whole
# deploy's dry-run counts (forward-dry.txt) and each check's line (proof.txt), which names the
# commit proved: `bin/kalandra deploy` runs those exact files, and only that commit.
#
# It runs Claude Code once (`claude -p`, Haiku, 3 turns at most) in a joined checkout, its MCP
# entry the one the join wrote, with its headersHelper, pointed by a --strict-mcp-config file at a
# forwarder on 127.0.0.1:7319 that checks an Authorization header is the sessions' credential's
# (by its digest) and never logs its value. That run needs the real HOME to log in: the transcript
# directory it makes under ~/.claude/projects/ is deleted.
#
# Refused, exit 1, before anything is started: the checkout has changes, grit_kalandra_e7 or
# grit_kalandra_e7_join exists, something listens on 7318 or 7319, KALANDRA_LISTEN names 7317,
# there is no jar, or the jar is not built against the grit snapshot's marker. Prints a line a
# check, and `edge-proof: passed`, exit 0, only when every check held. Every process it starts,
# and every one a deploy starts, runs in a directory under its own, and is stopped by its PID only
# while that PID still runs there; the copies are dropped, however it ends.
set -euo pipefail

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
edge="http://$listen"
[[ $# == 0 ]] || { echo "usage: ops/edge-proof.sh" >&2; exit 64; }

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
marker="$HOME/.ivy2/local/dev.grit/.publish-local-$version"
at="$(cat "$marker" 2>/dev/null)" || refuse "no marker for grit $version at $marker"
# The jar is an executable assembly, a launcher before its zip, which unzip warns of; Python's
# zipfile reads past it.
built="$(python3 -I -c 'import sys, zipfile; print(zipfile.ZipFile(sys.argv[1]).read("kalandra/grit.txt").decode().strip())' "$jar" 2>/dev/null)" ||
  refuse "the jar carries no kalandra/grit.txt"
[[ "$built" == "$version at $at" ]] || refuse "the jar is built against grit $built, the marker names $at: build again"
forward_sql="$(git -C "$grit_checkout" show "$at:core/dbos/it/resources/machine-from-worker.sql")" ||
  refuse "grit's checkout has no machine-from-worker.sql at $at"
[[ -f "$live/out/assembly.dest/out.jar" ]] || refuse "no live jar at $live/out/assembly.dest/out.jar"
for d in "$restored" "$joindb"; do
  if [[ -n "$(pg psql -U grit -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname = '$d'")" ]]; then
    refuse "$d exists: a proof may be running"
  fi
done
listening 7318 && refuse "something listens on 7318"
listening 7319 && refuse "something listens on 7319"

work="$(cd "$(mktemp -d)" && pwd -P)"
# Every process the proof starts runs in a directory under $work, and so does every one a deploy
# starts (its run, under the deploys' HOME; serve, in the checkout's copy), so a pid's cwd shows
# it is the proof's own; `started` holds each the proof started until it is waited for.
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
    for d in $restored $joindb $(pg psql -U grit -d postgres -At -c "SELECT datname FROM pg_database WHERE datname LIKE '${restored}\\_rolled\\_back\\_%'" 2>/dev/null); do
      pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS $d WITH (FORCE)" >/dev/null || true
    done
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

proved="$(git -C "$wt" rev-parse HEAD)"
began_at="$SECONDS"
say "edge-proof: $(date -u +%Y-%m-%dT%H:%M:%SZ), checkout $wt at $(git -C "$wt" rev-parse --short HEAD)"
say "commit $proved"
say "grit  the jar is built against grit $built"
keep_file "$jar" serve.jar
printf '%s\n' "$forward_sql" >"$work/machine-from-worker.sql"
keep_file "$work/machine-from-worker.sql" machine-from-worker.sql
keep_file "$wt/ops/worker-from-machine.sql" worker-from-machine.sql

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
[[ "$machine_before" == 0 ]]; holds $? "no place is under machine before the rename, so the reverse is exact ($machine_before)"
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

# ---- 2. The proved jar's join script, from a serve of it on an empty database; serve refuses to
# start without either digest.
mkdir -p "$work/home" "$work/cred"
( umask 077
  for c in a b wrong; do head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' >"$work/cred/$c"; done
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
(umask 077; printf 'ANTHROPIC_API_KEY=edge-proof-no-key\nJEV_API_KEY=edge-proof-no-key\n' >"$clone/.env")
say "live  $clone, a clone of $live at $old_head on $old_branch, its jar $old_jar"
git -C "$clone" merge-base --is-ancestor "$old_head" "$proved"; holds $? "the proved commit descends from the live checkout's"
dhome="$work/dhome"
mkdir -p "$work/shim" "$dhome" "$work/jhome2"
cat >"$work/shim/claude" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
f="$HOME/.claude.json"; [ -f "$f" ] || echo '{}' >"$f"
top="$(git rev-parse --show-toplevel)"
put() { jq --arg p "$top" --arg n "$1" --argjson e "$2" '.projects[$p].mcpServers[$n] = $e' "$f" >"$f.t" && mv "$f.t" "$f"; }
# The planted failure, once: while a deploy joins, a keep through the serve it started, and a key
# a session writes into the settings; then the add fails.
if [[ -n "${EDGE_PROOF_PLANT-}" && -e "$EDGE_PROOF_PLANT" && "$1 $2" == "mcp add-json" ]]; then
  rm -f -- "$EDGE_PROOF_PLANT"
  "$EDGE_PROOF_WT/bin/kalandra" new '{"project":"edge-proof-window"}' >/dev/null
  "$EDGE_PROOF_WT/bin/kalandra" keep '{"key":"edge-proof-window/m","kind":"milestone","title":"Window","body":"window-text-1"}' >/dev/null
  s="$top/.claude/settings.local.json"
  jq '. + {proofMidDeploy: "kept"}' "$s" >"$s.t" && mv "$s.t" "$s"
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
curl -q -fsS --noproxy '*' --max-time 10 "$edge/join/claude-code" >"$work/old-join.sh"
holds $? "the copy's serve serves its join script, $(sha "$work/old-join.sh")"
for w in "$repo" "$repo_wt"; do
  rc=0; J "$w" "$dhome" "$edge" --mcp-name kalandra <"$work/old-join.sh" >"$work/join-old.out" 2>&1 || rc=$?
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
}
fingerprint >"$work/began.txt"
say "began $(grep -c . "$work/began.txt") facts of the copy as it began"
mkdir -p "$work/proved"
for f in serve.jar join.sh machine-from-worker.sql worker-from-machine.sql; do
  cp -- "$proof/$f" "$proof/$f.sha256" "$work/proved/"
done
# The deploys read this proof's files and its lines so far, which end as a passed proof's do: a
# deploy runs only a proved commit, and the checks it is proved by follow.
stage_proof() {
  cp -- "$proof/proof.txt" "$work/proved/proof.txt"
  echo "edge-proof: passed" >>"$work/proved/proof.txt"
}
# deploy <name>: the deploy, run as a person runs it, into the clone; its exit in $drc, its last
# line in $dlast, its log in $dlog.
deploy() {
  drc=0
  (cd "$work/run" && exec timeout 900 "${denv[@]}" "$wt/bin/kalandra" deploy --checkout "$clone" --grit "$repo" "$work/proved") \
    >"$work/deploy-$1.out" 2>&1 || drc=$?
  local full
  full="$(tail -n 1 "$work/deploy-$1.out")"
  dlog="$(sed -n 's/^deploy: log //p' "$work/deploy-$1.out")"
  say "dep   $1: exit $drc: $full"
  dlast="${full%; took *}"
  [[ "$full" =~ \;\ took\ [0-9]+\.[0-9]\ s,\ serve\ down\ [0-9]+\.[0-9]\ s$ ]]
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

# ---- 4. (a) A data file that would drop a row's subject stops the deploy: one commit event under
# worker given a shape of 300 bytes, which the rename must take past the bound.
planted="$(sql "WITH e AS (SELECT session, regexp_replace(shape::text, ',\"subject\":\"[^\"\\\\]*\"\\}\$', '') AS head FROM grit.session_events WHERE kind = 'commit' AND shape ->> 'worktree' LIKE 'worker:%' AND shape::text ~ ',\"subject\":\"[^\"\\\\]*\"\\}\$' AND session IN (SELECT session FROM grit.session_events GROUP BY session HAVING max(seq) < 5000) ORDER BY session DESC, seq DESC LIMIT 1) INSERT INTO grit.session_events (session, seq, n, at, kind, shape) SELECT e.session, (SELECT max(seq) + 1 FROM grit.session_events WHERE session = e.session), (SELECT max(n) + 1 FROM grit.session_events WHERE session = e.session), now(), 'commit', (e.head || ',\"subject\":\"' || repeat('x', 300 - octet_length(e.head) - 14) || '\"}')::json FROM e RETURNING session || ' ' || seq || ' ' || octet_length(shape::text)")"
read -r p_session p_seq p_len <<<"$planted"
[[ "$p_len" == 300 ]]; holds $? "a commit event of 300 bytes under worker planted in the copy: $planted"
deploy a
[[ "$drc" == 2 && "$dlast" == "deploy: rolled back: machine-from-worker.sql would lose rows (grit.session_events rewritten without their subject 1); its counts are in the log, a person decides; left as it began: kalandra ${old_head:0:12}, jar ${old_jar:0:12}, serve answering on $listen" ]]
holds $? "(a) the deploy stops at the row that would lose its subject, exit 2"
grep -qxF "deploy: dry  machine-from-worker.sql  grit.session_events rewritten without their subject  1" "$dlog"; holds $? "  its log has the dry run's count"
[[ "$(stat -c %a "$dhome/.config/kalandra/credential" "$dhome/.config/grit/credential" | sort -u)" == 600 ]]
holds $? "  it made the two credentials, mode 600"
[[ "$(grep -cxE 'KALANDRA_(OWNER|SESSIONS)_DIGEST=[0-9a-f]{64}' "$clone/.env")" == 2 ]]; holds $? "  and appended their two digests to the copy's .env"
grep -qF "$(<"$dhome/.config/kalandra/credential")" "$dlog" "$work/deploy-a.out"; [[ $? == 1 ]]; holds $? "  and neither log holds the command line's credential"
began_as
sql "DELETE FROM grit.session_events WHERE session = $p_session AND seq = $p_seq" >/dev/null

# ---- 5. (b) A failure after the data file is kept and the new serve is up: the join's MCP add
# fails, after a keep through the new serve and a key a session writes into the settings.
touch "$plant"
stage_proof
deploy b
[[ "$drc" == 2 && "$dlast" == "deploy: rolled back: the join in $repo exited 1: grit claude-code: claude mcp add failed"*"; left as it began: kalandra ${old_head:0:12}, jar ${old_jar:0:12}, machine-from-worker.sql reversed, joins as before in 1 checkouts, serve answering on $listen" ]]
holds $? "(b) the deploy rolls back from a failed join, exit 2"
grep -qxF "deploy: machine-from-worker.sql: kept" "$dlog"; holds $? "  after it kept the data file"
[[ ! -e "$plant" ]]; holds $? "  the plant fired"
[[ "$(jq -r '.proofMidDeploy' "$repo/.claude/settings.local.json")" == kept ]]; holds $? "  the key written mid-deploy is still in the settings"
began_as
O() { env -i PATH=/usr/bin:/bin HOME="$dhome" KALANDRA_LISTEN="$listen" "$clone/bin/kalandra" "$@"; }
expect 0 "the copy's serve, rolled back, shows what the new serve kept in its window" O show '{"key":"edge-proof-window/m"}'
grep -q "window-text-1" <<<"$out"; holds $? "  as it kept it"

# ---- 6. (c) The whole deploy; (d) again, with nothing to rewrite.
stage_proof
deploy c
X12="${proved:0:12}"
[[ "$drc" == 0 && "$dlast" == "deploy: held: kalandra $X12 with grit $version at ${at:0:12} serving on $listen; 1 data files kept, 2 checkouts joined" ]]
holds $? "(c) the deploy holds, exit 0"
sed -n 's/^deploy: \([a-z]* took .*\)/time  (c) \1/p' "$dlog" | tee -a "$proof/proof.txt"
sed -n 's/^deploy: dry  machine-from-worker.sql  //p' "$dlog" >"$proof/forward-dry.txt"
sed 's/^/dry     /' "$proof/forward-dry.txt" | tee -a "$proof/proof.txt"
[[ "$(git -C "$clone" symbolic-ref -q --short HEAD) $(git -C "$clone" rev-parse HEAD)" == "$old_branch $proved" ]]
holds $? "  the copy's $old_branch is at the proved commit, checked out"
[[ "$(sha "$clone/out/assembly.dest/out.jar")" == "$(sha "$proof/serve.jar")" ]]; holds $? "  its jar is the proved jar"
grep -qxF "[kalandra] grit $version at $at" "$clone/logs/serve.log"; holds $? "  its serve logs [kalandra] grit $version at $at"
w_left="$(under worker)"; m_now="$(under machine)"
[[ "$w_left" == 0 && "$m_now" != 0 ]]; holds $? "  no place is under worker, $m_now under machine"
own="$dhome/.config/kalandra/credential"; ses="$dhome/.config/grit/credential"
grep -qxF "deploy: join    credential $ses, sent with every request" "$dlog"; holds $? "  the join reports the sessions' credential"
grep -qxF "deploy: join    mcp       kalandra at $edge/mcp, local scope, replacing kalandra at $edge/mcp" "$dlog"; holds $? "  and replaces the copy's MCP entry"
for w in "$repo" "$repo_wt"; do
  [[ "$(entry "$w" "$dhome")" == "$(jq -nc --arg u "$edge/mcp" --arg h "'$hook' header" '{type: "http", url: $u, headersHelper: $h}')" ]]
  holds $? "  $w's MCP entry is $edge/mcp with the hook's header as its headersHelper"
done
stage_proof
deploy d
[[ "$drc" == 0 && "$dlast" == "deploy: held: kalandra $X12 with grit $version at ${at:0:12} serving on $listen; 0 data files kept, 2 checkouts joined" ]]
holds $? "(d) a second deploy holds, exit 0"
grep -qxF "deploy: machine-from-worker.sql: nothing to rewrite" "$dlog" && ! grep -q "machine-from-worker.sql: kept" "$dlog"
holds $? "  its dry run counts 0, and nothing is kept"
grep -qxF "deploy: credentials: $own kept; its digest is configured already" "$dlog"; holds $? "  and the credentials are kept as they are"

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

# ---- 8. Each credential the deploy made gets its caller.
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

# ---- 9. The lower credential is refused adopt, and reaches the other own commands.
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

# ---- 10. The deploy's join beside a fresh join; then the hook.
new_entry="$(entry "$repo" "$dhome")"
rc=0; J "$work/repo2" "$work/jhome2" "$edge" --credential "$ses" --mcp-name kalandra <"$proof/join.sh" >"$work/join2.out" 2>&1 || rc=$?
holds "$rc" "a fresh join with --credential exits 0"
sed "s|$work|<work>|g" "$work/join2.out" >"$proof/join.txt"
sed 's/^/join    /' "$proof/join.txt" | tee -a "$proof/proof.txt"
norm() { sed "s|$2|<repo>|g" "$1"; }
settings_of() { jq -S 'del(.proofMidDeploy)' "$1/.claude/settings.local.json" | sed "s|$1|<repo>|g"; }
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

# ---- 11. A real Claude Code run, its MCP client through the join's headersHelper, by a forwarder
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
holds $? "  no handler and no MCP entry is left"
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

say "time  edge-proof took $((SECONDS - began_at)) s"
say "total $(grep -c '^ok ' "$proof/proof.txt") checks passed, $failed failed"
if [[ "$failed" == 0 ]]; then
  say "edge-proof: passed"
else
  say "edge-proof: $failed checks failed"
  for f in "$work"/deploy-*.out; do
    l="$(sed -n 's/^deploy: log //p' "$f")"
    if [[ -f "$l" ]]; then echo "$(basename "$f")'s log ends:" >&2; tail -20 "$l" >&2; fi
  done
  if [[ -f "$clone/logs/serve.log" ]]; then
    echo "the copy's serve log ends:" >&2
    tail -20 "$clone/logs/serve.log" >&2
  fi
  exit 1
fi
