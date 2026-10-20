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

    // ---- Seed ingestion ----

    /** Directory containing the seed file (FileSpout takes dir + file). */
    public static final String SEED_DIR_PARAM = "seed.dir";
    /** Seed file name within {@link #SEED_DIR_PARAM}. */
    public static final String SEED_FILE_PARAM = "seed.file";
}
