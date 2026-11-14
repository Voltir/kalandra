-- The rollback of grit's machine-from-worker.sql on grit_kalandra: rewrites every stored place
-- under the key `machine` back to `worker` (`machine:local/home/a` becomes `worker:local/home/a`),
-- in the same tables and columns the forward file rewrites, and nothing else, so that the grit
-- 0.2.0 jar, which reads places under `worker` alone, serves the database again. It covers the
-- rows the 0.3.0 serve wrote too: every keep, claim and event since the switch. Run it with
-- serve stopped, before 0.2.0's serve starts on the database. Delete this file once the switch
-- has held.
--
-- It holds no COMMIT: run as it is, it prints what it would change and rolls back as psql
-- disconnects; with COMMIT after it, it keeps what it printed.
--
--   psql -q -v ON_ERROR_STOP=1 -d grit_kalandra -f - < worker-from-machine.sql            (dry run)
--   psql -q -v ON_ERROR_STOP=1 -d grit_kalandra -f - -c COMMIT < worker-from-machine.sql  (the run)
--
-- `worker` is a byte shorter than `machine`, so every shape fits and nothing is deleted. What
-- the forward run dropped (a commit's subject, an event too long to rewrite) is not brought back.
--
-- It raises, changing nothing, when a shape names its worktree in a form other than the compact
-- one grit writes. A second run changes nothing.
BEGIN;

CREATE TEMP TABLE worker_from_machine (what TEXT PRIMARY KEY, n BIGINT NOT NULL) ON COMMIT DROP;

WITH changed AS (UPDATE grit.places SET path[1] = 'worker' WHERE path[1] = 'machine' RETURNING 1)
INSERT INTO worker_from_machine SELECT 'grit.places rewritten', count(*) FROM changed;

WITH changed AS (UPDATE grit.sessions SET place[1] = 'worker' WHERE place[1] = 'machine' RETURNING 1)
INSERT INTO worker_from_machine SELECT 'grit.sessions rewritten', count(*) FROM changed;

WITH changed AS (UPDATE grit.documents SET place[1] = 'worker' WHERE place[1] = 'machine' RETURNING 1)
INSERT INTO worker_from_machine SELECT 'grit.documents rewritten', count(*) FROM changed;

WITH changed AS (UPDATE grit.files SET place[1] = 'worker' WHERE place[1] = 'machine' RETURNING 1)
INSERT INTO worker_from_machine SELECT 'grit.files rewritten', count(*) FROM changed;

WITH changed AS (UPDATE grit.piece_sets SET place[1] = 'worker' WHERE place[1] = 'machine' RETURNING 1)
INSERT INTO worker_from_machine SELECT 'grit.piece_sets rewritten', count(*) FROM changed;

WITH changed AS (
       UPDATE grit.visibility_changes
          SET change = jsonb_set(change, '{room}',
                                 to_jsonb('worker:' || substr(change ->> 'room', length('machine:') + 1)))
        WHERE change ->> 'room' LIKE 'machine:%'
    RETURNING 1)
INSERT INTO worker_from_machine SELECT 'grit.visibility_changes rewritten', count(*) FROM changed;

-- Each shape naming a worktree under `machine`: its text with the one `"worktree":"machine:…"`
-- pair rewritten, the pair's value kept as written, escapes and all.
CREATE TEMP TABLE worker_shapes ON COMMIT DROP AS
SELECT session, seq, rewritten
  FROM (SELECT e.session, e.seq, e.shape::text AS was,
               replace(e.shape::text,
                       '"worktree":' || (e.shape -> 'worktree')::text,
                       '"worktree":"worker:' ||
                         substr((e.shape -> 'worktree')::text, length('"machine:') + 1)) AS rewritten
          FROM grit.session_events e
         WHERE e.shape ->> 'worktree' LIKE 'machine:%') s
 WHERE rewritten <> was;

DO $$
DECLARE
    named   BIGINT := (SELECT count(*) FROM grit.session_events WHERE shape ->> 'worktree' LIKE 'machine:%');
    matched BIGINT := (SELECT count(*) FROM worker_shapes);
BEGIN
    IF matched <> named THEN
        RAISE EXCEPTION '% shapes name a worktree under machine, but only % in the compact form grit writes',
            named, matched;
    END IF;
END $$;

WITH changed AS (
       UPDATE grit.session_events e SET shape = w.rewritten::json
         FROM worker_shapes w
        WHERE e.session = w.session AND e.seq = w.seq
    RETURNING 1)
INSERT INTO worker_from_machine SELECT 'grit.session_events rewritten', count(*) FROM changed;

SELECT what, n FROM worker_from_machine ORDER BY what;
