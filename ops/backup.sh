#!/usr/bin/env bash
# A dump of grit_kalandra (pg_dump -Fc), checked readable, kept as today's daily under
# ${KALANDRA_BACKUP_DIR:-$HOME/backups/grit_kalandra}/daily, and as the month's under monthly/ when
# it is the month's first. The newest 14 dailies and 6 monthlies are kept; older ones are deleted.
# Run nightly by ops/systemd/kalandra-backup.timer. Prints the dump's path.
#
# Postgres is the grit checkout's compose service (GRIT_COMPOSE_DIR, default
# $HOME/Projects/grit), whose pg_dump matches the server's version. A dump that fails or does
# not list leaves nothing behind and exits non-zero; no older dump is deleted then.
set -euo pipefail

db=grit_kalandra
dailies=14
monthlies=6
dir="${KALANDRA_BACKUP_DIR:-$HOME/backups/grit_kalandra}"
compose="${GRIT_COMPOSE_DIR:-$HOME/Projects/grit}"

pg() { docker compose --project-directory "$compose" exec -T postgres "$@"; }

mkdir -p "$dir/daily" "$dir/monthly"
day=$(date -u +%Y-%m-%d)
month=$(date -u +%Y-%m)
daily="$dir/daily/$db-$day.dump"
partial="$dir/daily/.$db-$day.dump.partial"
trap 'rm -f "$partial"' EXIT

pg pg_dump -U grit -Fc "$db" >"$partial"
pg pg_restore --list <"$partial" >/dev/null
mv -f "$partial" "$daily"

monthly="$dir/monthly/$db-$month.dump"
[[ -e "$monthly" ]] || ln "$daily" "$monthly" 2>/dev/null || cp "$daily" "$monthly"

# The names sort by date; keep the newest of each.
prune() {
  local keep=$1
  shift
  if (($# > keep)); then rm -f -- "${@:1:$# - keep}"; fi
}
shopt -s nullglob
prune "$dailies" "$dir"/daily/"$db"-*.dump
prune "$monthlies" "$dir"/monthly/"$db"-*.dump

echo "$daily"
