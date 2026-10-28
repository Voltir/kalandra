#!/usr/bin/env bash
# The cutover report for grit's planning tree in the project `grit` (S7's criteria (a)-(c)):
#
#   (a) the round trip on real data: `kalandra verify` (the stored project at the round-trip
#       law's normal form, and every source line in exactly one artifact, its text in that
#       artifact's body under normalise), after an import that must write nothing;
#   (b) the daily reads, each timed beside its markdown equivalent (the median of 5 runs):
#       Now and Next by project_list by state, a plan's stage by project_show, and backlog
#       search by project_find;
#   (c) the restore test, on the imported data: ops/restore-test.sh grit.
#
#   ops/cutover-report.sh [grit checkout]     default $HOME/Projects/grit
#
# The checkout is only read. A serve already answering KALANDRA_LISTEN (default
# 127.0.0.1:7317) is used; otherwise one is started on grit_kalandra and stopped by its PID.
# Exits 0 only when the import wrote nothing, verify found no problem and the restore passed.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
kalandra="$root/bin/kalandra"
grit="${1:-$HOME/Projects/grit}"
listen="${KALANDRA_LISTEN:-127.0.0.1:7317}"
export KALANDRA_LISTEN="$listen"
work="$(mktemp -d)"
started=()

cleanup() {
  for pid in "${started[@]}"; do
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  done
  rm -rf "$work"
}
trap cleanup EXIT

if curl -s -o /dev/null -X POST "http://$listen/command/project_list" -d '{}'; then
  echo "using the serve at $listen"
else
  GRIT_DATABASE_URL="jdbc:postgresql://localhost:5432/grit_kalandra" \
    "$kalandra" serve >"$work/serve.log" 2>&1 &
  started+=("$!")
  for _ in $(seq 1 120); do
    grep -q "serving commands" "$work/serve.log" && break
    kill -0 "$!" 2>/dev/null || { tail -20 "$work/serve.log" >&2; exit 1; }
    sleep 0.5
  done
  echo "serving grit_kalandra at $listen"
fi

echo
echo "== the import, again: it must write nothing"
"$kalandra" import "$grit" | tee "$work/import.txt"
grep -q '^writes: 0 ' "$work/import.txt" || { echo "cutover-report: the import wrote" >&2; exit 1; }

echo
echo "== (a) the round trip on real data"
"$kalandra" verify "$grit"

# median <label> <command ...>: runs the command 5 times, its output to $work/out, and prints
# the median wall time in milliseconds.
median() {
  local ms=()
  for _ in 1 2 3 4 5; do
    local t0 t1
    t0=$(date +%s%N)
    "$@" >"$work/out" 2>&1
    t1=$(date +%s%N)
    ms+=($(((t1 - t0) / 1000000)))
  done
  printf '%s\n' "${ms[@]}" | sort -n | sed -n 3p
}

# read <label> <store command ...> -- <markdown command ...>
read_pair() {
  local label="$1"; shift
  local store=() markdown=()
  while [[ "$1" != "--" ]]; do store+=("$1"); shift; done
  shift
  markdown=("$@")
  local s m
  s=$(median "${store[@]}")
  cp "$work/out" "$work/store.out"
  m=$(median "${markdown[@]}")
  cp "$work/out" "$work/markdown.out"
  printf '%-22s store %4s ms, %3s lines   markdown %4s ms, %3s lines\n' \
    "$label" "$s" "$(wc -l <"$work/store.out")" "$m" "$(wc -l <"$work/markdown.out")"
  sed 's/^/    store: /' "$work/store.out" | head -6 | cut -c1-140
}

echo
echo "== (b) the daily reads, median of 5"
read_pair "Now" "$kalandra" list '{"project":"grit","state":"now"}' \
  -- sed -n '/^## Now/,/^## Parallel/p' "$grit/roadmap/ROADMAP.md"
read_pair "Next" "$kalandra" list '{"project":"grit","state":"next"}' \
  -- sed -n '/^## Next/,/^## Done/p' "$grit/roadmap/ROADMAP.md"
read_pair "a plan's stage (S7)" "$kalandra" show '{"key":"grit/plan-m17-s7-import-shadow-cutover"}' \
  -- sed -n '/^## S7\./,/^## S8\./p' "$grit/.local/plans/m17.md"
read_pair "backlog search" "$kalandra" find '{"query":"member joined channel","project":"grit"}' \
  -- grep -ril 'member_joined_channel' "$grit/.local/backlog"

echo
echo "== (c) the restore test on the imported data"
"$root/ops/restore-test.sh" grit

echo
echo "cutover-report: (a)-(c) passed"
