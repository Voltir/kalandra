# The switch to grit's commands edge

The steps that move the live serve (`~/Projects/kalandra`, `127.0.0.1:7317`, `grit_kalandra`)
from kalandra's own `claude-code` edge, its spool and its `hooks/` to grit's commands edge, as
proved by `ops/edge-proof.sh` on the branch `commands-edge`. Each step has its command and its
undo; the rollback from any step is after them. The deletion of the old code (commit B) comes
only once the switch has held.

`P=~/.cache/kalandra-e4c` below: the proof's directory, holding the proven jar (`serve.jar`),
the join script that jar serves (`join.sh`), their sha256 files and the proof's lines
(`proof.txt`).

## Before

- `ops/edge-proof.sh` passed on the head of `commands-edge`: `head -1 $P/proof.txt` names that
  commit with no `with changes`, and its last line is `edge-proof: passed`. If `commands-edge`
  moved since, run it again from `~/Projects/kalandra-commands-edge` after `./mill show
  assembly` there.
- The proven files are the ones there: `(cd $P && sha256sum -c serve.jar.sha256 join.sh.sha256)`.
- `git -C ~/Projects/kalandra merge-base --is-ancestor main commands-edge` exits 0 (main has not
  moved under the branch, so the merge in step 6 is a fast-forward).

## 1. End every session

Every Claude Code session in grit's checkout and its worktrees ends first (`/exit`, or quit),
so no hook fires while the hooks change and nothing is spooled that the old serve would not
take. What still runs there:

```bash
for p in /proc/[0-9]*; do
  [[ "$(readlink "$p/exe" 2>/dev/null)" == *claude* ]] || continue
  echo "${p#/proc/} $(readlink "$p/cwd")"
done
```

Undo: none; sessions start again at step 9.

## 2. Back up what the switch changes

```bash
B=$P/before && mkdir -p $B && cd ~/Projects/grit
cp .claude/settings.local.json .git/hooks/post-commit .git/hooks/post-rewrite .git/info/exclude $B/
cp ~/Projects/kalandra/out/assembly.dest/out.jar $B/serve.jar
git -C ~/Projects/kalandra rev-parse HEAD > $B/kalandra-head
~/Projects/kalandra/bin/kalandra claims '{"project":"grit"}' > $B/claims.json
```

A worktree of grit's with old handlers of its own (`grep -l kalandra/hooks
<worktree>/.claude/settings.local.json` for each of `git -C ~/Projects/grit worktree list`)
gets its settings backed up the same way and is joined at step 3 too; the git hooks and
`info/exclude` are the repository's, shared by every worktree.

Undo: none needed; it changes nothing.

## 3. Join, retiring the old hooks

In grit's checkout, with the proven join script, for the address the new serve takes at step 7
(the old serve answers there until step 5; nothing posts meanwhile, as every session has ended):

```bash
cd ~/Projects/grit
bash -s -- http://127.0.0.1:7317 --mcp-name kalandra --retire ~/Projects/kalandra/hooks < $P/join.sh
```

It prints 11 `removed` lines, `settings … 11 handlers of ours`, the git hooks written, and
`mcp kalandra at http://127.0.0.1:7317/mcp, local scope, replacing kalandra at
http://127.0.0.1:7317/mcp`. In a worktree found at step 2, the same command there.

Undo: the join's undo, then only the settings' `hooks` key back, then the repository's files,
then the old MCP entry:

```bash
cd ~/Projects/grit
bash -s -- --undo --mcp-name kalandra < $P/join.sh
jq --slurpfile was $P/before/settings.local.json '.hooks = $was[0].hooks' .claude/settings.local.json \
  > .claude/settings.local.json.undo && mv .claude/settings.local.json.undo .claude/settings.local.json
cp $P/before/post-commit $P/before/post-rewrite .git/hooks/ && cp $P/before/exclude .git/info/exclude
claude mcp add --transport http --scope local kalandra http://127.0.0.1:7317/mcp
```

## 4. The spool is empty

```bash
find ~/.local/state/kalandra/spool -type f | wc -l     # 0
```

With every session ended it is 0 within a few seconds (the old serve takes each file every 2 s).
If it stays above 0, stop here and read `bin/kalandra status` and `logs/serve.log`. Undo: none.

## 5. Stop the old serve

```bash
cd ~/Projects/kalandra && bin/kalandra stop
```

Undo: `bin/kalandra start`.

## 6. The new code, and the proven jar: no build at the switch

```bash
cd ~/Projects/kalandra
git merge --ff-only commands-edge
cp $P/serve.jar out/assembly.dest/out.jar
echo "$(cut -d' ' -f1 $P/serve.jar.sha256)  out/assembly.dest/out.jar" | sha256sum -c
```

The last line prints `out/assembly.dest/out.jar: OK`; anything else, and the jar is put back
(the undo) before going on. The unit file is unchanged by this: the new serve reads no variable
the unit does not set. (`ops/systemd/kalandra-serve.service.next` is the unit's text after the
switch: its `Description` alone differs, naming grit's commands edge; it replaces the unit file in
commit B.)

Undo, kalandra's `main` never rewritten: a revert of the merged commits, then the old jar:

```bash
cd ~/Projects/kalandra
git revert --no-edit "$(cat $P/before/kalandra-head)"..HEAD
cp $P/before/serve.jar out/assembly.dest/out.jar
```

## 7. Start the new serve

```bash
cd ~/Projects/kalandra && bin/kalandra start
tail -3 logs/serve.log      # commands: serving on http://127.0.0.1:7317
bin/kalandra status         # commands answer at 127.0.0.1:7317
```

