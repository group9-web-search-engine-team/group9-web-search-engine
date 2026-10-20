---
inclusion: auto
name: crawler-architecture
description: Use for crawler planning, implementation, testing or deployment, including StormCrawler topology, frontier, S3 persistence, PostgreSQL metadata and Web graph.
---

# Crawler Architecture

## Flow
S3 seed object → seed parsing → persistent frontier → StormCrawler topology → robots/politeness/filtering → fetch → content/link extraction → raw HTML to S3 → metadata/state/link edges to PostgreSQL → eligible links back to frontier.

Use StormCrawler's native abstractions and compatible modules where possible. Verify the selected version's APIs before adding custom adapters.

## Frontier
Persist canonical URL, state, priority, depth, parent, attempts, timestamps and error details as needed. Enforce uniqueness in the database. Define legal states/transitions, bounded retries and safe concurrent claims. Do not create a central master that manually forwards every URL.

## URL handling
Resolve relative links; restrict schemes to HTTP/HTTPS; normalize scheme/host case and default ports; remove fragments; preserve meaningful path/query data. Do not strip query parameters without an explicit policy. Reject malformed URLs and block localhost, private/internal ranges and metadata endpoints. Revalidate redirects.

## Politeness and fetching
Check/cache robots policy, respect disallowed paths and applicable crawl delays, use conservative per-host concurrency/rate limits, and use an identifiable configurable User-Agent. Set connection/read timeouts. Stream and cap response bytes. Filter MIME types. Record status, content type, final URL, length, time and errors. Retry transient failures only with bounded exponential backoff and jitter.

## S3
Bucket `web-search-engine-group9-2026-corp`; raw prefix `crawl/raw/`. Use deterministic safe keys from stable IDs or hashes. Store key in PostgreSQL. Use SDK credential chain and verified configurable region. Keep bucket private. Design recovery for partial writes; do not claim cross-service atomicity.

## PostgreSQL
Represent documents, frontier/crawl state and directed links with suitable constraints, foreign keys and indexes. Accommodate discovered destination URLs that have not been fetched yet. Keep the inverted index elsewhere.

## Budget and observability
Configurable max pages, depth, concurrency, host delay, max response bytes, timeout and retry limit; conservative defaults. Report seeds, attempts, successes, failures, duplicates, robots exclusions, discovered links, S3 writes and DB writes.

## Acceptance test
Read a known seed object (local fixture allowed in tests); schedule and fetch a permitted controlled page; extract links; reject duplicates/disallowed links; store raw HTML in a deterministic S3 key; persist metadata/state/edges in PostgreSQL; rerun to show deduplication; stop at budget; show logs and test results.
