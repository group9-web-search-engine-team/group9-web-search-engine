# Kiro Web Search Engine Pack

This pack supplies Kiro with persistent context for the university Web Search Engine project. It is based on the project proposal and confirmed setup notes; it does not imply that crawler code has already been built or tested.

## Install
1. Create/open a dedicated workspace folder for the Web Search Engine. Keep the SOP in its own workspace.
2. Extract this ZIP into the Web Search Engine workspace, preserving `.kiro/steering/`.
3. Confirm the steering files are at the workspace root under `.kiro/steering/`.
4. In Kiro, create a **Feature Spec** for the crawler MVP.
5. Ask Kiro to read `KIRO_MASTER_BRIEF.md` and all steering files, then generate requirements, design, and tasks. Review them and run requirements analysis before approving implementation.

## Confirmed AWS setup
- Bucket: `web-search-engine-group9-2026-corp`
- Seed object: `s3://web-search-engine-group9-2026-corp/seeds/seed_urls.txt`
- Test object: `s3://web-search-engine-group9-2026-corp/crawl/s3-test.txt`
- Intended raw object prefix: `s3://web-search-engine-group9-2026-corp/crawl/raw/`
- AWS region and IAM permissions are not yet verified.
- AWS CLI is not installed locally; do not assume it exists.

## Official Kiro docs consulted
- Steering: https://kiro.dev/docs/steering/
- Specs: https://kiro.dev/docs/specs/

Kiro steering files belong in `.kiro/steering/`. The pack splits context by topic, consistent with Kiro's guidance to use focused Markdown steering files.
