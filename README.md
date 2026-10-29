# kalandra

A deployment of grit that keeps grit's own projects: a Scala module against grit's
`publishLocal` jars (`grit-kit` and `grit-projects`, `0.1.0-SNAPSHOT`), the way any deployment
outside grit builds. Package `kalandra`, outside `grit` on purpose, so the compiler treats it
as unseen deployment code: what grit keeps `private[grit]` is not visible here.

- **The projects plugin** (ADR 0049), under one method, `grit` (`kalandra.method`): grit's own
  process as data. Version 1 (milestones, stages, ideas, mechanisms) is kept because the project
  `restore-check` is pinned to its digest; version 2 has the kinds grit's planning tree imports
  into: milestones (later, next, now, parallel, done), plans with their stages and sections,
  mechanisms and notes. Version 3, the latest and so what a new project holds to, renames the
  stage a checkpoint and adds backlog items; its coder and manager views always carry their
  rules. `project_migrate` moves a project between versions.
- **One account, one room.** Its account is `workshop:local/nick` (stored; kept from the deployment's earlier name); its room is the grit
  checkout's directory (`KALANDRA_CHECKOUT`, default `$HOME/Projects/grit`), declared internal,
  and the account alone is cleared for internal. Every other room is unmapped, so a command
  run anywhere else would read public rows alone.
- **No model.** It passes no model's key to the kit, so nothing it serves can spend.
- **Its database** is `grit_kalandra` (`GRIT_DATABASE_URL`); it refuses to open `grit` or
  `grit_bort`. Unlike grit's test databases it is durable: never reset or drop it.
- **Its edge, `claude-code`** (`kalandra.edge`): HTTP on `KALANDRA_LISTEN` (`host:port` on a
  loopback address, default `127.0.0.1:7317`). `POST /command/<name>` runs the projects command
  `name` with the body, a JSON object, as its arguments, as the account in the room, believed at
  member (the integrity the deployment declares for its room, which grit meets with the room's
  cap). It answers the command's text with 200, a refusal with 409, and an error with its own
  status. `POST /mcp` serves the same commands as MCP tools (streamable HTTP, one JSON reply per
  message, no session): `initialize`, `ping`, `tools/list` (`project_release` taking `for` there
  too) and `tools/call`, run as `/command` runs it; a command that did not answer 200 is a tool
  error carrying its text. `POST /wait` (`bin/kalandra wait`) holds the request until an
  artifact reaches one of some states, or a commit is made in a worktree, as grit's `EdgeWatch`
  waits (grit's ADR 0051), and answers what met it, or 408 once it lapses. The edge also vouches for the account, the one member of its realm `workshop:local` (stored; kept from the earlier name),
  so a person is linked to it before the first command. It holds no transaction.
- **Brief files.** A `project_claim` that is done through the edge also writes its brief
  (`project_brief` for the claim's key and role) to a file a session hook reads:
  `${KALANDRA_BRIEFS:-$HOME/.local/state/kalandra/briefs}/<slot>.md`, the slot being the
  claim's `for` with `A`-`Z` lower-cased and every other character outside a slug part written `-`, and each `/`
  written `__` in the file's name. It is replaced whole, through a file beside it. A
  `project_release` through the edge takes `for` too, and once done deletes that file.
- **Session capture** (`kalandra.capture`). The edge records each Claude Code session that
  `hooks/spool-hook.sh` spools in grit's session record (grit's ADR 0050), as the account, at
  its room's label, each session placed at its worktree. Every 2 s it records the files the
  hook appended to `${KALANDRA_SPOOL:-$HOME/.local/state/kalandra/spool}`, one transaction a
  file, and deletes them. A file the store refuses 3 times goes to `quarantine/` there (the
  newest 20 kept); while the database cannot be reached, a pass ends at once, trying no file and
  taking no new one, so the hook's own bound holds what waits. A line it cannot read, or a
  session it cannot place, is dropped and said in serve's log.
- It names grit's kit, core, prose and the projects plugin alone.

It compiles under grit's flags (`-no-indent`, capture and separation checking, `-Werror`).

Packages, in one-way order: `method` (the method's data) → `capture` (the spool's lines and
their ingest) → `edge` (and its client) →
`deployment` → `importer` (grit's planning tree → export-shaped files → `project_keep` over
HTTP) → `main` (`serve`, `export`, `import`, `verify`).

## Build and run

```bash
# in a grit checkout
./mill --no-daemon __.publishLocal
# here
./mill compile
./mill test
KALANDRA_LINT=1 MILL_OUTPUT_DIR=out/lint ./mill __.compile   # the unused-warnings pass
bin/kalandra build                      # the jar serve and export run from
bin/kalandra serve                      # blocks, in the foreground; stop it with SIGTERM
bin/kalandra start                      # serve in the background, into logs/serve.log; waits until it answers
bin/kalandra stop                       # SIGTERM serve's own processes, by checkout and argv, never a pattern
bin/kalandra restart                    # stop, then start (does not build)
bin/kalandra status                     # serve's pids, and whether commands answer
bin/kalandra new '{"project":"m17"}'    # any command: `keep` or `project_keep`, then its JSON
bin/kalandra export m17 /tmp/m17        # the project's export files, through a running serve
```

A command through `bin/kalandra` fails loud, exit 2, when serve is not running. Use `start` and
`stop` rather than signalling serve by hand: a pattern kill matches its own shell.

## The brief hook

`hooks/brief-hook.sh` puts a claimed brief in a Claude Code session's context, or in a
subagent's: as a SessionStart and a SubagentStart hook it reads the event on stdin and prints
the brief file of its slot as `additionalContext`. A session's slot is the sha1 of its working
directory; a subagent's is `<agent type>/<sha1 of the session's working directory>`, normalised as
`BriefFiles.slot` normalises a claim's `for` (`BriefHookTests` holds the two to one rule);
`hooks/slot.sh` computes it for both hooks. `bin/kalandra slot [agent-type]`, run in the session's directory, prints it, for a claim's `for`:

```bash
cd ~/Projects/grit
w=~/Projects/kalandra/bin/kalandra
$w claim "{\"key\":\"<key>\",\"role\":\"<role>\",\"for\":\"$($w slot coder-haiku)\"}"
```

It needs bash, `jq`, `sha1sum`, `find` and `timeout`, never a JVM or the network, and it reads
the files the edge wrote, so it works with serve down. A missing file, a read over a second, or
anything else that fails, and it exits 0 with no output: the session starts without a brief.
SessionStart has no matcher, so the brief is injected at startup, on resume, after `/clear` and
after `/compact` (each resume injects it again). The edge writes a brief again after each keep or
move of its project, and after each ingest pass a digest of the slot's session beside it, which
a second SessionStart hook, `brief-hook.sh --digest`, injects after `/compact`, and with what the
session last said after `/clear` (the hook's header says which file, when it is left out, and why
it is a hook of its own). What a test hook saw of these events is in
[`docs/claude-code-hooks.md`](docs/claude-code-hooks.md). To install it in grit's checkout, in
`.claude/settings.local.json`:

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/brief-hook.sh", "timeout": 5 }] },
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/brief-hook.sh --digest", "timeout": 5 }] }
    ],
    "SubagentStart": [
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/brief-hook.sh", "timeout": 5 }] }
    ]
  }
}
```

## The spool hook

`hooks/spool-hook.sh` appends one line per session event to the spool serve records from
(Session capture, above); its header says what each line holds, what it bounds, and the one
collision that drops an event silently. A session's start line names its slot, as the brief
hook computes it, so the session record knows which claims its work keeps from lapsing (grit's
`Claims.Lease`). Like the brief hook it needs no JVM and no network and
fails open, and it works with serve down: the spool waits for serve, bounded. It needs bash 5,
`jq`, `git` and coreutils. Its PostToolUse hooks run in the background (`async`), so a tool
call never waits on it; the session's start, each reply's end, a compaction and the session's
end run in order with the session, so their lines are written before it moves on. To install
it beside the brief hook, in grit's checkout's `.claude/settings.local.json` (`hooks` entries
added to those above):

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh", "timeout": 5 }] }
    ],
    "PostToolUse": [
      { "matcher": "Read|Edit|Write|MultiEdit|NotebookEdit|Bash|mcp__scout__.*",
        "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh", "timeout": 5, "async": true }] }
    ],
    "PostToolUseFailure": [
      { "matcher": "Read|Edit|Write|MultiEdit|NotebookEdit|Bash|mcp__scout__.*",
        "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh", "timeout": 5, "async": true }] }
    ],
    "Stop": [
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh", "timeout": 5 }] }
    ],
    "PostCompact": [
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh", "timeout": 5 }] }
    ],
    "SessionEnd": [
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh", "timeout": 5 }] }
    ],
    "SubagentStart": [
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh", "timeout": 5 }] }
    ],
    "SubagentStop": [
      { "hooks": [{ "type": "command", "command": "/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh", "timeout": 5 }] }
    ]
  }
}
```

A commit made by a session's Bash is spooled by git's post-commit hook in that checkout
(`.git/hooks/post-commit`, or the common one a worktree shares), and the commits a rebase or an
amend leaves, with those they replace, by its post-rewrite hook (`.git/hooks/post-rewrite`), so
that a session's digest lists each surviving commit once:

```bash
#!/usr/bin/env bash
/home/nickchilders/Projects/kalandra/hooks/spool-hook.sh --commit
```

```bash
#!/usr/bin/env bash
exec /home/nickchilders/Projects/kalandra/hooks/spool-hook.sh --rewrite "$@"
```

## The MCP server

Claude Code reaches the commands as tools (`mcp__kalandra__project_show`, …) through serve's
`/mcp`. To add it for grit's checkout alone, run there:

```bash
claude mcp add --scope local --transport http kalandra http://127.0.0.1:7317/mcp
```

With serve down, a session starts without the tools.

Both `/mcp` and `/command` refuse, with 403 and running nothing, a request whose `Origin` is not
`http://` on `127.0.0.1`, `localhost` or `[::1]`, or whose `Host` is not the listen address or
`localhost` at its port: a web page in a browser on this machine could otherwise post to them,
by DNS rebinding with its own name as `Host`, and keep durable records. A request with no
`Origin`, as from `bin/kalandra`, curl or Claude Code's MCP client, passes on its `Host`.

