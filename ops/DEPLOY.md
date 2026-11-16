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
- When the proof prints a `note` line, the installed `claude` or the join script is not the one
  `ops/harness-check.sh` last passed under: run that check by hand (see "No model call").
- End every Claude Code session in grit's checkout and its worktrees, except the one running the
  deploy. A session's hooks record nothing while serve is stopped.
- Make sure no `bin/kalandra chat` or `work` is running. Deploy refuses while anything else is
  connected to the database.

## What it refuses

It refuses before changing anything, and says why, exiting 1, when:

- the proof did not pass, its files do not match their sha256, or it ran on uncommitted changes;
- the proof took longer than its budget, or its record does not say how long it took;
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

## Five minutes

A proof and the deploy after it take five minutes at most: 240 s for the proof (`proof_budget` at
the top of `ops/edge-proof.sh`) and 60 s for the deploy (`deploy_budget` at the top of
`ops/deploy.sh`). Each prints every stage's seconds.

- The proof fails itself at the first stage that ends past its budget, with a last line naming its
  three slowest stages, and its pass record says how long it took. Deploy refuses a record over
  the budget the proof names at the proved commit. A proof that comes near its budget is cut, not
  given a larger budget.
- A deploy past its budget is not rolled back for it, since a deploy that is slow but healthy
  costs less than the outage a rollback would cause. It ends as it would otherwise, with the line
  before the last saying `deploy: over its 60 s budget: it took <n> s; held, not rolled back`, and a
  held deploy then exits 4, not 0. Read the log's stage times and cut the slow one.

`KALANDRA_PROOF_BUDGET` and `KALANDRA_DEPLOY_BUDGET` may lower either budget, never raise it; the
proof lowers the deploy's to see the line.

## No model call

Neither the proof nor the deploy calls a model. The proof checks Claude Code's MCP client by
`claude mcp get`, which calls none, with no login, a key that is no key, and a model's base URL
and every proxy pointed at a local port that refuses all it is sent and must be sent nothing.

`ops/harness-check.sh` is the one check that calls a model: a real `claude -p` turn on Haiku that
calls a kalandra tool through the join's MCP entry, about a third of a cent. It is never a gate.
Run it by hand when the join script changes or `claude --version` changes, as the proof's `note`
line says; a pass records both in `~/.local/state/kalandra-harness-check/passed.txt`.

## Where the log is

`~/.local/state/kalandra-deploy/runs/<stamp>/deploy.log`, whose path the command prints first.
Each stage's line says the seconds it took. The last line says how the run ended, and ends with the
run's seconds and those serve was down (from its stop until it answered again):

- `deploy: held: …` (exit 0, or 4 over its budget)
- `deploy: rolled back: <why>; left as it began: …` (exit 2)
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
