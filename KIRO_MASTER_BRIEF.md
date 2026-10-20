# Kiro Master Brief — General-Purpose Web Search Engine

## 1. Mission and source of truth
Build the general-purpose Web search engine described in the team's project proposal. The proposal is the source of truth for the target architecture. Do not silently replace required components with easier alternatives. If a requirement is infeasible, explain the blocker and propose an explicit trade-off.

The planned engine continuously crawls Web pages, assigns quality/priority signals, stores raw documents and structured metadata, builds a distributed inverted index, retrieves using sparse and dense retrieval, and ranks candidates with a deterministic least-squares ranker followed by a BERT-based reranker.

## 2. Planned system architecture
**Offline/continuous pipeline:** seed URLs → Apache StormCrawler → quality-aware frontier → S3 raw documents + PostgreSQL metadata/Web graph → Hadoop MapReduce/SPIMI-style index.

**Offline DPO branch:** documents → synthetic query generation → ranking-based preference pairs → DPO fine-tuning → synthetic query-document pairs/hard negatives → dense retriever → vector index.

**Online pipeline:** query → sparse retrieval + dense retrieval → candidate union → feature extraction → least-squares linear ranker → top 100 → BERT + TF-Ranking reranker → final top-N.

Important boundaries:
- SPRAWL/query optimization is excluded.
- DPO is offline synthetic-query training, not runtime query rewriting.
- BEIR TREC-COVID (proposal estimate: about 171K documents and 50 test queries) is a separate benchmark, not the crawler seed list or the crawler's own Web corpus.
- PostgreSQL is not the inverted-index store.
- Do not implement indexing, QT5-Small, PageRank, dense retrieval or BERT reranking before the crawler vertical slice works.

## 3. Immediate deliverable: end-to-end crawler vertical slice
Use Apache StormCrawler as the actual crawling framework. Do not build a hand-written crawler and claim it is StormCrawler.

The crawler must:
1. Read seeds from the existing S3 seed object.
2. Insert eligible seeds into a persistent frontier.
3. Normalize and deduplicate URLs.
4. Respect robots.txt, scope rules, host politeness and configured crawl budgets.
5. Fetch permitted HTTP/HTTPS pages with timeouts, content-type and response-size limits.
6. Extract content and outgoing links.
7. Add eligible unseen links to the frontier.
8. Store raw HTML under the bucket's `crawl/raw/` prefix.
9. Store document metadata, crawl state and directed graph edges in PostgreSQL.
10. Provide structured logs, counters and a run summary.
11. Prevent duplicate processing across repeated runs.
12. Pass unit/integration tests and document exact setup/run/verification steps.

“One blow” means one coherent implementation effort, not skipping dependency verification, tests, or end-to-end proof.

## 4. Current resources and state
S3 bucket: `web-search-engine-group9-2026-corp`
- Seeds: `seeds/seed_urls.txt`
- Test object: `crawl/s3-test.txt`
- Raw HTML target prefix: `crawl/raw/`

The seed file contains candidate home pages for Wikipedia, NASA, NOAA, WHO, UN, Nature, arXiv, BBC, Reuters and GitHub, according to the setup notes. These are candidates only; robots rules, site terms, host delay and crawl budgets must be respected.

Confirmed: S3 bucket and seed file exist; no crawler implementation has been confirmed; user has no locally installed AWS CLI; crawler workspace must be separate from SOP workspace.

Unknown: Kiro surface (desktop/web), repository contents, AWS region/permissions, Java/build/Docker availability, StormCrawler version/integration, PostgreSQL target/credentials. Verify these; never guess.

## 5. Storage contract
### S3
Raw HTML/document bodies only. Use stable document IDs or collision-resistant content identifiers for keys; record the key in PostgreSQL. Keep the bucket private. Never hardcode AWS keys. Use the AWS SDK standard credential provider chain and a configurable, verified region.

### PostgreSQL
Structured data, crawl state and graph. At minimum model:
- `documents(doc_id, url, canonical_url, crawl_time, status, http_status, content_type, content_length, content_hash, s3_key, title, quality_score, pagerank, ...)`
- `links(source_doc_id, destination_doc_id, anchor_text, discovered_at, ...)`
- `crawl_state(url, canonical_url, priority, state, depth, parent_url, last_attempt, next_attempt, attempts, last_error, ...)`

Exact schema is an implementation decision; document it, use migrations, constraints and indexes. Handle discovered destinations that have not yet been fetched. Do not claim atomic transactions across S3 and PostgreSQL; design recovery for partial failures.

## 6. Crawler design rules
- Verify official StormCrawler documentation and compatible versions of StormCrawler, Storm, Java, build tool, database driver and AWS SDK before coding; pin compatible versions.
- Prefer supported StormCrawler components. Any custom frontier/storage adapter must be justified and tested.
- Use the global frontier model; do not create a literal central master that forwards every URL to every worker.
- HTTP/HTTPS only. Reject malformed/unsafe URLs; do not crawl localhost, private/internal networks, cloud metadata endpoints, login pages or arbitrary protocols.
- Honor robots.txt, host delay and conservative per-host politeness.
- Configure maximum pages, depth, concurrency, host delay, response bytes, timeouts, allowed MIME types and retry limit.
- Normalize scheme/host case, remove fragments, normalize default ports and resolve relative URLs. Preserve meaningful query parameters unless an explicit policy says otherwise.
- Revalidate redirects for unsafe destinations.
- Use bounded exponential backoff with jitter. Do not retry permanent failures forever.
- Persist canonical URL uniqueness in the database; in-memory deduplication alone is insufficient.
- Stream/cap response bodies; do not trust Content-Length alone.
- Use structured logs and counters.
- Make S3/database partial failures recoverable.

