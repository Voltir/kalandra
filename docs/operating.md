# Operating kalandra

A deployment of grit that keeps grit's own projects: a Scala module against grit's published
jars (`grit-kit`, `grit-chat`, `grit-projects`, `grit-commands` and `grit-claudecode`, at the
version `build.mill` pins), the way any deployment outside grit builds. Package `kalandra`, outside `grit` on purpose, so the compiler treats it
as unseen deployment code: what grit keeps `private[grit]` is not visible here.

- **The projects plugin** (ADR 0049), under one method, `grit`: grit's own process as data,
  one file a version in `methods/` (`grit@<version>.json`), each declared into the store
  (`bin/kalandra declare methods/grit@6.json`, `project_declare`, a caller believed at
  authority; a deploy declares each, and `ops/restore-test.sh` too) before a project may hold to
  it; `kalandra.method.Grit` holds the names the code reads of it
  and the checks its versions may name. Version 1 (milestones, stages, ideas, mechanisms) is kept because the project
  `restore-check` is pinned to its digest; version 2 has the kinds grit's planning tree imports
  into: milestones (later, next, now, parallel, done), plans with their stages and sections,
  mechanisms and notes. Version 3 renames the stage a checkpoint and adds backlog items; its
  coder and manager views always carry their rules. Version 4 adds amendments. Version 5 ends a
  checkpoint only once everything under it has ended. Version 6, the latest and so what a new
  project holds to, leaves an amendment's move to `adopted` to a caller believed at authority,
  and puts `ready` between a checkpoint's `planned` and `building`, reached once its body holds
  Goal, Signatures, Reads and Commits. `project_migrate` moves a project between versions.
