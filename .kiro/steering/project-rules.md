---
inclusion: always
---

# Non-Negotiable Rules

1. The project proposal defines the target architecture.
2. Use actual Apache StormCrawler; do not substitute a home-grown crawler and mislabel it.
3. Build and test the crawler vertical slice first.
4. Verify official APIs and dependency compatibility; never invent integrations.
5. Never guess AWS region, credentials, permissions, tools or database target.
6. Never hardcode secrets or ask for them in chat.
7. Do not make S3 public or create costly cloud infrastructure without approval.
8. Respect robots.txt, host politeness, bounded budgets and safe URL policy.
9. S3 stores raw objects; PostgreSQL stores metadata/frontier/graph; PostgreSQL is not the inverted-index store.
10. BEIR TREC-COVID is separate from the crawled Web corpus.
11. DPO is offline training; SPRAWL is excluded.
12. Defer QT5-Small/QMax, Hadoop/SPIMI, PageRank, retrieval/ranking and BERT reranking until the crawler works.
13. Do not claim success from generated code alone; show build/test/end-to-end evidence.
14. Report unrun tests and deferred requirements honestly.
15. Keep the SOP workspace and files separate and untouched.
16. Create and review Kiro requirements, design and tasks; run requirements analysis before implementation.
