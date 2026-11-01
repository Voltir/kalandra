# The slot a claim's `holder` names, sourced by brief-hook.sh and spool-hook.sh so both compute it
# alike: the sha1 of a session's cwd for a session, `<agent_type>/<sha1 of cwd>` for a subagent
# (a subagent's cwd is its session's), normalised as the edge's BriefFiles.slot does: per
# character, A-Z lower-cased, a-z, 0-9, `-` and `/` kept, any other written `-`; then each part's
# leading `-` dropped. It needs jq and sha1sum.

# The slot for the directory $1, and for the agent type $2 when one is given.
slot() {
  local cwd="$1" type="${2-}" hash
  hash="$(printf '%s' "$cwd" | sha1sum)" || return 1
  hash="${hash%% *}"
  [[ -n "$type" ]] || { printf '%s\n' "$hash"; return 0; }
  jq -nr --arg s "$type/$hash" \
    '$s | ascii_downcase | gsub("[^a-z0-9/-]"; "-") | gsub("(?<p>^|/)-+"; "\(.p)")'
}
