# Requirements Document

## Introduction

This document specifies the requirements for the **Crawler MVP: StormCrawler + S3 raw HTML + PostgreSQL frontier/metadata/graph**, the first vertical slice of the general-purpose Web search engine described in `KIRO_MASTER_BRIEF.md` (section 3) and governed by the steering files in `.kiro/steering` (`product.md`, `tech.md`, `project-rules.md`, `structure.md`, `crawler-architecture.md`), which are the authority for this feature.

The feature delivers one coherent, tested end-to-end crawling slice: read seeds from the existing S3 seed object, maintain a persistent PostgreSQL frontier, fetch permitted pages with Apache StormCrawler under robots/politeness/budget controls, store raw HTML in S3, and persist document metadata, crawl state and directed graph edges in PostgreSQL. The crawler MUST use the actual Apache StormCrawler framework, not a hand-written crawler.

### Out of Scope (deferred to later milestones)

The following capabilities are explicitly **out of scope** for this feature and MUST NOT be implemented here. They are listed so reviewers understand the deliberate boundary:

- Inverted index construction, Hadoop MapReduce and SPIMI-style indexing.
- PageRank computation.
- QT5-Small / QMax query-agnostic page-quality scoring and frontier priority signals.
- Soft focus / distillation focused-crawling heuristics.
- Sparse retrieval, dense retrieval and vector indexing.
- Least-squares linear ranking and relevance-judgment weight learning.
- DPO offline synthetic-query training branch.
- BERT / TF-Ranking reranking.
- SPRAWL / query optimization.
- BEIR TREC-COVID benchmark ingestion. TREC-COVID is a separate benchmark corpus, not the crawl seed corpus, and MUST NOT be conflated with the crawled Web corpus.

Additionally, PostgreSQL MUST NOT be used as the inverted-index store at any point in this feature.

## Glossary

