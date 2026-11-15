#!/usr/bin/env bash
# Deploys a proven build into a kalandra checkout, or rolls itself back: the proved commit and jar,
# with no build; the data files the build needs; the credentials serve takes; and every grit
# checkout joined to serve, joined again. Run by `bin/kalandra deploy`:
#
#   bin/kalandra deploy [--checkout DIR] [--grit DIR] [--detach] <proof-dir>
#
#   <proof-dir>      what ops/edge-proof.sh wrote: proof.txt, ending `edge-proof: passed` and naming
#                    the commit it proved (`commit <sha>`) and the grit the jar is built against;
#                    serve.jar, join.sh and every data file ops/data.tsv names, each with its .sha256
#   --checkout DIR   the checkout deployed into (default: the one this script is in); it must be
#                    clean, and at the proved commit or an ancestor of it
#   --grit DIR       the repository whose checkouts are joined again: each of its worktrees whose
#                    settings name its hook, joined to this serve (default $KALANDRA_CHECKOUT, or
#                    ~/Projects/grit)
#   --detach         return once the run has started, rather than when it ends
#
# The run is detached from the terminal and the session that started it (stopping serve ends that
# session's MCP connection), and writes one log, runs/<stamp>/deploy.log under
# ${XDG_STATE_HOME:-~/.local/state}/kalandra-deploy, whose last line is how it ended. The three
# newest runs are kept, each with what it backed up and its dump. Each stage's line in the log says
# the seconds it took; the last line, the run's and those serve was down (stopped to answering). A second deploy while one runs
# is refused. Exit: 0 held; 1 refused, nothing changed; 2 rolled back, left as it began; 3 the
# rollback did not finish, and the log's last line says what a person must do; 64 usage.
#
# Serve is the checkout's: its systemd unit when it manages serve (KALANDRA_LISTEN unset or
# 127.0.0.1:7317 and the unit enabled), whose GRIT_DATABASE_URL names the database; otherwise the
# serve bin/kalandra starts at KALANDRA_LISTEN, on GRIT_DATABASE_URL, which must then be set.
# Credentials are the command line's (~/.config/kalandra/credential) and the sessions'
# (~/.config/grit/credential); one that is missing is made, and its SHA-256 digest appended to the
# checkout's .env, whose other lines are never read or printed. No credential's text is put on a
# command line, in an environment or in the log.
set -uo pipefail

usage() {
  sed -n '5,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 64
}

self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/$(basename "${BASH_SOURCE[0]}")"
checkout=""
grit="${KALANDRA_CHECKOUT:-$HOME/Projects/grit}"
detach=0
run=""
proof=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --checkout) [[ $# -ge 2 ]] || usage; checkout="$2"; shift ;;
    --grit) [[ $# -ge 2 ]] || usage; grit="$2"; shift ;;
    --detach) detach=1 ;;
    --run) [[ $# -ge 2 ]] || usage; run="$2"; shift ;;
    -*) usage ;;
    *) [[ -z "$proof" ]] || usage; proof="$1" ;;
  esac
  shift
done
[[ -n "$proof" ]] || usage
[[ -n "$checkout" ]] || checkout="$(dirname "$(dirname "$self")")"

# What a session's environment would send a step elsewhere with, or a credential deploy does not name.
unset CLAUDE_CODE_SESSION_ID GRIT_EDGE GRIT_HOOK_STARTED GRIT_HOOK_SELF CLAUDE_PROJECT_DIR \
  KALANDRA_CREDENTIAL http_proxy HTTP_PROXY https_proxy HTTPS_PROXY all_proxy ALL_PROXY

listen="${KALANDRA_LISTEN:-127.0.0.1:7317}"
edge="http://$listen"
compose="${GRIT_COMPOSE_DIR:-$HOME/Projects/grit}"
state="${XDG_STATE_HOME:-$HOME/.local/state}/kalandra-deploy"
owner_cred="$HOME/.config/kalandra/credential"
sessions_cred="$HOME/.config/grit/credential"
unit=kalandra-serve.service
mcp=kalandra
# What the health check asks serve, through each credential: a read of the project kalandra keeps.
health_ask='{"project":"grit","state":"now"}'

# The last line of a run that ends, and the exit it means.
finish() {
  stage=ended
  printf '%s\n' "$1"
  case "$1" in
    "deploy: rollback incomplete:"*) ;;
    *) rm -f -- "$state/running" ;;
  esac
  prune
  exit 0
}
refuse() {
  if [[ -n "$run" ]]; then finish "deploy: refused: $*; nothing was changed"; fi
  echo "deploy: refused: $*; nothing was changed"
  exit 1
}
log() { [[ -z "$run" ]] || printf 'deploy: %s\n' "$*"; }
prune() {
  [[ -n "$run" && -d "$state/runs" ]] || return 0
  local d
  while IFS= read -r d; do
    [[ -n "$d" ]] && rm -rf -- "${state:?}/runs/$d"
  done < <(ls -1 "$state/runs" | sort | head -n -3)
}

