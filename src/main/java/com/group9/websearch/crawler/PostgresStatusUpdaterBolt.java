
package com.group9.websearch.crawler;

import com.group9.websearch.crawler.persistence.DocumentRecord;
import com.group9.websearch.crawler.persistence.FrontierRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.AbstractStatusUpdaterBolt;
import org.apache.stormcrawler.persistence.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom StormCrawler status-updater bolt that persists crawl outcomes to the existing
 * PostgreSQL websearch schema (crawl_state, documents, links).
 *
 * <p>Status mapping:
 * DISCOVERED / REDIRECTION -> pending
 * FETCHED -> documents row and fetched state
 * FETCH_ERROR / ERROR -> failed state
 *
 * <p>The PostgreSQL password is read from the configured environment variable.
 */
public class PostgresStatusUpdaterBolt extends AbstractStatusUpdaterBolt {

    private static final Logger LOG =
            LoggerFactory.getLogger(PostgresStatusUpdaterBolt.class);

    private transient Connection connection;
    private transient FrontierRepository repository;

    private String jdbcUrl;
    private String dbUser;
    private String dbPassword;

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(
            Map stormConf,
            TopologyContext context,
            OutputCollector collector) {

        super.prepare(stormConf, context, collector);

        this.jdbcUrl = strConf(
                stormConf,
                CrawlerConstants.PG_URL_PARAM,
                CrawlerConstants.DEFAULT_PG_URL);

        this.dbUser = strConf(
                stormConf,
                CrawlerConstants.PG_USER_PARAM,
                CrawlerConstants.DEFAULT_PG_USER);

        String passwordEnv = strConf(
                stormConf,
                CrawlerConstants.PG_PASSWORD_ENV_PARAM,
                CrawlerConstants.DEFAULT_PG_PASSWORD_ENV);

        this.dbPassword = System.getenv(passwordEnv);

        if (this.dbPassword == null) {
            LOG.warn(
                    "PostgreSQL password env var '{}' is not set; DB connection will likely fail.",
                    passwordEnv);
        }

        try {
            this.connection =
                    DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
            this.connection.setAutoCommit(true);
            this.repository = new FrontierRepository(connection);

            LOG.info(
                    "PostgresStatusUpdaterBolt connected to {}",
                    jdbcUrl);

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to open PostgreSQL connection to " + jdbcUrl,
                    e);
        }
    }

    /**
     * Intercept the S3 bolt's dedicated scalar stream
     * ({@link S3RawHtmlBolt#S3_STATUS_STREAM}) and persist the fetched document from the
     * primitive/String tuple fields directly, bypassing Metadata entirely (which arrives
     * empty under Storm LocalCluster). All other tuples are delegated to
     * {@link AbstractStatusUpdaterBolt#execute(Tuple)}, preserving native fetch/parse
     * error and discovery handling.
     */
    @Override
    public void execute(Tuple tuple) {
        String stream = tuple.getSourceStreamId();
        if (S3RawHtmlBolt.S3_STATUS_STREAM.equals(stream)) {
            handleS3FetchedScalars(tuple);
            return;
        }
        if (S3RawHtmlBolt.DISCOVERED_LINK_STREAM.equals(stream)) {
            handleDiscoveredLink(tuple);
            return;
        }
        super.execute(tuple);
    }

    /**
     * Handle a discovered outlink delivered as scalar fields {@code (sourceUrl, destUrl,
     * anchor)}. Inserts the destination into the frontier ({@code crawl_state} as
     * {@code pending}, deduplicated by PK) so {@link PostgresFrontierSpout} can later fetch
     * it, and records the parent->child {@code links} edge (the source document exists
     * because its FETCHED scalars were persisted before its links are emitted).
     */
    private void handleDiscoveredLink(Tuple tuple) {
        String sourceUrl = tuple.getStringByField("sourceUrl");
        String destUrl = tuple.getStringByField("destUrl");
        String anchor = (String) tuple.getValueByField("anchor");
        try {
            // Add the destination to the frontier (idempotent; PK dedup).
            repository.insertDiscovered(destUrl, defaultPriority());

            // Record the directed edge if the source document is known.
            OptionalLong srcDocId = repository.findDocId(sourceUrl);
            if (srcDocId.isPresent()) {
                OptionalLong destDocId = repository.findDocId(destUrl);
                repository.insertLink(srcDocId.getAsLong(), destDocId, destUrl, anchor);
                // A new parent edge may raise this destination's QMax (if the source page
                // already has a QT5 score). Recompute-from-scratch is idempotent and never
                // lowers QMax when the new parent scores lower.
                repository.recomputeQMaxForDestination(destUrl);
            } else {
                LOG.debug("No source doc_id yet for {}; link to {} not recorded this pass",
                        sourceUrl, destUrl);
            }
            _collector.ack(tuple);
        } catch (SQLException e) {
            LOG.error("Failed to persist discovered link {} -> {}: {}", sourceUrl, destUrl, e.toString());
            _collector.fail(tuple);
        }
    }

    /**
     * Persist a FETCHED document from the dedicated scalar stream. The HTTP status,
     * content length and S3 key come directly from tuple fields (not Metadata), so they
     * are never null due to the Metadata handoff issue.
     */
    private void handleS3FetchedScalars(Tuple tuple) {
        String url = tuple.getStringByField("url");
        Integer httpStatus = (Integer) tuple.getValueByField("httpStatus");
        Long contentLength = (Long) tuple.getValueByField("contentLength");
        String s3Key = (String) tuple.getValueByField("s3Key");
        Double qualityScore = (Double) tuple.getValueByField("qualityScore");

        try {
            DocumentRecord doc = new DocumentRecord(url, httpStatus, contentLength, s3Key, Instant.now());
            repository.upsertDocument(doc);
            // Persist the QT5 quality score (kept distinct from pagerank/QMax). Only when a
            // score is present; the UPDATE is idempotent so retries stay consistent.
            if (qualityScore != null) {
                repository.updateQualityScore(url, qualityScore);
                // QMax PROPAGATION: this page now has a QT5 score, so propagate it to every
                // URL it links to -- QMax(child) = max over parents' quality_score. The
                // child's frontier priority is updated so higher-QMax URLs are claimed first.
                int propagated = repository.recomputeQMaxForChildrenOf(url);
                if (propagated > 0) {
                    LOG.info("QMax propagated from {} to {} child URL(s)", url, propagated);
                }
            }
            repository.markTerminal(url, FrontierRepository.STATE_FETCHED);
            _collector.ack(tuple);
        } catch (SQLException e) {
            LOG.error("Failed to persist FETCHED scalars for {}: {}", url, e.toString());
            _collector.fail(tuple);
        }
    }

    @Override
    public void store(
            String url,
            Status status,
            Metadata metadata,
            Optional<Date> nextFetch,
            Tuple tuple) throws Exception {

        if (repository == null) {
            throw new IllegalStateException(
                    "repository not initialised; prepare() must run first");
        }

        switch (status) {
            case DISCOVERED, REDIRECTION -> {
                repository.insertDiscovered(url, defaultPriority());
                recordParentEdgeIfPossible(url, metadata);
            }

            case FETCHED -> {
                persistFetchedDocument(url, metadata);
                repository.markTerminal(
                        url,
                        FrontierRepository.STATE_FETCHED);
            }

            case FETCH_ERROR, ERROR ->
                    repository.markTerminal(
                            url,
                            FrontierRepository.STATE_FAILED);

            default ->
                    LOG.warn(
                            "Unhandled status {} for {}",
                            status,
                            url);
        }
    }

    /**
     * Record a directed link when the parent document is already persisted.
     */
    private void recordParentEdgeIfPossible(
            String destinationUrl,
            Metadata metadata) throws SQLException {

        String parentUrl = metadata.getFirstValue("parent.url");

        if (parentUrl == null || parentUrl.isBlank()) {
            return;
        }

        OptionalLong parentDocId = repository.findDocId(parentUrl);

        if (parentDocId.isEmpty()) {
            return;
        }

        String anchor = metadata.getFirstValue("anchor");
        OptionalLong destDocId = repository.findDocId(destinationUrl);

        repository.insertLink(
                parentDocId.getAsLong(),
                destDocId,
                destinationUrl,
                anchor);
    }

    /**
     * Persist the fetched document and log the metadata received by this bolt
     * immediately before writing it to PostgreSQL.
     */
    private void persistFetchedDocument(
            String url,
            Metadata metadata) throws SQLException {

        Integer httpStatus = parseNullableInt(
                metadata.getFirstValue("fetch.statusCode"));

        Long contentLength = parseNullableLong(
                metadata.getFirstValue("fetch.byteLength"));

        String s3Key = metadata.getFirstValue("s3.key");

        // Diagnostic: shows what this bolt actually receives from the status stream.
        LOG.info(
                "STATUS RECEIVED: url={}, metadataClass={}, keys={}, "
                        + "httpStatus={}, byteLength={}, s3Key={}",
                url,
                metadata == null ? "null" : metadata.getClass().getName(),
                metadata == null ? "null" : metadata.keySet(),
                metadata == null ? null : metadata.getFirstValue("fetch.statusCode"),
                metadata == null ? null : metadata.getFirstValue("fetch.byteLength"),
                metadata == null ? null : metadata.getFirstValue("s3.key"));

        DocumentRecord doc = new DocumentRecord(
                url,
                httpStatus,
                contentLength,
                s3Key,
                Instant.now());

        repository.upsertDocument(doc);
    }

    @Override
    public void cleanup() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("Error closing PostgreSQL connection", e);
            }
        }
    }

    /**
     * Safe-default frontier priority for a newly discovered URL whose QMax is not yet
     * known (no parent has a QT5 score yet). It is a high positive value so that, under
     * the frontier's {@code ORDER BY priority DESC}, unknown-QMax URLs are explored BEFORE
     * URLs that have already received a (negative, log-prob) QMax. This avoids
     * hard-discarding newly discovered pages and lets valuable pages be reached via
     * low-scoring parents. Once a parent's QT5 score arrives, QMax propagation overwrites
     * this with the real (negative) QMax value.
     */
    private static double defaultPriority() {
        return 500.0;
    }

    @SuppressWarnings("rawtypes")
    private static String strConf(
            Map conf,
            String key,
            String def) {

        Object value = conf == null ? null : conf.get(key);
        return value == null ? def : value.toString();
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
}
