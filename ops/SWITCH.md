# The switch to grit 0.3.0-SNAPSHOT, with credentials

The steps that move the live serve (`~/Projects/kalandra`, `127.0.0.1:7317`, `grit_kalandra`)
from the grit 0.2.0 commands edge, which takes every request with no credential as the owner's,
to grit 0.3.0-SNAPSHOT's, which answers a request 401 unless it bears one of two credentials:
the command line's, believed at authority, and the sessions', believed at member, both the
owner's account on the machine `local`. With it the stored place key `worker` becomes `machine`
(grit's `machine-from-worker.sql`). Each step has its command and its undo; the rollback from any
step is after them.

`P=~/.cache/kalandra-e7` below is the proof's directory (`ops/edge-proof.sh`): the proven jar
(`serve.jar`), the join script it serves (`join.sh`), the forward rename
(`machine-from-worker.sql`, grit's at the snapshot's commit) and its reverse
(`worker-from-machine.sql`), each with a `.sha256`, the proof's lines (`proof.txt`), the join's
output (`join.txt`) and the forward dry run's counts on the copy (`forward-dry.txt`).
`E=~/.cache/kalandra-e4c` holds the 0.2.0 jar and join script that are live now, and
`B=$P/before` is what step 4 saves. Every command runs in **a terminal of your own, never through
a Claude Code session** (not a Bash tool, not `!`): a session's `bin/kalandra` sends the
sessions' credential, and the switch stops the serve sessions talk to. `pg` below stands for:

```bash
pg() { docker compose --project-directory ~/Projects/grit exec -T postgres psql -U grit -q -v ON_ERROR_STOP=1 -d grit_kalandra "$@"; }
```

Paste each block whole: its commands are chained with `&&`, so one that fails stops the rest.

## Before you start

- **The proof passed** on a commit whose `src`, `build.mill`, `bin` and `ops/*.sql` are the
  branch head's, and its files are the ones there:

  ```bash
  P=~/.cache/kalandra-e7; E=~/.cache/kalandra-e4c; B=$P/before
  tail -1 $P/proof.txt                                                  # edge-proof: passed
  c=$(head -1 $P/proof.txt | sed -n 's/.* at \([0-9a-f]*\)$/\1/p')     # empty: proved `with changes`
  git -C ~/Projects/kalandra-e7 diff --quiet "$c" e7-repin -- src build.mill bin 'ops/*.sql' && echo same
  (cd $P && sha256sum -c serve.jar.sha256 join.sh.sha256 machine-from-worker.sql.sha256 worker-from-machine.sql.sha256)
  ```

  It prints `edge-proof: passed`, `same` and four `OK` lines. Anything else, and the proof is run
  again from `~/Projects/kalandra-e7` after `bin/kalandra build` there.
- `git -C ~/Projects/kalandra merge-base --is-ancestor main e7-repin` exits 0, so the merge at
  step 7 is a fast-forward.
- **No `bin/kalandra chat` or `work` is running**: serve waits on the engine lock they hold. This
  prints the pid of each (nothing, when none):

  ```bash
  for p in /proc/[0-9]*; do
    [[ "$(readlink "$p/exe" 2>/dev/null)" == */java ]] || continue
    a="$(tr '\0' '\n' <"$p/cmdline" 2>/dev/null)"
    grep -qxF ~/Projects/kalandra/out/assembly.dest/out.jar <<<"$a" && grep -qx -e chat -e work <<<"$a" && echo "${p#/proc/}"
  done
  ```

- **The live `.env` names no listener or database:** `grep -c
  '^\(export \)\{0,1\}\(KALANDRA_LISTEN\|GRIT_DATABASE_[A-Z_]*\)=' ~/Projects/kalandra/.env`
  prints `0`.
- **The date is 2026-11-15 or later.** The branch's first commit deletes the old edge, its spool
  and `hooks/`, which E4c kept a day after its switch.
- **While the pin is a snapshot, nothing is built in `~/Projects/kalandra`**: no `bin/kalandra
  build` there. The switch installs the proven jar by its sha, and kalandra re-pins to grit's
  release before its next build.
- **What holds, and what does not.** A session's keeps, claims and moves are recorded as yours at
  member, your command line's at authority, and a session's `project_adopt` is refused. A
  version's integrity is its last writer's: your `project_move` of a session's note records the
  note at authority, and a session's move of yours records it at member. A session runs as your
  unix user, so one that goes looking can read `~/.config/kalandra/credential`: the split keeps
  the command line's credential out of every session's environment, argv, transcript and
  `~/.claude.json`, and refuses an accident, not a session set on it. `project_keep` of a
  standing section is not guarded by adopt's authority.
- **What cannot be undone**, by the rollback or anything else: the commit subjects step 8 drops
  (saved at step 6 first); a claim's lease that lapses while the switch is under way, two hours
  after its holder's last event; and a rollback's revert commits, which stay on kalandra's
  `main`.
- **A proof killed with SIGKILL** leaves `grit_kalandra_e7`, a serve on 7318, a forwarder on
  7319 and its `mktemp -d` directory. Clear them by the pid, its cwd checked to be the proof's
  (`/tmp/tmp.…/run` or `/tmp/tmp.…/old`), never by pattern:

  ```bash
  ss -Hltnp 'sport = :7318'                    # users:(("java",pid=<pid>,…)); and :7319
  readlink /proc/<pid>/cwd                     # /tmp/tmp.…/run or /tmp/tmp.…/old
  kill <pid>
  docker compose --project-directory ~/Projects/grit exec -T postgres \
    psql -U grit -d postgres -c 'DROP DATABASE IF EXISTS grit_kalandra_e7 WITH (FORCE)'
  rm -rf -- /tmp/tmp.…                         # the directory that cwd named
  ```

## 1. Make the two credentials

```bash
( set -o noclobber; umask 077 && mkdir -p ~/.config/kalandra ~/.config/grit &&
  head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' > ~/.config/kalandra/credential &&
  head -c 32 /dev/urandom | base64 | tr -d '=\n' | tr '+/' '-_' > ~/.config/grit/credential ) &&
stat -c '%a %n' ~/.config/kalandra ~/.config/kalandra/credential ~/.config/grit/credential
```

The `stat` line prints `700`, `600`, `600`. Under `noclobber` a second run refuses (`cannot
overwrite existing file`) rather than replace a credential whose digest is configured. If
`~/.config/grit` already existed, its own mode is left as it was; the join checks the file's.
The text goes from urandom to the file through pipes, so it is on no argv and in no history, and
it is never printed. `~/.config/kalandra/credential` is the command line's;
`~/.config/grit/credential` is the sessions': the join writes its path into the hook, and
`bin/kalandra` reads it inside a session.

Undo: `rm -- ~/.config/kalandra/credential ~/.config/grit/credential`, and only after a rollback.

## 2. The digests into serve's environment file

```bash
d() { tr -d '\n' < "$1" | sha256sum | cut -d' ' -f1; }
f=~/Projects/kalandra/.env &&
[ "$(grep -c '^\(export \)\{0,1\}KALANDRA_\(OWNER\|SESSIONS\)_DIGEST=' $f)" = 0 ] &&
{ [ -z "$(tail -c1 $f)" ] || echo >> $f; } &&
{ printf 'KALANDRA_OWNER_DIGEST=%s\n' "$(d ~/.config/kalandra/credential)"
  printf 'KALANDRA_SESSIONS_DIGEST=%s\n' "$(d ~/.config/grit/credential)"; } >> $f &&
grep -c '^KALANDRA_\(OWNER\|SESSIONS\)_DIGEST=[0-9a-f]\{64\}$' $f        # 2
```

Only the two digests go in, and a digest is no secret. A last line with no newline gets one
first, so the first digest is a line of its own. `.env` keeps its mode. The live 0.2.0 serve
reads neither variable, so this step can come before step 5; `bin/kalandra chat` and
`cache-report` need both from step 7 on.

Undo: delete the two lines by hand. A rollback does not need it: 0.2.0 ignores them.

## 3. End every session

End every Claude Code session in grit's checkout and its worktrees (`/exit`, or quit). The
holders do not change, so no claim is re-made, but a claim lapses two hours after its holder's
last event: go from here to step 10 well inside that.

Undo: none. Sessions start again at step 11.

## 4. Back up what the switch changes

```bash
mkdir -p $B && cd ~/Projects/grit &&
cp .claude/settings.local.json .git/grit/claude-code/hook $B/ &&
claude mcp get kalandra > $B/mcp.txt &&
cp ~/Projects/kalandra/out/assembly.dest/out.jar $B/serve.jar &&
git -C ~/Projects/kalandra rev-parse HEAD > $B/kalandra-head &&
~/Projects/kalandra/bin/kalandra claims '{"project":"grit"}' > $B/claims.json
```

For each worktree in `git worktree list` with an MCP entry of its own, run `claude mcp get
kalandra` there as well, and save it as `$B/mcp-<name>.txt`.

Undo: none needed.

## 5. Stop serve, show nothing is connected, then dump

```bash
cd ~/Projects/kalandra && bin/kalandra stop &&
pg -At -c "SELECT count(*) FROM pg_stat_activity WHERE datname='grit_kalandra' AND pid <> pg_backend_pid()" &&
pg -At -c "SELECT count(*) FROM dbos.workflow_status w WHERE w.status IN ('PENDING','ENQUEUED') AND (w.inputs LIKE '%worker:%' OR EXISTS (SELECT 1 FROM dbos.operation_outputs o WHERE o.workflow_uuid = w.workflow_uuid AND o.output LIKE '%worker:%'))" &&
cp "$(ops/backup.sh)" $B/grit_kalandra.dump
```

Both counts print `0`: nothing else holds the database open, and no pending workflow names a
place under `worker` (the proof showed this query counts a planted one, in DBOS's own
encoding). A connection is ended before going on; a pending workflow that names one means the
switch waits for it to finish under 0.2.0. The dump comes after the stop, so it holds
everything. It is the last resort, used only if the reverse rewrite raises.

Undo: `bin/kalandra start`.

## 6. The rename, dry, and the rows it drops saved

```bash
pg -f - < $P/machine-from-worker.sql &&
pg -c "\copy (SELECT session, seq, kind, shape FROM grit.session_events WHERE shape ->> 'worktree' LIKE 'worker:%' AND octet_length(shape::text) >= 300 ORDER BY session, seq) TO STDOUT WITH CSV HEADER" > $B/dropped.csv &&
wc -l < $B/dropped.csv
```

The first prints the counts table and rolls back. On the copy of 2026-11-14 the proof printed
(`$P/forward-dry.txt`):

```
                        what                         |  n  
-----------------------------------------------------+-----
 grit.documents rewritten                            |   0
 grit.files rewritten                                |   0
 grit.piece_sets rewritten                           |   0
 grit.places rewritten                               |   0
 grit.session_events deleted, too long to rewrite    |   0
 grit.session_events rewritten                       | 202
 grit.session_events rewritten without their subject |   0
 grit.sessions rewritten                             |  12
 grit.visibility_changes rewritten                   |   0
(9 rows)
```

- `grit.session_events deleted, too long to rewrite` must be 0; anything else, stop.
- `grit.session_events rewritten without their subject` are the commits whose subject is
  dropped. On the copy there were none, and `dropped.csv` held its header line alone.

`$B/dropped.csv` saves every event the rename drops a subject from or deletes: its line count is
one header line plus that count and the deleted count. The live database may have grown since
the proof: a count that differs from the proof's by more than that growth is a reason to stop.
If the file raises, stop: nothing changed.

Undo: none.

## 7. The new code and the proven jar, with no build

```bash
cd ~/Projects/kalandra && git merge --ff-only e7-repin &&
cp $P/serve.jar out/assembly.dest/out.jar &&
echo "$(cut -d' ' -f1 $P/serve.jar.sha256)  out/assembly.dest/out.jar" | sha256sum -c &&
systemctl --user daemon-reload
```

It prints `out/assembly.dest/out.jar: OK`; anything else, and the jar is put back (the undo)
before going on. The merge changes the unit file's `Description` alone (it names grit's commands
edge), which `daemon-reload` reads. This comes before the rename is kept: the 0.2.0 jar must
never serve data under `machine`, while the new jar answers 401 before anything is stored.

