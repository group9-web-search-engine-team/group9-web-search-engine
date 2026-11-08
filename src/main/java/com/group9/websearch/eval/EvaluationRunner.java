package com.group9.websearch.eval;

import com.group9.websearch.index.TermPartitioner;
import com.group9.websearch.index.Tokenizer;
import com.group9.websearch.search.Bm25Params;
import com.group9.websearch.search.CorpusStatistics;
import com.group9.websearch.search.CorpusStatsArtifact;
import com.group9.websearch.search.DocumentStore;
import com.group9.websearch.search.InvertedIndex;
import com.group9.websearch.search.SearchConfig;
import com.group9.websearch.search.SearchResult;
import com.group9.websearch.search.SearchService;
import com.group9.websearch.search.cluster.ClusterConfig;
import com.group9.websearch.search.cluster.DistributedCoordinator;
import com.group9.websearch.search.cluster.HttpWorkerClient;
import com.group9.websearch.search.cluster.WorkerProtocol;
import com.group9.websearch.search.cluster.WorkerSearch;
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
        // Judged docs that resolve via the id map but are NOT present in the evaluated index
        // (out-of-subset), vs judged docs that fail to map at all (unresolved). Distinguishing
        // these is required so "missing because subset" is never confused with "mapping bug".
        public int judgedDocsOutOfSubset = -1;   // -1 = not computed (no index doc universe)
        public int judgedDocsUnresolved;         // judged docs with no mapping entry
        public long totalUnmappedResults;        // retrieved results kept as placeholders
        public long totalWallClockMillis;  // whole evaluation run (load + all queries + scoring)
        // Distributed mode diagnostics (local mode leaves these at defaults).
        public boolean distributed;
        public int partitionsHealthy = -1;        // workers that answered /health ok
        public int partitionsTotal = -1;          // TermPartitioner.NUM_PARTITIONS
        public int queriesWithFailedPartitions;   // queries whose results were PARTIAL
    }

    private final EvalConfig cfg;

    public EvaluationRunner(EvalConfig cfg) {
        this.cfg = cfg;
    }

    /** Dispatch to the local-index or distributed-coordinator evaluation path. */
    public Outcome run() throws IOException {
        return cfg.distributed ? runDistributed() : runLocal();
    }

    public Outcome runLocal() throws IOException {
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
        java.util.Set<String> judged = qrels.allJudgedDocIds();
        out.totalJudgedDocs = judged.size();
        out.mappedJudgedDocs = (int) Math.round(resolver.coverage(judged) * out.totalJudgedDocs);
        out.benchmarkCoverage = resolver.coverage(judged);
        out.judgedRelevantTotal = countRelevant(qrels, cfg.relevanceThreshold);

        // Split the judged docs that do NOT contribute into two honest buckets:
        //   * unresolved  -> no mapping entry at all (a mapping gap / bug to investigate);
        //   * out-of-subset -> mapping exists but the internal docId is absent from THIS index
        //     (legitimately excluded by subsetting -- not a mapping error).
        java.util.Set<Long> indexedDocIds = CorpusStatistics.fromIndex(index).docLengths().keySet();
        int unresolved = 0;
        int outOfSubset = 0;
        for (String b : judged) {
            Optional<Long> internal = resolver.toOurDocId(b);
            if (internal.isEmpty()) {
                unresolved++;
            } else if (!indexedDocIds.contains(internal.get())) {
                outOfSubset++;
            }
        }
        out.judgedDocsUnresolved = unresolved;
        out.judgedDocsOutOfSubset = outOfSubset;

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

            // Our ranked docIds in BM25 order.
            List<Long> ourDocIds = new ArrayList<>(resp.results.size());
            for (SearchResult r : resp.results) {
                ourDocIds.add(r.docId());
            }
            scoreAndRecord(out, reportByK, resolver, qid, grades, ourDocIds, List.of());
        }

        out.operational.sampleMemory(); // peak after the full query sweep
        out.totalWallClockMillis = (System.nanoTime() - runStart) / 1_000_000L;

        out.reports.addAll(reportByK.values());
        LOG.info("Evaluation complete (local): {} queries, {} evaluated, {} skipped, coverage={}, {} ms",
                out.queriesTotal, out.queriesEvaluated, out.queriesSkipped, out.benchmarkCoverage,
                out.totalWallClockMillis);
        return out;
    }

    /**
     * Distributed evaluation: run every benchmark query through the EXISTING production
     * {@link DistributedCoordinator} (HTTP fan-out to the five partition workers + the same
     * global Okapi BM25 ranking/dedup/ordering used by live distributed search). No local index
     * is loaded and no BM25/Boolean logic is re-implemented here. Ranked docIds are fed into the
     * same {@link DocIdResolver} -> {@link RetrievalMetrics} -> {@link AggregateReport} path as
     * local mode, so all evaluation semantics (judged-only condensing, macro-averaging, mapping)
     * are identical. Per-query failed partitions are recorded as PARTIAL, never silently empty.
     */
    public Outcome runDistributed() throws IOException {
        Outcome out = new Outcome();
        out.distributed = true;
        out.indexSnapshotPath = "distributed:" + cfg.clusterConfig
                + (cfg.coordinatorAddress != null ? " (coordinator " + cfg.coordinatorAddress + ")" : "");
        long runStart = System.nanoTime();

        // Global corpus statistics come from the published artifact (authoritative N/avgdl/dl);
        // there is NO local index in distributed mode.
        if (cfg.statsArtifact == null || cfg.statsArtifact.isBlank()) {
            throw new IOException("distributed mode requires --stats <corpus-stats.json> "
                    + "(global BM25 statistics); none supplied.");
        }
        final CorpusStatistics stats;
        try {
            stats = CorpusStatistics.fromArtifact(CorpusStatsArtifact.load(Path.of(cfg.statsArtifact)));
        } catch (RuntimeException e) {
            throw new IOException("could not load global corpus stats '" + cfg.statsArtifact
                    + "': " + e.getMessage(), e);
        }
        out.indexedDocCount = stats.numDocs();

        // Document metadata (optional) is still used by the id resolver's URL fallback path.
        DocumentStore store = loadStore(out);
        out.operational.sampleMemory();

        // --- Load benchmark data ---
        BenchmarkQueries queries = BenchmarkQueries.load(Path.of(cfg.queriesPath));
        RelevanceJudgments qrels = RelevanceJudgments.loadTsv(Path.of(cfg.qrelsPath));
        out.issues.addAll(prefix("queries", queries.problems()));
        out.issues.addAll(prefix("qrels", qrels.problems()));

        // --- Mapping + coverage (identical to local mode) ---
        DocIdResolver resolver = buildResolver(store, out);
        java.util.Set<String> judged = qrels.allJudgedDocIds();
        out.totalJudgedDocs = judged.size();
        out.benchmarkCoverage = resolver.coverage(judged);
        out.mappedJudgedDocs = (int) Math.round(out.benchmarkCoverage * out.totalJudgedDocs);
        out.judgedRelevantTotal = countRelevant(qrels, cfg.relevanceThreshold);
        int unresolved = 0;
        for (String b : judged) {
            if (resolver.toOurDocId(b).isEmpty()) {
                unresolved++;
            }
        }
        out.judgedDocsUnresolved = unresolved;
        // Out-of-subset is not computed in distributed mode (no single local doc universe).
        out.judgedDocsOutOfSubset = -1;
        if (out.benchmarkCoverage == 0.0) {
            out.issues.add("BENCHMARK COVERAGE IS ZERO: no qrels document mapped to an internal "
                    + "docId via the id map; distributed metrics would be meaningless. Supply a "
                    + "correct --idmap.");
        }

        // --- Build the production distributed coordinator (same wiring as CoordinatorCli) ---
        ClusterConfig cluster = ClusterConfig.load(Path.of(cfg.clusterConfig));
        long queryTimeout = cluster.queryTimeoutMillis();
        HttpWorkerClient[] clients = new HttpWorkerClient[TermPartitioner.NUM_PARTITIONS];
        WorkerSearch[] workers = new WorkerSearch[TermPartitioner.NUM_PARTITIONS];
        for (int p = 0; p < TermPartitioner.NUM_PARTITIONS; p++) {
            String addr = cluster.addressForPartition(p);
            clients[p] = new HttpWorkerClient(TermPartitioner.PARTITION_IDS[p],
                    "http://" + addr, cfg.connectTimeoutMillis, queryTimeout);
            workers[p] = clients[p];
        }
        // Health gate: report reachable partitions (does not abort; failures surface per query).
        out.partitionsTotal = TermPartitioner.NUM_PARTITIONS;
        int healthy = 0;
        for (int p = 0; p < clients.length; p++) {
            String pid = TermPartitioner.PARTITION_IDS[p];
            try {
                WorkerProtocol.HealthResponse h = clients[p].health();
                if (h.ok) {
                    healthy++;
                } else {
                    out.issues.add("partition " + pid + " @ " + cluster.addressForPartition(p)
                            + " reported ok=false at startup");
                }
            } catch (RuntimeException ex) {
                out.issues.add("partition " + pid + " @ " + cluster.addressForPartition(p)
                        + " UNREACHABLE at startup: " + ex.getMessage());
            }
        }
        out.partitionsHealthy = healthy;

        DistributedCoordinator coordinator =
                new DistributedCoordinator(workers, stats, Bm25Params.defaults(), queryTimeout);

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
                for (int k : cfg.cutoffs) {
                    reportByK.get(k).add(QueryEvaluation.skipped(qid, k, "no qrels for query"));
                }
                out.queriesSkipped++;
                continue;
            }

            // Same normalisation as live distributed search (indexing tokenizer -> OR search).
            List<String> terms = Tokenizer.tokenize(text);
            long t0 = System.nanoTime();
            DistributedCoordinator.Response resp = coordinator.search(terms, cfg.retrievalDepth);
            out.operational.recordQueryLatencyNanos(System.nanoTime() - t0);

            if (!resp.failedPartitions.isEmpty()) {
                out.queriesWithFailedPartitions++;
                out.issues.add("query " + qid + ": PARTIAL results -- failed/timed-out "
                        + "partitions " + resp.failedPartitions + " (NOT treated as empty; "
                        + "metrics for this query are computed over the partitions that responded).");
            }
            List<Long> ourDocIds = new ArrayList<>(resp.hits.size());
            for (DistributedCoordinator.Hit h : resp.hits) {
                ourDocIds.add(h.docId);
            }
            scoreAndRecord(out, reportByK, resolver, qid, grades, ourDocIds,
                    resp.failedPartitions);
        }

        out.operational.sampleMemory();
        out.totalWallClockMillis = (System.nanoTime() - runStart) / 1_000_000L;
        out.reports.addAll(reportByK.values());
        LOG.info("Evaluation complete (distributed): {} queries, {} evaluated, {} skipped, "
                + "{}/{} partitions healthy, {} queries with failed partitions, coverage={}, {} ms",
                out.queriesTotal, out.queriesEvaluated, out.queriesSkipped, out.partitionsHealthy,
                out.partitionsTotal, out.queriesWithFailedPartitions, out.benchmarkCoverage,
                out.totalWallClockMillis);
        return out;
    }

    /**
     * Shared per-query translation + scoring used by BOTH local and distributed modes. Translates
     * our ranked docIds to benchmark docIds PRESERVING ORIGINAL RANK POSITIONS (an unmapped
     * result occupies its slot with a unique non-judgeable placeholder, so a mapped relevant doc
     * keeps its true BM25 rank), then records the per-cutoff metrics. {@code failedPartitions}
     * (distributed mode only) is noted so a partial result is never confused with a complete one.
     */
    private void scoreAndRecord(Outcome out, Map<Integer, AggregateReport> reportByK,
            DocIdResolver resolver, String qid, Map<String, Integer> grades,
            List<Long> ourDocIds, List<String> failedPartitions) {
        List<String> ranking = new ArrayList<>(ourDocIds.size());
        int unmappedInRanking = 0;
        for (int i = 0; i < ourDocIds.size(); i++) {
            long docId = ourDocIds.get(i);
            Optional<String> bench = resolver.toBenchmarkDocId(docId);
            if (bench.isPresent()) {
                ranking.add(bench.get());
            } else {
                ranking.add("__UNMAPPED__#" + docId + "@" + i);
                unmappedInRanking++;
            }
        }
        if (unmappedInRanking > 0) {
            out.issues.add("query " + qid + ": " + unmappedInRanking + " of " + ourDocIds.size()
                    + " retrieved results were unmapped and kept as rank-preserving placeholders "
                    + "(scored non-relevant; positions NOT compacted).");
        }
        out.totalUnmappedResults += unmappedInRanking;

        String partialNote = failedPartitions.isEmpty() ? null
                : "PARTIAL (failed partitions " + failedPartitions + ")";
        out.queriesEvaluated++;
        for (int k : cfg.cutoffs) {
            reportByK.get(k).add(evaluateQuery(qid, k, ranking, grades, partialNote));
        }
    }

    private QueryEvaluation evaluateQuery(String qid, int k, List<String> ranking,
            Map<String, Integer> grades, String extraNote) {
        int threshold = cfg.relevanceThreshold;
        int judgedRel = RetrievalMetrics.numRelevant(grades, threshold);
        double p = RetrievalMetrics.precisionAtK(ranking, grades, k, threshold);
        double rec = RetrievalMetrics.recallAtK(ranking, grades, k, threshold);
        double f1 = RetrievalMetrics.f1AtK(ranking, grades, k, threshold);
        double ap = RetrievalMetrics.averagePrecision(ranking, grades, threshold);
        double ndcg = RetrievalMetrics.ndcgAtK(ranking, grades, k);
        String note = (judgedRel == 0) ? "no relevant judgements (recall/MAP undefined)" : null;
        if (extraNote != null) {
            note = (note == null) ? extraNote : note + "; " + extraNote;
        }
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
        if (cfg.idMapPath == null) {
            return DocIdResolver.empty();
        }
        Path idMapPath = Path.of(cfg.idMapPath);
        // Preferred path: the CANONICAL beirId<TAB>internalId map (CorpusAdapter output).
        // Identity comes only from this explicit, validated bijection -- no title/URL guessing.
        try {
            com.group9.websearch.index.IdMap idMap =
                    com.group9.websearch.index.IdMap.load(idMapPath);
            if (idMap.size() > 0) {
                for (String m : idMap.malformed()) {
                    out.issues.add("idmap " + m);
                }
                for (String c : idMap.conflicts()) {
                    out.issues.add("idmap " + c);
                }
                if (!idMap.isOneToOne()) {
                    out.issues.add("ID MAP IS NOT ONE-TO-ONE: duplicate/conflicting mappings "
                            + "were detected and skipped (first-wins). Results may be affected; "
                            + "fix the id map.");
                }
                return DocIdResolver.fromIdMap(idMap);
            }
        } catch (IOException | RuntimeException canonicalFailed) {
            // Not the canonical format (e.g. a benchDocId<TAB>url map); fall back to the legacy
            // resolver which supports URL-RHS lookup. Reported so the path used is explicit.
            out.issues.add("id map not in canonical beirId<TAB>internalId form ("
                    + canonicalFailed.getMessage() + "); falling back to legacy resolver "
                    + "(explicit entries + URL-RHS lookup only; no fuzzy matching).");
        }
        try {
            return DocIdResolver.build(idMapPath, store);
        } catch (IOException e) {
            out.issues.add("id map not loaded (" + e.getMessage() + "); using empty mapping.");
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