pg() { docker compose --project-directory "$compose" exec -T postgres "$@"; }
psql_db() { pg psql -U grit -d "$db" -q -v ON_ERROR_STOP=1 "$@"; }
sql() { psql_db -At -c "$1"; }
sha() { sha256sum <"$1" | cut -d' ' -f1; }
# The SHA-256 of a credential as serve takes it: its text, newlines removed.
digest() { tr -d '\n' <"$1" | sha256sum | cut -d' ' -f1; }
# curl to serve bearing the credential in the file $1, which is never on a command line.
cred_curl() {
  local f="$1"
  shift
  curl -q -s --noproxy '*' --max-time 15 -H @<(printf 'Authorization: Bearer %s\n' "$(<"$f")") "$@"
}
answers() { curl -q -s --noproxy '*' --max-time 5 -o /dev/null -X POST -d '{}' "$edge/command/project_list" 2>/dev/null; }
managed() { [[ "$listen" == 127.0.0.1:7317 ]] && systemctl --user is-enabled --quiet "$unit" 2>/dev/null; }
kalandra() { KALANDRA_LISTEN="$listen" "$checkout/bin/kalandra" "$@" 9>&-; }

# ---- What is checked before anything changes, by the command and again by the run.
checksum() {
  local s n
  [[ -f "$proof/$1" && -f "$proof/$1.sha256" ]] || refuse "$proof does not hold $1 and $1.sha256"
  read -r s n <"$proof/$1.sha256" || true
  [[ "$n" == "$1" && "$(sha "$proof/$1")" == "$s" ]] || refuse "$proof/$1 does not match $1.sha256"
}