Undo, kalandra's `main` never rewritten:

```bash
cd ~/Projects/kalandra && git revert --no-edit "$(cat $B/kalandra-head)"..HEAD &&
cp $B/serve.jar out/assembly.dest/out.jar && systemctl --user daemon-reload
```

## 8. The rename, kept

```bash
pg -At -c "SELECT count(*) FROM pg_stat_activity WHERE datname='grit_kalandra' AND pid <> pg_backend_pid()" &&
pg -f - -c COMMIT < $P/machine-from-worker.sql &&
pg -f - < $P/machine-from-worker.sql
```

The first prints `0` again (stop if not); the second prints step 6's counts and keeps them; the
third, every count `0`.

Undo: `pg -f - -c COMMIT < $P/worker-from-machine.sql && pg -f - < $P/worker-from-machine.sql`,
the second's counts all `0`.

## 9. Start the new serve

```bash
cd ~/Projects/kalandra && bin/kalandra start &&
grep -F '[kalandra] grit 0.3.0-SNAPSHOT at ' logs/serve.log | tail -1 &&
tail -1 logs/serve.log &&
curl -q -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST -d '{}' http://127.0.0.1:7317/command/project_list &&
bin/kalandra list '{"project":"grit","state":"now"}' > /dev/null && echo command line ok
```

