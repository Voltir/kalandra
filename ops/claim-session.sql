-- grit's claim-session.sql as a data file (ops/data.tsv): adds to grit.claims the session a claim
-- is worked by, `session_harness` and `session_local`, with the CHECKs grit's schema.sql creates,
-- for the build whose claims name their session. Its statements are grit's
-- core/dbos/it/resources/claim-session.sql's, at the commit the jar is built against, which
-- ops/edge-proof.sh checks; around them, only its counts.
--
-- It holds no COMMIT: run as it is, it prints what it would change and rolls back as psql
-- disconnects; with COMMIT after it, it keeps what it printed.
--
--   psql -q -v ON_ERROR_STOP=1 -d grit_kalandra -f - < claim-session.sql            (dry run)
--   psql -q -v ON_ERROR_STOP=1 -d grit_kalandra -f - -c COMMIT < claim-session.sql  (the run)
--
-- It counts 1 under `grit.claims session columns added` while either column is missing, and 0 once
-- both are there. No claim is deleted or rewritten: each is left with no session, for its holder's
-- next session to take, and `grit.claims deleted` counts 0. A second run changes nothing.
BEGIN;
SET LOCAL client_min_messages = warning;

CREATE TEMP TABLE claim_session (what TEXT PRIMARY KEY, n BIGINT NOT NULL) ON COMMIT DROP;

INSERT INTO claim_session
SELECT 'grit.claims session columns added', CASE WHEN count(*) = 2 THEN 0 ELSE 1 END
  FROM information_schema.columns
 WHERE table_schema = 'grit' AND table_name = 'claims'
   AND column_name IN ('session_harness', 'session_local');

INSERT INTO claim_session SELECT 'grit.claims before', count(*) FROM grit.claims;

ALTER TABLE grit.claims ADD COLUMN IF NOT EXISTS session_harness TEXT CHECK (session_harness ~ '^[a-z][a-z0-9-]{0,63}$');
ALTER TABLE grit.claims ADD COLUMN IF NOT EXISTS session_local TEXT CHECK (length(session_local) BETWEEN 1 AND 200);
ALTER TABLE grit.claims DROP CONSTRAINT IF EXISTS claims_session;
ALTER TABLE grit.claims ADD CONSTRAINT claims_session CHECK ((session_harness IS NULL) = (session_local IS NULL));

INSERT INTO claim_session
SELECT 'grit.claims deleted', (SELECT n FROM claim_session WHERE what = 'grit.claims before') - count(*)
  FROM grit.claims;
DELETE FROM claim_session WHERE what = 'grit.claims before';

SELECT what, n FROM claim_session ORDER BY what;
