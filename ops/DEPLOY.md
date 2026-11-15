# Deploying a new build

One command moves the live serve (this checkout, `127.0.0.1:7317`, `grit_kalandra`) to a build
that `ops/edge-proof.sh` has proved, and rolls itself back if anything fails:

```bash
bin/kalandra deploy ~/.cache/kalandra-e7
```

The argument is the proof's directory. Run it in this checkout. When this checkout does not yet
hold `ops/deploy.sh`, run it from the proved checkout and name this one:
`<proved checkout>/bin/kalandra deploy --checkout ~/Projects/kalandra <proof dir>`.

It makes no build. It installs the proved commit and the proved jar, makes any credential that is
missing, runs each data file in `ops/data.tsv` that has rows to rewrite, starts serve, joins every
joined grit checkout again, and checks serve's health. It then moves the checkout's branch to the
proved commit. The script's header, `ops/deploy.sh`, says each step and what each one refuses.

## Before

- Prove the commit: commit everything, then run `bin/kalandra build` and `ops/edge-proof.sh`.
  Deploy runs only the commit `proof.txt` names, and only when the proof ends `edge-proof: passed`.
- End every Claude Code session in grit's checkout and its worktrees, except the one running the
  deploy. A session's hooks record nothing while serve is stopped.
- Make sure no `bin/kalandra chat` or `work` is running. Deploy refuses while anything else is
  connected to the database.

## What it refuses

It refuses before changing anything, and says why, exiting 1, when:

- the proof did not pass, its files do not match their sha256, or it ran on uncommitted changes;
- the checkout has changes, or is not at the proved commit or an ancestor of it;
- serve is not answering, or the nightly backup is running;
- serve's systemd unit is installed but `systemctl --user` cannot be reached, or the unit runs
  without being enabled;
- a credential and its digest in `.env` disagree, or one exists without the other;
- another deploy is running, or the last one did not end.

Once it has started, it rolls back and exits 2 when:

- anything else is connected to the database;
- a workflow is pending while a data file has rows to rewrite;
- a data file would delete a row or drop a commit's subject (a person decides);
- any later step fails.

A rollback reverses each data file it ran. When a reverse raises, the rollback restores the dump it
took into `grit_kalandra_restoring`, and only then renames `grit_kalandra` aside, as
`grit_kalandra_rolled_back_<stamp>`, and the restored one into its place. The aside database is a
person's to drop once satisfied, and the last line names it. When the database cannot be put
right, the run ends `rollback incomplete` with serve stopped, the old code and jar in place, and
the unit disabled, so nothing starts serve on it.

Every step that waits has a bound: a Postgres statement or data file 300 s, the dump or a restore
900 s, a stop 120 s, a start until serve answers 130 s, a join 120 s.

## Where the log is

`~/.local/state/kalandra-deploy/runs/<stamp>/deploy.log`, whose path the command prints first.
Each stage's line says the seconds it took. The last line says how the run ended, and ends with the
run's seconds and those serve was down (from its stop until it answered again):

- `deploy: held: …`
- `deploy: rolled back: <why>; left as it began: …`
- `deploy: rollback incomplete: …; a person must …` (exit 3). This line says what to do, and the
  next deploy refuses until it is done.

The run is detached, so it goes on if the terminal or session that started it ends. The command
follows it until it ends; `--detach` returns as soon as it starts. The three newest runs are kept,
each with what it backed up and its dump.

## After

- Start new Claude Code sessions in the joined checkouts. In a session that stayed open, run
  `/mcp` to connect kalandra again: its MCP connection ended when serve stopped.
- Claims and their holders are unchanged.
- After a rollback, the credentials it made and the digest lines it added to `.env` stay: the old
  serve ignores them, and the next deploy uses them.