It prints `[kalandra] grit 0.3.0-SNAPSHOT at <the marker's commit>`, `commands: serving on
http://127.0.0.1:7317`, `401` for a request with no credential, and `command line ok` for one
with yours. A serve missing a digest exits at once, its log naming the variable
(`[kalandra] KALANDRA_OWNER_DIGEST is not set: …`); systemd starts it again every 10 s until
step 2 is right.

Undo: `bin/kalandra stop`, then the rollback.

## 10. Join again, with the sessions' credential

```bash
cd ~/Projects/grit &&
bash -s -- http://127.0.0.1:7317 --credential ~/.config/grit/credential --mcp-name kalandra < $P/join.sh
```

The join asks the new serve with the credential first, so this step comes after step 9. On the
proof's copy it printed, over a 0.2.0 join (its edge was the proof's on 7318, and `<work>` its
own directory):

```
grit claude-code: joined <work>/repo to http://127.0.0.1:7318
  hook      <work>/repo/.git/grit/claude-code/hook, shared by this repository's worktrees
  settings  .claude/settings.local.json: 11 handlers of ours
  ignore    .claude/settings.local.json in <work>/repo/.git/info/exclude, shared by this repository's worktrees
  git       post-commit and post-rewrite in <work>/repo/.git/hooks, shared by this repository's worktrees
  credential <work>/cred/sessions, sent with every request
  mcp       kalandra at http://127.0.0.1:7318/mcp, local scope, replacing kalandra at http://127.0.0.1:7318/mcp
Sessions pick the hooks up at once; run /mcp in a running session to connect kalandra.
Undo: curl -fsS http://127.0.0.1:7318/join/claude-code | bash -s -- --undo --mcp-name kalandra
```

