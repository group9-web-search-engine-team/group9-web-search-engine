-- Migration V2: add the QMax quality-propagation column to documents.
--
-- QMax(u) = max(Q(p)) over all known parent pages p linking to u, where Q(p) is the raw
-- QT5-Small quality score (a log-probability, <= 0, higher/closer-to-zero = better).
-- QMax is a propagated PARENT-derived signal, kept DISTINCT from:
--   * quality_score : the destination page's own QT5 score (already present)
--   * pagerank      : link-graph centrality (already present, unused by this task)
--
-- Idempotent and additive: ADD COLUMN IF NOT EXISTS does not touch existing rows/columns
-- and is safe to re-run. Owned by the table owner (run as an admin/owner role; the
-- read/write crawler role intentionally cannot ALTER).
--
-- Apply (PowerShell, from repo root):
--   $env:PGPASSWORD = "<admin password>"
--   & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -d websearch -h localhost `
--       -f db/migrations/V2__add_qmax_score.sql

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS qmax_score DOUBLE PRECISION;

COMMENT ON COLUMN documents.qmax_score IS
    'QMax(u) = max QT5 quality_score over known parent pages linking to this URL. '
    'Propagated parent-derived signal; distinct from quality_score and pagerank.';
