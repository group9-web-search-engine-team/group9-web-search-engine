package com.group9.websearch.crawler;

import com.group9.websearch.crawler.persistence.FrontierRepository;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the chosen {@link CrawlMode} to the crawl database before the topology starts.
 *
 * <ul>
 *   <li>{@link CrawlMode#CLEAN_START}: reset links/documents/crawl_state in one
 *       FK-respecting transaction via {@link FrontierRepository#resetCrawlTables()}. The
 *       frontier is then re-initialized naturally by the seed spout reading the configured
 *       seed file. S3 objects are preserved (this manager never deletes S3 data).</li>
 *   <li>{@link CrawlMode#RESUME}: never resets; recovers stale {@code in_progress} rows
 *       back to {@code pending} via {@link FrontierRepository#recoverStaleInProgress()} and
 *       continues.</li>
 * </ul>
 *
 * <p>Pure DB orchestration with no Storm dependency, so it is unit-testable against a
 * mocked {@link FrontierRepository}.
 */
public final class CrawlModeManager {

    private static final Logger LOG = LoggerFactory.getLogger(CrawlModeManager.class);

    private final FrontierRepository repository;

    public CrawlModeManager(FrontierRepository repository) {
        this.repository = repository;
    }

    /**
     * Apply the given mode. For clean-start, resets the crawl tables. For resume, recovers
     * stale in-progress rows. Returns a short human-readable summary of what happened.
     *
     * @throws SQLException if a DB operation fails (e.g. the role lacks DELETE privilege
     *                      for a clean-start reset)
     */
    public String apply(CrawlMode mode) throws SQLException {
        // Backward-compatible default: recover ALL stale in_progress (no time threshold), with
        // a conservative retry budget. Callers that want time-based recovery use the overload.
        return apply(mode, 0L, 3);
    }

    /**
     * Apply the given mode with TIME-BASED stale recovery for RESUME.
     *
     * @param mode             crawl mode (clean-start or resume)
     * @param staleAfterSecs   staleness threshold in seconds; when &gt; 0, only in_progress rows
     *                         whose last_attempt is older than this are recovered (so a running
     *                         crawl's fresh claims are never stolen); when &lt;= 0, ALL stale
     *                         in_progress rows are recovered (legacy behaviour).
     * @param maxFetchErrors   retry budget; stale rows that have exhausted it are marked failed.
     */
    public String apply(CrawlMode mode, long staleAfterSecs, int maxFetchErrors)
            throws SQLException {
        switch (mode) {
            case CLEAN_START -> {
                int[] deleted = repository.resetCrawlTables();
                String summary = String.format(
                        "CLEAN_START: reset complete (deleted links=%d, documents=%d, crawl_state=%d). "
                                + "Frontier will be re-initialized from the configured seed file. "
                                + "S3 objects preserved.",
                        deleted[0], deleted[1], deleted[2]);
                LOG.info(summary);
                return summary;
            }
            case RESUME -> {
                final String summary;
                if (staleAfterSecs > 0) {
                    FrontierRepository.StaleRecovery r =
                            repository.recoverStaleInProgress(staleAfterSecs, maxFetchErrors);
                    summary = String.format(
                            "RESUME: preserved existing data; time-based recovery (stale > %ds): "
                                    + "%d stale in_progress row(s) returned to pending, %d marked "
                                    + "failed (retries exhausted). Continuing eligible pending work.",
                            staleAfterSecs, r.recoveredToPending(), r.failedExhausted());
                } else {
                    int recovered = repository.recoverStaleInProgress();
                    summary = String.format(
                            "RESUME: preserved existing data; recovered %d stale in_progress row(s) "
                                    + "to pending. Continuing eligible pending work.",
                            recovered);
                }
                LOG.info(summary);
                return summary;
            }
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
    }
}