- **Crawler_System**: The complete StormCrawler-based crawler vertical slice defined by this document, including its topology, frontier, storage adapters, configuration and observability components.
- **StormCrawler**: Apache StormCrawler, the official crawling framework (https://stormcrawler.apache.org/docs/) that MUST be used as the crawling engine. "StormCrawler" in requirements refers to the actual framework and its native abstractions.
- **Topology**: The Apache Storm / StormCrawler processing graph (spouts and bolts) that schedules, fetches, parses and persists pages.
- **Seed_Reader**: The component that reads seed URLs from the S3 seed object (or a local fixture in tests).
- **Frontier**: The persistent crawl queue backed by the PostgreSQL `crawl_state` table, holding canonical URLs, state, priority, depth, parent, attempt counts, timestamps and error details.
- **URL_Normalizer**: The component that resolves relative URLs and produces a canonical URL form.
- **Fetcher**: The StormCrawler component that performs HTTP/HTTPS requests under timeout, size and safety controls.
- **Safety_Filter**: The component that enforces scheme, destination-address and page-type restrictions, including on redirect destinations.
- **Robots_Policy**: The robots.txt rules and crawl-delay directives retrieved and cached per host.
- **Link_Extractor**: The component that extracts outgoing links and content from fetched pages.
- **S3_Store**: The Amazon S3 storage adapter that writes raw HTML under the `crawl/raw/` prefix.
- **Metadata_Store**: The PostgreSQL storage adapter that persists the `documents`, `links` and `crawl_state` tables.
- **Run_Summary**: The end-of-run report of aggregate counters produced by the Crawler_System.
- **Canonical_URL**: The normalized, deduplicated URL form used as the uniqueness key in the database.
- **Crawl_Budget**: The configured set of limits (max pages, max depth, concurrency, host delay, max response bytes, connection/read timeouts, allowed MIME types, retry limit) that bound a crawl.
- **Seed_Bucket**: The S3 bucket `web-search-engine-group9-2026-corp`.
- **Seed_Object**: The S3 object `seeds/seed_urls.txt` within the Seed_Bucket.
- **Raw_Prefix**: The S3 key prefix `crawl/raw/` within the Seed_Bucket where raw HTML is stored.
- **Transient_Failure**: A recoverable fetch or storage failure (for example a timeout, connection reset, or HTTP 429/5xx) that is eligible for bounded retry.
- **Permanent_Failure**: A non-recoverable failure (for example HTTP 404/410, a disallowed URL, or a malformed URL) that MUST NOT be retried indefinitely.

## Requirements

### Requirement 1: Read seeds from the S3 seed object

**User Story:** As a search engine operator, I want the crawler to read its seed URLs from the existing S3 seed object, so that the crawl starts from the agreed, centrally managed seed list.

#### Acceptance Criteria

1. WHEN a crawl run starts, THE Seed_Reader SHALL read seed URLs from the Seed_Object `seeds/seed_urls.txt` in the Seed_Bucket `web-search-engine-group9-2026-corp`.
2. WHERE the run is configured for test mode, THE Seed_Reader SHALL read seed URLs from a configured local fixture file instead of the Seed_Object, applying the same parsing and validation rules as for the Seed_Object.
3. WHEN reading the seed source, THE Seed_Reader SHALL interpret each line as one candidate seed entry, SHALL trim leading and trailing whitespace from each line, and SHALL skip lines that are empty after trimming and lines whose first non-whitespace character is `#` without counting them as candidate seeds.
4. IF the configured seed source cannot be read, THEN THE Seed_Reader SHALL record an error that names the seed source and the failure cause, SHALL insert no seeds into the Frontier, and SHALL stop the run.
5. IF a candidate seed entry is not a well-formed URL using the HTTP or HTTPS scheme, THEN THE Seed_Reader SHALL skip that entry, SHALL increment a count of skipped invalid seeds, and SHALL continue processing remaining entries without stopping the run.
6. WHEN the seed source has been fully processed, THE Seed_Reader SHALL insert each valid, trimmed HTTP/HTTPS seed URL into the Frontier exactly once.
7. IF the seed source is read successfully but yields zero valid HTTP/HTTPS seed URLs, THEN THE Seed_Reader SHALL record an error indicating that no valid seeds were found in the named seed source, SHALL insert no seeds into the Frontier, and SHALL stop the run.

### Requirement 2: Insert eligible seeds into the persistent frontier

**User Story:** As a search engine operator, I want eligible seeds inserted into a persistent frontier, so that crawl progress survives restarts and is coordinated through durable storage.

#### Acceptance Criteria

1. THE `crawl_state` of a Frontier entry SHALL be one of the legal states {`unfetched`, `in_progress`, `fetched`, `failed`}, where `unfetched` is the initial state on insertion, `fetched` and `failed` are terminal states, and the legal transitions are `unfetched` → `in_progress`, `in_progress` → `fetched`, `in_progress` → `failed`, and `in_progress` → `unfetched` (for a scheduled retry).
2. WHEN a seed URL passes URL normalization and Safety_Filter checks, THE Frontier SHALL insert the Canonical_URL into the PostgreSQL `crawl_state` table with state `unfetched`, depth 0 and attempt count 0.
3. THE Frontier SHALL persist the crawl queue in the PostgreSQL `crawl_state` table such that the queue contents survive a restart of the Crawler_System process.
4. IF a seed Canonical_URL already exists in the `crawl_state` table, THEN THE Frontier SHALL retain the existing row unchanged, SHALL NOT insert an additional row, and THE Run_Summary SHALL count the seed as a duplicate.
5. WHEN inserting a frontier entry, THE Frontier SHALL record the Canonical_URL, state, a priority (integer in the range 0 to 1000), depth (non-negative integer), parent reference, attempt count (non-negative integer, initially 0), and created and last-updated timestamps in UTC.

### Requirement 3: Normalize and deduplicate URLs with database-enforced uniqueness

**User Story:** As a search engine operator, I want URLs normalized and deduplicated with uniqueness enforced in the database, so that the same page is not crawled or stored multiple times.

#### Acceptance Criteria

1. WHEN normalizing a URL, THE URL_Normalizer SHALL lowercase only the scheme and host components and SHALL preserve the case of the path and query components.
2. WHEN normalizing a URL, THE URL_Normalizer SHALL remove the fragment component, defined as the `#` character and all characters following it.
3. WHEN normalizing a URL, THE URL_Normalizer SHALL remove the port when it is the scheme default (80 for `http`, 443 for `https`) and SHALL retain any non-default port.
4. WHEN a link is relative to a base page, THE URL_Normalizer SHALL resolve the relative link against the base page URL into an absolute URL that includes a scheme and host.
5. THE URL_Normalizer SHALL preserve path and query parameters unless a configured normalization policy specifies their removal.
6. THE Metadata_Store SHALL enforce a uniqueness constraint on the Canonical_URL within the `crawl_state` table at the database level.
7. IF an insert would create a duplicate Canonical_URL in the database, THEN THE Metadata_Store SHALL reject that insert, SHALL leave the existing row unchanged, and SHALL return a uniqueness-violation indication to the caller.
8. FOR ALL URLs, normalizing a URL and then normalizing the resulting Canonical_URL again SHALL produce a byte-for-byte identical Canonical_URL (idempotent normalization).

### Requirement 4: Respect robots.txt, scope rules, host politeness and budgets

**User Story:** As a responsible crawler operator, I want the crawler to honor robots.txt, scope rules, host politeness and configured budgets, so that target sites are crawled lawfully and without overload.

#### Acceptance Criteria

1. WHEN the Crawler_System first schedules a URL for a host, THE Robots_Policy SHALL retrieve the host robots.txt and SHALL cache the parsed rules for the host for a configurable cache time-to-live (default 3600 seconds, range 60 to 86400 seconds).
2. IF the host robots.txt cannot be retrieved within the configured connection and read timeouts, or returns a server error response, THEN THE Robots_Policy SHALL treat the host as allow-all for the configured cache time-to-live and THE Run_Summary SHALL record the host as having an unavailable robots.txt.
3. IF a URL is disallowed by the host Robots_Policy, THEN THE Fetcher SHALL skip the URL, SHALL NOT issue any HTTP request for the URL, and THE Run_Summary SHALL increment the robots-exclusion count by one for that URL.
4. WHILE a host declares a crawl delay in the Robots_Policy, THE Fetcher SHALL wait at least the greater of the declared crawl delay and the configured host delay between successive requests to that host, where the configured host delay is a non-negative duration in seconds (default 1 second, range 0 to 60 seconds).
5. THE Fetcher SHALL limit concurrent in-flight requests to a single host to the configured per-host concurrency limit, which is an integer (default 2, range 1 to 10).
6. WHEN the number of successfully fetched pages reaches the configured maximum pages budget (an integer of at least 1), THE Crawler_System SHALL stop scheduling new fetches, SHALL allow already in-flight fetches to complete, and SHALL mark the run as complete once no fetches remain in flight.
7. IF a candidate link depth exceeds the configured maximum depth budget (a non-negative integer where the seed URL has depth 0), THEN THE Frontier SHALL exclude the link from scheduling and SHALL NOT add it to the queue.
8. WHEN issuing an HTTP request, THE Fetcher SHALL send a configurable non-empty User-Agent header value, and IF no User-Agent value is configured, THEN THE Crawler_System SHALL reject the run configuration before scheduling any fetch with an error indicating a missing User-Agent.

### Requirement 5: Fetch permitted HTTP/HTTPS pages safely

**User Story:** As a security-conscious operator, I want fetches restricted to safe HTTP/HTTPS destinations with enforced timeouts, MIME filtering and size caps, so that the crawler avoids unsafe targets and resource exhaustion.

#### Acceptance Criteria

1. IF a URL scheme is not exactly "http" or "https" (case-insensitive), THEN THE Safety_Filter SHALL reject the URL and THE Fetcher SHALL NOT issue any HTTP request for the URL.
2. IF a URL destination, after DNS resolution, resolves to any IP address in a loopback, link-local, private, or otherwise non-publicly-routable range, or to a known cloud metadata endpoint address, THEN THE Safety_Filter SHALL reject the URL and THE Fetcher SHALL NOT issue any HTTP request to that resolved address.
3. IF a URL is identified by the Safety_Filter as a login page, THEN THE Fetcher SHALL NOT issue any HTTP request for the URL and THE Run_Summary SHALL record the URL as skipped for being a login page.
4. WHEN establishing and reading an HTTP connection, THE Fetcher SHALL apply the configured connection timeout (default 10 seconds, range 1 to 60 seconds) and read timeout (default 30 seconds, range 1 to 120 seconds), and WHEN either timeout elapses THE Fetcher SHALL abort the request, discard any partial response, and record the URL as a timeout failure in the Run_Summary.
5. IF a response content type is not in the configured allowed MIME type list, THEN THE Fetcher SHALL discard the response body, SHALL NOT store the response, and SHALL record the URL as skipped for disallowed content type.
6. WHILE reading a response body, THE Fetcher SHALL stream the body and SHALL, once the cumulative bytes read reach the configured maximum response byte cap (default 10485760 bytes, range 1024 to 104857600 bytes), stop reading and discard the response, without relying on the Content-Length header alone to enforce the cap.
7. WHEN a fetch returns an HTTP redirect, THE Safety_Filter SHALL resolve the redirect destination via DNS and revalidate it against the scheme restriction in criterion 1 and the resolved-address restrictions in criterion 2 before the Fetcher follows the redirect.
8. IF a redirect destination fails Safety_Filter revalidation, THEN THE Fetcher SHALL NOT follow the redirect and SHALL record the URL as rejected for an unsafe redirect destination.
9. IF the number of redirects followed for a single original URL reaches the configured maximum redirect hop limit (default 5, range 0 to 20), THEN THE Fetcher SHALL stop following redirects, SHALL NOT store the response, and SHALL record the URL as a redirect-limit failure.

### Requirement 6: Extract content and outgoing links

**User Story:** As a search engine operator, I want the crawler to extract page content and outgoing links, so that documents can be stored and the Web graph can be expanded.

#### Acceptance Criteria

1. WHEN a permitted page is fetched successfully, THE Link_Extractor SHALL extract the page content from the response body.
2. WHEN a permitted page is fetched successfully, THE Link_Extractor SHALL extract outgoing links from the page, including their anchor text.
3. WHEN an outgoing link is extracted, THE URL_Normalizer SHALL resolve the link against the fetched page URL into an absolute Canonical_URL.

### Requirement 7: Add eligible unseen links to the frontier

**User Story:** As a search engine operator, I want eligible newly discovered links added to the frontier, so that the crawl expands to reachable pages within scope and budget.

#### Acceptance Criteria

1. WHEN an extracted link passes URL normalization, Safety_Filter and scope checks and does not exceed the maximum depth budget, THE Frontier SHALL insert the Canonical_URL into the `crawl_state` table with an unfetched state and a depth one greater than the parent page depth.
2. IF an extracted link Canonical_URL already exists in the `crawl_state` table, THEN THE Frontier SHALL NOT insert a duplicate row.
3. WHEN inserting a discovered link into the Frontier, THE Frontier SHALL record the parent page reference for the inserted link.

### Requirement 8: Store raw HTML in S3 with keys recorded in PostgreSQL

**User Story:** As a search engine operator, I want raw HTML stored privately in S3 under a deterministic key with the key recorded in PostgreSQL, so that documents are retrievable and linked to their metadata.

#### Acceptance Criteria

1. WHEN a permitted page is fetched successfully, THE S3_Store SHALL write the raw HTML body to the Seed_Bucket under the Raw_Prefix `crawl/raw/`.
2. WHEN writing a raw HTML object, THE S3_Store SHALL derive the object key deterministically from a stable document identifier or a collision-resistant content hash.
3. WHEN a raw HTML object is written, THE Metadata_Store SHALL record the S3 object key in the corresponding `documents` row.
4. THE S3_Store SHALL obtain AWS credentials through the AWS SDK standard credential provider chain and SHALL NOT use hardcoded credentials.
5. THE S3_Store SHALL use a configurable AWS region value that is verified before use rather than a guessed default.
6. THE S3_Store SHALL keep the Seed_Bucket private and SHALL NOT grant public access to stored objects.

### Requirement 9: Persist documents, crawl state and graph edges in PostgreSQL

**User Story:** As a search engine operator, I want document metadata, crawl state and directed link edges persisted in PostgreSQL, so that the Web graph and crawl history are durable and queryable.

#### Acceptance Criteria

1. WHEN a permitted page is fetched successfully, THE Metadata_Store SHALL persist a `documents` row recording at least the Canonical_URL, HTTP status, content type, content length, content hash, S3 key and crawl timestamp.
2. WHEN a page fetch completes, THE Metadata_Store SHALL update the page `crawl_state` row to a terminal fetched or failed state with the attempt count and last attempt timestamp.
3. WHEN outgoing links are extracted from a fetched page, THE Metadata_Store SHALL persist a directed `links` edge from the source page to each destination Canonical_URL, recording anchor text and a discovery timestamp.
4. WHERE a destination Canonical_URL has been discovered but not yet fetched, THE Metadata_Store SHALL persist the `links` edge referencing the destination Canonical_URL without requiring an existing fetched `documents` row for the destination.
5. THE Metadata_Store SHALL define foreign key constraints and indexes across the `documents`, `links` and `crawl_state` tables through versioned database migrations.
6. THE Metadata_Store SHALL NOT store inverted-index data in PostgreSQL.

### Requirement 10: Provide structured logs, counters and a run summary

**User Story:** As a search engine operator, I want structured logs, counters and an end-of-run summary, so that I can verify crawl behavior and measure yield.

#### Acceptance Criteria

1. WHILE a crawl run is active, THE Crawler_System SHALL emit structured log records for seed reads, fetch attempts, fetch outcomes, robots exclusions, storage writes and errors.
2. WHEN a crawl run completes, THE Run_Summary SHALL report the count of seeds, fetch attempts, fetch successes, fetch failures, duplicates rejected, robots exclusions, discovered links, S3 writes, database writes, bytes stored and graph edges created.
3. WHEN recording a fetch outcome, THE Crawler_System SHALL log the HTTP status, content type, final URL, content length, elapsed fetch time and any error detail for the fetch.

### Requirement 11: Prevent duplicate processing across repeated runs

**User Story:** As a search engine operator, I want repeated runs to avoid reprocessing already-crawled pages, so that reruns are idempotent and do not duplicate stored data.

#### Acceptance Criteria

1. WHEN a crawl run encounters a Canonical_URL already in a fetched state in the `crawl_state` table, THE Crawler_System SHALL skip refetching the URL within the same configured recrawl policy window and THE Run_Summary SHALL count the URL as a duplicate.
2. WHEN a crawl run is repeated over the same seeds under an unchanged configuration, THE S3_Store SHALL NOT create additional duplicate raw HTML objects for pages already stored.
3. WHEN a crawl run is repeated over the same seeds under an unchanged configuration, THE Metadata_Store SHALL NOT create duplicate `documents` rows for pages already recorded.

### Requirement 12: Bounded retries for transient failures

**User Story:** As a search engine operator, I want transient failures retried with bounded backoff and permanent failures abandoned, so that the crawler recovers from blips without looping forever.

#### Acceptance Criteria

1. IF a fetch results in a Transient_Failure, THEN THE Fetcher SHALL schedule a retry using exponential backoff with randomized jitter.
2. WHEN the recorded attempt count for a URL reaches the configured retry limit, THE Fetcher SHALL stop retrying the URL and THE Metadata_Store SHALL mark the URL `crawl_state` row as failed.
3. IF a fetch results in a Permanent_Failure, THEN THE Fetcher SHALL NOT retry the URL and THE Metadata_Store SHALL mark the URL `crawl_state` row as failed with the failure detail.
4. WHEN scheduling a retry, THE Frontier SHALL record the incremented attempt count and the next attempt timestamp for the URL.

### Requirement 13: Configurable crawl budgets with conservative defaults

**User Story:** As a search engine operator, I want all crawl limits configurable with conservative defaults, so that crawls stay bounded and safe without manual tuning.

#### Acceptance Criteria

1. THE Crawler_System SHALL expose configurable values for maximum pages, maximum depth, concurrency, per-host delay, maximum response bytes, connection timeout, read timeout, allowed MIME types and retry limit.
2. WHERE a Crawl_Budget value is not supplied in configuration, THE Crawler_System SHALL apply a conservative built-in default value for that setting.
3. THE Crawler_System SHALL read Crawl_Budget values from configuration or environment at run start and SHALL apply the resolved values for the duration of the run.

### Requirement 14: Use actual Apache StormCrawler as the crawling framework

**User Story:** As a project stakeholder, I want the crawler built on actual Apache StormCrawler, so that the deliverable matches the proposal architecture rather than a mislabeled hand-written crawler.

#### Acceptance Criteria

1. THE Crawler_System SHALL perform scheduling, fetching, parsing and link extraction through an Apache StormCrawler Topology using StormCrawler native abstractions.
2. THE Crawler_System SHALL pin verified-compatible versions of StormCrawler, Apache Storm, the Java toolchain, the build tool, the database driver and the AWS SDK before implementation.
3. THE Crawler_System SHALL target a Java 17 LTS toolchain for the StormCrawler build and runtime, because stable StormCrawler and Storm releases target Java 11 or 17 LTS and the locally installed JDK 24 is not a supported target.
4. THE Crawler_System SHALL build with a reproducible build tool configuration, using either a system-installed Maven or a committed Maven Wrapper, because Maven is not currently installed in the target environment.
5. WHERE a custom Frontier or storage adapter is introduced instead of a native StormCrawler component, THE Crawler_System SHALL document the justification for the custom adapter and SHALL provide tests covering the custom adapter.

### Requirement 15: Partial-failure recovery across S3 and PostgreSQL

**User Story:** As a search engine operator, I want partial failures between S3 and PostgreSQL to be recoverable, so that no page is left silently inconsistent, without relying on cross-service atomicity.

#### Acceptance Criteria

1. THE Crawler_System SHALL NOT claim or rely on atomic transactions spanning both S3 and PostgreSQL.
2. IF the raw HTML write to S3 succeeds but the corresponding PostgreSQL metadata write fails, THEN THE Crawler_System SHALL record the inconsistency and SHALL leave the URL in a state that allows a later run to complete the missing metadata write.
3. IF the PostgreSQL metadata write succeeds but the raw HTML write to S3 fails, THEN THE Crawler_System SHALL mark the page as not durably stored and SHALL leave the URL eligible for a later retry of the S3 write.
4. WHEN a subsequent run encounters a page recorded as partially stored, THE Crawler_System SHALL reconcile the page to a consistent stored or failed state.

### Requirement 16: Tests and honest verification reporting

**User Story:** As a project stakeholder, I want unit and integration tests with documented setup and honest status reporting, so that crawl behavior is verified and unrun tests are never claimed as passing.

#### Acceptance Criteria

1. THE Crawler_System SHALL provide unit tests covering URL normalization, Safety_Filter decisions, retry/backoff scheduling and the Run_Summary counters.
2. THE Crawler_System SHALL provide integration tests covering the end-to-end slice: seed read, frontier insert, controlled fetch, link extraction, duplicate rejection, S3 raw HTML write, PostgreSQL persistence and repeat-run deduplication.
3. THE Crawler_System SHALL provide documented exact setup, run and verification steps for executing the unit and integration tests.
4. IF a test cannot run because required AWS or PostgreSQL configuration is missing, THEN THE test report SHALL mark the test as failed, SHALL state the precise missing-configuration blocker, and SHALL NOT mark the test as passed.
