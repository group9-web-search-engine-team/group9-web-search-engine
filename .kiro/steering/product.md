---
inclusion: always
---

# Product Overview

Build a practical, general-purpose Web search engine for a university project. The full target includes crawling, quality-aware frontier, document/graph storage, distributed inverted indexing, sparse+dense retrieval, deterministic least-squares ranking and BERT reranking.

## Immediate deliverable
A tested crawler vertical slice using Apache StormCrawler, existing S3 storage for raw HTML, and PostgreSQL for metadata, crawl state and directed Web graph.

## Success criteria
Read seeds from S3; fetch permitted pages; extract links; deduplicate and persist frontier state; store raw HTML in S3; store metadata and graph edges in PostgreSQL; respect robots and host politeness; expose useful logs/counters; pass tests and document a reproducible run.

## Architecture boundaries
- PostgreSQL is not the inverted-index store.
- QT5-Small/QMax/soft focus/distillation are later frontier-quality work.
- Hadoop/SPIMI, PageRank, query processing, DPO/dense retrieval, ranking and BERT reranking are later phases.
- SPRAWL is excluded.
- DPO is offline training, not runtime query rewriting.
- BEIR TREC-COVID is an independent benchmark, not the crawler seed corpus.

## Known state
S3 bucket `web-search-engine-group9-2026-corp`, seed object `seeds/seed_urls.txt`, and test object `crawl/s3-test.txt` are reported ready. AWS region, permissions, repository state and PostgreSQL target remain unverified. Preserve the separate SOP workspace.
