# Claude Code hooks: what a test hook saw

What `hooks/brief-hook.sh` relies on, seen with a hook that logged its stdin and injected a
codeword naming its event, under Claude Code 2.1.303, headless (`claude -p --model haiku`, a custom
system prompt, one custom agent), 2026-10-27. The model was asked which codewords its context named.

## SubagentStart

- **`hookSpecificOutput.additionalContext` is applied to the subagent**, not to its parent: the
  subagent named its own codeword, and the parent's answer named only its SessionStart one.
- **Payload:** `session_id` (the parent's), `prompt_id` (the parent's prompt), `agent_id`,
  `agent_type` (the agent's name as defined, e.g. `probe`), `cwd`, `transcript_path`,
  `hook_event_name`. **No prompt:** the subagent's task text is not in the payload, so a hook
  cannot pick a brief by what the subagent was asked; it picks by `agent_type` and `cwd` alone.
- `cwd` is the parent session's working directory.
- A subagent that runs in the background reports back through a `<task-notification>` that the
  parent receives as a user prompt: **UserPromptSubmit fires for it**, with that text as `prompt`.

## SessionStart

Every source carries `session_id`, `cwd`, `transcript_path`, `hook_event_name` and `source`; the
`additionalContext` of each was in the model's context afterwards.

| `source` | How it was reached | Also carries |
|---|---|---|
| `startup` | a new `claude -p` | nothing more |
| `resume` | `-p --resume <id>` | `seconds_since_last_response`, `context_tokens`, `prompt_cache_likely_expired`, `estimated_cache_write_usd` |
| `compact` | `-p --resume <id> "/compact"` | `prompt_id`, `model` |
| `fork` | `-p --resume <id> --fork-session` (new `session_id`) | as `resume` |
| `clear` | `-p --resume <id> "/clear"` (new `session_id`; `resume` fired first, under the old id) | nothing more |

- **After `/compact` the injected text is in context again**: a hook with no matcher re-injects.
  What an earlier SessionStart injected before the compaction is gone unless the summary kept it.
- **Each resume injects again**: the model named the `resume` and `compact` codewords side by side,
  so a session resumed n times without compacting holds n + 1 copies of a brief.
- `model` appeared only on `compact`, not on `startup` in print mode.

## UserPromptSubmit

- `prompt` is the user's text (the docs did not say; seen here).

## The hook and the MCP server together

A sixth run, against a serve of a throwaway database on another port, with `hooks/brief-hook.sh`
as both hooks and the edge's `/mcp` given by `--mcp-config`: the session named the artifact of the
brief claimed for its directory's slot, called `mcp__workshop__project_show` on it and quoted it,
and a subagent named the one claimed for `probe/<sha1>`.

## Cost of the spike

| Run | What | Cost |
|---|---|---|
| 1 | startup, one subagent | 0.157¢ |
| 2 | resume, `/compact` | 0.251¢ |
| 3 | resume, ask what survived | 0.360¢ |
| 4 | fork | 0.400¢ |
| 5 | resume, `/clear` | 0¢ |
| 6 | the hook and `/mcp` end to end | 0.240¢ |

1.41¢ in all, from each run's `total_cost_usd`.
