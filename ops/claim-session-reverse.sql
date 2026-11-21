-- The rollback of claim-session.sql on grit_kalandra: drops the constraint and both columns it
-- adds to grit.claims, and nothing else, so that a jar whose claims name no session serves the
-- database again. Run it with serve stopped, before that jar's serve starts on the database.
--
-- It holds no COMMIT: run as it is, it prints what it would change and rolls back as psql
-- disconnects; with COMMIT after it, it keeps what it printed.
--
--   psql -q -v ON_ERROR_STOP=1 -d grit_kalandra -f - < claim-session-reverse.sql            (dry run)
--   psql -q -v ON_ERROR_STOP=1 -d grit_kalandra -f - -c COMMIT < claim-session-reverse.sql  (the run)
--
-- It counts 1 under `grit.claims session columns dropped` while either column is there, and under
-- `grit.claims sessions forgotten` each claim a session was named for, which is lost: the
-- older jar's claims name none. No claim is deleted. A second run changes nothing.
BEGIN;
SET LOCAL client_min_messages = warning;

CREATE TEMP TABLE claim_session_reverse (what TEXT PRIMARY KEY, n BIGINT NOT NULL) ON COMMIT DROP;

INSERT INTO claim_session_reverse
SELECT 'grit.claims session columns dropped', CASE WHEN count(*) = 0 THEN 0 ELSE 1 END
  FROM information_schema.columns
 WHERE table_schema = 'grit' AND table_name = 'claims'
   AND column_name IN ('session_harness', 'session_local');

-- Read through the row's JSON, so that it reads no column that may not be there.
INSERT INTO claim_session_reverse
SELECT 'grit.claims sessions forgotten', count(*)
  FROM grit.claims c
 WHERE to_jsonb(c) ->> 'session_harness' IS NOT NULL OR to_jsonb(c) ->> 'session_local' IS NOT NULL;

ALTER TABLE grit.claims DROP CONSTRAINT IF EXISTS claims_session;
ALTER TABLE grit.claims DROP COLUMN IF EXISTS session_harness;
ALTER TABLE grit.claims DROP COLUMN IF EXISTS session_local;

SELECT what, n FROM claim_session_reverse ORDER BY what;
