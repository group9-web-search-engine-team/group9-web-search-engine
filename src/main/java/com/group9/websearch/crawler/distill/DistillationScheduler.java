package com.group9.websearch.crawler.distill;

import com.group9.websearch.crawler.persistence.FrontierRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs topic distillation periodically, INTERSPERSED with normal crawling rather than on
 * every fetched page. A single-thread scheduler fires every {@code intervalSeconds}; an
 * {@link AtomicBoolean} guard prevents overlapping runs (a long graph pass is skipped, not
 * queued). Each run uses its own short-lived JDBC connection so it never contends with the
 * crawl bolts' connections.
 *
 * <p>This exists so the expensive HITS graph computation happens on a bounded schedule, not
 * synchronously in a performance-critical fetch callback.
 */
public final class DistillationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DistillationScheduler.class);

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int intervalSeconds;
    private final int topHubs;
    private final double boost;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public DistillationScheduler(String jdbcUrl, String dbUser, String dbPassword,
                                 int intervalSeconds, int topHubs, double boost) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.topHubs = topHubs;
        this.boost = boost;
    }

    /** Start the periodic distillation. Idempotent: a second call is a no-op. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "distillation-scheduler");
            t.setDaemon(true);
            return t;
        });
        LOG.info("Distillation scheduler starting: interval={}s topHubs={} boost={}",
                intervalSeconds, topHubs, boost);
        executor.scheduleWithFixedDelay(this::runOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** Stop the scheduler and release resources. */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Run a single distillation pass unless one is already in progress (overlap guard).
     * Exceptions are logged and swallowed so a transient DB issue never kills the scheduler
     * or the crawl.
     */
    void runOnce() {
        if (!running.compareAndSet(false, true)) {
            LOG.info("Distillation: previous run still in progress; skipping this tick.");
            return;
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            conn.setAutoCommit(true);
            FrontierRepository repo = new FrontierRepository(conn);
            int promoted = new TopicDistiller(repo, topHubs, boost).distill();
            LOG.info("Distillation pass complete; promoted {} URL(s).", promoted);
        } catch (SQLException e) {
            LOG.warn("Distillation pass failed (continuing crawl): {}", e.toString());
        } catch (Exception e) {
            LOG.warn("Distillation pass error (continuing crawl): {}", e.toString());
        } finally {
            running.set(false);
        }
    }
}
