package com.group9.websearch.crawler;

import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;
import org.apache.stormcrawler.ConfigurableTopology;
import org.apache.stormcrawler.Constants;
import org.apache.stormcrawler.bolt.FetcherBolt;
import org.apache.stormcrawler.bolt.JSoupParserBolt;
import org.apache.stormcrawler.bolt.URLPartitionerBolt;
import org.apache.stormcrawler.util.StringTabScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local-mode entry point for the Group 9 crawler vertical slice.
 *
 * <p>This assembles a genuine Apache StormCrawler topology from native components and a
 * single justified custom adapter ({@link PostgresStatusUpdaterBolt}):
 *
 * <pre>
 *   FileSpout(seeds)
 *       -> URLPartitionerBolt        (native: per-host/domain partitioning for politeness)
 *       -> FetcherBolt               (native: robots.txt, host delay, timeouts, byte cap, redirects)
 *       -> JSoupParserBolt           (native: HTML parse + outgoing-link extraction)
 *       -> S3RawHtmlBolt             (custom: upload raw HTML to S3, record s3.key, page budget)
 *       -> PostgresStatusUpdaterBolt (custom: persists crawl_state/documents/links)
 * </pre>
 *
 * The Fetcher and Parser emit status tuples (DISCOVERED/FETCHED/ERROR/REDIRECTION) on
 * {@link Constants#StatusStreamName}; the status-updater subscribes to that stream and
 * writes the frontier/metadata/graph to PostgreSQL. Discovered links are therefore fed
 * back into the frontier through the status stream, as in the standard StormCrawler
 * crawl loop.
 *
 * <p>The class name and package match the Maven Shade {@code mainClass} manifest entry
 * {@code com.group9.websearch.crawler.CrawlerTopologyRunner}.
 *
 * <p><b>Running a bounded local crawl</b> is a manual, explicit action; this class never
 * runs automatically. See the README / completion report for the exact command.
 */
public class CrawlerTopologyRunner extends ConfigurableTopology {

    private static final Logger LOG = LoggerFactory.getLogger(CrawlerTopologyRunner.class);

    private static final String TOPOLOGY_NAME = "group9-crawler-mvp";

    /** Default crawler configuration file, relative to the working directory. */
    static final String DEFAULT_CONF_FILE = "config/crawler-conf.yaml";

    public static void main(String[] args) {
        // StormCrawler's getConf() only merges crawler-default.yaml (which leaves
        // http.agent.name unset) plus whatever is supplied via "-conf <file>". If the
        // operator forgets "-conf", the FetcherBolt dies with "No agents listed in
        // 'http.agent.name'". To make the packaged jar self-sufficient while keeping
        // crawler-conf.yaml as the single source of truth, default to it when no -conf
        // was supplied and the file is present in the working directory.
        ConfigurableTopology.start(new CrawlerTopologyRunner(), withDefaultConf(args));
    }

    /**
     * If the caller did not pass {@code -conf} and {@link #DEFAULT_CONF_FILE} exists in the
     * current working directory, append {@code -conf config/crawler-conf.yaml} so the
     * crawler is configured correctly without requiring the flag. Otherwise returns the
     * args unchanged.
     */
    static String[] withDefaultConf(String[] args) {
        if (hasFlag(args, "-conf")) {
            return args;
        }
        if (!new java.io.File(DEFAULT_CONF_FILE).isFile()) {
            LOG.warn("No -conf supplied and default config '{}' not found in working dir; "
                    + "StormCrawler defaults will be used (http.agent.name may be unset).",
                    DEFAULT_CONF_FILE);
            return args;
        }
        String[] augmented = new String[args.length + 2];
        System.arraycopy(args, 0, augmented, 0, args.length);
        augmented[args.length] = "-conf";
        augmented[args.length + 1] = DEFAULT_CONF_FILE;
        LOG.info("No -conf supplied; defaulting to '{}'.", DEFAULT_CONF_FILE);
        return augmented;
    }

    /** Default bounded local-mode runtime (seconds) if no -ttl is supplied. */
    private static final int DEFAULT_LOCAL_TTL_SECONDS = 120;

    @Override
    protected int run(String[] args) {
        Config conf = getConf();

        // Explicit startup mode: resume (default, never resets) or clean-start (resets the
        // crawl tables then re-seeds). The DB never resets automatically.
        CrawlMode mode;
        try {
            mode = CrawlMode.parse(strFlag(args, "--mode", null));
        } catch (IllegalArgumentException e) {
            LOG.error(e.getMessage());
            return 2;
        }
        boolean purgeS3 = hasFlag(args, "--purge-s3");
        int modeResult = applyCrawlMode(conf, mode, purgeS3);
        if (modeResult != 0) {
            return modeResult;
        }

        String seedDir = strConf(conf, CrawlerConstants.SEED_DIR_PARAM, ".");
        String seedFile = strConf(conf, CrawlerConstants.SEED_FILE_PARAM, "seeds.txt");
        LOG.info("Building crawler topology with seeds {}/{}", seedDir, seedFile);

        TopologyBuilder builder = buildTopology(seedDir, seedFile);

        // StormCrawler 3.1.0's ConfigurableTopology.submit() submits to a remote cluster
        // (StormSubmitter). For the bounded smoke crawl we run in local mode explicitly
        // via LocalCluster, which needs no standalone Storm install, then shut down after
        // a TTL so the run stays bounded.
        boolean local = hasFlag(args, "-local");
        if (local) {
            int ttl = intFlag(args, "-ttl", DEFAULT_LOCAL_TTL_SECONDS);
            return runLocal(conf, builder, ttl);
        }
        return submit(TOPOLOGY_NAME, conf, builder);
    }

    /** Run the topology in an embedded LocalCluster for {@code ttlSeconds}, then shut down. */
    int runLocal(Config conf, TopologyBuilder builder, int ttlSeconds) {
        // StormCrawler's ConfigurableTopology.submit() registers kryo serializers for
        // Metadata and Status before submitting. Our local path bypasses submit(), so we
        // MUST register them here too; otherwise Metadata is serialized with Storm's
        // default serializer and arrives EMPTY at downstream bolts (losing fetch.*,
        // protocol.* and our s3.key), which previously left documents.s3_key null.
        Config.registerSerialization(conf, org.apache.stormcrawler.Metadata.class);
        Config.registerSerialization(conf, org.apache.stormcrawler.persistence.Status.class);

        StormTopology topology = builder.createTopology();
        LOG.info("Submitting topology '{}' to LocalCluster for {}s (bounded smoke crawl)",
                TOPOLOGY_NAME, ttlSeconds);

        // Optional topic distillation: a periodic, overlap-guarded background pass that
        // promotes hub neighbours in the frontier. Runs interspersed with crawling, not per
        // page. Disabled by default; fully separate from the per-page QMax path.
        com.group9.websearch.crawler.distill.DistillationScheduler distiller = maybeStartDistiller(conf);
        try (LocalCluster cluster = new LocalCluster()) {
            cluster.submitTopology(TOPOLOGY_NAME, conf, topology);
            long deadline = System.currentTimeMillis() + ttlSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000L);
            }
            LOG.info("TTL elapsed; killing topology and shutting down LocalCluster.");
            cluster.killTopology(TOPOLOGY_NAME);
            Thread.sleep(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Local run interrupted", e);
            return 1;
        } catch (Exception e) {
            LOG.error("Local run failed", e);
            return 1;
        } finally {
            if (distiller != null) {
                distiller.stop();
            }
        }
        return 0;
    }

    /**
     * Start the distillation scheduler if {@code distill.enabled} is true. Returns the
     * scheduler (to stop later) or null when disabled. The DB password is read from the
     * same env var the crawler uses; distillation shares the configured crawler role.
     */
    private com.group9.websearch.crawler.distill.DistillationScheduler maybeStartDistiller(Config conf) {
        boolean enabled = boolConf(conf, CrawlerConstants.DISTILL_ENABLED_PARAM,
                CrawlerConstants.DEFAULT_DISTILL_ENABLED);
        if (!enabled) {
            return null;
        }
        String url = strConf(conf, CrawlerConstants.PG_URL_PARAM, CrawlerConstants.DEFAULT_PG_URL);
        String user = strConf(conf, CrawlerConstants.PG_USER_PARAM, CrawlerConstants.DEFAULT_PG_USER);
        String passwordEnv = strConf(conf, CrawlerConstants.PG_PASSWORD_ENV_PARAM,
                CrawlerConstants.DEFAULT_PG_PASSWORD_ENV);
        String password = System.getenv(passwordEnv);
        int interval = intConf(conf, CrawlerConstants.DISTILL_INTERVAL_SECS_PARAM,
                CrawlerConstants.DEFAULT_DISTILL_INTERVAL_SECS);
        int topHubs = intConf(conf, CrawlerConstants.DISTILL_TOP_HUBS_PARAM,
                CrawlerConstants.DEFAULT_DISTILL_TOP_HUBS);
        double boost = doubleConf(conf, CrawlerConstants.DISTILL_BOOST_PARAM,
                CrawlerConstants.DEFAULT_DISTILL_BOOST);
        com.group9.websearch.crawler.distill.DistillationScheduler scheduler =
                new com.group9.websearch.crawler.distill.DistillationScheduler(
                        url, user, password, interval, topHubs, boost);
        scheduler.start();
        return scheduler;
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static int intFlag(String[] args, String flag, int def) {
        if (args == null) {
            return def;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1].trim());
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        return def;
    }

    private static String strFlag(String[] args, String flag, String def) {
        if (args == null) {
            return def;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }

    /**
     * Apply the chosen {@link CrawlMode} against the database before the topology starts.
     * Opens a short-lived JDBC connection using the configured admin role (which defaults
     * to the crawler role) and runs the reset (clean-start) or stale-recovery (resume).
     * Returns 0 on success, non-zero to abort the run.
     *
     * <p>Clean-start preserves S3 objects. {@code --purge-s3} is accepted but intentionally
     * NOT implemented as a destructive S3 delete here; it only logs that raw HTML is
     * preserved, so no object is ever removed without a dedicated, reviewed implementation.
     */
    int applyCrawlMode(Config conf, CrawlMode mode, boolean purgeS3) {
        if (purgeS3) {
            LOG.warn("--purge-s3 requested but S3 deletion is not implemented; raw HTML in S3 is PRESERVED.");
        }
        String url = strConf(conf, CrawlerConstants.PG_URL_PARAM, CrawlerConstants.DEFAULT_PG_URL);
        String user = resolveAdminUser(conf);
        String password = resolveAdminPassword(conf);

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(true);
            com.group9.websearch.crawler.persistence.FrontierRepository repo =
                    new com.group9.websearch.crawler.persistence.FrontierRepository(conn);
            String summary = new CrawlModeManager(repo).apply(mode);
            LOG.info("Crawl mode applied: {}", summary);
            return 0;
        } catch (java.sql.SQLException e) {
            if (mode == CrawlMode.CLEAN_START) {
                LOG.error("CLEAN_START reset failed (the DB role must have DELETE privilege on "
                        + "links/documents/crawl_state): {}", e.toString());
            } else {
                LOG.error("RESUME recovery failed: {}", e.toString());
            }
            return 1;
        }
    }

    private static String resolveAdminUser(Config conf) {
        String adminUser = strConf(conf, CrawlerConstants.PG_ADMIN_USER_PARAM, null);
        if (adminUser != null) {
            return adminUser;
        }
        return strConf(conf, CrawlerConstants.PG_USER_PARAM, CrawlerConstants.DEFAULT_PG_USER);
    }

    private static String resolveAdminPassword(Config conf) {
        String adminEnv = strConf(conf, CrawlerConstants.PG_ADMIN_PASSWORD_ENV_PARAM, null);
        if (adminEnv == null) {
            adminEnv = strConf(conf, CrawlerConstants.PG_PASSWORD_ENV_PARAM,
                    CrawlerConstants.DEFAULT_PG_PASSWORD_ENV);
        }
        return System.getenv(adminEnv);
    }

    /**
     * Assemble the topology. Extracted from {@link #run} so it can be unit-tested without
     * submitting to a Storm cluster.
     */
    public TopologyBuilder buildTopology(String seedDir, String seedFile) {
        TopologyBuilder builder = new TopologyBuilder();

        // Seed spout: BoundedFileSpout emits (url, metadata) tuples via StringTabScheme.
        // By default it emits ALL seeds (no artificial cap); an optional crawl.max.seeds
        // cap is used only by short smoke tests.
        BoundedFileSpout spout = new BoundedFileSpout(seedDir, seedFile, false);
        spout.setScheme(new StringTabScheme());
        builder.setSpout("seeds", spout);

        // Frontier spout: feeds discovered-but-unfetched URLs from crawl_state back into
        // the fetch pipeline (the recursive crawl loop). Atomic claim prevents re-emitting
        // the same URL, and depth/robots/dedup keep the crawl bounded.
        builder.setSpout("frontier", new PostgresFrontierSpout());

        // Partition URLs by host/domain so the fetcher can enforce per-host politeness.
        // Both the seed spout and the frontier spout feed the partitioner.
        builder.setBolt("partitioner", new URLPartitionerBolt())
                .shuffleGrouping("seeds")
                .shuffleGrouping("frontier");

        // Native fetcher: robots.txt, host delay, timeouts, size cap, redirect handling.
        builder.setBolt("fetch", new FetcherBolt())
                .fieldsGrouping("partitioner", new Fields("key"));

        // Native HTML parser: content + outgoing link extraction.
        builder.setBolt("parse", new JSoupParserBolt())
                .localOrShuffleGrouping("fetch");

        // Custom S3 storage: upload raw HTML, stamp s3.key into metadata, enforce page budget.
        // Sits on the parser's main (content) stream so s3.key is set before persistence.
        builder.setBolt("s3", new S3RawHtmlBolt())
                .localOrShuffleGrouping("parse");

        // Custom status updater: persist crawl_state/documents/links to PostgreSQL.
        // It extends StormCrawler's AbstractStatusUpdaterBolt, whose execute() requires a
        // "status" field on every incoming tuple, so it MUST only consume status-stream
        // tuples (fields: url, status, metadata). It subscribes to the status stream from
        // fetch (fetch errors/redirects) and parse (FETCHED + discovered outlinks).
        //
        // It does NOT subscribe to the S3 bolt's MAIN stream: that carries
        // (url, content, metadata) with no "status" field and would crash the bolt with
        // "status does not exist". Instead it consumes the S3 bolt's DEDICATED SCALAR
        // stream (S3RawHtmlBolt.S3_STATUS_STREAM), on which the S3 bolt emits the fetched
        // document's (url, httpStatus, contentLength, s3Key) as primitive/String fields
        // after a successful upload — this reliably persists the documents row (with real
        // values) even under Storm LocalCluster, where a Metadata object would arrive
        // empty. Fetch/parse status streams carry fetch errors, redirects and discovered
        // outlinks.
        builder.setBolt("status", new PostgresStatusUpdaterBolt())
                .localOrShuffleGrouping("fetch", Constants.StatusStreamName)
                .localOrShuffleGrouping("parse", Constants.StatusStreamName)
                .localOrShuffleGrouping("s3", S3RawHtmlBolt.S3_STATUS_STREAM)
                .localOrShuffleGrouping("s3", S3RawHtmlBolt.DISCOVERED_LINK_STREAM);

        return builder;
    }

    private static String strConf(Config conf, String key, String def) {
        Object v = conf.get(key);
        return v == null ? def : v.toString();
    }

    private static boolean boolConf(Config conf, String key, boolean def) {
        Object v = conf.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static int intConf(Config conf, String key, int def) {
        Object v = conf.get(key);
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

    private static double doubleConf(Config conf, String key, double def) {
        Object v = conf.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