Here it names `http://127.0.0.1:7317` and `~/.config/grit/credential`. Its last line's undo asks
`/join` with no credential, which the new serve refuses: use the undo below instead.

Run the same command in each worktree found at step 4. The hook file is shared, so every
worktree sends the same credential.

Undo (the second line puts the 0.2.0 join back; compare `claude mcp get kalandra` with
`$B/mcp.txt`):

```bash
bash -s -- --undo --mcp-name kalandra < $P/join.sh &&
bash -s -- http://127.0.0.1:7317 --mcp-name kalandra < $E/join.sh
```

## 11. New sessions, not resumed ones

Start a new `claude` in grit's checkout and in each joined worktree. In each, `/mcp` lists
`kalandra` as connected. A claim made for an agent of a session that has ended is re-made from
the new session for the holder its opening's agent line prints: release it first, then claim
it.

Undo: none.

## 12. What to check

- The opening's `grit: this session's claim holder is local/grit-…` line is the holder in
  `$B/claims.json`, unchanged, and `bin/kalandra claims '{"project":"grit"}'` matches that file.
- `mcp__kalandra__project_show` answers. A keep from the session and one from your terminal are
  read back as member (2) and authority (3):
  `pg -At -c "SELECT d.key, l.level FROM grit.documents d JOIN grit.integrity_labels l ON l.id = d.integrity_id WHERE d.superseded_at IS NULL AND d.key IN ('<key1>','<key2>')"`.
- The session's `bin/kalandra show` works, and its `bin/kalandra adopt` is refused 403 naming
  authority.
- A commit wait by holder answers `committed …`. After a `/compact`, the digest shows the edit.
- The agent path gets its brief at SubagentStart.
- `bin/kalandra export grit /tmp/x` works from your terminal.

## 13. Held

After a day of work, once the switch has held:

- remove E4c's leftover state by name, never `rm -r` of the parent; `rmdir` refuses a directory
  that still holds something, and what it names is looked at, not removed:

  ```bash
  S=~/.local/state/kalandra
  rm -f -- "$S"/briefs/*.md "$S"/spool/quarantine/*
  rmdir "$S"/spool/quarantine 2>/dev/null; rmdir "$S"/spool "$S"/briefs "$S"
  ```

- run `ops/restore-test.sh` once;
- delete `ops/worker-from-machine.sql` in a commit of its own, and `rm -r -- $E`;
- keep `$B` for a week.

## The rollback, from any step before 13

1. End every session (step 3).
2. `cd ~/Projects/kalandra && bin/kalandra stop`.
3. **Before step 10 ran:** if step 8 ran, its undo (the reverse rewrite); then, if step 7 ran,
   its undo (the old code and jar). **After step 10 ran:** step 7's undo first, then step 8's.
4. If the reverse rewrite raises, restore instead, never dropping the database:

   ```bash
   docker compose --project-directory ~/Projects/grit exec -T postgres psql -U grit -d postgres -v ON_ERROR_STOP=1 \
     -c 'ALTER DATABASE grit_kalandra RENAME TO grit_kalandra_e7_failed' -c 'CREATE DATABASE grit_kalandra' &&
   docker compose --project-directory ~/Projects/grit exec -T postgres pg_restore -U grit -d grit_kalandra --no-owner --exit-on-error < $B/grit_kalandra.dump
   ```

   This loses everything since step 5, which `grit_kalandra_e7_failed` still holds.
5. `bin/kalandra start`. The log says `commands: serving on http://127.0.0.1:7317`.
6. If step 10 ran: its undo, in grit's checkout and each worktree.
7. New sessions.

The keeps, claims and events the new serve wrote stay, under `worker` again; the proof's step 10
shows the 0.2.0 jar serving them, with the same holders.
