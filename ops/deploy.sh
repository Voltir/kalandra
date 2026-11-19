#!/usr/bin/env bash
# Deploys a proven build into a kalandra checkout, or rolls itself back: the proved commit and jar,
# with no build; the data files the build needs; the credentials serve takes; the method versions
# the commit holds, declared; and every grit checkout joined to serve, joined again. Run by
# `bin/kalandra deploy`:
#
#   bin/kalandra deploy [--checkout DIR] [--grit DIR] [--detach] <proof-dir>
#   bin/kalandra deploy --health
#
#   <proof-dir>      what ops/edge-proof.sh wrote: proof.txt, ending `edge-proof: passed` and naming
#                    the commit it proved (`commit <sha>`), the grit the jar is built against and
#                    the seconds the proof took, within the budget edge-proof.sh names at that
#                    commit; serve.jar, join.sh and every data file ops/data.tsv names, each with
#                    its .sha256
#   --health         only ask the running serve three of the health check's five asks, with this
#                    HOME's credentials, and change nothing: project_list through each credential,
#                    the brief, and the 401 with none (not serve's startup line, nor the join
#                    script, which only a deploy has a proof to compare with); exit 0 when every
#                    answer is right, 1 naming the first that is not
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
# the seconds it took; the last line, the run's and those serve was down (stopped to answering).
# A second deploy while one runs is refused, and so is one whose brief, the health check's, the
# running serve refuses (brief_before says which answers refuse). A run past deploy_budget
# seconds, counted from the command's start, says `deploy: over its <n> s budget` in the line
# before its last, and is not rolled back for it: a slow deploy that is healthy stays, as an outage
# would cost more.
# KALANDRA_DEPLOY_BUDGET may set a budget lower than deploy_budget, never higher. Exit: 0 held;
# 1 refused, nothing changed; 2 rolled back, left as it began; 3 the rollback did not finish, and
# the log's last line says what a person must do; 4 held, over its budget; 64 usage. With
# --detach, 0 once the run has started (or 1, refused before it), and the log's last line says
# how it ended.
#
# Serve is the checkout's: its systemd unit when it manages serve (KALANDRA_LISTEN unset or
# 127.0.0.1:7317 and the unit enabled), whose GRIT_DATABASE_URL names the database; otherwise the
# serve bin/kalandra starts at KALANDRA_LISTEN, on GRIT_DATABASE_URL, which must then be set.
# Credentials are the command line's (~/.config/kalandra/credential) and the sessions'
# (~/.config/grit/credential); one that is missing is made, and its SHA-256 digest appended to the
# checkout's .env, whose other lines are never read or printed. No credential's text is put on a
# command line, in an environment or in the log.
#
# Every step that waits is bounded: a Postgres statement or data file 300 s, a dump or restore
# 900 s, a stop 120 s, a start until serve answers 130 s, a join (its `claude mcp` calls with it)
# 120 s, a re-key 120 s. Once serve answers, every project the store holds in the old form
# (ADR 0063) is re-keyed through the new serve before the health check (project_rekey: a survey,
# each project's re-key, a survey that lists none). When a data file's reverse raises, once a method version may have been declared new, or once a
# re-key has been sent, the rollback restores the dump into <db>_restoring and
# only then renames <db> aside as <db>_rolled_back_<stamp>, a person's to drop, and the restored
# one into its place. A rollback that cannot put the database right ends `rollback incomplete`
# with serve stopped, the old code and jar in place, and the unit disabled when it owns serve.
set -uo pipefail

# The deploy's budget, in seconds; with the proof's, ops/edge-proof.sh's proof_budget, five minutes.
deploy_budget=60
began="${EPOCHREALTIME/,/.}"

usage() {
  sed -n '7,/^#   --detach/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 64
}

