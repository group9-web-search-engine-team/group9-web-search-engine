package com.group9.websearch.crawler;

import com.group9.websearch.crawler.persistence.FrontierRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frontier spout that feeds discovered-but-unfetched URLs from the PostgreSQL
 * {@code crawl_state} table back into the fetch pipeline, implementing the recursive
 * crawl loop that the seed-only {@link BoundedFileSpout} does not.
 *
 * <p>How it avoids duplicate / infinite recrawl:
 * <ul>
 *   <li>It only claims rows in state {@code pending} that are due
 *       ({@code next_attempt} null or past), and the claim atomically flips them to
 *       {@code in_progress} ({@link FrontierRepository#claimPending(int)} using
 *       {@code FOR UPDATE SKIP LOCKED}). A claimed URL therefore leaves the pending set
 *       immediately and is not re-emitted by a later poll.</li>
 *   <li>Terminal outcomes ({@code fetched} / {@code failed}) are written by the status
 *       updater, so a URL is never returned to {@code pending} unless an explicit retry
 *       schedules it.</li>
 *   <li>Depth is bounded by the native {@code MaxDepthFilter} (configured in
 *       {@code urlfilters.json}); dedup is enforced by the {@code crawl_state} primary key
 *       on {@code url}.</li>
 * </ul>
 *
 * <p>Emits {@code (url, metadata)} on the default stream — the same shape the
 * {@code URLPartitionerBolt}/{@code FetcherBolt} consume from the seed spout.
 */
public class PostgresFrontierSpout extends BaseRichSpout {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresFrontierSpout.class);

    /** Max URLs to claim per poll. */
    static final int CLAIM_BATCH = 10;

    private transient SpoutOutputCollector collector;
    private transient Connection connection;
    private transient FrontierRepository repository;
    private transient Deque<String> buffer;

    private String jdbcUrl;
    private String dbUser;
    private String dbPassword;

    @Override
    @SuppressWarnings("rawtypes")
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        this.collector = collector;
        this.buffer = new ArrayDeque<>();

        this.jdbcUrl = strConf(conf, CrawlerConstants.PG_URL_PARAM, CrawlerConstants.DEFAULT_PG_URL);
        this.dbUser = strConf(conf, CrawlerConstants.PG_USER_PARAM, CrawlerConstants.DEFAULT_PG_USER);
        String passwordEnv = strConf(conf, CrawlerConstants.PG_PASSWORD_ENV_PARAM,
                CrawlerConstants.DEFAULT_PG_PASSWORD_ENV);
        this.dbPassword = System.getenv(passwordEnv);

        try {
            this.connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
            this.connection.setAutoCommit(true);
            this.repository = new FrontierRepository(connection);
            LOG.info("PostgresFrontierSpout connected to {}", jdbcUrl);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open PostgreSQL connection to " + jdbcUrl, e);
        }
    }

    @Override
    public void nextTuple() {
        if (buffer.isEmpty()) {
            try {
                List<String> claimed = repository.claimPending(CLAIM_BATCH);
                buffer.addAll(claimed);
                if (!claimed.isEmpty()) {
                    LOG.info("Frontier claimed {} pending URL(s) from crawl_state", claimed.size());
                }
            } catch (SQLException e) {
                LOG.error("Frontier claim query failed: {}", e.toString());
            }
        }
        if (buffer.isEmpty()) {
            // Nothing due; avoid a busy spin.
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        String url = buffer.poll();
        collector.emit(new Values(url, new Metadata()), url);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("url", "metadata"));
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("Error closing PostgreSQL connection", e);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static String strConf(Map conf, String key, String def) {
        Object v = conf == null ? null : conf.get(key);
        return v == null ? def : v.toString();
    }
}