Undo: `bin/kalandra stop`, step 6's undo, then `bin/kalandra start`.

## 8. Re-make the claims

A holder is now `local/<directory>-<8 hex>` (and an agent's `…/<type>/<12 hex>`), so every claim
`claims` listed at step 2 is made again for the holder `bin/kalandra holder` prints. A session's
claim names the sha1 of its directory; each is re-made for that directory's new holder here. An
agent's claim (a holder with a `/` in it) belonged to a session that has ended: it is re-made from
the new session at step 9.

```bash
cd ~/Projects/kalandra
declare -A dir_of
while read -r d; do dir_of[$(printf %s "$d" | sha1sum | cut -c1-40)]="$d"; done \
  < <(git -C ~/Projects/grit worktree list --porcelain | sed -n 's/^worktree //p')
jq -r '.claims[] | [.key, .role, .holder] | @tsv' $P/before/claims.json |
  while IFS=$'\t' read -r key role old; do
    dir="${dir_of[$old]:-}"
    if [[ -z "$dir" ]]; then echo "at step 9: $key ($role), was $old"; continue; fi
    new="$(bin/kalandra holder "{\"dir\":\"$dir\"}")" &&
      bin/kalandra release "{\"key\":\"$key\"}" &&
      bin/kalandra claim "{\"key\":\"$key\",\"role\":\"$role\",\"holder\":\"$new\"}"
  done
bin/kalandra claims '{"project":"grit"}'
```

The last line lists each claim with its `local/…` holder.

Undo: release each claim made here, and claim it again for the holder `before/claims.json` names:

```bash
jq -r '.claims[] | [.key, .role, .holder] | @tsv' $P/before/claims.json |
  while IFS=$'\t' read -r key role old; do
    bin/kalandra release "{\"key\":\"$key\"}"
    bin/kalandra claim "{\"key\":\"$key\",\"role\":\"$role\",\"holder\":\"$old\"}"
  done
```

(That undo is only meaningful after a rollback to the old serve, which reads the old holders.)

## 9. New sessions, not resumed ones

Start a new `claude` in grit's checkout (and in each worktree joined at step 3). Not `claude
--resume`, and not `/compact`: a session keeps the holder its first event named, so a session
whose first event came before the switch would keep the old one. In an open session, `/clear`
also starts a new session id. In each new session, `/mcp` lists `kalandra` connected.

An agent's claim set aside at step 8 is made again from the session that starts the agent, with
the holder its opening's agent line prints, or in that session's Bash:

```bash
~/Projects/kalandra/bin/kalandra holder '{"type":"coder-opus"}'   # its session id from CLAUDE_CODE_SESSION_ID
```

Undo: none.

## 10. What to check

In a new session in grit's checkout:

- its start's context holds the manager note's brief, and the line `grit: this session's claim
  holder is local/grit-…`, the holder `bin/kalandra claims '{"project":"grit"}'` shows for it;
- `mcp__kalandra__project_show` of a key answers, and `~/Projects/kalandra/bin/kalandra list
  '{"project":"grit","state":"now"}'` answers;
- after one edit and one commit, `/compact`: the digest at the compaction's start shows them;
  `bin/kalandra wait '{"commit":{"holder":"local/grit-…"},"within":60}'` started before a commit
  answers `committed …`;
- an agent claimed for the holder the opening's agent line prints gets its brief at
  SubagentStart;
- a keep of the claimed manager note: the next `/clear`'s brief shows the new text (the brief
  follows every keep, in grit);
- `ls ~/.local/state/kalandra/spool` stays empty, and nothing new appears under `briefs/`.

## 11. The process line

`grit/roadmap-how-work-is-run`'s coder start names `bash ~/Projects/kalandra/hooks/brief-hook.sh
--slot coder-opus`; it becomes the holder command the session's opening prints (`'<hook>'
holder coder-opus --dir … --session …`), or `~/Projects/kalandra/bin/kalandra holder
'{"type":"coder-opus"}'`. Undo: keep the previous version back.

## 12. Held

After a day of work, once the switch has held: commit B on `commands-edge` deletes the old
edge, `kalandra.capture`, `hooks/` and their tests, and moves
`ops/systemd/kalandra-serve.service.next` over the unit file; it is merged, built and restarted
(`bin/kalandra build && bin/kalandra restart`, then `systemctl --user daemon-reload` for the
unit's new `Description`). Then the old state goes, by name, never `rm -r` of the parent:

```bash
S=~/.local/state/kalandra
rm -f -- "$S"/briefs/*.md "$S"/spool/quarantine/*
rmdir "$S"/spool/quarantine 2>/dev/null; rmdir "$S"/spool "$S"/briefs "$S"
```

`rmdir` refuses a directory that still holds something: what it names is looked at, not
removed. Then `ops/restore-test.sh` once (it keeps `restore-check` in the live database, as it
always has). `$P/before` is kept a week.

## The rollback, from any step before 12

1. `cd ~/Projects/kalandra && bin/kalandra stop`.
2. When step 6 ran: its undo (the revert, then the old jar).
3. `bin/kalandra start`: the log says `claude-code: serving commands on http://127.0.0.1:7317`
   and `claude-code: recording the sessions spooled in …`.
4. When step 3 ran: its undo, in grit's checkout and each worktree joined.
5. When step 8 ran: its undo.
6. New sessions, as at step 9.

Events the new serve recorded stay in the store: they are grit's session record, which the old
code reads too. The new serve wrote no files, so nothing else needs undoing. What cannot be
undone: a claim's lease that lapsed while the switch was under way (two hours).
