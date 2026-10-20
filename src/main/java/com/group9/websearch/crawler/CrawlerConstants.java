package com.group9.websearch.crawler;

/**
 * Configuration keys and defaults specific to the Group 9 crawler slice.
 *
 * <p>These sit alongside (and never clash with) StormCrawler's own configuration keys.
 * Connection secrets (PostgreSQL password, AWS credentials) are intentionally NOT defined
 * here as literals — they are resolved at runtime from environment variables or the AWS
 * default credential provider chain.
 */
public final class CrawlerConstants {

    private CrawlerConstants() {
    }

    // ---- PostgreSQL status-updater configuration (values from conf/env, never hardcoded) ----

    /** JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/websearch}. */
    public static final String PG_URL_PARAM = "postgres.jdbc.url";
    /** Database user name. */
    public static final String PG_USER_PARAM = "postgres.user";
    /**
     * Name of the environment variable holding the DB password. The password itself is
     * never stored in configuration files — only the name of the env var that holds it.
     */
    public static final String PG_PASSWORD_ENV_PARAM = "postgres.password.env";

    public static final String DEFAULT_PG_URL = "jdbc:postgresql://localhost:5432/websearch";
    /** Dedicated, non-superuser crawler role (never the postgres superuser). */
    public static final String DEFAULT_PG_USER = "websearch_crawler";
    public static final String DEFAULT_PG_PASSWORD_ENV = "WEBSEARCH_PG_PASSWORD";

    // ---- Clean-start reset uses a SEPARATE admin role (needs DELETE privilege) ----
    // The crawler role is intentionally read/write-only (no DELETE), so a clean-start reset
    // is performed with a distinct role whose credentials come only from env/config.

    /** DB user used for the clean-start reset (defaults to the crawler user). */
    public static final String PG_ADMIN_USER_PARAM = "postgres.admin.user";
    /** Name of the env var holding the admin password (defaults to the crawler password env). */
    public static final String PG_ADMIN_PASSWORD_ENV_PARAM = "postgres.admin.password.env";

    // ---- S3 raw-HTML storage configuration ----

    public static final String S3_BUCKET_PARAM = "s3.bucket";
    public static final String S3_PREFIX_PARAM = "s3.raw.prefix";
    public static final String S3_REGION_PARAM = "s3.region";

    public static final String DEFAULT_S3_BUCKET = "web-search-engine-group9-2026-corp";
    public static final String DEFAULT_S3_PREFIX = "crawl/raw/";

    // ---- Topic distillation (optional; hub/authority promotion of frontier priority) ----

    /** Enable periodic topic distillation (default false). */
    public static final String DISTILL_ENABLED_PARAM = "distill.enabled";
    /** Interval in seconds between distillation passes. */
    public static final String DISTILL_INTERVAL_SECS_PARAM = "distill.interval.secs";
    /** Number of top hubs selected per pass. */
    public static final String DISTILL_TOP_HUBS_PARAM = "distill.top.hubs";
    /** Priority boost added to promoted hub-neighbour URLs. */
    public static final String DISTILL_BOOST_PARAM = "distill.boost";

    public static final boolean DEFAULT_DISTILL_ENABLED = false;
    /** Engineering default (the paper mandates no universal interval). */
    public static final int DEFAULT_DISTILL_INTERVAL_SECS = 120;
    public static final int DEFAULT_DISTILL_TOP_HUBS = 10;
    public static final double DEFAULT_DISTILL_BOOST = 1000.0;

    // ---- QT5-Small quality scorer (optional; connects to the local Python service) ----

    /** Enable QT5 scoring (default false so the crawler works without the Python service). */
    public static final String QT5_ENABLED_PARAM = "qt5.enabled";
    /** Base URL of the local scoring service. */
    public static final String QT5_URL_PARAM = "qt5.service.url";
    /** Per-request timeout in milliseconds. */
    public static final String QT5_TIMEOUT_MS_PARAM = "qt5.timeout.ms";
    /** Max characters of page text sent to the scorer (the model truncates further). */
    public static final String QT5_MAX_CHARS_PARAM = "qt5.max.chars";

    public static final boolean DEFAULT_QT5_ENABLED = false;
    public static final String DEFAULT_QT5_URL = "http://127.0.0.1:8077";
    public static final int DEFAULT_QT5_TIMEOUT_MS = 5000;
    public static final int DEFAULT_QT5_MAX_CHARS = 20000;

    // ---- Seed ingestion ----

    /** Directory containing the seed file (FileSpout takes dir + file). */
    public static final String SEED_DIR_PARAM = "seed.dir";
    /** Seed file name within {@link #SEED_DIR_PARAM}. */
    public static final String SEED_FILE_PARAM = "seed.file";
}
