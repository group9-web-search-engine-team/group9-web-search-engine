---
inclusion: always
---

# Repository Structure

Inspect existing files and Git state before creating or moving anything. Preserve the separate SOP workspace.

Use the official layout/conventions for the verified StormCrawler version; do not force a different language or build system. A sensible organization may include:
- `README.md`
- `.env.example` (placeholders only)
- `docker-compose.yml` for necessary local services only
- `config/` for topology and crawler settings
- `db/migrations/` for schema migrations
- `src/main/...` for topology extensions/adapters
- `src/test/...` for unit/integration tests
- `scripts/` for repeatable setup/verification
- `docs/` for architecture decisions
- `.kiro/steering/` for persistent project guidance

Raw response bodies go to S3. PostgreSQL holds metadata, frontier state and graph edges. Store each object's S3 key in metadata. Document recovery from partial S3/database failures.

Workflow: inspect environment → verify versions → create spec → implement coherent vertical slice → compile/test → run bounded crawl → independently verify S3 and PostgreSQL → document limitations and cleanup.