self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/$(basename "${BASH_SOURCE[0]}")"
checkout=""
grit="${KALANDRA_CHECKOUT:-$HOME/Projects/grit}"
detach=0
health_only=0
run=""
began_given=0
proof=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --checkout) [[ $# -ge 2 ]] || usage; checkout="$2"; shift ;;
    --grit) [[ $# -ge 2 ]] || usage; grit="$2"; shift ;;
    --detach) detach=1 ;;
    --health) health_only=1 ;;
    --run) [[ $# -ge 2 ]] || usage; run="$2"; shift ;;
    # The command's start, which the run's budget counts from: the command passes it to the run,
    # and a person's command line may not, so that no one can start the count late.
    --began) [[ $# -ge 2 && "$2" =~ ^[0-9]+\.[0-9]+$ ]] || usage; began="$2"; began_given=1; shift ;;
    -*) usage ;;
    *) [[ -z "$proof" ]] || usage; proof="$1" ;;
  esac
  shift
done
[[ -n "$proof" || "$health_only" == 1 ]] || usage
[[ -z "$proof" || "$health_only" == 0 ]] || usage
[[ "$began_given" == 0 || -n "$run" ]] || usage
[[ -n "$checkout" ]] || checkout="$(dirname "$(dirname "$self")")"
budget="$deploy_budget"
if [[ -n "${KALANDRA_DEPLOY_BUDGET:-}" ]]; then
  [[ "$KALANDRA_DEPLOY_BUDGET" =~ ^[0-9]+$ && "$KALANDRA_DEPLOY_BUDGET" -le "$deploy_budget" ]] ||
    { echo "deploy: KALANDRA_DEPLOY_BUDGET may lower the budget of $deploy_budget s, not raise it" >&2; exit 64; }
  budget="$KALANDRA_DEPLOY_BUDGET"
fi

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
# What the health check asks serve, by two commands that only read: the project kalandra keeps,
# through each credential, and a brief, through the sessions', as a session asks for one.
health_ask='{"project":"grit","state":"now"}'
health_brief_key=grit/roadmap-coder-rules
health_brief_role=coder
# What serve answers a request with no credential.
no_credential="refused: this edge needs a credential: send Authorization: Bearer <your credential>"

# The last line of a run that ends, and the exit it means; the line before it says when the run
# went over its budget.
finish() {
  local took
  stage=ended
  prune
  case "$1" in
    "deploy: rollback incomplete:"*) ;;
    *) rm -f -- "$state/running" ;;
  esac
  took="$(since "$began")"
  if awk -v a="$took" -v b="$budget" 'BEGIN { exit !(a > b) }'; then
    case "$1" in
      "deploy: held"*) printf 'deploy: over its %s s budget: it took %s s; held, not rolled back\n' "$budget" "$took" ;;
      *) printf 'deploy: over its %s s budget: it took %s s; it ended %s\n' "$budget" "$took" "$(sed 's/^deploy: \([a-z ]*\):.*/\1/' <<<"$1")" ;;
    esac
  fi
  printf '%s\n' "$1"
  exit 0
}
refuse() {
  if [[ -n "$run" ]]; then finish "deploy: refused: $*; nothing was changed"; fi
  echo "deploy: refused: $*; nothing was changed"
  exit 1
}
log() { [[ -z "$run" && "$health_only" == 0 ]] || printf 'deploy: %s\n' "$*"; }
prune() {
  [[ -n "$run" && -d "$state/runs" ]] || return 0
  local d
  while IFS= read -r d; do
    [[ -n "$d" ]] && rm -rf -- "${state:?}/runs/$d"
  done < <(ls -1 "$state/runs" | sort | head -n -3)
}

