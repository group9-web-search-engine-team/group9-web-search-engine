package com.group9.websearch.search.cluster;

import java.util.List;
import java.util.Map;

/**
 * The contract a worker exposes to the coordinator. A worker owns one or more logical term
 * partitions and answers term lookups from its LOCAL index partition.
 *
 * <p><b>Global-BM25 design (documented):</b> a worker returns RAW postings features for the
 * terms it owns -- for each term, its document frequency df(t) and, per matching document, the
 * term frequency tf(t,d) -- NOT a BM25 score. It does NOT compute a worker-local BM25 score,
 * because a local score would use worker-local N / avgdl and be inconsistent with the global
 * index. The coordinator combines these raw features with the GLOBAL corpus statistics
 * (N, avgdl, dl(d) from the published {@link com.group9.websearch.search.CorpusStatsArtifact})
 * to produce one globally-consistent BM25 score per document. Because the term-range partitioner
 * assigns each term to exactly one partition, the owning worker's df(t) IS the global df(t), so
 * IDF is exact.
 */
public interface WorkerSearch {

    /**
     * Postings for one term from a worker: df(t), per-doc tf(t,d), and per-doc token positions.
     *
     * <p>Positions ({@code positionsByDoc}) are the sorted, de-duplicated token positions of the
     * term in each matching document, MERGED across index fields into the document's single token
     * stream. They are required for positional {@code NEAR/n} proximity matching, which must use
     * real positions (never a document-level approximation). {@code tf} is retained unchanged so
     * the existing global BM25 path is byte-for-byte unaffected (tf == number of positions, but
     * kept explicit for compatibility and because empty-position legacy responses still carry tf).
     */
    final class TermPostingsResult {
        public final String term;
        public final int documentFrequency;          // df(t) -- global, since term is partitioned
        public final Map<Long, Integer> termFreqByDoc; // docId -> tf(t,d)
        public final Map<Long, int[]> positionsByDoc;  // docId -> sorted token positions

        public TermPostingsResult(String term, int documentFrequency,
                Map<Long, Integer> termFreqByDoc, Map<Long, int[]> positionsByDoc) {
            this.term = term;
            this.documentFrequency = documentFrequency;
            this.termFreqByDoc = termFreqByDoc;
            this.positionsByDoc = positionsByDoc == null ? Map.of() : positionsByDoc;
        }

        /** Backward-compatible constructor (no positions) for callers that only need tf/df. */
        public TermPostingsResult(String term, int documentFrequency,
                Map<Long, Integer> termFreqByDoc) {
            this(term, documentFrequency, termFreqByDoc, Map.of());
        }

        public static TermPostingsResult empty(String term) {
            return new TermPostingsResult(term, 0, Map.of(), Map.of());
        }
    }

    /**
     * Look up the given terms in this worker's local partition(s). Only terms this worker owns
     * should be sent; the coordinator routes by {@link com.group9.websearch.index.TermPartitioner}.
     * Returns one result per requested term (empty result if the term is absent).
     */
    List<TermPostingsResult> lookup(List<String> terms);

    /** A stable identifier for diagnostics/logging (e.g. the partition id or address). */
    String id();
}
