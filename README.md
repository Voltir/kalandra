# kalandra

A deployment of grit that keeps grit's own projects: a Scala module against grit's published
jars (`grit-kit`, `grit-chat`, `grit-projects`, `grit-commands` and `grit-claudecode`, at the
version `build.mill` pins), the way any deployment outside grit builds. Package `kalandra`, outside `grit` on purpose, so the compiler treats it
as unseen deployment code: what grit keeps `private[grit]` is not visible here.

- **The projects plugin** (ADR 0049), under one method, `grit` (`kalandra.method`): grit's own
  process as data. Version 1 (milestones, stages, ideas, mechanisms) is kept because the project
  `restore-check` is pinned to its digest; version 2 has the kinds grit's planning tree imports
  into: milestones (later, next, now, parallel, done), plans with their stages and sections,
  mechanisms and notes. Version 3 renames the stage a checkpoint and adds backlog items; its
  coder and manager views always carry their rules. Version 4 adds amendments. Version 5, the
  latest and so what a new project holds to, ends a checkpoint only once everything under it
  has ended. `project_migrate` moves a project between versions.
- **One account, one room.** Its account is `workshop:local/nick` (stored; kept from the deployment's earlier name); its room is the grit
  checkout's directory (`KALANDRA_CHECKOUT`, default `$HOME/Projects/grit`), declared internal,
  and the account alone is cleared for internal. Every other room is unmapped, so a command
  run anywhere else would read public rows alone.
- **No model.** It passes no model's key to the kit, so nothing it serves can spend.
- **Its database** is `grit_kalandra` (`GRIT_DATABASE_URL`); it refuses to open `grit` or
  `grit_bort`. Unlike grit's test databases it is durable: never reset or drop it.
- **Its edge, `commands`**: grit's commands edge (ADR 0062), mounted by `kalandra.edge.Mount`
  on `KALANDRA_LISTEN` (`host:port` on loopback, default `127.0.0.1:7317`). Every request with no
  credential is the account's, believed at member, from the worker `local`; one carrying a
  credential is refused. `POST /command/<name>` runs a projects command with its body, a JSON
  object, as its arguments; `POST /mcp` serves the same commands as MCP tools; `POST /wait`
  (`bin/kalandra wait`) waits for a commit (`{"commit":{"holder":…}}` or `{"commit":{"worktree":…}}`)
  or for an artifact to reach a state; `GET /holder` answers the claim holder a session or its
  agent works as (`local/<dir>-<8 hex>`); `GET /brief` a holder's brief, or with `part=digest`
  what its last session did; `POST /events` records a session's events in grit's session record;
  `GET /join/claude-code` serves Claude Code's join script. What each answers, and refuses, is
  in the docs of `grit.commands`' `CommandsEdge`. kalandra's own commands (`kalandra.edge.OwnCommands`):
  `project_move` and `project_batch` refuse a move to `adopted`, which `project_adopt` alone
  makes, and `project_standing` and `project_adopt` are added at `/command` alone. The edge
  vouches for the account, the one member of its realm `workshop:local` (stored; kept from the
  earlier name), so a person is linked to it before the first command. serve writes no files.
- **Sessions** reach the edge through Claude Code's adapter: in a checkout, `curl -fsS
  http://127.0.0.1:7317/join/claude-code | bash -s -- http://127.0.0.1:7317 --mcp-name kalandra`
  installs the hook that briefs each session and agent from `/brief` and posts what they do to
  `/events`, and registers `/mcp` as `kalandra`; `--undo` removes it (grit's
  `extensions/claudecode/README.md`).
- It names grit's kit, core, prose and the projects plugin alone.

It compiles under grit's flags (`-no-indent`, capture and separation checking, `-Werror`).

Packages, in one-way order: `method` (the method's data) → `amend` (a standing section's
amendments) → `edge` (the mount
of grit's commands edge, kalandra's own commands, and the client) →
`deployment` → `importer` (grit's planning tree → export-shaped files → `project_keep` over
HTTP) → `main` (`serve`, `export`, `import`, `verify`).

## Build and run

```bash
# grit is published once per version, from its tag (grit's scripts/publish-local refuses a
# version already published); build.mill pins the version. Here:
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

A command through `bin/kalandra` fails loud, exit 2, when serve is not running. Use `start` and
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
standing text, which an `amendment` (GritMethod from version 4) proposes to edit: its `## Diff`
names the section and the version it is against, then `old` and `new` code blocks, exact
replacements as a coder's edits are.

```bash
bin/kalandra standing '{"key":"grit/amend-…"}' > standing.md   # the candidate a bench runs under
bin/kalandra adopt '{"key":"grit/amend-…"}'                    # keep it as the section, adopt the amendment
```

For a section, `standing` also says its version on stderr, which `scripts/eval propose
--against` takes. Both are the edge's own commands, not tools, and `adopt` is the only way an amendment reaches
`adopted`: what each refuses is in `OwnCommands`' and `Adoption.candidate`'s docs.

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
offered the coding tools' reads and `project_propose` alone, a new amendment in `proposed`;
serve's turns may not move an amendment to `adopted`, which only `project_adopt` does.

## The MCP server

Claude Code reaches the commands as tools (`mcp__kalandra__project_show`, …) through serve's
`/mcp`, which the join registers for its checkout (`--mcp-name kalandra`). With serve down, a
session starts without the tools.

Every route refuses, with 403 and running nothing, a request whose `Host` is not a loopback name
(`localhost`, `127.0.0.1`, `::1`) at any port, whose `Origin`, when sent, is not `http://` that
same `Host`, or whose `Sec-Fetch-Site`, when sent, is not `same-origin` or `none`: a web page in a
browser on this machine could otherwise post to them, by DNS rebinding or a cross-site request,
and keep durable records. A request with no `Origin`, as from `bin/kalandra`, curl or Claude
Code's MCP client, passes on its `Host`; one carrying `Authorization` is refused.

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
