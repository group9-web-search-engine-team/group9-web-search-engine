package com.group9.websearch.crawler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Custom StormCrawler bolt that stores a fetched page's raw HTML in Amazon S3, then
 * records the resulting object key in the tuple metadata ({@code s3.key}) so the
 * downstream {@link PostgresStatusUpdaterBolt} only writes {@code documents.s3_key}
 * AFTER a successful upload.
 *
 * <p>It consumes the parser's main stream {@code (url, content, metadata)}. Only on a
 * successful upload does it emit the fetched-document scalars
 * {@code (url, httpStatus, contentLength, s3Key)} on {@link #S3_STATUS_STREAM} and the
 * discovered outlinks {@code (sourceUrl, destUrl, anchor)} on
 * {@link #DISCOVERED_LINK_STREAM}. If the upload fails or the content is empty it does NOT
 * emit on those streams; it only acks the input tuple so the topology does not hang (no
 * cross-service atomicity is claimed, and no retry is implemented here).
 *
 * <p>There is NO artificial page cap: every fetched page with content is stored. Crawl
 * boundedness comes from the finite seed file, the depth-1 {@code MaxDepthFilter}, robots
 * rules, per-host politeness and database-enforced deduplication.
 *
 * <p>Credentials come from the AWS SDK v2 default credential provider chain (never
 * hardcoded). The destination is the configured bucket under an ISOLATED prefix
 * (default {@code crawl/raw/smoke-test/}) so existing seed/test objects are never
 * touched. Keys are deterministic (SHA-256 of the canonical URL), so re-storing the same
 * page overwrites the same object rather than creating duplicates.
 */
public class S3RawHtmlBolt extends BaseRichBolt {

    private static final Logger LOG = LoggerFactory.getLogger(S3RawHtmlBolt.class);

    /** Metadata key under which the successful S3 object key is recorded. */
    public static final String S3_KEY_MD = "s3.key";

    /**
     * Dedicated stream carrying the fetched-document scalars as PRIMITIVE/String tuple
     * fields (NOT inside Metadata), to work around a Storm LocalCluster Metadata handoff
     * issue where a Metadata object emitted by this bolt arrives EMPTY at the downstream
     * status updater. Fields: {@code (url, httpStatus, contentLength, s3Key)}.
     */
    public static final String S3_STATUS_STREAM = "s3-fetched-status";
    public static final Fields S3_STATUS_FIELDS =
            new Fields("url", "httpStatus", "contentLength", "s3Key");

    /**
     * Dedicated stream carrying discovered outlinks as PRIMITIVE/String tuple fields
     * {@code (sourceUrl, destUrl, anchor)}. Emitted after a successful fetch/store so the
     * status updater can both (a) insert the destination into the {@code crawl_state}
     * frontier as {@code pending} and (b) record the parent->child {@code links} edge.
     * Scalars are used (not Metadata) because Metadata arrives empty at the downstream
     * bolt under Storm LocalCluster.
     */
    public static final String DISCOVERED_LINK_STREAM = "s3-discovered-links";
    public static final Fields DISCOVERED_LINK_FIELDS =
            new Fields("sourceUrl", "destUrl", "anchor");

    /** Upper bound on outlinks emitted per page, to keep fan-out sane (not a crawl cap). */
    static final int MAX_OUTLINKS_PER_PAGE = 50;

    private transient OutputCollector collector;
    private transient S3Client s3;

    private String bucket;
    private String prefix;
    private String region;

    // Count of pages successfully stored (for logging / observability only; not a cap).
    private final AtomicInteger storedCount = new AtomicInteger(0);

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
        this.bucket = strConf(conf, CrawlerConstants.S3_BUCKET_PARAM, CrawlerConstants.DEFAULT_S3_BUCKET);
        this.prefix = normalizePrefix(
                strConf(conf, CrawlerConstants.S3_PREFIX_PARAM, "crawl/raw/smoke-test/"));
        this.region = strConf(conf, CrawlerConstants.S3_REGION_PARAM, null);

        this.s3 = buildClient(region);
        LOG.info("S3RawHtmlBolt ready: bucket={} prefix={} region={}",
                bucket, prefix, region == null ? "<default-chain>" : region);
    }

    /** Build an S3 client using the default credential provider chain. */
    static S3Client buildClient(String region) {
        S3ClientBuilder b = S3Client.builder();
        if (region != null && !region.isBlank()) {
            b = b.region(Region.of(region.trim()));
        }
        // No credentials set explicitly: SDK uses the default provider chain
        // (env vars, profile, SSO, container/instance roles, etc.).
        return b.build();
    }

    @Override
    public void execute(Tuple tuple) {
        String url = tuple.getStringByField("url");
        byte[] content = tuple.getBinaryByField("content");
        Metadata metadata = (Metadata) tuple.getValueByField("metadata");
        if (metadata == null) {
            metadata = new Metadata();
        }

        // No content to store: do NOT upload and do NOT emit FETCHED. Ack and return.
        if (content == null || content.length == 0) {
            LOG.debug("No content for {}; skipping S3 store and FETCHED emit", url);
            collector.ack(tuple);
            return;
        }

        String key = prefix + keyFor(url) + ".html";
        boolean uploaded = false;
        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(firstNonNull(metadata.getFirstValue("content-type"), "text/html"))
                    .build();
            s3.putObject(req, RequestBody.fromBytes(content));
            uploaded = true;
            int n = storedCount.incrementAndGet();
            LOG.info("Stored raw HTML [{}] s3://{}/{} ({} bytes)", n, bucket, key, content.length);
        } catch (Exception e) {
            // Upload failed: log and do NOT emit FETCHED. Ack so the topology does not hang.
            LOG.error("S3 upload failed for {} (key {}): {}", url, key, e.toString());
        }

        if (uploaded) {
            emitFetched(tuple, url, metadata, key);
            emitDiscoveredLinks(tuple, url, content, metadata);
        }
        collector.ack(tuple);
    }

    /**
     * Parse the fetched HTML for outgoing links and emit each distinct destination as a
     * scalar tuple {@code (sourceUrl, destUrl, anchor)} on {@link #DISCOVERED_LINK_STREAM}.
     * Resolves relative links against the page URL and keeps only http/https destinations.
     * Scalars are used (not Metadata) to avoid the empty-Metadata handoff under LocalCluster.
     */
    private void emitDiscoveredLinks(Tuple anchorTuple, String sourceUrl, byte[] content, Metadata metadata) {
        try {
            String charset = charsetFrom(metadata);
            String html = new String(content, java.nio.charset.Charset.forName(charset));
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html, sourceUrl);
            org.jsoup.select.Elements anchors = doc.select("a[href]");
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            int emitted = 0;
            for (org.jsoup.nodes.Element a : anchors) {
                if (emitted >= MAX_OUTLINKS_PER_PAGE) {
                    break;
                }
                String abs = a.absUrl("href"); // resolved against sourceUrl base
                if (abs == null || abs.isBlank()) {
                    continue;
                }
                int hashIdx = abs.indexOf('#');
                if (hashIdx >= 0) {
                    abs = abs.substring(0, hashIdx); // drop fragment
                }
                if (!abs.startsWith("http://") && !abs.startsWith("https://")) {
                    continue;
                }
                if (abs.equals(sourceUrl) || !seen.add(abs)) {
                    continue; // skip self-links and duplicates within the page
                }
                String anchorText = a.text();
                collector.emit(DISCOVERED_LINK_STREAM, anchorTuple, new Values(sourceUrl, abs, anchorText));
                emitted++;
            }
            LOG.info("Discovered {} outlink(s) from {}", emitted, sourceUrl);
        } catch (Exception e) {
            LOG.warn("Outlink extraction failed for {}: {}", sourceUrl, e.toString());
        }
    }

    private static String charsetFrom(Metadata metadata) {
        String ct = metadata.getFirstValue("content-type");
        if (ct != null) {
            int idx = ct.toLowerCase().indexOf("charset=");
            if (idx >= 0) {
                String cs = ct.substring(idx + "charset=".length()).trim();
                int sep = cs.indexOf(';');
                if (sep >= 0) {
                    cs = cs.substring(0, sep).trim();
                }
                try {
                    if (!cs.isEmpty() && java.nio.charset.Charset.isSupported(cs)) {
                        return cs;
                    }
                } catch (Exception ignored) {
                    // fall through to default
                }
            }
        }
        return "UTF-8";
    }

    /**
     * After a successful S3 upload, read the HTTP status and content length from the
     * Metadata HERE (where it is non-empty) and emit them — together with the URL and the
     * S3 object key — as SEPARATE primitive/String tuple fields on {@link #S3_STATUS_STREAM}.
     *
     * <p>These scalars are passed as plain tuple fields rather than inside a Metadata
     * object because, under Storm LocalCluster, a Metadata emitted by this bolt arrives
     * empty at the downstream {@link PostgresStatusUpdaterBolt}. Primitive/String fields
     * serialize reliably, so the status updater persists real values instead of nulls.
     *
     * @param anchor the input tuple to anchor the emission to (for acking)
     * @param url    the fetched page URL
     * @param metadata the fetched page metadata (non-empty at this point)
     * @param s3Key  the S3 object key for the stored raw HTML
     */
    private void emitFetched(Tuple anchor, String url, Metadata metadata, String s3Key) {
        Integer httpStatus = parseNullableInt(metadata.getFirstValue("fetch.statusCode"));
        Long contentLength = parseNullableLong(metadata.getFirstValue("fetch.byteLength"));
        LOG.info("S3 emitting FETCHED scalars for {}: httpStatus={}, contentLength={}, s3Key={}",
                url, httpStatus, contentLength, s3Key);
        collector.emit(S3_STATUS_STREAM, anchor, new Values(url, httpStatus, contentLength, s3Key));
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("url", "content", "metadata"));
        // Dedicated scalar status stream consumed by the PostgreSQL status updater.
        declarer.declareStream(S3_STATUS_STREAM, S3_STATUS_FIELDS);
        // Dedicated scalar discovered-links stream (sourceUrl, destUrl, anchor).
        declarer.declareStream(DISCOVERED_LINK_STREAM, DISCOVERED_LINK_FIELDS);
    }

    private static Integer parseNullableInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseNullableLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void cleanup() {
        if (s3 != null) {
            s3.close();
        }
    }

    /** Deterministic, collision-resistant key component from the canonical URL. */
    static String keyFor(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte x : digest) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String normalizePrefix(String p) {
        if (p == null || p.isBlank()) {
            return "crawl/raw/smoke-test/";
        }
        String trimmed = p.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    @SuppressWarnings("rawtypes")
    private static String strConf(Map conf, String key, String def) {
        Object v = conf == null ? null : conf.get(key);
        return v == null ? def : v.toString();
    }
}
