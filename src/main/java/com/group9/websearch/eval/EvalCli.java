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
        root.put("indexSnapshot", o.indexSnapshotPath);
        root.put("indexSizeBytes", o.indexSizeBytes);
        root.put("indexedDocCount", o.indexedDocCount);
        root.put("relevanceThreshold", cfg.relevanceThreshold);
        root.put("queriesTotal", o.queriesTotal);
        root.put("queriesEvaluated", o.queriesEvaluated);
        root.put("queriesSkipped", o.queriesSkipped);
        root.put("judgedRelevantTotal", o.judgedRelevantTotal);
        root.put("benchmarkCoverage", o.benchmarkCoverage);
        root.put("mappedJudgedDocs", o.mappedJudgedDocs);
        root.put("totalJudgedDocs", o.totalJudgedDocs);

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
            "Usage: EvalCli --queries <file> --qrels <file> [--idmap <tsv>] [--index DIR]\n"
            + "               [--meta JSONL] [--stats JSON] [--k N]... [--threshold G]\n"
            + "               [--depth N] [--json-out FILE] [--csv-out FILE]\n"
            + "\n"
            + "  --queries FILE   BEIR queries.jsonl ({_id,text}) or a 'id<TAB>text' TSV (required)\n"
            + "  --qrels FILE     BEIR qrels TSV: query-id<TAB>corpus-id<TAB>score (required)\n"
            + "  --idmap TSV      benchmarkDocId<TAB>ourDocId|url mapping (for id matching)\n"
            + "  --index DIR      FIXED index snapshot directory (default: index-local)\n"
            + "  --meta JSONL     document metadata export (default: week3-export/documents.jsonl)\n"
            + "  --stats JSON     published BM25 stats artifact (for N in the report)\n"
            + "  --k N            metric cutoff; repeatable (default: 10)\n"
            + "  --threshold G    binary relevance threshold: grade >= G is relevant (default 1)\n"
            + "  --depth N        results requested per query (default 100; >= max k)\n"
            + "  --json-out FILE  JSON report (default: eval/report.json)\n"
            + "  --csv-out FILE   per-query CSV (default: eval/per-query.csv)\n"
            + "\n"
            + "Metrics: Precision@k, Recall@k, F1@k, MAP, NDCG@k (graded). The evaluator reads the\n"
            + "snapshot read-only and never modifies the index or the crawler/indexer.");
    }
}
