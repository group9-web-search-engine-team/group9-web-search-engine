package com.group9.websearch.index;

import com.group9.websearch.index.mapreduce.IndexJob;
import com.group9.websearch.index.mapreduce.SpimiIndexMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.GenericOptionsParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the Week 3 distributed SPIMI indexing pipeline.
 *
 * <p>It is intentionally the ONLY main class in the indexer artifact; it never touches Storm
 * or launches a crawl topology. Operations:
 * <ul>
 *   <li>{@code export}   -- PostgreSQL + S3 -> local JSONL (no Hadoop needed).</li>
 *   <li>{@code index}    -- run the MapReduce SPIMI job over JSONL in HDFS -> index in HDFS.</li>
 *   <li>{@code validate} -- structurally validate a produced index directory.</li>
 *   <li>{@code full} / {@code run} -- export then index (index phase still runs on Hadoop).</li>
 * </ul>
 *
 * <p>Hadoop {@code -D}/{@code -fs}/{@code -conf} generic options are honoured for the Hadoop
 * phases via {@link GenericOptionsParser}. Exit codes: 0 success, 2 usage error, 1 runtime
 * failure, 3 validation failed.
 */
public final class IndexerCli {

    private static final Logger LOG = LoggerFactory.getLogger(IndexerCli.class);

    static final int EXIT_OK = 0;
    static final int EXIT_RUNTIME = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_VALIDATION = 3;