## Importing grit's planning tree

`bin/kalandra import <grit checkout>` reads `roadmap/` (the roadmap, `done.md`, the other notes,
`mechanisms/`), `.local/backlog/` and the open plans (those the roadmap names outside Done and
the Update Protocol) and keeps them, through a running serve, as the project `grit`, under the
mapping `kalandra.importer.Importer` documents. It only ever adds and changes: an artifact whose
source went away stays. An existing artifact keeps the state the store holds: the source sets a new
artifact's state, and a milestone's (from its roadmap section, moved when the method has the way;
a milestone with no such way is reported, not a stop). A second run over an unchanged tree writes nothing; `--dry-run` writes
no artifact, and also checks the import against its own normal form. `bin/kalandra verify <grit
checkout>` checks the stored project holds the tree whole (`Fidelity.problems`) and is at the
round-trip law's normal form; it exits non-zero on any problem. The tree is only read.

## Backups and the restore test

`ops/backup.sh` dumps `grit_kalandra` (`pg_dump -Fc`, through the grit checkout's compose
Postgres, whose `pg_dump` matches the server) into `${KALANDRA_BACKUP_DIR:-$HOME/backups/grit_kalandra}`:
today's under `daily/`, the month's first also under `monthly/`; the newest 14 dailies and 6
monthlies are kept. Point `KALANDRA_BACKUP_DIR` at a second disk to keep them off the
database's.

`ops/systemd/` holds the user units that run it nightly at 03:30 (missed nights run at the next
start). They need systemd as the machine's init; on WSL, `[boot] systemd=true` in
`/etc/wsl.conf`. To install:

```bash
mkdir -p ~/.config/systemd/user
ln -s ~/Projects/kalandra/ops/systemd/kalandra-backup.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now kalandra-backup.timer
systemctl --user list-timers kalandra-backup.timer
```

`ops/restore-test.sh [project ...]` shows a dump restores whole: it keeps two artifacts of the
project `restore-check` through `bin/kalandra` (the same each run), dumps with `ops/backup.sh`,
restores into `grit_kalandra_restore`, serves that on `127.0.0.1:7318`, and compares each
project's export from the two databases; then stops what it started and drops the copy.