- **One account, one room.** Its account is `workshop:local/nick` (stored; kept from the deployment's earlier name); its room is the grit
  checkout's directory (`KALANDRA_CHECKOUT`, default `$HOME/Projects/grit`), declared internal,
  and the account alone is cleared for internal. Every other room is unmapped, so a command
  run anywhere else would read public rows alone.
- **Its models.** Its own turns run on Claude through Anthropic's API (`ANTHROPIC_API_KEY`), a day's
  spend capped. A turn may start a subagent (`kalandra.deployment.Kalandra.AgentTypes`): a coder in
  `grit` (the checkout) or `kalandra` (its own checkout, `KALANDRA_REPOSITORY`, default
  `$HOME/Projects/kalandra`), on GLM through OpenRouter (`OPENROUTER_API_KEY`), which works in a
  worktree under `.claude/worktrees` in that clone and commits on its own `grit/*` branch, never
  pushing; or a specialist on Claude Haiku, offered no tool. Both repositories are labelled
  internal, and their files believed at most at member.
- **Its database** is `grit_kalandra` (`GRIT_DATABASE_URL`); it opens that, or a database
  whose name starts `grit_kalandra_` (a proof's copy, a restore), and refuses any other. Unlike grit's test databases it is durable: never reset or drop it.
- **Its edge, `commands`**: grit's commands edge (ADR 0062), mounted by `kalandra.edge.Mount`
  on `KALANDRA_LISTEN` (`host:port` on loopback, default `127.0.0.1:7317`). Every request bears a
  credential, and is refused (401) without one serve knows. There are two, both the account's on
  the machine `local`: the command line's, believed at authority, and the sessions' the account
  starts, believed at member, so a delegated agent writes as its person's account at lower
  integrity. serve knows each by its SHA-256 digest, `KALANDRA_OWNER_DIGEST` and
  `KALANDRA_SESSIONS_DIGEST` in `.env`, and refuses to start without both; a digest is no secret,
  and the texts live in `~/.config/kalandra/credential` and `~/.config/grit/credential`, mode 0600.
  Sessions on another machine bear that machine's own credential: `KALANDRA_MACHINES` in `.env`
  names each, `<machine>:<digest>` joined by commas, and serve believes it as the account's
  sessions on that machine, at member, never at the command line's authority. Its text lives on
  that machine alone, and whoever runs serve adds the line; deploy neither makes nor changes it.
  serve refuses to start, naming the variable and the entry's machine, on an entry not
  `<machine>:<digest>`, a machine named `local` or twice, a name grit refuses as a machine's, or a
  digest another credential holds.
  `POST /command/<name>` runs a projects command with its body, a JSON
  object, as its arguments; `POST /mcp` serves the same commands as MCP tools; `POST /wait`
  (`bin/kalandra wait`) waits for a commit (`{"commit":{"holder":…}}` or `{"commit":{"worktree":…}}`)
  or for an artifact to reach a state; `GET /holder` answers the claim holder a session or its
  agent works as (`local/<dir>-<8 hex>`); `GET /brief` a holder's brief, or with `part=digest`
  what its last session did; `POST /events` records a session's events in grit's session record;
  `GET /join/claude-code` serves Claude Code's join script. What each answers, and refuses, is
  in the docs of `grit.commands`' `CommandsEdge`. kalandra's own commands (`kalandra.edge.OwnCommands`),
  `project_standing` and `project_adopt`, are added at `/command` alone; `project_adopt` asks a
  caller believed at authority, so a session's is refused (403). Who may move an artifact where
  is the method's: the plugin refuses a move its method gives to people alone unless its caller
  is believed at authority. The edge
  vouches for the account, the one member of its realm `workshop:local` (stored; kept from the
  earlier name), so a person is linked to it before the first command. serve writes no files.
- **Sessions** reach the edge through Claude Code's adapter: in a checkout, `curl -fsS -H
  @<(printf 'Authorization: Bearer %s' "$(<~/.config/grit/credential)")
  http://127.0.0.1:7317/join/claude-code | bash -s -- http://127.0.0.1:7317 --credential
  ~/.config/grit/credential --mcp-name kalandra` installs the hook that briefs each session and agent from `/brief` and posts what they do to
  `/events`, and registers `/mcp` as `kalandra`; `--undo` removes it (grit's
  `extensions/claudecode/README.md`).
- It names grit's kit, core, prose and the projects plugin alone.

It compiles under grit's flags (`-no-indent`, capture and separation checking, `-Werror`).

Packages, in one-way order: `method` (the method's names and checks) → `amend` (a standing section's
amendments) → `edge` (the mount
of grit's commands edge, kalandra's own commands, and the client) →
`deployment` → `main` (`serve`, `export`).

## Build and run

```bash
# A release is published once, from its tag; a snapshot is published again from grit's main by
# scripts/publish-local, and must be published on this machine before the first build. build.mill
# pins the version. Here:
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
bin/kalandra holder                     # the claim holder a session here works as, for a claim
```

A command through `bin/kalandra` fails loud, exit 2, when serve is not running, and sends the
credential its header names (the sessions' inside a Claude Code session). Use `start` and
`stop` rather than signalling serve by hand: a pattern kill matches its own shell.

**Kept up by systemd.** Where systemd runs (on WSL, `[boot] systemd=true` in `/etc/wsl.conf`),
`ops/systemd/kalandra-serve.service` runs serve at boot and starts it again 10 s after it exits,
including while Postgres, under Docker Desktop, is not yet up. Once the unit is enabled, `start`,
`stop` and `restart` go through it and `status` says so. Installed, with the nightly backup, by:

```bash
for u in kalandra-serve.service kalandra-backup.service kalandra-backup.timer; do
  systemctl --user link "$PWD/ops/systemd/$u"
done
systemctl --user enable --now kalandra-serve.service kalandra-backup.timer
loginctl enable-linger "$USER"   # user units run without a login session
```

## Amending a standing section

A section that states a cap above its rule (`**Cap: N characters**`, then `***`) holds a
standing text, which an `amendment` (the method's, from version 4) proposes to edit: its `## Diff`
names the section and the version it is against, then `old` and `new` code blocks, exact
replacements as a coder's edits are.

```bash
bin/kalandra standing '{"key":"grit/amend-…"}' > standing.md   # the candidate a bench runs under
bin/kalandra adopt '{"key":"grit/amend-…"}'                    # keep it as the section, adopt the amendment
```

For a section, `standing` also says its version on stderr, which `scripts/eval propose
--against` takes. Both are the edge's own commands, not tools, and from version 6 only a caller believed at
authority moves an amendment to `adopted`, as `adopt` does: what each refuses is in `OwnCommands`' and `Adoption.candidate`'s docs.

## The proposer

A proposer reads one task's runs as the bench kept them and keeps amendments to the grit
coder's standing section. `scripts/eval propose` in grit writes its evidence checkout under the
evidence root (`KALANDRA_EVIDENCE`, default `~/.cache/grit-propose`), its earlier amendments
read from a directory this writes:

```bash
bin/kalandra amendments /tmp/amendments      # every amendment of grit, ended ones included
cd ~/.cache/grit-propose/propose-<seconds>
bin/kalandra work --dry evidence/PROPOSE.md  # its first model call shown, no key read
bin/kalandra work evidence/PROPOSE.md        # its turns run
```

It is its own deployment (`Kalandra.proposing`, version `proposer`) on `grit_kalandra`, so a
run needs the engine to itself, a dry one too (on serve's engine it would show serve's tools):
`work` refuses one while serve holds it, and `bin/kalandra work` stops serve for the run and
starts it again however it ends. Its turns are
offered the coding tools' reads and `project_propose` alone, a new amendment in `proposed`.

## The MCP server

Claude Code reaches the commands as tools (`mcp__kalandra__project_show`, …) through serve's
`/mcp`, which the join registers for its checkout (`--mcp-name kalandra`). With serve down, a
session starts without the tools.

Every route refuses, with 403 and running nothing, a request whose `Host` is not a loopback name
(`localhost`, `127.0.0.1`, `::1`) at any port, whose `Origin`, when sent, is not `http://` that
same `Host`, or whose `Sec-Fetch-Site`, when sent, is not `same-origin` or `none`. Claude Code's
MCP client sends the sessions' credential through the hook's `header`, its `headersHelper`, so the
credential is in neither `~/.claude.json` nor a session's environment.

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
