package com.group9.websearch.index;

/**
 * Configuration for the document exporter. Values come from CLI flags / environment; secrets
 * (the DB password) are resolved from the named environment variable, never stored here.
 */
public final class ExporterConfig {

    public String jdbcUrl = "jdbc:postgresql://localhost:5432/websearch";
    public String dbUser = "websearch_crawler";
    public String passwordEnv = "WEBSEARCH_PG_PASSWORD";

    public String s3Bucket = "web-search-engine-group9-2026-corp";
    public String s3Region = "us-east-1";
    /** Optional s3_key prefix filter; null/empty means "no prefix filter" (export all keys). */
    public String s3PrefixFilter = null;

    /** Output JSONL path (local filesystem). */
    public String outputPath = "week3-export/documents.jsonl";

    /** DB fetch batch size (JDBC cursor fetch size) for streaming reads. */
    public int fetchBatchSize = 200;
    /** Max bytes to download per S3 object (bounded download). */
    public long maxObjectBytes = 10L * 1024 * 1024;
    /** Max characters of extracted body text kept per document. */
    public int maxBodyChars = 200_000;

    /**
     * Eligibility: export documents whose s3_key is non-empty and whose HTTP status is OK
     * (2xx) or unknown (null). When false, only s3_key presence is required.
     */
    public boolean requireOkStatus = true;

    /** Dry run: do everything except write output (counts only). */
    public boolean dryRun = false;

    public String resolvePassword() {
        return System.getenv(passwordEnv);
    }
}
