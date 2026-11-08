package com.group9.websearch.search.cluster;

import com.group9.websearch.index.TermPartitioner;
import com.group9.websearch.search.Bm25Params;
import com.group9.websearch.search.Bm25Ranker;
import com.group9.websearch.search.CorpusStatistics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator for distributed query execution over the five term-range partitions.
 *
 * <p>Flow (mirrors the single-node {@link com.group9.websearch.search.SearchService} semantics):
 * <ol>
 *   <li>Distinct query terms are grouped by partition via the SHARED {@link TermPartitioner}
 *       (same rule indexing used), so each term goes to exactly the worker that owns it.</li>
 *   <li>Each owning worker is asked (in parallel, with a timeout) for df(t) and tf(t,d) of its
 *       terms -- RAW features, not local scores.</li>
 *   <li>The coordinator forms the OR candidate set (union of all matching docs) and computes
 *       ONE globally-consistent BM25 score per candidate using the GLOBAL
 *       {@link CorpusStatistics} (N, avgdl, dl(d)) and the per-term global df (the owning
 *       worker's df, which equals the global df because the term is wholly in one partition).</li>
 *   <li>Results are deduplicated (a doc scored once even if several terms/partitions matched it),
 *       sorted by score desc then docId asc (identical deterministic tie-break to the single-node
 *       engine), and truncated to topK.</li>
 * </ol>
 *
 * <p>Fault handling: a worker that fails or times out yields NO features for its terms; the
 * coordinator records the failed partitions and continues (partial, clearly-flagged result)
 * rather than corrupting scores or hanging. It never fabricates postings for a dead worker.
 */
public final class DistributedCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(DistributedCoordinator.class);

    /** One ranked hit: docId + global BM25 score. */
    public static final class Hit {
        public final long docId;
        public final double score;

        public Hit(long docId, double score) {
            this.docId = docId;
            this.score = score;
        }
    }

    /** The coordinator's response: ranked hits + diagnostics (failed partitions, candidates). */
    public static final class Response {
        public final List<Hit> hits;
        public final int candidateCount;
        public final List<String> failedPartitions;

        public Response(List<Hit> hits, int candidateCount, List<String> failedPartitions) {
            this.hits = hits;
            this.candidateCount = candidateCount;
            this.failedPartitions = failedPartitions;
        }
    }

    private final WorkerSearch[] workersByPartition; // index 0..4 -> worker owning that partition
    private final CorpusStatistics globalStats;
    private final Bm25Params bm25;
    private final long timeoutMillis;

    /**
     * @param workersByPartition one worker per logical partition (size 5); the same worker
     *                           instance may appear multiple times (e.g. P4 and P5 co-located).
     * @param globalStats        the GLOBAL corpus statistics (from the published artifact).
     */
    public DistributedCoordinator(WorkerSearch[] workersByPartition,
            CorpusStatistics globalStats, Bm25Params bm25, long timeoutMillis) {
        if (workersByPartition.length != TermPartitioner.NUM_PARTITIONS) {
            throw new IllegalArgumentException("need exactly " + TermPartitioner.NUM_PARTITIONS
                    + " partition->worker slots");
        }
        this.workersByPartition = workersByPartition.clone();
        this.globalStats = globalStats;
        this.bm25 = bm25;
        this.timeoutMillis = timeoutMillis;
    }

    /** The fan-out result: per-term postings (tf/df/positions) + any partitions that failed. */
    public static final class TermFetch {
        public final Map<String, WorkerSearch.TermPostingsResult> byTerm;
        public final List<String> failedPartitions;

        TermFetch(Map<String, WorkerSearch.TermPostingsResult> byTerm,
                List<String> failedPartitions) {
            this.byTerm = byTerm;
            this.failedPartitions = failedPartitions;
        }
    }

    /**
     * Fan out the DISTINCT terms to their owning workers (one worker per term via the shared
     * {@link TermPartitioner}), in parallel, bounded by the query timeout. A worker that fails or
     * times out contributes no features for its terms and its partition is recorded as failed.
     * This is the single shared transport step used by BOTH plain OR search and the Boolean/NEAR
     * evaluator, so routing, timeout and fault handling are identical everywhere.
     */
    public TermFetch fetchTerms(Collection<String> terms) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>(terms);
        Map<Integer, List<String>> termsByPartition = new LinkedHashMap<>();
        for (String term : distinct) {
            int p = TermPartitioner.partitionFor(term);
            termsByPartition.computeIfAbsent(p, x -> new ArrayList<>()).add(term);
        }
        Map<String, WorkerSearch.TermPostingsResult> termResults = new HashMap<>();
        List<String> failedPartitions = new ArrayList<>();
        if (termsByPartition.isEmpty()) {
            return new TermFetch(termResults, failedPartitions);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, termsByPartition.size()));
        try {
            Map<Integer, Future<List<WorkerSearch.TermPostingsResult>>> futures = new HashMap<>();
            for (Map.Entry<Integer, List<String>> e : termsByPartition.entrySet()) {
                int p = e.getKey();
                List<String> t = e.getValue();
                WorkerSearch worker = workersByPartition[p];
                futures.put(p, pool.submit((Callable<List<WorkerSearch.TermPostingsResult>>)
                        () -> worker.lookup(t)));
            }
            long deadline = System.currentTimeMillis() + timeoutMillis;
            for (Map.Entry<Integer, Future<List<WorkerSearch.TermPostingsResult>>> e
                    : futures.entrySet()) {
                int p = e.getKey();
                long remaining = Math.max(1, deadline - System.currentTimeMillis());
                try {
                    for (WorkerSearch.TermPostingsResult r
                            : e.getValue().get(remaining, TimeUnit.MILLISECONDS)) {
                        termResults.put(r.term, r);
                    }
                } catch (Exception ex) {
                    failedPartitions.add(TermPartitioner.PARTITION_IDS[p]);
                    LOG.warn("partition {} worker failed/timed out: {}",
                            TermPartitioner.PARTITION_IDS[p], ex.toString());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new TermFetch(termResults, failedPartitions);
    }

    /**
     * Global BM25 score for a set of candidate docs, using the global N/avgdl/dl(d) and the
     * per-term global df (owning worker's df == global df). Ranking, length normalisation and the
     * deterministic tie-break (score desc, docId asc) are UNCHANGED from the original engine.
     * Only the candidate DOC SET varies (all matching docs for OR; the Boolean/NEAR-filtered set
     * otherwise); BM25 scoring itself is identical.
     */
    public List<Hit> rankDocs(Collection<Long> docIds,
            Collection<WorkerSearch.TermPostingsResult> scoringTerms, int topK) {
        int n = globalStats.numDocs();
        double avgdl = globalStats.avgDocLength();
        List<Hit> ranked = new ArrayList<>(docIds.size());
        for (long docId : docIds) {
            long dl = globalStats.docLength(docId);
            double lengthNorm = (avgdl > 0.0) ? (1.0 - bm25.b + bm25.b * (dl / avgdl)) : 1.0;
            double total = 0.0;
            for (WorkerSearch.TermPostingsResult r : scoringTerms) {
                Integer tf = r.termFreqByDoc.get(docId);
                if (tf == null || tf <= 0) {
                    continue;
                }
                double idf = Bm25Ranker.idf(n, r.documentFrequency);
                double numerator = tf * (bm25.k1 + 1.0);
                double denominator = tf + bm25.k1 * lengthNorm;
                if (denominator != 0.0) {
                    total += idf * (numerator / denominator);
                }
            }
            ranked.add(new Hit(docId, total));
        }
        ranked.sort(Comparator.<Hit>comparingDouble(h -> h.score).reversed()
                .thenComparingLong(h -> h.docId));
        if (ranked.size() > topK) {
            ranked = new ArrayList<>(ranked.subList(0, topK));
        }
        return ranked;
    }

    /** Execute a list of already-normalised, distinct query terms and return ranked hits. */
    public Response search(List<String> normalisedTerms, int topK) {
        TermFetch fetch = fetchTerms(normalisedTerms);
        // Union candidate docs across all terms (OR semantics -- unchanged).
        LinkedHashSet<Long> candidates = new LinkedHashSet<>();
        for (WorkerSearch.TermPostingsResult r : fetch.byTerm.values()) {
            candidates.addAll(r.termFreqByDoc.keySet());
        }
        List<Hit> ranked = rankDocs(candidates, fetch.byTerm.values(), topK);
        return new Response(ranked, candidates.size(), fetch.failedPartitions);
    }

    /**
     * Execute a Boolean/positional-proximity query (AND/OR/NOT/NEAR/n) with the mandated
     * precedence NOT &gt; AND &gt; OR &gt; NEAR/n. Flow:
     * <ol>
     *   <li>Collect the distinct TERM leaves of the parsed {@link BooleanQuery} and fetch their
     *       postings (tf/df/positions) from the owning workers via the shared {@link #fetchTerms}
     *       (so routing/timeout/fault handling is identical to plain search; terms on different
     *       partitions are combined centrally).</li>
     *   <li>Evaluate the AST with {@link BooleanEvaluator} to get the matching doc set. NOT uses
     *       the global corpus universe; NEAR/n uses the REAL token positions.</li>
     *   <li>Apply BM25 ranking (unchanged {@link #rankDocs}) to ONLY the surviving documents,
     *       using the global corpus statistics. Boolean/proximity filtering therefore happens
     *       BEFORE top-K ranking; scoring itself is identical to the existing engine.</li>
     * </ol>
     */
    public Response searchBoolean(BooleanQuery query, int topK) {
        TermFetch fetch = fetchTerms(query.terms());
        java.util.Set<Long> universe = globalStats.docLengths().keySet();
        BooleanEvaluator evaluator = new BooleanEvaluator(fetch.byTerm, universe);
        java.util.Set<Long> matching = evaluator.evaluate(query);
        List<Hit> ranked = rankDocs(matching, fetch.byTerm.values(), topK);
        return new Response(ranked, matching.size(), fetch.failedPartitions);
    }
}
