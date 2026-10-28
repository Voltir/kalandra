# workshop

A deployment of grit that keeps grit's own projects: a Scala module against grit's
`publishLocal` jars (`grit-kit` and `grit-projects`, `0.1.0-SNAPSHOT`), the way any deployment
outside grit builds. Package `workshop`, outside `grit` on purpose, so the compiler treats it
as unseen deployment code: what grit keeps `private[grit]` is not visible here.

- **The projects plugin** (ADR 0049), under one method, `grit` (`workshop.method`): grit's own
  process as data. Version 1 (milestones, stages, ideas, mechanisms) is kept because the project
  `restore-check` is pinned to its digest; version 2, the latest and so what a new project holds
  to, has the kinds grit's planning tree imports into: milestones (later, next, now, parallel,
  done), plans with their stages and sections, mechanisms and notes.
- **One account, one room.** Its account is `workshop:local/nick`; its room is the grit
  checkout's directory (`WORKSHOP_CHECKOUT`, default `$HOME/Projects/grit`), declared internal,
  and the account alone is cleared for internal. Every other room is unmapped, so a command
  run anywhere else would read public rows alone.
- **No model.** It passes no model's key to the kit, so nothing it serves can spend.
- **Its database** is `grit_workshop` (`GRIT_DATABASE_URL`); it refuses to open `grit` or
  `grit_bort`. Unlike grit's test databases it is durable: never reset or drop it.
- **Its edge, `claude-code`** (`workshop.edge`): HTTP on `WORKSHOP_LISTEN` (`host:port` on a
  loopback address, default `127.0.0.1:7317`). `POST /command/<name>` runs the projects command
  `name` with the body, a JSON object, as its arguments, as the account in the room, believed at
  member (the integrity the deployment declares for its room, which grit meets with the room's
  cap). It answers the command's text with 200, a refusal with 409, and an error with its own
  status. The edge also vouches for the account, the one member of its realm `workshop:local`,
  so a person is linked to it before the first command. It holds no transaction.
- **Brief files.** A `project_claim` that is done through the edge also writes its brief
  (`project_brief` for the claim's key and role) to a file a session hook reads:
  `${WORKSHOP_BRIEFS:-$HOME/.local/state/workshop/briefs}/<slot>.md`, the slot being the
  claim's `for`, lower-cased with every character outside a slug part written `-`, and each `/`
  written `__` in the file's name. It is replaced whole, through a file beside it. A
  `project_release` through the edge takes `for` too, and once done deletes that file.
- It names grit's kit, core, prose and the projects plugin alone.

It compiles under grit's flags (`-no-indent`, capture and separation checking, `-Werror`).

Packages, in one-way order: `method` (the method's data) → `edge` (and its client) →
`deployment` → `importer` (grit's planning tree → export-shaped files → `project_keep` over
HTTP) → `main` (`serve`, `export`, `import`, `verify`).

## Build and run

```bash
# in a grit checkout
./mill --no-daemon __.publishLocal
# here
./mill compile
./mill test
WORKSHOP_LINT=1 MILL_OUTPUT_DIR=out/lint ./mill __.compile   # the unused-warnings pass
bin/workshop build                      # the jar serve and export run from
bin/workshop serve                      # blocks; stop it with SIGTERM
bin/workshop new '{"project":"m17"}'    # any command: `keep` or `project_keep`, then its JSON
bin/workshop export m17 /tmp/m17        # the project's export files, through a running serve
```

A command through `bin/workshop` fails loud, exit 2, when serve is not running.

## Importing grit's planning tree

`bin/workshop import <grit checkout>` reads `roadmap/` (the roadmap, `done.md`, the other notes,
`mechanisms/`), `.local/backlog/` and the open plans (those the roadmap names outside Done and
the Update Protocol) and keeps them, through a running serve, as the project `grit`, under the
mapping `workshop.importer.Importer` documents. It only ever adds and changes: an artifact whose
source went away stays. An existing artifact keeps the state the store holds: the source sets a new
artifact's state, and a milestone's (from its roadmap section, moved when the method has the way;
a milestone with no such way is reported, not a stop). A second run over an unchanged tree writes nothing; `--dry-run` writes
no artifact, and also checks the import against its own normal form. `bin/workshop verify <grit
checkout>` checks the stored project holds the tree whole (`Fidelity.problems`) and is at the
round-trip law's normal form; it exits non-zero on any problem. The tree is only read.

## Backups and the restore test

`ops/backup.sh` dumps `grit_workshop` (`pg_dump -Fc`, through the grit checkout's compose
Postgres, whose `pg_dump` matches the server) into `${WORKSHOP_BACKUP_DIR:-$HOME/backups/grit_workshop}`:
today's under `daily/`, the month's first also under `monthly/`; the newest 14 dailies and 6
monthlies are kept. Point `WORKSHOP_BACKUP_DIR` at a second disk to keep them off the
database's.

`ops/systemd/` holds the user units that run it nightly at 03:30 (missed nights run at the next
start). They need systemd as the machine's init; on WSL, `[boot] systemd=true` in
`/etc/wsl.conf`. To install:

```bash
mkdir -p ~/.config/systemd/user
ln -s ~/Projects/workshop/ops/systemd/workshop-backup.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now workshop-backup.timer
systemctl --user list-timers workshop-backup.timer
```

`ops/restore-test.sh [project ...]` shows a dump restores whole: it keeps two artifacts of the
project `restore-check` through `bin/workshop` (the same each run), dumps with `ops/backup.sh`,
restores into `grit_workshop_restore`, serves that on `127.0.0.1:7318`, and compares each
project's export from the two databases; then stops what it started and drops the copy.
