package com.group9.websearch.eval;

import com.group9.websearch.search.CorpusStatistics;
import com.group9.websearch.search.CorpusStatsArtifact;
import com.group9.websearch.search.DocumentStore;
import com.group9.websearch.search.InvertedIndex;
import com.group9.websearch.search.SearchConfig;
import com.group9.websearch.search.SearchResult;
import com.group9.websearch.search.SearchService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates one evaluation run against a FIXED index snapshot. It loads the snapshot index
 * and metadata read-only, runs every benchmark query through the existing {@link SearchService}
 * (the current Okapi BM25 ranker -- nothing is swapped), translates ranked result docIds back to
 * benchmark docIds, scores them against the qrels, and aggregates.
 *
 * <p>It never writes to the index and never touches the crawler/indexer. The index path it reads
 * is exactly the one supplied; the live, changing index is not substituted.
 */
public final class EvaluationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationRunner.class);

    /** The complete outcome of a run, consumed by the CLI to write JSON/CSV/console. */
    public static final class Outcome {
        public final List<AggregateReport> reports = new ArrayList<>(); // one per cutoff
        public final OperationalMetrics operational = new OperationalMetrics();
        public final List<String> issues = new ArrayList<>();
        public String indexSnapshotPath;
        public long indexSizeBytes = -1;
        public int indexedDocCount = -1;
        public int queriesTotal;
        public int queriesEvaluated;
        public int queriesSkipped;
        public long judgedRelevantTotal;
        public double benchmarkCoverage = Double.NaN; // fraction of judged docs mapped to index
        public int mappedJudgedDocs;
        public int totalJudgedDocs;
        public long totalWallClockMillis;  // whole evaluation run (load + all queries + scoring)
    }

    private final EvalConfig cfg;

    public EvaluationRunner(EvalConfig cfg) {
        this.cfg = cfg;
    }

    public Outcome run() throws IOException {
        Outcome out = new Outcome();
        out.indexSnapshotPath = cfg.indexDir;
        long runStart = System.nanoTime();

        // --- Load the fixed snapshot (read-only) ---
        InvertedIndex index = InvertedIndex.load(Path.of(cfg.indexDir));
        DocumentStore store = loadStore(out);
        out.operational.sampleMemory(); // after loading index + metadata

        // Index size + indexed doc count for the operational report.
        out.indexSizeBytes = OperationalMetrics.directorySizeBytes(Path.of(cfg.indexDir));
        out.indexedDocCount = resolveIndexedDocCount(index, out);

        // --- Load benchmark data ---
        BenchmarkQueries queries = BenchmarkQueries.load(Path.of(cfg.queriesPath));
        RelevanceJudgments qrels = RelevanceJudgments.loadTsv(Path.of(cfg.qrelsPath));
        out.issues.addAll(prefix("queries", queries.problems()));
        out.issues.addAll(prefix("qrels", qrels.problems()));

        // --- Build the benchmark<->index id mapping and compute coverage ---
        DocIdResolver resolver = buildResolver(store, out);
        out.totalJudgedDocs = qrels.allJudgedDocIds().size();
        out.mappedJudgedDocs = (int) Math.round(resolver.coverage(qrels.allJudgedDocIds()) * out.totalJudgedDocs);
        out.benchmarkCoverage = resolver.coverage(qrels.allJudgedDocIds());
        out.judgedRelevantTotal = countRelevant(qrels, cfg.relevanceThreshold);

        if (out.benchmarkCoverage == 0.0) {
            out.issues.add("BENCHMARK COVERAGE IS ZERO: no qrels document mapped to an index "
                    + "docId. Benchmark scores would be meaningless; provide an --idmap "
                    + "(benchmarkDocId<TAB>ourDocId|url) or index the benchmark corpus. "
                    + "Per-query metrics below will be computed but are NOT a valid benchmark "
                    + "result until coverage is non-trivial.");
        }

        // --- Build the search service over the snapshot ---
        SearchConfig sc = new SearchConfig();
        sc.indexDir = cfg.indexDir;
        sc.metadataJsonl = cfg.metadataJsonl;
        sc.statsArtifact = cfg.statsArtifact;
        sc.topK = cfg.retrievalDepth;
        SearchService service = new SearchService(index, store, sc);

        // One aggregate report per cutoff.
        Map<Integer, AggregateReport> reportByK = new LinkedHashMap<>();
        for (int k : cfg.cutoffs) {
            reportByK.put(k, new AggregateReport(k, cfg.relevanceThreshold));
        }

        out.queriesTotal = queries.all().size();
        for (Map.Entry<String, String> e : queries.all().entrySet()) {
            String qid = e.getKey();
            String text = e.getValue();
            Map<String, Integer> grades = qrels.gradesFor(qid);

            if (!qrels.hasJudgements(qid)) {
                // No judgements for this query: cannot evaluate; skip (documented).
                for (int k : cfg.cutoffs) {
                    reportByK.get(k).add(QueryEvaluation.skipped(qid, k, "no qrels for query"));
                }
                out.queriesSkipped++;
                continue;
            }

            // Run the query through BM25, timed for latency.
            long t0 = System.nanoTime();
            SearchService.Response resp = service.search(text);
            long elapsed = System.nanoTime() - t0;
            out.operational.recordQueryLatencyNanos(elapsed);

            // Translate ranked result (our docIds) -> benchmark docIds for scoring.
            List<String> ranking = new ArrayList<>(resp.results.size());
            for (SearchResult r : resp.results) {
                Optional<String> bench = resolver.toBenchmarkDocId(r.docId());
                // Only benchmark-mapped results can be judged; unmapped results are kept out of
                // the scored ranking (they cannot match any qrel). This is documented behaviour.
                bench.ifPresent(ranking::add);
            }

            out.queriesEvaluated++;
            for (int k : cfg.cutoffs) {
                reportByK.get(k).add(evaluateQuery(qid, k, ranking, grades));
            }
        }

        out.operational.sampleMemory(); // peak after the full query sweep
        out.totalWallClockMillis = (System.nanoTime() - runStart) / 1_000_000L;

        out.reports.addAll(reportByK.values());
        LOG.info("Evaluation complete: {} queries, {} evaluated, {} skipped, coverage={}, {} ms",
                out.queriesTotal, out.queriesEvaluated, out.queriesSkipped, out.benchmarkCoverage,
                out.totalWallClockMillis);
        return out;
    }

    private QueryEvaluation evaluateQuery(String qid, int k, List<String> ranking,
            Map<String, Integer> grades) {
        int threshold = cfg.relevanceThreshold;
        int judgedRel = RetrievalMetrics.numRelevant(grades, threshold);
        double p = RetrievalMetrics.precisionAtK(ranking, grades, k, threshold);
        double rec = RetrievalMetrics.recallAtK(ranking, grades, k, threshold);
        double f1 = RetrievalMetrics.f1AtK(ranking, grades, k, threshold);
        double ap = RetrievalMetrics.averagePrecision(ranking, grades, threshold);
        double ndcg = RetrievalMetrics.ndcgAtK(ranking, grades, k);
        String note = (judgedRel == 0) ? "no relevant judgements (recall/MAP undefined)" : null;
        return new QueryEvaluation(qid, k, RetrievalMetrics.dedup(ranking).size(), judgedRel,
                p, rec, f1, ap, ndcg, false, note);
    }

    private DocumentStore loadStore(Outcome out) {
        try {
            return DocumentStore.loadJsonl(Path.of(cfg.metadataJsonl));
        } catch (IOException e) {
            out.issues.add("metadata JSONL not loaded (" + e.getMessage()
                    + "); URL-based id mapping and result rendering are unavailable.");
            return DocumentStore.empty();
        }
    }

    private DocIdResolver buildResolver(DocumentStore store, Outcome out) {
        try {
            Path idMap = cfg.idMapPath == null ? null : Path.of(cfg.idMapPath);
            return DocIdResolver.build(idMap, store);
        } catch (IOException e) {
            out.issues.add("id map not loaded (" + e.getMessage() + "); using URL-only / empty "
                    + "mapping.");
            return DocIdResolver.empty();
        }
    }

    private int resolveIndexedDocCount(InvertedIndex index, Outcome out) {
        if (cfg.statsArtifact != null && !cfg.statsArtifact.isBlank()) {
            try {
                CorpusStatsArtifact art = CorpusStatsArtifact.load(Path.of(cfg.statsArtifact));
                return art.numDocs;
            } catch (IOException e) {
                out.issues.add("stats artifact not loaded for N (" + e.getMessage()
                        + "); deriving from index.");
            }
        }
        return CorpusStatistics.fromIndex(index).numDocs();
    }

    private static long countRelevant(RelevanceJudgments qrels, int threshold) {
        long n = 0;
        for (String qid : qrels.judgedQueryIds()) {
            n += RetrievalMetrics.numRelevant(qrels.gradesFor(qid), threshold);
        }
        return n;
    }

    private static List<String> prefix(String tag, List<String> problems) {
        List<String> out = new ArrayList<>(problems.size());
        for (String p : problems) {
            out.add(tag + ": " + p);
        }
        return out;
    }
}
