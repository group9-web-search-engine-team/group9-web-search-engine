package com.group9.websearch.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone evaluation CLI. Loads benchmark queries + qrels, runs each query through the
 * existing BM25 search service against a fixed index snapshot, evaluates the ranked results, and
 * writes a machine-readable JSON report + a per-query CSV, plus a readable console summary.
 *
 * <p>It does not modify the index or interfere with the crawler/indexer; it only reads the
 * snapshot path it is given.
 *
 * <p>Exit codes: 0 ok, 2 usage error, 1 runtime error.
 */
public final class EvalCli {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private EvalCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        // Optional subcommand: verify/extract the canonical BEIR id mapping from the full corpus
        // metadata export and persist a versioned artifact. All other invocations are the normal
        // evaluation run (default), so existing CLI behaviour is unchanged.
        if (args.length > 0 && args[0].equals("verify-idmap")) {
            return MappingVerifier.run(java.util.Arrays.copyOfRange(args, 1, args.length));
        }

        EvalConfig cfg = new EvalConfig();
        List<Integer> cutoffs = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--queries" -> cfg.queriesPath = req(args, ++i, "--queries");
                    case "--qrels" -> cfg.qrelsPath = req(args, ++i, "--qrels");
                    case "--idmap" -> cfg.idMapPath = req(args, ++i, "--idmap");
                    case "--index" -> cfg.indexDir = req(args, ++i, "--index");
                    case "--meta" -> cfg.metadataJsonl = req(args, ++i, "--meta");
                    case "--stats" -> cfg.statsArtifact = req(args, ++i, "--stats");
                    case "--distributed" -> cfg.distributed = true;
                    case "--cluster-config" -> cfg.clusterConfig = req(args, ++i, "--cluster-config");
                    case "--coordinator" -> cfg.coordinatorAddress = req(args, ++i, "--coordinator");
                    case "--connect-timeout" ->
                            cfg.connectTimeoutMillis = Long.parseLong(req(args, ++i, "--connect-timeout"));
                    case "--k" -> cutoffs.add(Integer.parseInt(req(args, ++i, "--k")));
                    case "--threshold" -> cfg.relevanceThreshold = Integer.parseInt(req(args, ++i, "--threshold"));
                    case "--depth" -> cfg.retrievalDepth = Integer.parseInt(req(args, ++i, "--depth"));
                    case "--json-out" -> cfg.jsonOut = req(args, ++i, "--json-out");
                    case "--csv-out" -> cfg.csvOut = req(args, ++i, "--csv-out");
                    case "-h", "--help" -> {
                        usage(null);
                        return 0;
                    }
                    default -> {
                        usage("unknown flag: " + args[i]);
                        return 2;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            usage(e.getMessage());
            return 2;
        }

        if (cfg.queriesPath == null || cfg.qrelsPath == null) {
            usage("--queries and --qrels are required");
            return 2;
        }
        if (cfg.distributed && (cfg.statsArtifact == null || cfg.statsArtifact.isBlank())) {
            usage("--distributed requires --stats <corpus-stats.json> (global BM25 statistics)");
            return 2;
        }
        if (!cutoffs.isEmpty()) {
            cfg.cutoffs = cutoffs;
        }
        // Ensure retrieval depth covers the largest cutoff.
        cfg.retrievalDepth = Math.max(cfg.retrievalDepth, cfg.maxCutoff());

