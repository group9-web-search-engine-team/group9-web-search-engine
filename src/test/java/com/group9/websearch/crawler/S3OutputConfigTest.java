package com.group9.websearch.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.storm.Config;
import org.apache.stormcrawler.util.ConfUtils;
import org.junit.jupiter.api.Test;

/**
 * Asserts the crawler's S3 OUTPUT configuration is correct and that the S3 bolt builds object
 * keys under the configured prefix. The full TREC-COVID crawl MUST write exclusively to the new
 * output bucket under {@code crawl/raw/trec-covid/} in {@code us-east-1}; the original bucket
 * must NOT be the output destination.
 */
class S3OutputConfigTest {

    private static final String OUTPUT_BUCKET =
            "web-search-engine-group9-crawler-output-20261110-709845";
    private static final String OUTPUT_PREFIX = "crawl/raw/trec-covid/";
    private static final String REGION = "us-east-1";
    private static final String ORIGINAL_BUCKET = "web-search-engine-group9-2026-corp";

    @Test
    void crawlerConfPointsAtNewOutputBucketPrefixAndRegion() throws Exception {
        Config conf = ConfUtils.loadConf("config/crawler-conf.yaml", new Config());
        assertEquals(OUTPUT_BUCKET, conf.get(CrawlerConstants.S3_BUCKET_PARAM),
                "s3.bucket must be the NEW crawler output bucket");
        assertEquals(OUTPUT_PREFIX, conf.get(CrawlerConstants.S3_PREFIX_PARAM),
                "s3.raw.prefix must be crawl/raw/trec-covid/");
        assertEquals(REGION, conf.get(CrawlerConstants.S3_REGION_PARAM),
                "s3.region must be us-east-1");
        assertFalse(ORIGINAL_BUCKET.equals(conf.get(CrawlerConstants.S3_BUCKET_PARAM)),
                "the original bucket must NOT be the crawl output destination");
    }

    @Test
    void s3BoltBuildsKeyUnderConfiguredPrefix() {
        // The S3 bolt's object key = normalizePrefix(prefix) + sha256(url) + ".html".
        String prefix = S3RawHtmlBolt.normalizePrefix(OUTPUT_PREFIX);
        String key = prefix + S3RawHtmlBolt.keyFor("https://example.com/doc") + ".html";
        assertEquals("crawl/raw/trec-covid/", prefix);
        assertTrue(key.startsWith("crawl/raw/trec-covid/"),
                "object key must live under the trec-covid prefix: " + key);
        assertTrue(key.endsWith(".html"));
        // Expected full path shape: s3://<bucket>/crawl/raw/trec-covid/<sha256>.html
        assertTrue(key.matches("crawl/raw/trec-covid/[0-9a-f]{64}\\.html"), key);
    }

    @Test
    void smokeConfUsesSeparateSmokePrefixSameOutputBucket() throws Exception {
        Config conf = ConfUtils.loadConf("config/crawler-conf-smoke.yaml", new Config());
        assertEquals(OUTPUT_BUCKET, conf.get(CrawlerConstants.S3_BUCKET_PARAM));
        assertEquals("crawl/raw/smoke-test/", conf.get(CrawlerConstants.S3_PREFIX_PARAM),
                "smoke prefix stays separate from the full-crawl prefix");
    }
}