# Every Postgres call is bounded, so a hung Docker cannot hold serve down: a statement or a data
# file 300 s, a dump or a restore 900 s.
pg() { timeout 300 docker compose --project-directory "$compose" exec -T postgres "$@"; }
pg_long() { timeout 900 docker compose --project-directory "$compose" exec -T postgres "$@"; }
psql_db() { pg psql -U grit -d "$db" -q -v ON_ERROR_STOP=1 "$@"; }
sql() { psql_db -At -c "$1"; }
sha() { sha256sum <"$1" | cut -d' ' -f1; }
# Seconds, to a tenth.
now() { local t="$EPOCHREALTIME"; echo "${t/,/.}"; }
since() { awk -v a="$1" -v b="${2:-$(now)}" 'BEGIN { printf "%.1f", b - a }'; }
# The SHA-256 of a credential as serve takes it: its text, newlines removed.
digest() { tr -d '\n' <"$1" | sha256sum | cut -d' ' -f1; }
# curl to serve bearing the credential in the file $1, which is never on a command line.
cred_curl() {
  local f="$1"
  shift
  curl -q -s --noproxy '*' --max-time 15 -H @<(printf 'Authorization: Bearer %s\n' "$(<"$f")") "$@"
}
# Whether serve answers: nothing listening is no answer at once, as a host may drop a connection to
# a port nothing listens on rather than refuse it, leaving curl to wait out its bound (the machine
# kalandra runs on does, which cost 5 s a stop). Without ss, curl alone decides.
listens() { ! command -v ss >/dev/null || ss -Hltn "sport = :${listen##*:}" 2>/dev/null | grep -q .; }
answers() { listens && curl -q -s --noproxy '*' --max-time 5 -o /dev/null -X POST -d '{}' "$edge/command/project_list" 2>/dev/null; }
# The body of serve's answer in `body`, its status in `code`; curl's arguments are $@.
asked() {
  body="$("$@" -w '\n%{http_code}')"
  code="${body##*$'\n'}"
  body="${body%$'\n'*}"
}
# The health check's brief, asked of serve through the credential in the file $1: true when it
# answers 200 with the brief, headed by its key (before ids) or by its id and handle, `grit/<n>
# roadmap-coder-rules`; its answer in `code` and `body` either way.
brief_asked() {
  asked cred_curl "$1" -X POST -H 'Content-Type: application/json' \
    --data-binary "{\"key\":\"$health_brief_key\",\"role\":\"$health_brief_role\",\"chars\":100000}" "$edge/command/project_brief"
  local head
  head="$(head -n 1 <<<"$body")"
  [[ "$code" == 200 && ( "$head" == "$health_brief_key | "* ||
    "$head" =~ ^${health_brief_key%%/*}/[0-9]+\ ${health_brief_key#*/}\ \|\  ) ]]
}
# What the health check asks serve, by commands that only read, so it writes nothing; `why` says
# the first answer that is not what it should be.
health_asks() {
  local who f code body
  for who in "the owner's" "the sessions'"; do
    if [[ "$who" == "the owner's" ]]; then f="$owner_cred"; else f="$sessions_cred"; fi
    [[ -s "$f" ]] || { why="there is no credential in $f"; return 1; }
    asked cred_curl "$f" -o /dev/null -X POST -H 'Content-Type: application/json' --data-binary "$health_ask" "$edge/command/project_list"
    [[ "$code" == 200 ]] || { why="project_list with $who credential answered $code"; return 1; }
  done
  brief_asked "$sessions_cred" ||
    { why="a brief of $health_brief_key as $health_brief_role answered $code: $(head -n 1 <<<"$body" | head -c 200)"; return 1; }
  asked curl -q -s --noproxy '*' --max-time 15 -X POST -d '{}' "$edge/command/project_list"
  [[ "$code" == 401 && "$body" == "$no_credential" ]] ||
    { why="a request with no credential answered $code $(head -c 200 <<<"$body"), not 401 $no_credential"; return 1; }
  log "health: project_list of grit answers 200 to the owner's and the sessions' credentials"
  log "health: a brief of $health_brief_key as $health_brief_role answers 200 to the sessions' credential"
  log "health: with no credential serve answers 401: $no_credential"
}
unit_file="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/$unit"
# Whether the unit owns serve, as bin/kalandra decides it, asked of systemd now; `managed` is the
# answer preflight got, which the whole run keeps.
managed_now() { [[ "$listen" == 127.0.0.1:7317 ]] && timeout 30 systemctl --user is-enabled --quiet "$unit" 2>/dev/null; }
is_managed=0
managed() { ((is_managed == 1)); }
same_manager() {
  local n=0
  if managed_now; then n=1; fi
  ((n == is_managed))
}
manager() { if managed; then echo "the unit $unit (systemctl --user)"; else echo "its pids (bin/kalandra)"; fi; }
# bin/kalandra $2… under a wall-clock bound of $1 seconds, the lock's descriptor closed.
kalandra() {
  local bound="$1"
  shift
  KALANDRA_LISTEN="$listen" timeout "$bound" "$checkout/bin/kalandra" "$@" 9>&-
}

# ---- What is checked before anything changes, by the command and again by the run.
checksum() {
  local s n
  [[ -f "$proof/$1" && -f "$proof/$1.sha256" ]] || refuse "$proof does not hold $1 and $1.sha256"
  read -r s n <"$proof/$1.sha256" || true
  [[ "$n" == "$1" && "$(sha "$proof/$1")" == "$s" ]] || refuse "$proof/$1 does not match $1.sha256"
}