preflight() {
  local p="$proof/proof.txt" first line f
  [[ -f "$p" ]] || refuse "$proof holds no proof.txt"
  [[ "$(tail -n 1 "$p")" == "edge-proof: passed" ]] || refuse "$p does not end edge-proof: passed"
  first="$(head -n 1 "$p")"
  [[ "$first" != *" with changes" ]] || refuse "the proof ran on ${first##* at } with changes; prove a commit"
  X="$(sed -n 's/^commit \([0-9a-f]\{40\}\)$/\1/p' "$p" | head -n 1)"
  [[ -n "$X" ]] || refuse "proof.txt names no commit; run ops/edge-proof.sh again"
  line="$(sed -n 's/^grit  the jar is built against grit \(.*\) at \([0-9a-f]\{40\}\)$/\1 \2/p' "$p" | head -n 1)"
  [[ -n "$line" ]] || refuse "proof.txt names no grit the jar is built against"
  read -r gv gc <<<"$line"
  checksum serve.jar
  checksum join.sh
  jar_sha="$(sha "$proof/serve.jar")"

  [[ "$(git -C "$checkout" rev-parse --show-toplevel 2>/dev/null)" == "$checkout" ]] ||
    refuse "$checkout is not the top of a git checkout"
  git -C "$checkout" cat-file -e "$X^{commit}" 2>/dev/null ||
    refuse "the proved commit ${X:0:12} is not in $checkout's repository"
  [[ "$(sha "$self")" == "$(git -C "$checkout" show "$X:ops/deploy.sh" 2>/dev/null | sha256sum | cut -d' ' -f1)" ]] ||
    refuse "this deploy.sh is not ops/deploy.sh at the proved commit ${X:0:12}"

  fwd=() rev=() loss=()
  local a b c
  while IFS=$'\t' read -r a b c; do
    [[ -z "$a" || "$a" == '#'* ]] && continue
    [[ -n "$b" ]] || refuse "ops/data.tsv names no reverse for $a"
    checksum "$a"
    checksum "$b"
    [[ "$(sha "$proof/$b")" == "$(git -C "$checkout" show "$X:ops/$b" 2>/dev/null | sha256sum | cut -d' ' -f1)" ]] ||
      refuse "$proof/$b is not ops/$b at the proved commit ${X:0:12}"
    fwd+=("$a") rev+=("$b") loss+=("$c")
  done < <(git -C "$checkout" show "$X:ops/data.tsv" 2>/dev/null)

  [[ -z "$(git -C "$checkout" status --porcelain --untracked-files=no)" ]] || refuse "$checkout has changes to tracked files"
  H="$(git -C "$checkout" rev-parse HEAD)"
  B="$(git -C "$checkout" symbolic-ref -q --short HEAD || true)"
  git -C "$checkout" merge-base --is-ancestor "$H" "$X" ||
    refuse "$checkout is at ${H:0:12}, which the proved commit ${X:0:12} does not descend from"
  [[ -f "$checkout/out/assembly.dest/out.jar" ]] || refuse "$checkout has no jar at out/assembly.dest/out.jar to roll back to"

  local url
  if managed; then
    url="$(systemctl --user show -p Environment --value "$unit" | tr ' ' '\n' | sed -n 's/^GRIT_DATABASE_URL=//p')"
    [[ -z "${GRIT_DATABASE_URL:-}" || "$GRIT_DATABASE_URL" == "$url" ]] ||
      refuse "the unit's database $url is not GRIT_DATABASE_URL's $GRIT_DATABASE_URL"
    ! systemctl --user is-active --quiet kalandra-backup.service || refuse "the nightly backup is running; deploy after it"
  else
    [[ "$listen" == 127.0.0.1:7317 || -n "${GRIT_DATABASE_URL:-}" ]] || refuse "GRIT_DATABASE_URL is not set for serve on $listen"
    url="${GRIT_DATABASE_URL:-jdbc:postgresql://localhost:5432/grit_kalandra}"
    export GRIT_DATABASE_URL="$url"
  fi
  [[ "$url" =~ ^jdbc:postgresql://localhost:5432/([a-z_][a-z0-9_]*)$ ]] ||
    refuse "serve's database $url is not one on localhost:5432"
  db="${BASH_REMATCH[1]}"
  answers || refuse "serve does not answer at $listen; deploy starts from a running serve"

  # Each credential: made, its digest appended, or kept as it is.
  env_file="$checkout/.env"
  local x n f
  for x in OWNER SESSIONS; do
    if [[ "$x" == OWNER ]]; then f="$owner_cred"; else f="$sessions_cred"; fi
    n=0
    [[ ! -f "$env_file" ]] || n="$(grep -cE "^(export )?KALANDRA_${x}_DIGEST=" "$env_file")"
    ((n <= 1)) || refuse "KALANDRA_${x}_DIGEST is in $env_file more than once"
    if [[ -f "$f" ]]; then
      [[ "$(stat -c %a "$f")" == *00 ]] || refuse "$f is readable by others; make it 0600"
      if ((n == 1)); then
        line="$(grep -xE "(export )?KALANDRA_${x}_DIGEST=[0-9a-f]{64}" "$env_file")" ||
          refuse "KALANDRA_${x}_DIGEST in $env_file is not 64 lowercase hex"
        [[ "${line##*=}" == "$(digest "$f")" ]] || refuse "$f's digest is not KALANDRA_${x}_DIGEST in $env_file"
        plan[$x]=keep
      else
        plan[$x]=append
      fi
    else
      ((n == 0)) || refuse "$env_file has KALANDRA_${x}_DIGEST but $f is missing; a person decides which to keep"
      plan[$x]=make
    fi
  done

  # The checkouts joined to this serve, and the credential each one's hook sends now.
  joined=() old_cred=()
  git -C "$grit" rev-parse --git-dir >/dev/null 2>&1 || refuse "$grit is not a git checkout"
  local w common hook settings e cr
  while IFS= read -r w; do
    [[ "$w" == "worktree "* ]] || continue
    w="${w#worktree }"
    if [[ ! -d "$w" ]]; then log "join: $w skipped: it is gone"; continue; fi
    common="$(git -C "$w" rev-parse --path-format=absolute --git-common-dir)" || refuse "git cannot name $w's repository"
    hook="$common/grit/claude-code/hook"
    settings="$w/.claude/settings.local.json"
    [[ "$hook" != *"'"* ]] || refuse "$hook holds a quote"
    [[ -f "$settings" ]] && jq -e --arg ours "'$hook' " \
      '[(.hooks // {})[][] | (.hooks // [])[] | .command? | strings | select(startswith($ours))] | length > 0' \
      "$settings" >/dev/null 2>&1 || continue
    [[ -f "$hook" ]] || { log "join: $w skipped: its settings name $hook, which is gone"; continue; }
    e="$(sed -n "s/^edge='\(.*\)'$/\1/p" "$hook" | head -n 1)"
    if [[ "$e" != "$edge" ]]; then log "join: $w skipped: it is joined to ${e:-no edge}, not $edge"; continue; fi
    cr="$(sed -n "s/^credential='\(.*\)'$/\1/p" "$hook" | head -n 1)"
    joined+=("$w") old_cred+=("$cr")
  done < <(git -C "$grit" worktree list --porcelain)
  ((${#joined[@]} == 0)) || command -v claude >/dev/null || refuse "claude is not on PATH, which the join needs"
}

declare -A plan
X="" gv="" gc="" jar_sha="" H="" B="" db="" env_file=""
fwd=() rev=() loss=() joined=() old_cred=()

# ---- The command: refusals, then the run, detached, followed to its end unless --detach.
if [[ -z "$run" ]]; then
  proof="$(cd "$proof" 2>/dev/null && pwd -P)" || refuse "no proof directory $proof"
  checkout="$(cd "$checkout" 2>/dev/null && pwd -P)" || refuse "no checkout $checkout"
  grit="$(cd "$grit" 2>/dev/null && pwd -P)" || refuse "no grit checkout $grit"
  mkdir -p "$state/runs" && chmod 700 "$state" || refuse "cannot make $state"
  exec 9>>"$state/lock"
  if ! flock -n 9; then
    read -r p l <"$state/running" 2>/dev/null || true
    refuse "a deploy is running (pid ${p:-?}): ${l:-its log}"
  fi
  if [[ -e "$state/running" ]]; then
    read -r p l <"$state/running" || true
    refuse "the last deploy did not end held or rolled back; read ${l:-its log}, then remove $state/running"
  fi
  preflight
  run="$state/runs/$(date -u +%Y%m%dT%H%M%SZ)-$$"
  mkdir -p "$run" && chmod 700 "$run" && cp -- "$self" "$run/deploy.sh" || { echo "deploy: refused: cannot make $run; nothing was changed"; exit 1; }
  log_file="$run/deploy.log"
  ( cd "$run" && setsid nohup bash "$run/deploy.sh" --run "$run" --checkout "$checkout" --grit "$grit" "$proof" \
      </dev/null >>"$log_file" 2>&1 & )
  for _ in $(seq 50); do [[ -s "$run/pid" ]] && break; sleep 0.2; done
  rpid="$(cat "$run/pid" 2>/dev/null)"
  echo "deploy: kalandra ${X:0:12} with grit $gv at ${gc:0:12}, jar ${jar_sha:0:12}, from $proof"
  echo "deploy: log $log_file"
  echo "deploy: running as pid ${rpid:-?}; it goes on if this terminal or session ends"
  ((detach == 0)) || exit 0
  exec 9>&-
  ended() {
    local last
    last="$(tail -n 1 "$log_file" 2>/dev/null)"
    case "$last" in
      "deploy: held"*) echo "$last"; exit 0 ;;
      "deploy: refused:"*) echo "$last"; exit 1 ;;
      "deploy: rolled back:"*) echo "$last"; exit 2 ;;
      "deploy: rollback incomplete:"*) echo "$last"; exit 3 ;;
    esac
  }
  while :; do
    ended
    if [[ -z "$rpid" ]] || ! kill -0 "$rpid" 2>/dev/null; then
      ended
      echo "deploy: the run (pid ${rpid:-?}) ended without a last line; read $log_file"
      exit 3
    fi
    sleep 2
  done
fi

# ---- The run.
cd "$run" || exit 1
echo "$$" >"$run/pid"
# Seconds, to a tenth: the run's start, and when serve stopped answering and answered again.
now() { local t="$EPOCHREALTIME"; echo "${t/,/.}"; }
since() { awk -v a="$1" -v b="${2:-$(now)}" 'BEGIN { printf "%.1f", b - a }'; }
t0="$(now)" down_at="" up_at=""
timing() {
  local said="took $(since "$t0") s"
  if [[ -z "$down_at" ]]; then
    said+=", serve never stopped"
  elif [[ -n "$up_at" ]]; then
    said+=", serve down $(since "$down_at" "$up_at") s"
  else
    said+=", serve down $(since "$down_at") s and not answering"
  fi
  echo "$said"
}
printf '%s %s\n' "$$" "$run/deploy.log" >"$state/running"
stage=preflight
trap '[[ "$stage" == ended ]] || printf "deploy: rollback incomplete: the run stopped during %s without rolling back; a person must read this log\n" "$stage"' EXIT
log "run $$ in $run"
preflight
log "deploying kalandra $X with grit $gv at $gc, jar $jar_sha, from $proof into $checkout"
log "serve at $listen on $db$(managed && echo ", by $unit")"
if ((${#joined[@]} == 0)); then log "join: no checkout of $grit is joined to $edge"; else log "join: joined to $edge: ${joined[*]}"; fi

stopped=0 started=0 code_switched=0 jar_switched=0 restored=""
kept=() joined_new=()
old_jar_sha=""
why=""

stage_credentials() {
  local x f d appended=()
  for x in OWNER SESSIONS; do
    if [[ "$x" == OWNER ]]; then f="$owner_cred"; else f="$sessions_cred"; fi
    case "${plan[$x]}" in
      make)
        ( umask 077 && mkdir -p "$(dirname "$f")" && set -o noclobber &&
          head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' >"$f" ) || { why="making $f failed"; return 1; }
        log "credentials: $f made"
        appended+=("$x")
        ;;
      append) log "credentials: $f kept; its digest is configured now"; appended+=("$x") ;;
      keep) log "credentials: $f kept; its digest is configured already" ;;
    esac
  done
  ((${#appended[@]} > 0)) || return 0
  [[ -e "$env_file" ]] || (umask 077 && : >"$env_file") || { why="making $env_file failed"; return 1; }
  {
    printf '\n'
    for x in "${appended[@]}"; do
      if [[ "$x" == OWNER ]]; then f="$owner_cred"; else f="$sessions_cred"; fi
      printf 'KALANDRA_%s_DIGEST=%s\n' "$x" "$(digest "$f")"
    done
  } >>"$env_file" || { why="appending to $env_file failed"; return 1; }
  for x in "${appended[@]}"; do log "credentials: KALANDRA_${x}_DIGEST appended to $env_file"; done
}

stage_backup() {
  mkdir -p "$run/before" && chmod 700 "$run/before" || { why="cannot make $run/before"; return 1; }
  printf '%s %s\n' "$H" "${B:--}" >"$run/before/head"
  cp -p -- "$checkout/out/assembly.dest/out.jar" "$run/before/out.jar" || { why="copying the jar failed"; return 1; }
  old_jar_sha="$(sha "$run/before/out.jar")"
  log "backup: kalandra $H${B:+ on $B}, jar $old_jar_sha"
  ((${#joined[@]} > 0)) || return 0
  local code
  if [[ -n "${old_cred[0]}" ]]; then
    code="$(cred_curl "${old_cred[0]}" -o "$run/before/join.sh" -w '%{http_code}' "$edge/join/claude-code")"
  else
    code="$(curl -q -s --noproxy '*' --max-time 15 -o "$run/before/join.sh" -w '%{http_code}' "$edge/join/claude-code")"
  fi
  [[ "$code" == 200 && -s "$run/before/join.sh" ]] || { why="the running serve's /join/claude-code answered $code"; return 1; }
  log "backup: the running serve's join script, $(sha "$run/before/join.sh"), to join again as before"
}

stage_stop() {
  stopped=1
  down_at="$(now)"
  kalandra stop || { why="bin/kalandra stop failed"; return 1; }
  ! answers || { why="serve still answers at $listen after the stop"; return 1; }
}

stage_connections() {
  local n
  for _ in $(seq 10); do
    n="$(sql "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()")" ||
      { why="Postgres did not answer"; return 1; }
    [[ "$n" == 0 ]] && { log "connections: nothing else is connected to $db"; return 0; }
    sleep 1
  done
  log "connections: $(sql "SELECT string_agg(coalesce(nullif(application_name, ''), 'no name') || ' from ' || coalesce(client_addr::text, 'a socket'), ', ') FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()")"
  why="$n other connections to $db"
  return 1
}

stage_dump() {
  pg pg_dump -U grit -Fc "$db" >"$run/before.dump" && pg pg_restore --list <"$run/before.dump" >/dev/null ||
    { why="the dump failed: $(tail -n 1 "$run/before.dump" 2>/dev/null | head -c 200)"; return 1; }
  log "dump: $db to $run/before.dump, $(du -h "$run/before.dump" | cut -f1)"
}

# Runs the data file $1 (any more arguments go to psql); sets `rows`, its counts as `name|n` lines,
# and `err`, what went wrong. Returns 1 when psql failed, so nothing was kept; 2 when it ran but
# printed something other than its counts.
data_run() {
  local out rc=0
  out="$(psql_db -At -F'|' -f - "${@:2}" <"$1" 2>"$run/psql.err")" || rc=$?
  err="$(grep -m 1 'ERROR' "$run/psql.err" || tail -n 1 "$run/psql.err")"
  rows="$(grep -E '^[^|]+\|[0-9]+$' <<<"$out")"
  ((rc == 0)) || return 1
  if grep -vqE '^([^|]+\|[0-9]+|COMMIT|)$' <<<"$out" || [[ -z "$rows" ]]; then
    err="it printed something other than its counts"
    return 2
  fi
}
nonzero() { awk -F'|' '$2 != 0' <<<"$1"; }

stage_data() {
  local i f r name n lost
  for i in "${!fwd[@]}"; do
    f="${fwd[i]}"
    data_run "$proof/$f" || { why="$f raised: $err"; return 1; }
    while IFS='|' read -r name n; do log "dry  $f  $name  $n"; done <<<"$rows"
    if [[ -z "$(nonzero "$rows")" ]]; then log "$f: nothing to rewrite"; continue; fi
    n="$(sql "SELECT count(*) FROM dbos.workflow_status WHERE status IN ('PENDING', 'ENQUEUED')")" || { why="Postgres did not answer"; return 1; }
    [[ "$n" == 0 ]] || { why="$n workflows pending or enqueued while $f is pending; let them finish, then deploy again"; return 1; }
    lost=()
    IFS=';' read -r -a names <<<"${loss[i]}"
    for name in "${names[@]}"; do
      [[ -n "$name" ]] || continue
      n="$(awk -F'|' -v w="$name" '$1 == w { print $2 }' <<<"$rows")"
      [[ -n "$n" ]] || { why="$f printed no row $name; its counts are in the log, a person decides"; return 1; }
      [[ "$n" == 0 ]] || lost+=("$name $n")
    done
    if ((${#lost[@]} > 0)); then
      why="$f would lose rows ($(IFS=';'; echo "${lost[*]}" | sed 's/;/, /g')); its counts are in the log, a person decides"
      return 1
    fi
    local dry="$rows" rc=0
    data_run "$proof/$f" -c COMMIT || rc=$?
    ((rc != 1)) || { why="$f raised: $err"; return 1; }
    kept+=("$i")
    ((rc == 0)) || { why="$f, kept, $err"; return 1; }
    [[ "$rows" == "$dry" ]] || { why="$f kept counts other than its dry run's"; return 1; }
    log "$f: kept"
    data_run "$proof/$f" || { why="$f raised on its second run: $err"; return 1; }
    [[ -z "$(nonzero "$rows")" ]] || { why="$f counts $(nonzero "$rows" | head -n 1) on a second run: it is not idempotent"; return 1; }
    log "$f: a second run counts 0 everywhere"
  done
}

put_jar() {
  local dir="$checkout/out/assembly.dest"
  cp -- "$1" "$dir/.out.jar.new" && [[ "$(sha "$dir/.out.jar.new")" == "$2" ]] && mv -f -- "$dir/.out.jar.new" "$dir/out.jar"
}

stage_code() {
  code_switched=1
  git -C "$checkout" switch -q --detach "$X" || { why="git could not check out ${X:0:12} in $checkout"; return 1; }
  jar_switched=1
  put_jar "$proof/serve.jar" "$jar_sha" || { why="the proved jar did not go into $checkout"; return 1; }
  if managed; then systemctl --user daemon-reload || { why="systemctl daemon-reload failed"; return 1; }; fi
  log "code: $checkout at $X, detached; jar $jar_sha"
}

stage_start() {
  serve_log_size="$(stat -c %s "$checkout/logs/serve.log" 2>/dev/null || echo 0)"
  started=1
  kalandra start || { why="serve did not answer within 120 s"; return 1; }
  up_at="$(now)"
}

stage_join() {
  local w out rc
  for w in "${joined[@]}"; do
    joined_new+=("$w")
    rc=0
    out="$(cd "$w" && bash -s -- "$edge" --credential "$sessions_cred" --mcp-name "$mcp" <"$proof/join.sh" 2>&1)" || rc=$?
    sed 's/^/deploy: join  /' <<<"$out"
    ((rc == 0)) || { why="the join in $w exited $rc: $(tail -n 1 <<<"$out")"; return 1; }
  done
}

stage_health() {
  local line code who f
  line="$(tail -c +"$((serve_log_size + 1))" "$checkout/logs/serve.log" | grep -a '^\[kalandra\] grit ' | tail -n 1)"
  [[ "$line" == "[kalandra] grit $gv at $gc" ]] ||
    { why="serve's startup line names grit ${line#\[kalandra\] grit }, not $gv at ${gc:0:12}"; [[ -n "$line" ]] || why="serve logged no startup line naming its grit"; return 1; }
  log "health: serve says $line"
  for who in "the owner's" "the sessions'"; do
    if [[ "$who" == "the owner's" ]]; then f="$owner_cred"; else f="$sessions_cred"; fi
    code="$(cred_curl "$f" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "$health_ask" "$edge/command/project_list")"
    [[ "$code" == 200 ]] || { why="a request with $who credential answered $code"; return 1; }
  done
  code="$(curl -q -s --noproxy '*' --max-time 15 -o /dev/null -w '%{http_code}' -X POST -d '{}' "$edge/command/project_list")"
  [[ "$code" == 401 ]] || { why="a request with no credential answered $code, not 401"; return 1; }
  log "health: project_list of grit answers 200 to the owner's and the sessions' credentials, 401 to none"
  code="$(cred_curl "$sessions_cred" -o "$run/join.served" -w '%{http_code}' "$edge/join/claude-code")"
  [[ "$code" == 200 && "$(sha "$run/join.served")" == "$(sha "$proof/join.sh")" ]] ||
    { why="/join/claude-code served a script that is not join.sh"; return 1; }
  log "health: /join/claude-code serves join.sh"
}

incomplete() {
  stage=ended
  finish "deploy: rollback incomplete: $1; a person must $2; $(timing)"
}

rollback() {
  local left=() i r w rc out
  stage=rollback
  log "rollback: $1"
  if ((started)); then
    kalandra stop || incomplete "stopping the new serve failed" "stop serve (bin/kalandra stop in $checkout), then read this log"
  fi
  for ((i = ${#kept[@]} - 1; i >= 0; i--)); do
    r="${rev[${kept[i]}]}"
    rc=0
    data_run "$proof/$r" -c COMMIT || rc=$?
    if ((rc == 1)); then
      log "rollback: $r raised: $err; restoring the dump"
      restored="${db}_rolled_back_$(date -u +%Y%m%d%H%M%S)"
      pg psql -U grit -d postgres -q -v ON_ERROR_STOP=1 -c "ALTER DATABASE $db RENAME TO $restored" -c "CREATE DATABASE $db" &&
        pg pg_restore -U grit -d "$db" --no-owner --exit-on-error <"$run/before.dump" ||
        incomplete "restoring $db from $run/before.dump failed" "restore it by hand; the database as the deploy left it is $restored"
      left+=("$db restored from the dump, the deploy's kept as $restored")
      break
    fi
    data_run "$proof/$r" && [[ -z "$(nonzero "$rows")" ]] ||
      incomplete "$r counts other than 0 after it ran" "read this log; the dump from before the deploy is $run/before.dump"
    log "rollback: $r kept"
    left+=("${fwd[${kept[i]}]} reversed")
  done
  if ((code_switched)); then
    if [[ -n "$B" ]]; then git -C "$checkout" switch -q "$B"; else git -C "$checkout" switch -q --detach "$H"; fi ||
      incomplete "git could not check out ${B:-$H} in $checkout" "check out ${B:-$H} there and put $run/before/out.jar back as out/assembly.dest/out.jar"
  fi
  if ((jar_switched)); then
    put_jar "$run/before/out.jar" "$old_jar_sha" ||
      incomplete "the old jar did not go back" "copy $run/before/out.jar to $checkout/out/assembly.dest/out.jar"
  fi
  if ((code_switched)) && managed; then systemctl --user daemon-reload || true; fi
  if ((stopped)) && ! answers; then
    up_at=""
    kalandra start || incomplete "the old serve did not start" "start serve (bin/kalandra start in $checkout) and read this log"
    up_at="$(now)"
  fi
  for i in "${!joined_new[@]}"; do
    w="${joined_new[i]}"
    rc=0
    local args=("$edge")
    [[ -z "${old_cred[i]}" ]] || args+=(--credential "${old_cred[i]}")
    out="$(cd "$w" && bash -s -- "${args[@]}" --mcp-name "$mcp" <"$run/before/join.sh" 2>&1)" || rc=$?
    sed 's/^/deploy: join  /' <<<"$out"
    ((rc == 0)) || incomplete "joining $w again as before exited $rc: $(tail -n 1 <<<"$out")" \
      "join it again: cd $w && bash -s -- ${args[*]} --mcp-name $mcp < $run/before/join.sh"
  done
  ((${#joined_new[@]} == 0)) || left+=("joins as before in ${#joined_new[@]} checkouts")
  stage=ended
  local said
  said="kalandra ${H:0:12}, jar ${old_jar_sha:0:12}"
  for r in "${left[@]}"; do said+=", $r"; done
  if ((stopped)); then said+=", serve answering on $listen"; fi
  finish "deploy: rolled back: $1; left as it began: $said; $(timing)"
}

for s in credentials backup stop connections dump data code start join health; do
  stage="$s"
  why=""
  s0="$(now)"
  "stage_$s" || { log "$s took $(since "$s0") s, and failed"; rollback "${why:-the $s stage failed}"; }
  log "$s took $(since "$s0") s"
done
stage=land

# Held: the checkout's branch moves to the proved commit, with no change to its files.
landed=""
if [[ -z "$B" ]]; then
  landed="; $checkout left detached at ${X:0:12}, as it was detached before"
elif git -C "$checkout" update-ref "refs/heads/$B" "$X" "$H" && git -C "$checkout" switch -q "$B"; then
  log "land: $B at $X"
else
  landed="; $checkout left detached at ${X:0:12}: $B did not move from ${H:0:12}"
fi
finish "deploy: held: kalandra ${X:0:12} with grit $gv at ${gc:0:12} serving on $listen; ${#kept[@]} data files kept, ${#joined_new[@]} checkouts joined$landed; $(timing)"
