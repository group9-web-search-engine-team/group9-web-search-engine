package com.group9.websearch.crawler;

import java.util.List;
import java.util.Map;
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
 */
public class BoundedFileSpout extends FileSpout {

    private static final Logger LOG = LoggerFactory.getLogger(BoundedFileSpout.class);

    /** Config key for the OPTIONAL seed emit cap. Non-positive / absent => unlimited. */
    public static final String MAX_SEEDS_PARAM = "crawl.max.seeds";
    /** Default: no cap (emit all seeds). */
    public static final int DEFAULT_MAX_SEEDS = -1;

    private int maxSeeds = DEFAULT_MAX_SEEDS;
    private transient CountingCollector counting;

    public BoundedFileSpout(String dir, String file, boolean withDiscoveredStatus) {
        super(dir, file, withDiscoveredStatus);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        this.maxSeeds = intConf(conf, MAX_SEEDS_PARAM, DEFAULT_MAX_SEEDS);
        this.counting = new CountingCollector(collector, maxSeeds);
        // Hand the FileSpout our counting collector so every emission is tallied/capped.
        super.open(conf, context, new SpoutOutputCollector(counting));
        LOG.info("BoundedFileSpout active; seed emit cap = {}",
                maxSeeds <= 0 ? "unlimited" : maxSeeds);
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
     * This is the authoritative global page limiter.
     */
    static final class CountingCollector implements ISpoutOutputCollector {

        private final ISpoutOutputCollector delegate;
        private final int cap;
        private int emitted = 0;

        CountingCollector(ISpoutOutputCollector delegate, int cap) {
            this.delegate = delegate;
            this.cap = cap;
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
            emitted++;
            return delegate.emit(streamId, tuple, messageId);
        }

        @Override
        public void emitDirect(int taskId, String streamId, List<Object> tuple, Object messageId) {
            if (reachedCap()) {
                return;
            }
            emitted++;
            delegate.emitDirect(taskId, streamId, tuple, messageId);
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
