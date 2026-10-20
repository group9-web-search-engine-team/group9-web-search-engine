-- Migration V3: additive columns for paper-faithful soft-focus ordering and topic
-- distillation. Additive and idempotent (ADD COLUMN IF NOT EXISTS); does not touch
-- existing data, the QMax path, quality_score, qmax_score, pagerank, or ownership.
--
-- Soft focus (Chakrabarti, van den Berg & Dom, 1999): the crawl frontier is ordered
-- (numtries ASC, R DESC). We track "numtries" (total fetch attempts, successful or not)
-- in crawl_state.attempt_count, incremented once per claim. R (relevance) is our existing
-- QMax-based crawl_state.priority.
--
-- Distillation: a hub-promotion boost is stored SEPARATELY in crawl_state.distill_boost so
-- that a normal QMax priority update never erases a valid distillation promotion. The
-- effective relevance used for ordering is (priority + distill_boost); attempt_count still
-- dominates, so a promotion can never let a higher-attempt URL overtake a lower-attempt one.
--
-- Apply (PowerShell, from repo root):
--   $env:PGPASSWORD = "<admin password>"
--   & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -d websearch -h localhost `
--       -f db/migrations/V3__add_softfocus_and_distillation.sql

ALTER TABLE crawl_state
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE crawl_state
    ADD COLUMN IF NOT EXISTS distill_boost DOUBLE PRECISION NOT NULL DEFAULT 0;

COMMENT ON COLUMN crawl_state.attempt_count IS
    'Soft focus "numtries": total fetch attempts (success or failure), incremented once '
    'per claim. Frontier orders by attempt_count ASC first.';
COMMENT ON COLUMN crawl_state.distill_boost IS
    'Topic-distillation hub-neighbour promotion added to the relevance (priority) term for '
    'frontier ordering. Separate from priority/qmax so a QMax update does not erase it.';

-- Match the claim ordering so the planner can use an index for the hot path.
CREATE INDEX IF NOT EXISTS idx_crawl_state_softfocus
    ON crawl_state (state, attempt_count ASC, priority DESC);
