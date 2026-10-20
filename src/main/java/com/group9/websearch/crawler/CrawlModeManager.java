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
                int recovered = repository.recoverStaleInProgress();
                String summary = String.format(
                        "RESUME: preserved existing data; recovered %d stale in_progress row(s) to pending. "
                                + "Continuing eligible pending work.",
                        recovered);
                LOG.info(summary);
                return summary;
            }
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
    }
}
