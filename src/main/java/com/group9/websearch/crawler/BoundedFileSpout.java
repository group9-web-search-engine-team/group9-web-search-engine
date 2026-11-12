package com.group9.websearch.crawler;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.apache.storm.spout.ISpoutOutputCollector;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.stormcrawler.spout.FileSpout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link FileSpout} that emits seed URLs from the seed file, with an OPTIONAL emit cap.
 *
 * <p>Capping behaviour:
 * <ul>
 *   <li>The cap is read from {@code crawl.max.seeds}. When it is absent or non-positive
 *       (the default), this spout emits ALL seeds from the file with NO artificial limit —
 *       boundedness comes from the finite seed file, the native {@code MaxDepthFilter},
 *       robots rules, and database-enforced deduplication.</li>
 *   <li>When a positive cap is configured (used by short smoke tests), the spout emits at
 *       most that many seed tuples and then becomes a no-op.</li>
 * </ul>
 *
 * <p>This spout caps only the seed tuples IT emits; it does not implement recursive
 * fetching of discovered URLs. Discovered links are persisted to {@code crawl_state} and
 * fed back into the fetch pipeline by {@link PostgresFrontierSpout}.
 *
 * <p>The cap is enforced precisely by wrapping the {@link SpoutOutputCollector} and
 * counting every {@code emit}/{@code emitDirect} call.
 *
 * <p>Dataset adaptations (both on by default, both configurable):
 * <ul>
 *   <li>{@code crawl.rewrite.dataset.urls}: each seed URL is passed through
 *       {@link DatasetUrlRewriter} before emission, so stale CORD-19 URL shapes (old PMC
 *       host, arXiv PDFs, ...) are fetched at their current crawlable location.</li>
 *   <li>{@code crawl.seeds.skip.done}: on start-up the spout loads the URLs already in the
 *       {@code fetched} state (redirect sources are marked fetched too) from
 *       {@code crawl_state} and does not
 *       re-emit them, so a resumed crawl continues where it stopped instead of re-fetching
 *       the whole 53k-URL seed list. If the DB is unreachable the spout logs a warning and
 *       emits every seed.</li>
 * </ul>
 */
public class BoundedFileSpout extends FileSpout {

    private static final Logger LOG = LoggerFactory.getLogger(BoundedFileSpout.class);

    /** Config key for the OPTIONAL seed emit cap. Non-positive / absent => unlimited. */
    public static final String MAX_SEEDS_PARAM = "crawl.max.seeds";
    /** Default: no cap (emit all seeds). */
    public static final int DEFAULT_MAX_SEEDS = -1;

    /** Config key: skip seeds that a previous run already fetched (default true). */
    public static final String SKIP_DONE_PARAM = "crawl.seeds.skip.done";

    private static final String SELECT_DONE_SQL =
            "SELECT url FROM crawl_state WHERE state = 'fetched'";

    private int maxSeeds = DEFAULT_MAX_SEEDS;
    private transient CountingCollector counting;

    public BoundedFileSpout(String dir, String file, boolean withDiscoveredStatus) {
        super(dir, file, withDiscoveredStatus);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        this.maxSeeds = intConf(conf, MAX_SEEDS_PARAM, DEFAULT_MAX_SEEDS);
        boolean rewrite = boolConf(conf, DatasetUrlRewriter.ENABLED_PARAM, DatasetUrlRewriter.DEFAULT_ENABLED);
        Set<String> done = boolConf(conf, SKIP_DONE_PARAM, true) ? loadDoneUrls(conf) : Set.of();
        this.counting = new CountingCollector(collector, maxSeeds,
                rewrite ? DatasetUrlRewriter::rewrite : UnaryOperator.identity(),
                done::contains);
        // Hand the FileSpout our counting collector so every emission is tallied/capped.
        super.open(conf, context, new SpoutOutputCollector(counting));
        LOG.info("BoundedFileSpout active; seed emit cap = {}, dataset URL rewrite = {}, "
                        + "already-done seeds skipped = {}",
                maxSeeds <= 0 ? "unlimited" : maxSeeds, rewrite, done.size());
    }

