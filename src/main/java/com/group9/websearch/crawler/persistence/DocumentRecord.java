package com.group9.websearch.crawler.persistence;

import java.time.Instant;

/**
 * Immutable value object describing a {@code documents} row to persist, matching the
 * verified live schema:
 *
 * <pre>
 * documents(
 *   doc_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 *   url             TEXT NOT NULL UNIQUE,
 *   crawl_time      TIMESTAMPTZ,
 *   status          INTEGER,          -- HTTP status code
 *   content_length  BIGINT,
 *   s3_key          TEXT,
 *   quality_score   DOUBLE PRECISION, -- not set by the crawler slice
 *   pagerank        DOUBLE PRECISION) -- not set by the crawler slice
 * </pre>
 *
 * <p>Nullable fields use boxed types so "unknown" is distinguishable from a real value
 * (e.g. an HTTP status of 0 vs. no status captured). {@code quality_score} and
 * {@code pagerank} are intentionally not modeled here: they belong to later milestones
 * and are left untouched by the crawler.
 */
public record DocumentRecord(
        String url,
        Integer status,
        Long contentLength,
        String s3Key,
        Instant crawlTime) {

    public DocumentRecord {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
    }
}
