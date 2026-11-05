# The slot a claim's `holder` names, sourced by brief-hook.sh and spool-hook.sh so both compute it
# alike: the sha1 of a session's cwd for a session, `<agent_type>/<sha1 of cwd>/<session_id>` for a
# subagent, whose cwd and session_id are its session's (so two sessions in one directory give
# their subagents two slots, and a session resumed keeps its id and so its subagents' slots),
# normalised as the edge's BriefFiles.slot does: per character, A-Z lower-cased, a-z, 0-9, `-` and
# `/` kept, any other written `-`; then each part's leading `-` dropped. It needs jq and sha1sum.

# The slot for the directory $1; for the agent type $2 in the session $3 when a type is given,
# failing when the session is empty.
slot() {
  local cwd="$1" type="${2-}" session="${3-}" hash
  hash="$(printf '%s' "$cwd" | sha1sum)" || return 1
  hash="${hash%% *}"
  [[ -n "$type" ]] || { printf '%s\n' "$hash"; return 0; }
  [[ -n "$session" ]] || return 1
  jq -nr --arg s "$type/$hash/$session" \
    '$s | ascii_downcase | gsub("[^a-z0-9/-]"; "-") | gsub("(?<p>^|/)-+"; "\(.p)")'
}
