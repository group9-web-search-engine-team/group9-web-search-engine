
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
    /** Max store-failure retries before a URL is marked terminally failed (from max.fetch.errors). */
    private int maxFetchErrors = 3;
    /** Base backoff (seconds) between store-failure retries; grows linearly with retry_count. */
    private int retryBackoffSecs = 120;

    // ---- Pipeline counters (make persistence losses immediately visible) ----
    private long s3FetchedTuples;      // FETCHED scalar tuples received from the S3 bolt
    private long documentRowsUpserted; // documents upserted
    private long urlsMarkedFetched;    // crawl_state -> fetched
    private long discoveredLinkTuples; // discovered-link tuples received
    private long linksInserted;        // links rows inserted
    private long storeFailureTuples;   // S3 store-failure tuples received
    private long urlsRetried;          // store-failure retries scheduled
    private long urlsTerminallyFailed; // store-failure terminal fails
    private long statusTuplesAcked;    // native store() tuples acked
    private long persistErrors;        // DB/persist exceptions (tuple failed)
    private long redirectsHandled;     // REDIRECTION status events handled
    private long redirectTargetsQueued; // redirect targets followed into the frontier

    /** Log the counter snapshot every N status/S3 tuples. */
    private static final long COUNTER_LOG_INTERVAL = 50L;
    private long processedSinceLog;

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

        // Reuse the EXISTING retry configuration for store-failure retries (no new keys):
        // max.fetch.errors caps attempts; fetchInterval.fetch.error (minutes) is the backoff.
        this.maxFetchErrors = intConf(stormConf, "max.fetch.errors", 3);
        int fetchErrorIntervalMin = intConf(stormConf, "fetchInterval.fetch.error", 2);
        this.retryBackoffSecs = Math.max(1, fetchErrorIntervalMin) * 60;

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
        if (S3RawHtmlBolt.STORE_FAILURE_STREAM.equals(stream)) {
            handleStoreFailure(tuple);
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
        discoveredLinkTuples++;
        try {
            // Add the destination to the frontier (idempotent; PK dedup).
            repository.insertDiscovered(destUrl, defaultPriority());

            // Record the directed edge if the source document is known.
            OptionalLong srcDocId = repository.findDocId(sourceUrl);
            if (srcDocId.isPresent()) {
                OptionalLong destDocId = repository.findDocId(destUrl);
                if (repository.insertLink(srcDocId.getAsLong(), destDocId, destUrl, anchor)) {
                    linksInserted++;
                }
                LOG.debug("LINK recorded: {} -> {}", sourceUrl, destUrl);
                // A new parent edge may raise this destination's QMax (if the source page
                // already has a QT5 score). Recompute-from-scratch is idempotent and never
                // lowers QMax when the new parent scores lower.
                repository.recomputeQMaxForDestination(destUrl);
            } else {
                LOG.debug("No source doc_id yet for {}; link to {} not recorded this pass",
                        sourceUrl, destUrl);
            }
            _collector.ack(tuple);
            maybeLogCounters();
        } catch (SQLException e) {
            persistErrors++;
            LOG.error("Failed to persist discovered link {} -> {}: {}", sourceUrl, destUrl, e.toString());
            _collector.fail(tuple);
        }
    }

    /**
     * Handle an S3 store failure ({@code (url, retryable)}) so the frontier is updated
     * reliably and the URL is never left permanently {@code in_progress}:
     * <ul>
     *   <li><b>retryable</b> (transient upload failure): if the current {@code retry_count}
     *       is below {@code max.fetch.errors}, schedule a bounded retry with a linear backoff
     *       (returns the URL to {@code pending} with a future {@code next_attempt} and
     *       increments {@code retry_count}); once attempts are exhausted, mark it
     *       {@code failed}.</li>
     *   <li><b>non-retryable</b> (empty/no content): mark {@code failed} immediately.</li>
     * </ul>
     * Uses only the EXISTING retry configuration, attempt counter and backoff.
     */
    private void handleStoreFailure(Tuple tuple) {
        String url = tuple.getStringByField("url");
        Boolean retryable = (Boolean) tuple.getValueByField("retryable");
        storeFailureTuples++;
        try {
            if (Boolean.TRUE.equals(retryable)) {
                int attempts = repository.currentRetryCount(url);
                if (attempts < maxFetchErrors) {
                    long backoff = (long) retryBackoffSecs * (attempts + 1L); // linear backoff
                    repository.scheduleRetry(url, Instant.now().plusSeconds(backoff));
                    urlsRetried++;
                    LOG.warn("STATE retry: url={} attempt={}/{} backoff={}s",
                            url, attempts + 1, maxFetchErrors, backoff);
                } else {
                    repository.markTerminal(url, FrontierRepository.STATE_FAILED);
                    urlsTerminallyFailed++;
                    LOG.error("STATE failed (retries exhausted {}): url={}", maxFetchErrors, url);
                }
            } else {
                repository.markTerminal(url, FrontierRepository.STATE_FAILED);
                urlsTerminallyFailed++;
                LOG.warn("STATE failed (no content): url={}", url);
            }
            _collector.ack(tuple);
            maybeLogCounters();
        } catch (SQLException e) {
            persistErrors++;
            LOG.error("Failed to update frontier after S3 store failure for {}: {}", url, e.toString());
            _collector.fail(tuple);
        }
    }

    /** Periodically log the pipeline counter snapshot so losses are immediately visible. */
    private void maybeLogCounters() {
        if (++processedSinceLog % COUNTER_LOG_INTERVAL == 0) {
            logCounters();
        }
    }

    void logCounters() {
        LOG.info("PIPELINE COUNTERS [status-updater]: s3FetchedTuples={} documentRowsUpserted={} "
                + "urlsMarkedFetched={} discoveredLinkTuples={} linksInserted={} "
                + "storeFailureTuples={} urlsRetried={} urlsTerminallyFailed={} "
                + "redirectsHandled={} redirectTargetsQueued={} statusTuplesAcked={} persistErrors={}",
                s3FetchedTuples, documentRowsUpserted, urlsMarkedFetched, discoveredLinkTuples,
                linksInserted, storeFailureTuples, urlsRetried, urlsTerminallyFailed,
                redirectsHandled, redirectTargetsQueued, statusTuplesAcked, persistErrors);
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

        s3FetchedTuples++;
        LOG.info("STATUS received: stream=S3_STATUS url={} httpStatus={} contentLength={} s3Key={}",
                url, httpStatus, contentLength, s3Key);
        try {
            DocumentRecord doc = new DocumentRecord(url, httpStatus, contentLength, s3Key, Instant.now());
            repository.upsertDocument(doc);
            documentRowsUpserted++;
            LOG.info("DOC upserted: url={} s3Key={}", url, s3Key);
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
            urlsMarkedFetched++;
            LOG.info("STATE fetched: url={}", url);
            _collector.ack(tuple);
            maybeLogCounters();
        } catch (SQLException e) {
            persistErrors++;
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

        LOG.info("STATUS store(): status={} url={} stream={}",
                status, url, tuple.getSourceStreamId());
        switch (status) {
            case DISCOVERED -> {
                // Normal recursive discovery: every discovered URL enters the frontier
                // (deduplicated by PK). No allowlist restriction.
                repository.insertDiscovered(url, defaultPriority());
                recordParentEdgeIfPossible(url, metadata);
            }

            case REDIRECTION -> handleRedirection(url, metadata);

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

        // CRITICAL: AbstractStatusUpdaterBolt.execute() calls store() but does NOT ack the
        // tuple afterwards (it only fails on exception). The store() implementation owns the
        // ack. Without this, EVERY fetcher/parser status-stream tuple (DISCOVERED outlinks,
        // REDIRECTION, FETCH_ERROR/ERROR) was left un-acked, so the anchored root spout tuple
        // never completed, timed out after topology.message.timeout.secs, failed, and was
        // dropped -> the URL stayed in_progress forever and nothing downstream committed.
        // ack(tuple, url) also records the URL in the dedup cache (native StormCrawler
        // behaviour) and calls _collector.ack(tuple).
        ack(tuple, url);
        statusTuplesAcked++;
        LOG.debug("STATUS acked: status={} url={}", status, url);
    }

    /**
     * Handle a REDIRECTION status for {@code sourceUrl}:
     * <ol>
     *   <li>The source URL yields no content of its own, so take it OUT of the active set
     *       (otherwise it would stay stranded in {@code in_progress} forever -- a redirect is
     *       not retryable). It is marked terminal.</li>
     *   <li>Follow the redirect: queue the target ({@code _redirTo}) into the frontier so its
     *       content is fetched (normal crawling -- no allowlist restriction), and record the
     *       source -&gt; target link edge if the source document row exists.</li>
     * </ol>
     */
    private void handleRedirection(String sourceUrl, Metadata metadata) throws SQLException {
        String target = metadata == null ? null : metadata.getFirstValue("_redirTo");
        LOG.info("REDIRECT: source={} target={}", sourceUrl, target);

        repository.markTerminal(sourceUrl, FrontierRepository.STATE_FETCHED);
        urlsMarkedFetched++;
        redirectsHandled++;

        if (target != null && !target.isBlank()) {
            boolean inserted = repository.insertDiscovered(target, defaultPriority());
            redirectTargetsQueued++;
            LOG.info("REDIRECT followed: queued target={} (newRow={})", target, inserted);
            OptionalLong srcDocId = repository.findDocId(sourceUrl);
            if (srcDocId.isPresent()) {
                if (repository.insertLink(srcDocId.getAsLong(), OptionalLong.empty(), target, null)) {
                    linksInserted++;
                }
            }
        } else {
            LOG.warn("REDIRECT target missing (_redirTo absent) for source={}; cannot follow. "
                    + "Metadata may be empty under LocalCluster; check serializer registration.",
                    sourceUrl);
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
        logCounters(); // final pipeline-counter snapshot on shutdown
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

    @SuppressWarnings("rawtypes")
    private static int intConf(Map conf, String key, int def) {
        Object v = conf == null ? null : conf.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
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