    private IndexerCli() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] rawArgs) throws Exception {
        if (rawArgs.length == 0) {
            usage("no operation specified");
            return EXIT_USAGE;
        }
        String op = rawArgs[0].toLowerCase(java.util.Locale.ROOT);
        try {
            switch (op) {
                case "export":
                    // Export talks only to PostgreSQL + S3 + the local filesystem. It needs no
                    // Hadoop, so we do NOT create a Hadoop Configuration / GenericOptionsParser
                    // here -- that lets `export` run with just the exporter deps on the classpath
                    // (no hadoop-client required), as documented.
                    return doExport(sub(rawArgs));
                case "help":
                case "-h":
                case "--help":
                    usage(null);
                    return EXIT_OK;
                case "index":
                case "validate":
                case "stats":
                case "full":
                case "run": {
                    // Hadoop operations: let Hadoop strip -D/-conf/-fs first; remaining args are
                    // ours. Hadoop classes are only touched on this path.
                    Configuration conf = new Configuration();
                    String[] args = new GenericOptionsParser(conf, rawArgs).getRemainingArgs();
                    String[] rest = sub(args);
                    switch (op) {
                        case "index":    return doIndex(conf, rest);
                        case "validate": return doValidate(conf, rest);
                        case "stats":    return doStats(conf, rest);
                        default:         return doFull(conf, rest); // full / run
                    }
                }
                default:
                    usage("unknown operation: " + op);
                    return EXIT_USAGE;
            }
        } catch (IllegalArgumentException e) {
            usage(e.getMessage());
            return EXIT_USAGE;
        } catch (Exception e) {
            LOG.error("operation '{}' failed: {}", op, e.toString());
            return EXIT_RUNTIME;
        }
    }

    // ---- export ----

    private static int doExport(String[] a) throws Exception {
        ExporterConfig cfg = new ExporterConfig();
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--output": cfg.outputPath = req(a, ++i, "--output"); break;
                case "--jdbc-url": cfg.jdbcUrl = req(a, ++i, "--jdbc-url"); break;
                case "--db-user": cfg.dbUser = req(a, ++i, "--db-user"); break;
                case "--password-env": cfg.passwordEnv = req(a, ++i, "--password-env"); break;
                case "--s3-bucket": cfg.s3Bucket = req(a, ++i, "--s3-bucket"); break;
                case "--s3-region": cfg.s3Region = req(a, ++i, "--s3-region"); break;
                case "--max-body-chars": cfg.maxBodyChars = Integer.parseInt(req(a, ++i, "--max-body-chars")); break;
                case "--allow-non-ok": cfg.requireOkStatus = false; break;
                case "--dry-run": cfg.dryRun = true; break;
                default: throw new IllegalArgumentException("unknown export flag: " + a[i]);
            }
        }
        DocumentExporter.Stats stats = new DocumentExporter(cfg).run();
        LOG.info("EXPORT {}", stats);
        System.out.println("export: " + stats);
        return EXIT_OK;
    }

    // ---- index ----

    private static int doIndex(Configuration conf, String[] a) throws Exception {
        IndexJob.Params p = new IndexJob.Params();
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--input": p.inputPath = req(a, ++i, "--input"); break;
                case "--output": p.outputPath = req(a, ++i, "--output"); break;
                case "--reducers": p.numReducers = Integer.parseInt(req(a, ++i, "--reducers")); break;
                case "--spimi-max-entries": p.spimiMaxEntries = Long.parseLong(req(a, ++i, "--spimi-max-entries")); break;
                case "--overwrite": p.overwrite = true; break;
                case "--job-name": p.jobName = req(a, ++i, "--job-name"); break;
                default: throw new IllegalArgumentException("unknown index flag: " + a[i]);
            }
        }
        if (p.inputPath == null || p.outputPath == null) {
            throw new IllegalArgumentException("index requires --input and --output");
        }
        return IndexJob.run(conf, p);
    }

    // ---- validate ----

    private static int doValidate(Configuration conf, String[] a) throws Exception {
        String path = null;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("--index")) {
                path = req(a, ++i, "--index");
            } else {
                throw new IllegalArgumentException("unknown validate flag: " + a[i]);
            }
        }
        if (path == null) {
            throw new IllegalArgumentException("validate requires --index <path>");
        }
        IndexValidator.Result r = IndexValidator.validate(conf, path);
        System.out.println("validate: " + r);
        for (String problem : r.problems) {
            System.out.println("  problem: " + problem);
        }
        return r.valid() ? EXIT_OK : EXIT_VALIDATION;
    }

    // ---- stats: compute BM25 corpus statistics from the final merged index ----

    private static int doStats(Configuration conf, String[] a) throws Exception {
        String indexPath = null;
        String statsOut = "stats/corpus-stats.json";
        String dfOut = "stats/df.tsv";
        boolean requireComplete = false;
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--index": indexPath = req(a, ++i, "--index"); break;
                case "--stats-out": statsOut = req(a, ++i, "--stats-out"); break;
                case "--df-out": dfOut = req(a, ++i, "--df-out"); break;
                case "--require-complete": requireComplete = true; break;
                case "--no-df": dfOut = null; break;
                default: throw new IllegalArgumentException("unknown stats flag: " + a[i]);
            }
        }
        if (indexPath == null) {
            throw new IllegalArgumentException("stats requires --index <path>");
        }
        StatsCalculator calc = new StatsCalculator(conf);
        StatsCalculator.Result result = calc.compute(indexPath);

        // Report (never claims success of anything not computed).
        System.out.println("corpus statistics for index: " + indexPath);
        System.out.println("  complete:              " + result.artifact.complete);
        System.out.println("  N (unique documents):  " + result.artifact.numDocs);
        System.out.println("  unique terms:          " + result.artifact.numTerms);
        System.out.println("  total indexed tokens:  " + result.artifact.totalTokens);
        System.out.printf("  Lave (avg doc length): %.4f%n", result.artifact.avgDocLength);
        System.out.println("  min doc length:        " + result.artifact.minDocLength);
        System.out.println("  max doc length:        " + result.artifact.maxDocLength);
        System.out.printf("  median doc length:     %.1f%n", result.artifact.medianDocLength);
        if (!result.problems.isEmpty()) {
            System.out.println("  problems / unreliable statistics:");
            for (String p : result.problems) {
                System.out.println("    - " + p);
            }
        }

        if (requireComplete && !result.artifact.complete) {
            System.err.println("stats: index is NOT complete; not publishing "
                    + "(remove --require-complete to publish anyway).");
            return EXIT_VALIDATION;
        }

        calc.publish(result, statsOut, dfOut);
        System.out.println("published stats artifact: " + statsOut
                + (dfOut == null ? " (df sidecar skipped)" : " and df sidecar: " + dfOut));
        if (!result.artifact.complete) {
            System.out.println("NOTE: artifact is marked incomplete; BM25 will refuse to use it "
                    + "until indexing completes and stats are recomputed.");
        }
        return EXIT_OK;
    }

    // ---- full: export then index ----

    private static int doFull(Configuration conf, String[] a) throws Exception {
        // Split args between export and index at the "--" separator if present; otherwise
        // flags are routed by name. To keep it simple and explicit, require the caller to
        // pass index flags; export uses defaults unless export-specific flags are given.
        // Here we run export with its defaults (or shared flags) then index.
        java.util.List<String> exportArgs = new java.util.ArrayList<>();
        java.util.List<String> indexArgs = new java.util.ArrayList<>();
        java.util.List<String> target = indexArgs;
        for (String arg : a) {
            if (arg.equals("--export")) { target = exportArgs; continue; }
            if (arg.equals("--index-args")) { target = indexArgs; continue; }
            target.add(arg);
        }
        int e = doExport(exportArgs.toArray(new String[0]));
        if (e != EXIT_OK) {
            return e;
        }
        return doIndex(conf, indexArgs.toArray(new String[0]));
    }

    // ---- helpers ----

    private static String[] sub(String[] args) {
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    private static String req(String[] a, int i, String flag) {
        if (i >= a.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return a[i];
    }

    private static void usage(String err) {
        if (err != null) {
            System.err.println("error: " + err);
        }
        System.err.println(
            "Usage: IndexerCli <operation> [flags]   (Hadoop -D/-conf/-fs options supported)\n"
            + "\n"
            + "Operations:\n"
            + "  export   --output <path> [--jdbc-url U] [--db-user U] [--password-env VAR]\n"
            + "           [--s3-bucket B] [--s3-region R] [--max-body-chars N]\n"
            + "           [--allow-non-ok] [--dry-run]\n"
            + "      PostgreSQL + S3 -> local JSONL. Password read from env var (default\n"
            + "      WEBSEARCH_PG_PASSWORD). Never launches a crawl.\n"
            + "\n"
            + "  index    --input <hdfsPath> --output <hdfsPath> [--reducers N]\n"
            + "           [--spimi-max-entries N] [--overwrite] [--job-name NAME]\n"
            + "      MapReduce SPIMI: JSONL -> term-partitioned inverted index. Run with\n"
            + "      `hadoop jar` / `yarn jar` so the Hadoop runtime is on the classpath.\n"
            + "\n"
            + "  validate --index <hdfsPath>\n"
            + "      Checks _SUCCESS, record decodability, duplicate (term,docId), docId order.\n"
            + "\n"
            + "  stats    --index <path> [--stats-out stats/corpus-stats.json] [--df-out stats/df.tsv]\n"
            + "           [--no-df] [--require-complete]\n"
            + "      Read-only BM25 corpus statistics from the final merged index. Computes N,\n"
            + "      unique terms, df(t) (sidecar), dl(d), Lave, min/max/median length; atomically\n"
            + "      publishes a JSON artifact. Never modifies the index. Safe to rerun.\n"
            + "\n"
            + "  full     [--export <exportFlags...>] [--index-args <indexFlags...>]\n"
            + "      export then index.\n"
            + "\n"
            + "Default SPIMI block budget: " + SpimiIndexMapper.DEFAULT_MAX_ENTRIES + " entries.");
    }
}
