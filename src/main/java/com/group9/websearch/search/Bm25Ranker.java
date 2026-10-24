package com.group9.websearch.search;

import com.group9.websearch.index.Posting;
import com.group9.websearch.index.PostingList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Okapi BM25 document ranker. Replaces the previous linear/OLS scoring stage; it scores the
 * candidate set produced by the existing {@link RetrievalEngine} (it does not change retrieval).
 *
 * <p>For each query term {@code t} and candidate document {@code d}:
 * <pre>
 *   BM25(t,d) = IDF(t) * ( tf(t,d) * (k1 + 1) )
 *                        / ( tf(t,d) + k1 * (1 - b + b * dl(d)/avgdl) )
 *   IDF(t)    = ln( 1 + (N - df(t) + 0.5) / (df(t) + 0.5) )
 * </pre>
 * The document score is the sum of the per-term scores. IDF uses the {@code ln(1 + ...)} form,
 * which is always non-negative (no negative-IDF clamping needed).
 *
 * <p>Statistics come from the existing index: {@code df(t)} and {@code tf(t,d)} from the
 * postings, {@code N}, {@code dl(d)} and {@code avgdl} from {@link CorpusStatistics}. PageRank
 * and field boosts are intentionally NOT part of the score.
 *
 * <p>Query-term handling: each <em>distinct</em> query term is scored once. Repeated query
 * terms are collapsed (the existing OR/AND/NOT semantics treat the query as a set of terms;
 * there is no phrase semantics that would require counting a query term twice). Unknown terms
 * (absent from the postings supplied by retrieval) contribute nothing. An empty query, empty
 * candidate set, empty index, or {@code avgdl == 0} are all handled safely (score 0, no NaN /
 * divide-by-zero).
 */
public final class Bm25Ranker {

    private final CorpusStatistics stats;
    private final Bm25Params params;

    public Bm25Ranker(CorpusStatistics stats, Bm25Params params) {
        this.stats = stats;
        this.params = params;
    }

    /** A query term's contribution to a document's BM25 score (for --explain debugging). */
    public record TermScore(String term, int tf, int df, double idf, double contribution) {}

    /** BM25 outcome for one document: total score + per-term breakdown (term -> contribution). */
    public static final class Scored {
        public final double score;
        public final Map<String, Double> breakdown;
        public final List<TermScore> details;

        Scored(double score, Map<String, Double> breakdown, List<TermScore> details) {
            this.score = score;
            this.breakdown = breakdown;
            this.details = details;
        }
    }

    /**
     * Score a single candidate document for the given query terms and the postings retrieval
     * matched (term -> postings; only terms present in the index are keys).
     */
    public Scored score(long docId, List<String> queryTerms, Map<String, PostingList> termPostings) {
        double total = 0.0;
        Map<String, Double> breakdown = new LinkedHashMap<>();
        java.util.ArrayList<TermScore> details = new java.util.ArrayList<>();

        int n = stats.numDocs();
        double avgdl = stats.avgDocLength();
        long dl = stats.docLength(docId);
        // Length-normalisation denominator component; if avgdl is 0 (empty index) fall back to
        // b-less normalisation so we never divide by zero.
        double lengthNorm = (avgdl > 0.0) ? (1.0 - params.b + params.b * (dl / avgdl)) : 1.0;

        // Distinct query terms only (no double counting of repeated query terms).
        for (String term : new LinkedHashSet<>(queryTerms)) {
            PostingList pl = termPostings.get(term);
            if (pl == null || pl.isEmpty()) {
                continue; // unknown term: no contribution
            }
            Posting p = postingFor(pl, docId);
            if (p == null) {
                continue; // term not in this document
            }
            int tf = p.totalTermFrequency();
            if (tf <= 0) {
                continue;
            }
            int df = pl.documentFrequency();
            double idf = idf(n, df);
            double numerator = tf * (params.k1 + 1.0);
            double denominator = tf + params.k1 * lengthNorm;
            double contribution = denominator == 0.0 ? 0.0 : idf * (numerator / denominator);
            total += contribution;
            breakdown.put(term, contribution);
            details.add(new TermScore(term, tf, df, idf, contribution));
        }
        return new Scored(total, breakdown, details);
    }

    /** IDF(t) = ln(1 + (N - df + 0.5) / (df + 0.5)); non-negative for all df in [0, N]. */
    static double idf(int n, int df) {
        double numerator = (n - df) + 0.5;
        double denominator = df + 0.5;
        return Math.log(1.0 + (numerator / denominator));
    }

    private static Posting postingFor(PostingList pl, long docId) {
        for (Posting p : pl.postings()) {
            if (p.docId() == docId) {
                return p;
            }
        }
        return null;
    }
}
