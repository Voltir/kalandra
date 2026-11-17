#!/usr/bin/env bash
# Whether a backup restores: declares each method version in methods/ and keeps two artifacts of
# the project `restore-check` in grit_kalandra through bin/kalandra, dumps it with ops/backup.sh, restores that dump into
# grit_kalandra_restore, and compares each project's export (ADR 0049's one function pair)
# from the two databases, file by file; then drops grit_kalandra_restore.
#
#   ops/restore-test.sh [project ...]     the projects compared besides restore-check
#
# A serve already answering KALANDRA_LISTEN (default 127.0.0.1:7317) is used for
# grit_kalandra; otherwise one is started and stopped. The restored database is served on
# KALANDRA_RESTORE_LISTEN (default 127.0.0.1:7318). Exits 0 only when every export matches.
# Every serve it starts is stopped by its PID, and the restored database dropped, however it ends.
# Declaring asks a caller believed at authority: bin/kalandra's credential is the command line's,
# the file KALANDRA_CREDENTIAL names or ~/.config/kalandra/credential, which serve must know.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
kalandra="$root/bin/kalandra"
compose="${GRIT_COMPOSE_DIR:-$HOME/Projects/grit}"
live_listen="${KALANDRA_LISTEN:-127.0.0.1:7317}"
restore_listen="${KALANDRA_RESTORE_LISTEN:-127.0.0.1:7318}"
restored=grit_kalandra_restore
projects=(restore-check "$@")
work="$(mktemp -d)"
started=()

pg() { docker compose --project-directory "$compose" exec -T postgres "$@"; }

cleanup() {
  for pid in "${started[@]}"; do
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  done
  pg psql -U grit -q -c "DROP DATABASE IF EXISTS $restored WITH (FORCE)" >/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT

up() { curl -s -o /dev/null -X POST "http://$1/command/project_list" -d '{}'; }

# serve <database> <listen>: started in the background; its PID added to `started`. serve writes
# no files, and only sessions joined to its address post to it, so the restored database's serve
# records nothing of the live sessions.
serve() {
  local log="$work/serve-$1.log"
  env GRIT_DATABASE_URL="jdbc:postgresql://localhost:5432/$1" KALANDRA_LISTEN="$2" \
    "$kalandra" serve >"$log" 2>&1 &
  started+=("$!")
  for _ in $(seq 1 120); do
    grep -q "serving on" "$log" && return 0
    kill -0 "$!" 2>/dev/null || break
    sleep 0.5
  done
  echo "restore-test: serve on $1 did not start:" >&2
  tail -20 "$log" >&2
  return 1
}

if up "$live_listen"; then
  echo "using the serve at $live_listen"
else
  echo "serving grit_kalandra at $live_listen"
  serve grit_kalandra "$live_listen"
fi

# Kept the same each run, so a second run writes no new version: a method version declared already
# under its digest changes nothing. Methods first, as a project is read under its method's.
export KALANDRA_LISTEN="$live_listen"
for f in $(cd "$root/methods" && ls -- *.json | sort -t@ -k1,1 -k2,2n); do
  "$kalandra" declare "$root/methods/$f"
done
"$kalandra" new '{"project":"restore-check"}' || true
"$kalandra" keep '{"key":"restore-check/plan","kind":"milestone","title":"Restore check","body":"Kept by ops/restore-test.sh, to see a dump restore whole."}'
"$kalandra" keep '{"key":"restore-check/dump","kind":"stage","title":"Dump and restore","body":"## Goal\n\nThe export after a restore is the export before it. See [the plan](artifact:restore-check/plan).","parent":"restore-check/plan","cites":["restore-check/plan"]}'

dump="$("$root/ops/backup.sh")"
echo "dumped $dump"

pg psql -U grit -q -c "DROP DATABASE IF EXISTS $restored WITH (FORCE)" >/dev/null
pg psql -U grit -q -c "CREATE DATABASE $restored" >/dev/null
pg pg_restore -U grit -d "$restored" --no-owner --exit-on-error <"$dump"
echo "restored into $restored"

for p in "${projects[@]}"; do
  KALANDRA_LISTEN="$live_listen" "$kalandra" export "$p" "$work/live/$p" >/dev/null
done

echo "serving $restored at $restore_listen"
serve "$restored" "$restore_listen"
for p in "${projects[@]}"; do
  KALANDRA_LISTEN="$restore_listen" "$kalandra" export "$p" "$work/restored/$p" >/dev/null
done

if diff -r "$work/live" "$work/restored"; then
  for p in "${projects[@]}"; do
    echo "$p: $(find "$work/live/$p" -type f | wc -l) files, the same after the restore"
  done
  echo "restore-test: passed"
else
  echo "restore-test: the restored exports differ" >&2
  exit 1
fi