# The health check's brief, asked of the serve running before anything changes, since a store
# reason to refuse it (the artifact unknown, the role missing, the brief over its size) would
# refuse the new serve's health check too, rolling back a deploy that is healthy. Asked through
# the sessions' credential, or the owner's when the sessions' is not yet configured, as only a
# credential whose digest serve was started with is known to it; with neither, not asked. Two
# answers are no verdict on the brief, and the health check alone asks it, of the new serve: a
# serve too old to have project_brief answers 404, `no command is named …` (or `no route …`), and
# one that does not take the credential 401. Every other answer but 200 refuses: the command's own
# refusal is 409 (a 400 for arguments it does not read), as grit's commands edge answers them, and
# the rest say serve cannot answer it now.
brief_before() {
  local f code body
  if [[ "${plan[SESSIONS]}" == keep ]]; then f="$sessions_cred"
  elif [[ "${plan[OWNER]}" == keep ]]; then f="$owner_cred"
  else
    log "preflight: the brief is not asked before: serve is configured with neither credential yet"
    return 0
  fi
  if brief_asked "$f"; then
    log "preflight: a brief of $health_brief_key as $health_brief_role answers 200 to the running serve"
    return 0
  fi
  case "$code" in
    404) if [[ "$body" == "no command is named "* || "$body" == "no route "* ]]; then
        log "preflight: the running serve answers no project_brief (404), so the brief is asked of the new serve alone"
        return 0
      fi ;;
    401) log "preflight: the running serve does not take $f (401), so the brief is asked of the new serve alone"; return 0 ;;
  esac
  refuse "the running serve answers a brief of $health_brief_key as $health_brief_role $code: $(head -n 1 <<<"$body" | head -c 200); the health check asks the same of the new serve"
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
  local took proof_budget
  took="$(sed -n 's/^time  edge-proof took \([0-9]\{1,\}\(\.[0-9]\)\{0,1\}\) s.*$/\1/p' "$p" | tail -n 1)"
  [[ -n "$took" ]] || refuse "proof.txt says not how long the proof took; run ops/edge-proof.sh again"
  proof_budget="$(git -C "$checkout" show "$X:ops/edge-proof.sh" 2>/dev/null | sed -n 's/^proof_budget=\([0-9]\{1,\}\)$/\1/p' | head -n 1)"
  [[ -n "$proof_budget" ]] || refuse "ops/edge-proof.sh at the proved commit ${X:0:12} names no proof_budget"
  ! awk -v a="$took" -v b="$proof_budget" 'BEGIN { exit !(a > b) }' ||
    refuse "the proof took $took s, over its $proof_budget s budget; prove again, cutting what made it slow"

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
  is_managed=0
  if [[ "$listen" == 127.0.0.1:7317 && ( -e "$unit_file" || -L "$unit_file" ) ]]; then
    timeout 30 systemctl --user show-environment >/dev/null 2>&1 ||
      refuse "$unit is installed for $listen, but systemctl --user cannot be reached (is XDG_RUNTIME_DIR set?); a stop by pid would be undone by its Restart=always"
    if managed_now; then
      is_managed=1
    elif timeout 30 systemctl --user is-active --quiet "$unit"; then
      refuse "$unit runs but is not enabled, so bin/kalandra would stop serve by pid, which its Restart=always undoes"
    fi
  fi
  if managed; then
    url="$(timeout 30 systemctl --user show -p Environment --value "$unit" | tr ' ' '\n' | sed -n 's/^GRIT_DATABASE_URL=//p')"
    [[ -z "${GRIT_DATABASE_URL:-}" || "$GRIT_DATABASE_URL" == "$url" ]] ||
      refuse "the unit's database $url is not GRIT_DATABASE_URL's $GRIT_DATABASE_URL"
    ! timeout 30 systemctl --user is-active --quiet kalandra-backup.service || refuse "the nightly backup is running; deploy after it"
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
  brief_before

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

# ---- --health: the health check's asks alone, of the serve running now.
if ((health_only)); then
  why=""
  health_asks || { echo "deploy: health: $why"; exit 1; }
  exit 0
fi

# ---- The command: refusals, then the run, detached, followed to its end unless --detach.
if [[ -z "$run" ]]; then
  # Each named as given when it is refused, since a failed resolve leaves nothing.
  given="$proof"; proof="$(cd "$proof" 2>/dev/null && pwd -P)" || refuse "no proof directory $given"
  given="$checkout"; checkout="$(cd "$checkout" 2>/dev/null && pwd -P)" || refuse "no checkout $given"
  given="$grit"; grit="$(cd "$grit" 2>/dev/null && pwd -P)" || refuse "no grit checkout $given"
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
  ( cd "$run" && setsid nohup bash "$run/deploy.sh" --run "$run" --began "$began" --checkout "$checkout" --grit "$grit" "$proof" \
      </dev/null >>"$log_file" 2>&1 & )
  for _ in $(seq 50); do [[ -s "$run/pid" ]] && break; sleep 0.2; done
  rpid="$(cat "$run/pid" 2>/dev/null)"
  echo "deploy: kalandra ${X:0:12} with grit $gv at ${gc:0:12}, jar ${jar_sha:0:12}, from $proof"
  echo "deploy: log $log_file"
  echo "deploy: running as pid ${rpid:-?}; it goes on if this terminal or session ends"
  ((detach == 0)) || exit 0
  exec 9>&-
  # The run's last line, and the budget's before it when it went over, once the run has ended.
  ended() {
    local last over rc
    last="$(tail -n 1 "$log_file" 2>/dev/null)"
    case "$last" in
      "deploy: held"*) rc=0 ;;
      "deploy: refused:"*) rc=1 ;;
      "deploy: rolled back:"*) rc=2 ;;
      "deploy: rollback incomplete:"*) rc=3 ;;
      *) return 0 ;;
    esac
    over="$(tail -n 2 "$log_file" | head -n 1)"
    if [[ "$over" == "deploy: over its "* ]]; then
      echo "$over"
      ((rc != 0)) || rc=4
    fi
    echo "$last"
    exit "$rc"
  }
  while :; do
    ended
    if [[ -z "$rpid" ]] || ! kill -0 "$rpid" 2>/dev/null; then
      ended
      echo "deploy: the run (pid ${rpid:-?}) ended without a last line; read $log_file"
      exit 3
    fi
    sleep 0.5
  done
