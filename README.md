# kalandra

[grit](https://github.com/Voltir/grit) is an LLM agent harness. kalandra is the deployment of
grit that grit is built with.

**grit plans itself with grit.** Its milestones, plans, checkpoints and notes are not files in a
repository; they are versioned artifacts in kalandra's Postgres, kept under a method that says
which states a piece of work moves through and who may move it. The coding sessions that build
grit, Claude Code today, join kalandra: each claims the work it takes, is briefed from the store
when it starts, and records what it did there. Nothing about the plan is carried in a
session's context or a person's memory.

**It is a deployment like any other.** kalandra lives outside grit's repository and builds
against grit's published jars, so it sees only what any deployment sees. Whatever grit needs in
order to coordinate its own development, it has to offer everyone.

**It is where grit's next step is tried first.** The aim is a repository whose coordination and
feature work both go through a grit deployment, with grit's own agents writing code beside
developer-driven harnesses. kalandra is the first one run that way.

It calls no model, so it spends nothing. Building, running and backing it up:
[docs/operating.md](docs/operating.md).
<!-- W7 probe: this pull request is open, then closed unmerged. -->