## 7. Page quality and frontier priority — later milestone
The proposal uses QT5-Small for query-agnostic page-quality estimation, based on Pezzuti, MacAvaney and Tonellotto (2025).

Project-specific proposed rule:
`QMax(u) = max Q(p)` over known parent pages `p` linking to unseen URL `u`.

QMax is a project-specific hypothesis, not a claim that the paper defines this exact rule. Use soft focus inspired by Chakrabarti, van den Berg and Dom (1999): lower-quality pages must not be absolutely excluded. Distillation may promote useful hubs/access points. Add these only after basic frontier/storage works, or leave a clean interface for them.

## 8. Milestones from proposal
1. Week 1: crawler, seeds, frontier and storage.
2. Week 2: QT5-Small, QMax, soft focus/distillation and PostgreSQL graph.
3. Week 3: Hadoop/SPIMI index and query processing.
4. Week 4: PageRank, features, least-squares ranking and qrels.
5. Week 5: DPO synthetic-query branch and dense retrieval.
6. Week 6: BERT reranking, evaluation and demonstration.

This is the proposed sequence, not proof that milestones are complete. Prioritize the deadline and deliver a tested crawler first.

## 9. Later indexing/retrieval requirements
- Hadoop InputFormat splits documents.
- Mapper implements SPIMI-style blocks and flushes on memory limits.
- Shuffle partitions records by term.
- Reducers merge partial postings and write term-partitioned index files in HDFS or configured distributed/object storage, not PostgreSQL.
- Preserve docID-to-URL/metadata mapping and hit/position/field information where implemented.
- Boolean retrieval, proximity, k-grams/wildcards and spelling correction are later features.
- First-stage score: `Score(d,q) = Σ g_i(d,q) s_i`.
- Learn weights by squared error against relevance judgments; sort deterministically.
- Send top 100 first-stage candidates to BERT + TF-Ranking reranker.

## 10. Evaluation
Crawler: attempted/successful/failed fetches, retries, unique discoveries, duplicates, robots exclusions, documents and bytes stored, graph edges, harvest-rate-style yield under a stated budget, throughput and storage.

Later retrieval/ranking: NDCG, MAP, Recall, Precision using benchmark queries/qrels; compare sparse/dense retrieval, linear ranking and BERT reranking on the same held-out data; report query latency and indexing throughput.

Do not conflate the Web corpus and BEIR TREC-COVID.

## 11. Papers cited in the proposal
- Brin & Page (1998), Anatomy of a Large-Scale Hypertextual Web Search Engine: https://research.google/pubs/the-anatomy-of-a-large-scale-hypertextual-web-search-engine/
- Chakrabarti, van den Berg & Dom (1999), Focused Crawling: https://www.cse.iitb.ac.in/~soumen/doc/www1999f/pdf/www1999f.pdf
- Pezzuti, MacAvaney & Tonellotto (2025), Neural Prioritisation for Web Crawling: https://arxiv.org/abs/2506.16146
- Bruch et al. (2020), A Stochastic Treatment of Learning to Rank Scoring Functions: https://research.google/pubs/a-stochastic-treatment-of-learning-to-rank-scoring-functions/
- Han, Wang, Bendersky & Najork (2020), Learning-to-Rank with BERT in TF-Ranking: https://arxiv.org/abs/2004.08476
- Lee et al. (2024), LTR methods in NLP: https://aclanthology.org/2024.findings-naacl.123/
- Thakur et al. (2021), BEIR: https://arxiv.org/abs/2104.08663
- Coelho, Martins, Magalhães & Xiong (2025), DPO for Web query generation: https://arxiv.org/abs/2505.19307

## 12. Mandatory Kiro workflow
Before coding:
1. Inspect repository, Git state and available tools.
2. Read this brief and all steering files.
3. Verify official StormCrawler documentation and compatible dependency versions.
4. Create measurable requirements, architecture/design and dependency-aware tasks.
5. Run requirements/contradiction analysis.
6. Identify unresolved decisions: region, credentials/IAM, PostgreSQL target, deployment target and integration choice.

During coding:
- Use pinned versions and actual framework APIs; inspect official docs and compile.
- Implement real integrations, not placeholder classes.
- Add tests alongside code.
- Include migrations, `.env.example` placeholders, README, reproducible setup and cleanup.
- Never commit secrets or real `.env` files.
- Do not silently drop requirements; report deferrals and reasons.

Before declaring success:
- Show exact commands and actual results.
- Demonstrate a small bounded crawl.
- Independently verify S3 objects and PostgreSQL rows.
- Demonstrate repeat-run deduplication, robots handling and budget stopping.
- Report limitations and cleanup steps.
- If a test cannot run, state the precise blocker; never mark it passed.

## 13. Cost/security/SOP boundaries
- Keep S3 private and Block Public Access enabled.
- Do not launch costly EC2/RDS/ECS/EKS/NAT or other billable services without approval.
- Do not run a high-volume or unbounded crawl.
- Never request AWS secret keys in chat.
- Confirm account, region, permissions and deployment plan before cloud mutations.
- Do not read, modify or merge the separate SOP workspace unless explicitly asked.

## 14. First Kiro task
Create a Feature Spec titled **Crawler MVP: StormCrawler + S3 raw HTML + PostgreSQL frontier/metadata/graph**. Generate requirements, design and tasks first. Analyze conflicts and dependency risks. Then implement, test and prove the bounded end-to-end crawl before working on the rest of the search engine.