fi

# ---- The run.
cd "$run" || exit 1
echo "$$" >"$run/pid"
# The command's start, and when serve stopped answering and answered again.
t0="$began" down_at="" up_at=""
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
log "serve at $listen on $db, stopped and started by $(manager)"
if ((${#joined[@]} == 0)); then log "join: no checkout of $grit is joined to $edge"; else log "join: joined to $edge: ${joined[*]}"; fi

stopped=0 started=0 code_switched=0 jar_switched=0 made_creds=0
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
  made_creds=1
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
  if managed && timeout 30 systemctl --user is-active --quiet kalandra-backup.service; then
    why="the nightly backup started; deploy after it"
    return 1
  fi
  same_manager || { why="systemctl --user's answer changed since the deploy began, so serve was not stopped"; return 1; }
  stopped=1
  down_at="$(now)"
  kalandra 120 stop || { why="bin/kalandra stop failed or took over 120 s"; return 1; }
  ! answers || { why="serve still answers at $listen after the stop"; return 1; }
  log "stop: serve stopped by $(manager)"
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
  pg_long pg_dump -U grit -Fc "$db" >"$run/before.dump" 2>"$run/dump.err" &&
    pg_long pg_restore --list <"$run/before.dump" >/dev/null 2>>"$run/dump.err" ||
    { why="the dump failed: $(tail -n 1 "$run/dump.err" | head -c 200)"; return 1; }
  log "dump: $db to $run/before.dump, $(du -h "$run/before.dump" | cut -f1)"
}

# Runs the data file $1 (any more arguments go to psql); sets `rows`, its counts as `name|n` lines,
# and `err`, what went wrong. Returns 1 when the server reported an ERROR, so the file's
# transaction did not commit; 3 when psql failed otherwise (Docker, the stream, the bound), so
# whether a COMMIT after it was kept is not known; 2 when it ran but printed something other than
# its counts.
data_run() {
  local out rc=0
  out="$(psql_db -At -F'|' -f - "${@:2}" <"$1" 2>"$run/psql.err")" || rc=$?
  err="$(grep -m 1 'ERROR:' "$run/psql.err" || tail -n 1 "$run/psql.err")"
  rows="$(grep -E '^[^|]+\|[0-9]+$' <<<"$out")"
  if ((rc != 0)); then
    grep -q 'ERROR:' "$run/psql.err" && return 1
    err="psql failed with no server error (exit $rc${err:+: $err})"
    return 3
  fi
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
    data_run "$proof/$f" || { why="$f's dry run failed: $err"; return 1; }
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
    # Counted as kept before it runs, so a rollback reverses it whatever this run did: its reverse
    # is idempotent (ops/data.tsv), so reversing a run that did not commit changes nothing.
    kept+=("$i")
    data_run "$proof/$f" -c COMMIT || rc=$?
    ((rc == 0)) || { why="$f did not run whole: $err"; return 1; }
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
  if managed; then timeout 30 systemctl --user daemon-reload || { why="systemctl daemon-reload failed"; return 1; }; fi
  log "code: $checkout at $X, detached; jar $jar_sha"
}

# The bound on a start: bin/kalandra start, and serve answering, within 130 s of wall clock.
start_serve() { kalandra 130 start || answers; }

stage_start() {
  same_manager || incomplete "systemctl --user's answer changed since the deploy began, so serve was not started" \
    "start serve (bin/kalandra start in $checkout) once systemctl --user answers, and read this log"
  serve_log_size="$(stat -c %s "$checkout/logs/serve.log" 2>/dev/null || echo 0)"
  started=1
  start_serve || { why="serve did not answer within 130 s"; return 1; }
  up_at="$(now)"
  log "start: serve started by $(manager), answering"
}

# Each method version the proved commit holds (methods/<name>@<version>.json), declared through
# the command line's credential, oldest first: a version declared already under its digest changes
# nothing, so only a new one is kept; one declared under another digest, or refused for any other
# reason, fails the deploy. The old serve reads every declared version, and refuses one it cannot
# read, so once a declaration may have kept a new version (counted so before it is sent, as a data
# file is, since one that times out may have committed), the rollback restores the dump, as only
# it undoes one. A rollback of a deploy that adds a version so loses what the new serve kept in the
# seconds it served, as after a re-key.
methods_new=0
stage_methods() {
  local f code body said=() declared=0
  for f in $(cd "$checkout/methods" 2>/dev/null && ls -- *.json | sort -t@ -k1,1 -k2,2n); do
    methods_new=1
    code="$(jq -nc --rawfile m "$checkout/methods/$f" '{method: $m}' |
      cred_curl "$owner_cred" -o "$run/declare.out" -w '%{http_code}' -X POST \
        -H 'Content-Type: application/json' --data-binary @- "$edge/command/project_declare")"
    body="$(head -c 300 "$run/declare.out")"
    [[ "$code" == 200 ]] || { why="declaring $f answered $code: $body"; return 1; }
    if [[ "$body" == *"declared already, under the same digest"* ]]; then
      said+=("${f%.json} kept")
      methods_new="$declared"
    else
      said+=("${f%.json} declared")
      declared=1
    fi
  done
  log "methods: ${said[*]:-none in $checkout/methods}"
}

# The join script $1 run in the checkout $2 with the arguments after, under a bound of 120 s,
# which covers its `claude mcp` calls; its output in `out`, its exit in `rc`.
join_in() {
  local script="$1" w="$2"
  shift 2
  rc=0
  out="$(cd "$w" && timeout 120 bash -s -- "$@" <"$script" 2>&1 9>&-)" || rc=$?
  sed 's/^/deploy: join  /' <<<"$out"
}

stage_join() {
  local w out rc
  for w in "${joined[@]}"; do
    joined_new+=("$w")
    join_in "$proof/join.sh" "$w" "$edge" --credential "$sessions_cred" --mcp-name "$mcp"
    ((rc == 0)) || { why="the join in $w exited $rc: $(tail -n 1 <<<"$out")"; return 1; }
  done
}

# project_rekey through the command line's credential, the JSON $1 its arguments; its answer in
# `code` and `body`. Bounded at 120 s, as one re-key of every artifact of a project is one
# transaction.
rekey_ask() {
  asked cred_curl "$owner_cred" --max-time 120 -X POST -H 'Content-Type: application/json' \
    --data-binary "$1" "$edge/command/project_rekey"
}
# What a survey line of a project left to re-key is; any other line is why one cannot be.
rekey_line='^[a-z0-9][a-z0-9-]*: [0-9]+ artifacts, [0-9]+ body links, [0-9]+ head cites, [0-9]+ handles carried$'
rekey_none="No project is left to re-key."
rekey_sent=0

# Every project the store holds in the old form (ADR 0063), re-keyed by the new serve, as a
# pending data file is run: a survey (project_rekey with dry and no project), whose lines go to
# the log; a refusal it reports fails the stage before any re-key is sent; then each project's
# re-key, one transaction each; then a survey that must list none. Once a re-key is sent, the
# rollback restores the dump, as only it undoes one.
stage_rekey() {
  local p line bad s0
  rekey_ask '{"dry":true}'
  [[ "$code" == 200 ]] || { why="project_rekey's survey answered $code: $(head -n 1 <<<"$body" | head -c 200)"; return 1; }
  if [[ "$body" == "$rekey_none" ]]; then log "rekey: nothing to re-key"; return 0; fi
  while IFS= read -r line; do log "rekey dry  $line"; done <<<"$body"
  bad="$(grep -vE "$rekey_line" <<<"$body")"
  [[ -z "$bad" ]] || { why="project_rekey's survey says a project cannot be re-keyed: $(head -n 1 <<<"$bad" | head -c 300)"; return 1; }
  for p in $(sed 's/:.*//' <<<"$body"); do
    rekey_sent=1
    s0="$(now)"
    rekey_ask "{\"project\":\"$p\"}"
    [[ "$code" == 200 ]] || { why="project_rekey of $p answered $code: $(head -n 1 <<<"$body" | head -c 300)"; return 1; }
    while IFS= read -r line; do log "rekey  $line"; done <<<"$body"
    log "rekey: $p took $(since "$s0") s"
  done
  rekey_ask '{"dry":true}'
  [[ "$code" == 200 && "$body" == "$rekey_none" ]] ||
    { why="after the re-key, project_rekey's survey answered $code: $(head -n 1 <<<"$body" | head -c 200)"; return 1; }
  log "rekey: a second survey lists none"
}

stage_health() {
  local line code
  line="$(tail -c +"$((serve_log_size + 1))" "$checkout/logs/serve.log" | grep -a '^\[kalandra\] grit ' | tail -n 1)"
  [[ "$line" == "[kalandra] grit $gv at $gc" ]] ||
    { why="serve's startup line names grit ${line#\[kalandra\] grit }, not $gv at ${gc:0:12}"; [[ -n "$line" ]] || why="serve logged no startup line naming its grit"; return 1; }
  log "health: serve says $line"
  health_asks || return 1
  code="$(cred_curl "$sessions_cred" -o "$run/join.served" -w '%{http_code}' "$edge/join/claude-code")"
  [[ "$code" == 200 && "$(sha "$run/join.served")" == "$(sha "$proof/join.sh")" ]] ||
    { why="/join/claude-code served a script that is not join.sh"; return 1; }
  log "health: /join/claude-code serves join.sh"
}

incomplete() {
  stage=ended
  finish "deploy: rollback incomplete: $1; a person must $2; $(timing)"
}

# The names of the data files kept, as a person reads them.
kept_names() {
  local i names=()
  for i in "${kept[@]}"; do names+=("${fwd[i]}"); done
  local IFS=,
  echo "${names[*]}" | sed 's/,/, /g'
}

# A rollback that cannot put the database right: serve is left stopped, with the old code and jar
# in place (the rollback puts them back before it touches the data), and the unit, when it owns
# serve, disabled, so that nothing starts any jar on a database not known to be good.
bad_db() {
  local held="serve is stopped, with the old code and jar in place" start="bin/kalandra start in $checkout"
  if managed; then
    if timeout 30 systemctl --user disable --quiet "$unit" 2>/dev/null; then
      held+=", and $unit is disabled, so nothing starts it"
      start="systemctl --user enable --now $unit"
    else
      held+=", but $unit could not be disabled, so the next boot starts it"
    fi
  fi
  incomplete "$1; $2; $held" "put $db right (the dump from before the deploy is $run/before.dump), then start serve: $start"
}

# The rollback's last resort, when a reverse raised: the dump restored into ${db}_restoring, and
# only once that has succeeded, $db renamed aside and the restored one renamed into its place.
restore_dump() {
  local tmp="${db}_restoring" aside="${db}_rolled_back_$(date -u +%Y%m%d%H%M%S)" untouched there names e
  untouched="$db is untouched, as the deploy left it, with $(kept_names) kept and not reversed"
  log "rollback: $1; restoring the dump from before the deploy into $tmp"
  if ! pg psql -U grit -d postgres -q -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $tmp" -c "CREATE DATABASE $tmp" 2>"$run/restore.err"; then
    e="$(grep -m 1 'ERROR:' "$run/restore.err" || tail -n 1 "$run/restore.err")"
    there="$(pg psql -U grit -d postgres -At -c "SELECT count(*) FROM pg_database WHERE datname = '$tmp'" 2>/dev/null)"
    if [[ "$there" == 1 ]]; then there="$tmp is left as it was, not the deploy's"; else there="no $tmp was made"; fi
    bad_db "$1, and making $tmp for the restore failed: $e" "$untouched; $there"
  fi
  if ! pg_long pg_restore -U grit -d "$tmp" --no-owner --exit-on-error <"$run/before.dump" 2>"$run/restore.err"; then
    e="$(grep -m 1 'error:' "$run/restore.err" || tail -n 1 "$run/restore.err")"
    if pg psql -U grit -d postgres -q -c "DROP DATABASE IF EXISTS $tmp" 2>/dev/null; then there="$tmp removed"; else there="$tmp left, partly restored"; fi
    bad_db "$1, and restoring the dump into $tmp failed: $e" "$untouched; $there"
  fi
  if ! pg psql -U grit -d postgres -q -v ON_ERROR_STOP=1 -c "ALTER DATABASE $db RENAME TO $aside" -c "ALTER DATABASE $tmp RENAME TO $db" 2>"$run/restore.err"; then
    e="$(grep -m 1 'ERROR:' "$run/restore.err" || tail -n 1 "$run/restore.err")"
    names="$(pg psql -U grit -d postgres -At -c "SELECT string_agg(datname, ', ' ORDER BY datname) FROM pg_database WHERE datname IN ('$db', '$aside', '$tmp')" 2>/dev/null)"
    bad_db "$1, the dump restored into $tmp, and renaming it into place failed: $e" "the databases named now are: ${names:-not known}"
  fi
  log "rollback: $db restored from the dump; the database as the deploy left it is $aside"
  left+=("$db restored from the dump taken before the deploy (the database as the deploy left it is kept as $aside, for a person to drop once satisfied)")
}

rollback() {
  local left=() i r w rc out
  stage=rollback
  log "rollback: $1"
  if ((started)); then
    same_manager || incomplete "systemctl --user's answer changed since the deploy began, so the new serve was not stopped" \
      "stop serve once systemctl --user answers, then read this log"
    kalandra 120 stop || incomplete "stopping the new serve failed or took over 120 s" "stop serve (bin/kalandra stop in $checkout), then read this log"
    log "rollback: the new serve stopped by $(manager)"
  fi
  # The old code and jar first, so that whatever becomes of the data, the new jar is not left to
  # start on it.
  if ((code_switched)); then
    if [[ -n "$B" ]]; then git -C "$checkout" switch -q "$B"; else git -C "$checkout" switch -q --detach "$H"; fi ||
      incomplete "git could not check out ${B:-$H} in $checkout" "check out ${B:-$H} there and put $run/before/out.jar back as out/assembly.dest/out.jar"
  fi
  if ((jar_switched)); then
    put_jar "$run/before/out.jar" "$old_jar_sha" ||
      incomplete "the old jar did not go back" "copy $run/before/out.jar to $checkout/out/assembly.dest/out.jar"
  fi
  if ((code_switched)) && managed; then timeout 30 systemctl --user daemon-reload || true; fi
  # A re-key, or a method version the old serve may not read, is undone only by the dump, which was
  # taken before the data files ran, so it undoes them too.
  local dumped=0
  if ((rekey_sent == 1)); then
    restore_dump "project_rekey was sent, which only the dump undoes"; dumped=1
  elif ((methods_new == 1)); then
    restore_dump "a method version was declared, which only the dump undoes"; dumped=1
  fi
  for ((i = ${#kept[@]} - 1; i >= 0 && dumped == 0; i--)); do
    r="${rev[${kept[i]}]}"
    rc=0
    data_run "$proof/$r" -c COMMIT || rc=$?
    case "$rc" in
      0)
        data_run "$proof/$r" && [[ -z "$(nonzero "$rows")" ]] ||
          bad_db "$r, run to reverse ${fwd[${kept[i]}]}, counts other than 0 on a second run" "$db is as the reverse left it"
        log "rollback: $r kept"
        left+=("${fwd[${kept[i]}]} reversed")
        ;;
      1)
        restore_dump "$r raised: $err"
        break
        ;;
      *)
        bad_db "$r, run to reverse ${fwd[${kept[i]}]}, did not run: $err" \
          "$db is as the deploy left it, with $(kept_names) kept and perhaps not reversed"
        ;;
    esac
  done
  if ((stopped)) && ! answers; then
    up_at=""
    start_serve || incomplete "the old serve did not answer within 130 s" "start serve (bin/kalandra start in $checkout) and read this log"
    up_at="$(now)"
    log "rollback: the old serve started by $(manager), answering"
  fi
  for i in "${!joined_new[@]}"; do
    w="${joined_new[i]}"
    local args=("$edge")
    [[ -z "${old_cred[i]}" ]] || args+=(--credential "${old_cred[i]}")
    join_in "$run/before/join.sh" "$w" "${args[@]}" --mcp-name "$mcp"
    ((rc == 0)) || incomplete "joining $w again as before exited $rc: $(tail -n 1 <<<"$out")" \
      "join it again: cd $w && bash -s -- ${args[*]} --mcp-name $mcp < $run/before/join.sh"
  done
  ((${#joined_new[@]} == 0)) || left+=("joins as before in ${#joined_new[@]} checkouts")
  local said
  said="kalandra ${H:0:12}, jar ${old_jar_sha:0:12}"
  for r in "${left[@]}"; do said+=", $r"; done
  if ((stopped)); then said+=", serve answering on $listen"; fi
  # The old serve, started again from $env_file, takes them; a serve older than credentials
  # ignores them.
  if ((made_creds)); then said+="; the credentials it made and the digest lines it added to $env_file stay, for the next deploy"; fi
  finish "deploy: rolled back: $1; left as it began: $said; $(timing)"
}

for s in credentials backup stop connections dump data code start methods join rekey health; do
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
