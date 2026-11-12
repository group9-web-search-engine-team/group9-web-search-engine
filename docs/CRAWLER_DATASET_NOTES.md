# Crawling the TREC-COVID seed list

## Why the crawler failed on the dataset
| Seeds | Share | What happened before |
|---|---|---|
| 26,803 `www.ncbi.nlm.nih.gov/pmc/articles/…` | 49.9% | PMC moved to `pmc.ncbi.nlm.nih.gov`; the old host's robots.txt now has `Disallow: /pmc/articles/` → robots-denied |
| 14,301 `www.ncbi.nlm.nih.gov/pubmed/…` | 26.6% | Redirect to `pubmed.ncbi.nlm.nih.gov`, which returns a 203 "cookies required" bot-check page |
| 6,519 `doi.org/…` | 12.1% | 302 to the publisher; the redirecting URL was re-enqueued and stuck `in_progress` (fixed on `main` in 3515fe5) |
| 3,678 `medrxiv.org/cgi/content/short/…` | 6.9% | Cloudflare bot challenge (403) |
| 2,316 `arxiv.org/pdf/….pdf` | 4.3% | PDF → rejected by the JSoup parser |

Also: parsed outlinks (nav, footer, cookie-policy links) were enqueued unfiltered and competed with the seeds; the seed file was grouped by host (one politeness queue busy at a time); `max.spout.pending: 5` kept at most 5 URLs in flight; StormCrawler stripped `fetch.statusCode`/`anchor` before `store()`.

## What changed
* `DatasetUrlRewriter` / `DatasetUrlRewriteFilter`: old PMC → `pmc.ncbi.nlm.nih.gov/articles/PMCn/`, old PubMed → `pubmed.ncbi.nlm.nih.gov/n/`, Europe PMC PDF → PMC HTML, arXiv PDF → `/abs/`, medRxiv RSS → canonical. Applied to seeds, redirect targets and outlinks.
* `DatasetSeedPreparer`: rewrites, deduplicates and interleaves seeds by host → `config/beir-seed-urls.dataset.txt`.
* `PostgresStatusUpdaterBolt` (on top of the redirect/ack fixes already on `main`): redirect targets and outlinks are rewritten; redirect targets (DISCOVERED from the fetcher) are always enqueued, parser/S3 outlinks only become link-graph edges unless `crawl.follow.outlinks: true`; FETCH_ERROR is retried only when transient (429, 5xx, network exception) using `max.fetch.errors` and `fetchInterval.fetch.error`; one `CRAWL-FAIL … cause=…` log line per failure.
* `BoundedFileSpout`: rewrites seeds on emit and skips seeds already `fetched` (resume).
* Config: robots.txt compliance is back ON (the rewrite makes PMC allowed again), `redirections.allowed: true`, `fetch.statusCode`/`fetch.exception`/`anchor` added to `metadata.persist`, 50 seeds in flight, 600 s tuple timeout, seed file → `beir-seed-urls.dataset.txt`.

## Run
```bash
# regenerate the prepared seeds after changing beir-seed-urls.txt or the rewrite rules
java -cp "target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" \
  com.group9.websearch.seeds.DatasetSeedPreparer config/beir-seed-urls.txt config/beir-seed-urls.dataset.txt
# full dataset crawl: ~11-12 h (PMC at its 1 s crawl-delay, arXiv at 15 s in parallel)
java -jar target/crawler-mvp.jar -conf config/crawler-conf.yaml -local -ttl 45000
# why did URLs fail?
grep CRAWL-FAIL crawler.log | sed 's/.*cause=//' | sort | uniq -c | sort -rn
```
Expected reachable: PMC + arXiv + ~⅓ of DOIs ≈ 31k of 53.7k seeds (~58%). PubMed and medRxiv need their official APIs (NCBI E-utilities, `api.medrxiv.org`); see the project notes.
