---
inclusion: always
---

# Technology and Engineering Constraints

- Use Apache StormCrawler as the actual crawler framework.
- Use Amazon S3 for raw HTML/document objects and PostgreSQL for metadata, crawl state and graph.
- Use AWS SDK standard credential-provider chain; no hardcoded credentials.
- Verify compatible StormCrawler, Storm, Java, build-tool, database-driver and AWS SDK versions from official documentation before coding; pin them.
- Official StormCrawler docs: https://stormcrawler.apache.org/docs/
- S3 bucket: `web-search-engine-group9-2026-corp`; seed key `seeds/seed_urls.txt`; test key `crawl/s3-test.txt`; raw prefix `crawl/raw/`.
- AWS region and IAM permissions are unknown; verify/configure rather than guess.
- Keep bucket private and Block Public Access enabled.
- No costly AWS infrastructure without explicit approval.
- Honor robots.txt, host politeness and conservative crawl budgets.
- Configure page/depth/concurrency limits, timeouts, maximum response bytes, allowed MIME types and retry limit.
- Restrict to HTTP/HTTPS; block localhost, private/internal ranges and cloud metadata endpoints, including redirects.
- Use migrations, tests, structured logs, `.env.example` placeholders and reproducible documentation.
- Never claim tests passed without execution evidence. Report external blockers precisely.