        try {
            EvaluationRunner.Outcome outcome = new EvaluationRunner(cfg).run();
            printConsole(cfg, outcome);
            writeJson(cfg, outcome);
            writeCsv(cfg, outcome);
            System.out.println("\nWrote JSON report: " + cfg.jsonOut);
            System.out.println("Wrote per-query CSV: " + cfg.csvOut);
            return 0;
        } catch (IOException e) {
            System.err.println("evaluation failed: " + e.getMessage());
            return 1;
        }
    }

    // ---- console summary ----

    private static void printConsole(EvalConfig cfg, EvaluationRunner.Outcome o) {
        System.out.println("=== Retrieval evaluation (Okapi BM25) ===");
        System.out.println("mode:                  " + (o.distributed ? "DISTRIBUTED (coordinator->workers)" : "local index"));
        if (o.distributed) {
            System.out.println("partitions healthy:    " + o.partitionsHealthy + "/" + o.partitionsTotal);
            System.out.println("queries w/ partial:    " + o.queriesWithFailedPartitions
                    + " (had failed/timed-out partitions)");
        }
        System.out.println("index snapshot:        " + o.indexSnapshotPath);
        System.out.println("index size on disk:    " + OperationalMetrics.humanBytes(o.indexSizeBytes));
        System.out.println("indexed documents (N): " + (o.indexedDocCount < 0 ? "unavailable" : o.indexedDocCount));
        System.out.println("relevance threshold:   grade >= " + cfg.relevanceThreshold);
        System.out.println("queries total:         " + o.queriesTotal);
        System.out.println("queries evaluated:     " + o.queriesEvaluated);
        System.out.println("queries skipped:       " + o.queriesSkipped);
        System.out.println("judged relevant total: " + o.judgedRelevantTotal);
        System.out.printf("benchmark coverage:    %.4f (%d of %d judged docs mapped to index)%n",
                o.benchmarkCoverage, o.mappedJudgedDocs, o.totalJudgedDocs);

        System.out.println("\n-- Query latency --");
        System.out.printf("  samples: %d  avg: %.2f ms  p50: %.2f  p90: %.2f  p95: %.2f  p99: %.2f%n",
                o.operational.latencySampleCount(),
                o.operational.averageLatencyMillis(),
                o.operational.latencyPercentileMillis(50),
                o.operational.latencyPercentileMillis(90),
                o.operational.latencyPercentileMillis(95),
                o.operational.latencyPercentileMillis(99));

        System.out.println("\n-- Time & memory --");
        System.out.printf("  total wall-clock: %d ms (%.2f s)%n",
                o.totalWallClockMillis, o.totalWallClockMillis / 1000.0);
        System.out.println("  peak used heap:   " + OperationalMetrics.humanBytes(o.operational.peakUsedHeapBytes()));
        System.out.println("  max heap (-Xmx):  " + OperationalMetrics.humanBytes(OperationalMetrics.maxHeapBytes()));

        System.out.println("\n-- Macro-averaged retrieval metrics --");
        for (AggregateReport r : o.reports) {
            System.out.printf("  k=%-3d  P@k=%s  R@k=%s  F1@k=%s  MAP=%s  NDCG@k=%s%n",
                    r.k, fmt(r.meanPrecision()), fmt(r.meanRecall()), fmt(r.meanF1()),
                    fmt(r.meanAveragePrecision()), fmt(r.meanNdcg()));
        }

        if (!o.issues.isEmpty()) {
            System.out.println("\n-- Issues / caveats --");
            for (String issue : o.issues) {
                System.out.println("  * " + issue);
            }
        }
        System.out.println("\n-- Unavailable operational metrics (missing instrumentation) --");
        System.out.println("  * indexing throughput (docs/tokens per sec): requires the MR job's "
                + "wall-clock duration + counters; not available to the evaluator.");
        System.out.println("  * crawl throughput / harvest rate / coverage: live in PostgreSQL "
                + "crawl_state/documents + crawler logs; not available to the evaluator.");
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    // ---- JSON report ----

    private static void writeJson(EvalConfig cfg, EvaluationRunner.Outcome o) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("ranker", "okapi-bm25");
        root.put("mode", o.distributed ? "distributed" : "local");
        if (o.distributed) {
            root.put("partitionsHealthy", o.partitionsHealthy);
            root.put("partitionsTotal", o.partitionsTotal);
            root.put("queriesWithFailedPartitions", o.queriesWithFailedPartitions);
            root.put("clusterConfig", cfg.clusterConfig);
            if (cfg.coordinatorAddress != null) {
                root.put("coordinatorAddress", cfg.coordinatorAddress);
            }
        }
        root.put("indexSnapshot", o.indexSnapshotPath);
        root.put("indexSizeBytes", o.indexSizeBytes);
        root.put("indexedDocCount", o.indexedDocCount);
        root.put("relevanceThreshold", cfg.relevanceThreshold);
        root.put("queriesTotal", o.queriesTotal);
        root.put("queriesEvaluated", o.queriesEvaluated);
        root.put("queriesSkipped", o.queriesSkipped);
        root.put("judgedRelevantTotal", o.judgedRelevantTotal);
        root.put("benchmarkCoverage", o.benchmarkCoverage);
        root.put("benchmarkCoverageScope",
                "fraction of DISTINCT qrels docIds (in the supplied qrels) that resolve via the "
                + "id map; NOT full-corpus coverage");
        root.put("mappedJudgedDocs", o.mappedJudgedDocs);
        root.put("totalJudgedDocs", o.totalJudgedDocs);
        root.put("judgedDocsUnresolved", o.judgedDocsUnresolved);
        root.put("judgedDocsOutOfSubset", o.judgedDocsOutOfSubset);
        root.put("totalUnmappedResults", o.totalUnmappedResults);

        // Reproducibility: the exact configuration that produced this report.
        ObjectNode config = root.putObject("config");
        config.put("queriesPath", cfg.queriesPath);
        config.put("qrelsPath", cfg.qrelsPath);
        config.put("idMapPath", cfg.idMapPath);
        config.put("indexDir", cfg.indexDir);
        config.put("metadataJsonl", cfg.metadataJsonl);
        config.put("statsArtifact", cfg.statsArtifact);
        config.put("relevanceThreshold", cfg.relevanceThreshold);
        config.put("retrievalDepth", cfg.retrievalDepth);
        ArrayNode cutoffCfg = config.putArray("cutoffs");
        for (int kk : cfg.cutoffs) {
            cutoffCfg.add(kk);
        }
        config.put("rankingPositionsPreserved", true);
        config.put("unjudgedPolicy",
                "judged-only (condensed list): unjudged retrieved documents (incl. unmapped "
                + "placeholders) are REMOVED from the ranking before metrics; relative order of "
                + "the surviving judged documents is preserved");
        config.put("apDepth", cfg.retrievalDepth);
        config.put("ndcgGain", "raw-grade");
        config.put("metricConventions",
                "computed over the judged-only condensed ranking: P@k denom=min(k, judged "
                + "retrieved); recall denom=all judged-relevant; AP over condensed ranking / "
                + "judged-relevant; nDCG@k over condensed ranking with IDCG from judged grades; "
                + "zero-relevant queries -> recall/F1/AP NaN and excluded from those macro "
                + "averages; all aggregates are MACRO averages over eligible queries");

        ObjectNode latency = root.putObject("queryLatencyMillis");
        latency.put("samples", o.operational.latencySampleCount());
        latency.put("avg", o.operational.averageLatencyMillis());
        latency.put("p50", o.operational.latencyPercentileMillis(50));
        latency.put("p90", o.operational.latencyPercentileMillis(90));
        latency.put("p95", o.operational.latencyPercentileMillis(95));
        latency.put("p99", o.operational.latencyPercentileMillis(99));

        ObjectNode timeMem = root.putObject("timeAndMemory");
        timeMem.put("totalWallClockMillis", o.totalWallClockMillis);
        timeMem.put("peakUsedHeapBytes", o.operational.peakUsedHeapBytes());
        timeMem.put("maxHeapBytes", OperationalMetrics.maxHeapBytes());

        ArrayNode cutoffsNode = root.putArray("cutoffs");
        for (AggregateReport r : o.reports) {
            ObjectNode c = cutoffsNode.addObject();
            c.put("k", r.k);
            c.put("precision", r.meanPrecision());
            c.put("recall", r.meanRecall());
            c.put("f1", r.meanF1());
            c.put("map", r.meanAveragePrecision());
            c.put("ndcg", r.meanNdcg());
            c.put("evaluated", r.evaluatedCount());
            c.put("skipped", r.skippedCount());
        }

        ArrayNode issues = root.putArray("issues");
        o.issues.forEach(issues::add);

        Path target = Path.of(cfg.jsonOut);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    }

    // ---- per-query CSV ----

    private static void writeCsv(EvalConfig cfg, EvaluationRunner.Outcome o) throws IOException {
        Path target = Path.of(cfg.csvOut);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write("queryId,k,retrieved,judgedRelevant,precision,recall,f1,averagePrecision,ndcg,skipped,note\n");
            for (AggregateReport r : o.reports) {
                for (QueryEvaluation q : r.perQuery) {
                    w.write(csv(q.queryId));
                    w.write("," + q.k);
                    w.write("," + q.retrieved);
                    w.write("," + q.judgedRelevant);
                    w.write("," + num(q.precision));
                    w.write("," + num(q.recall));
                    w.write("," + num(q.f1));
                    w.write("," + num(q.averagePrecision));
                    w.write("," + num(q.ndcg));
                    w.write("," + q.skipped);
                    w.write("," + csv(q.note == null ? "" : q.note));
                    w.write("\n");
                }
            }
        }
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "" : String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    private static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ---- helpers ----

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
            "Usage: EvalCli --queries <file> --qrels <file> [--idmap <tsv>]\n"
            + "               [ LOCAL:  --index DIR ]\n"
            + "               [ DISTRIBUTED: --distributed --cluster-config FILE [--coordinator HOST] ]\n"
            + "               [--meta JSONL] [--stats JSON] [--k N]... [--threshold G]\n"
            + "               [--depth N] [--connect-timeout MS] [--json-out FILE] [--csv-out FILE]\n"
            + "\n"
            + "  --queries FILE       BEIR queries.jsonl ({_id,text}) or a 'id<TAB>text' TSV (required)\n"
            + "  --qrels FILE         BEIR qrels TSV: query-id<TAB>corpus-id<TAB>score (required)\n"
            + "  --idmap TSV          beirId<TAB>internalId mapping (required for real benchmarking)\n"
            + "  --distributed        evaluate THROUGH the live DistributedCoordinator (HTTP fan-out\n"
            + "                       to the 5 partition workers + global BM25); no local index loaded\n"
            + "  --cluster-config FILE  cluster properties with worker.P1..P5 addresses (distributed)\n"
            + "  --coordinator HOST   coordinator host annotation for the report (routing is from\n"
            + "                       the cluster properties; optional)\n"
            + "  --connect-timeout MS per-worker /health connect timeout in distributed mode (def 2000)\n"
            + "  --index DIR          LOCAL mode: fixed index snapshot directory (default: index-local)\n"
            + "  --meta JSONL         document metadata export (default: week3-export/documents.jsonl)\n"
            + "  --stats JSON         BM25 stats artifact; REQUIRED in --distributed mode (global N/avgdl)\n"
            + "  --k N                metric cutoff; repeatable (default: 10)\n"
            + "  --threshold G        binary relevance threshold: grade >= G is relevant (default 1)\n"
            + "  --depth N            results requested per query (default 100; >= max k)\n"
            + "  --json-out FILE      JSON report (default: eval/report.json)\n"
            + "  --csv-out FILE       per-query CSV (default: eval/per-query.csv)\n"
            + "\n"
            + "Metrics: Precision@k, Recall@k, F1@k, MAP, NDCG@k (graded), macro-averaged. Distributed\n"
            + "mode reuses the production coordinator/worker query path and the SAME global BM25; it\n"
            + "never modifies indexes, workers, crawler, or indexer.");
    }
}