    /** Load URLs already fetched/redirected in a previous run. Empty set on any DB error. */
    @SuppressWarnings("rawtypes")
    private static Set<String> loadDoneUrls(Map conf) {
        String url = strConf(conf, CrawlerConstants.PG_URL_PARAM, CrawlerConstants.DEFAULT_PG_URL);
        String user = strConf(conf, CrawlerConstants.PG_USER_PARAM, CrawlerConstants.DEFAULT_PG_USER);
        String pwEnv = strConf(conf, CrawlerConstants.PG_PASSWORD_ENV_PARAM,
                CrawlerConstants.DEFAULT_PG_PASSWORD_ENV);
        Set<String> done = new HashSet<>();
        try (Connection c = DriverManager.getConnection(url, user, System.getenv(pwEnv));
                PreparedStatement ps = c.prepareStatement(SELECT_DONE_SQL);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                done.add(rs.getString(1));
            }
        } catch (SQLException e) {
            LOG.warn("Could not load already-fetched seeds from crawl_state ({}); emitting all seeds.",
                    e.toString());
            return Set.of();
        }
        return done;
    }

    @Override
    public void nextTuple() {
        if (counting != null && counting.reachedCap()) {
            // Stop pulling from the seed buffer entirely once the cap is hit.
            return;
        }
        super.nextTuple();
    }

    /** Visible for testing: how many seed tuples have actually been emitted so far. */
    int emittedCount() {
        return counting == null ? 0 : counting.emitted();
    }

    @SuppressWarnings("rawtypes")
    private static String strConf(Map conf, String key, String def) {
        Object v = conf == null ? null : conf.get(key);
        return v == null ? def : v.toString();
    }

    @SuppressWarnings("rawtypes")
    private static boolean boolConf(Map conf, String key, boolean def) {
        Object v = conf == null ? null : conf.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString().trim());
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

    /**
     * Delegating collector that counts emissions and refuses to emit beyond the cap.
     * This is the authoritative global page limiter. It also rewrites the URL field
     * (tuple index 0) through {@code urlRewriter} and silently drops seeds matched by
     * {@code skip} (already crawled); dropped seeds do not count towards the cap.
     */
    static final class CountingCollector implements ISpoutOutputCollector {

        private final ISpoutOutputCollector delegate;
        private final int cap;
        private final UnaryOperator<String> urlRewriter;
        private final Predicate<String> skip;
        private int emitted = 0;
        private int skipped = 0;

        CountingCollector(ISpoutOutputCollector delegate, int cap) {
            this(delegate, cap, UnaryOperator.identity(), u -> false);
        }

        CountingCollector(ISpoutOutputCollector delegate, int cap,
                UnaryOperator<String> urlRewriter, Predicate<String> skip) {
            this.delegate = delegate;
            this.cap = cap;
            this.urlRewriter = urlRewriter;
            this.skip = skip;
        }

        int skipped() {
            return skipped;
        }

        /**
         * Rewrite the URL in a copy of the tuple; return null if the seed must be skipped.
         */
        private List<Object> prepare(List<Object> tuple) {
            if (tuple == null || tuple.isEmpty() || !(tuple.get(0) instanceof String url)) {
                return tuple;
            }
            String rewritten = urlRewriter.apply(url);
            if (skip.test(rewritten)) {
                skipped++;
                return null;
            }
            if (rewritten.equals(url)) {
                return tuple;
            }
            List<Object> copy = new ArrayList<>(tuple);
            copy.set(0, rewritten);
            return copy;
        }

        boolean reachedCap() {
            // A non-positive cap means "unlimited".
            return cap > 0 && emitted >= cap;
        }

        int emitted() {
            return emitted;
        }

        @Override
        public List<Integer> emit(String streamId, List<Object> tuple, Object messageId) {
            if (reachedCap()) {
                return List.of();
            }
            List<Object> out = prepare(tuple);
            if (out == null) {
                return List.of();
            }
            emitted++;
            return delegate.emit(streamId, out, messageId);
        }

        @Override
        public void emitDirect(int taskId, String streamId, List<Object> tuple, Object messageId) {
            if (reachedCap()) {
                return;
            }
            List<Object> out = prepare(tuple);
            if (out == null) {
                return;
            }
            emitted++;
            delegate.emitDirect(taskId, streamId, out, messageId);
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void reportError(Throwable error) {
            delegate.reportError(error);
        }

        @Override
        public long getPendingCount() {
            return delegate.getPendingCount();
        }
    }
}
