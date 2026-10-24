package com.group9.websearch.eval;

import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic implementations of the retrieval-evaluation metrics required by the
 * project report, plus F1@k as an auxiliary measure.
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code ranking} -- the ordered list of retrieved docIds for one query (rank 1 first).
 *       Duplicates are collapsed keeping first occurrence (a document is judged once).</li>
 *   <li>{@code grades} -- the judged relevance grades for that query ({@code docId -> grade}).
 *       A document NOT present in this map is <em>unjudged</em>. Per TREC/BEIR convention we do
 *       NOT invent a label for unjudged documents: for binary metrics an unjudged document is
 *       treated as not-relevant for precision accounting (standard pooled-judgement practice),
 *       which the caller is told via documentation; relevant-document totals for recall/MAP use
 *       only judged-relevant documents.</li>
 * </ul>
 *
 * <p>Relevance threshold: a document is "relevant" for the binary metrics (P@k, R@k, F1@k, MAP)
 * when its grade {@code >= relevanceThreshold}. The threshold is explicit and configurable
 * (BEIR TREC-COVID uses grades 0,1,2; a threshold of 1 counts grades 1 and 2 as relevant).
 * NDCG uses the graded relevance values directly (gain = grade), so it is unaffected by the
 * binary threshold.
 *
 * <p>Conventions for degenerate cases (documented and consistent):
 * <ul>
 *   <li>No judged-relevant documents for a query -> recall and MAP/AP are undefined; such
 *       queries are reported as {@link Double#NaN} and excluded from macro averages (a query
 *       with no positives cannot contribute a meaningful recall/AP). Precision is still defined
 *       and reported.</li>
 *   <li>Empty ranking -> all metrics are 0 except where the query also has no relevant docs
 *       (then recall/MAP are NaN as above).</li>
 *   <li>Fewer than k retrieved -> metrics use min(k, retrieved) positions; P@k divides by k as
 *       usual (so short rankings can cap P@k below 1).</li>
 * </ul>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** Distinct ranking preserving first-seen order (dedup so a doc is counted once). */
    static List<String> dedup(List<String> ranking) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(ranking);
        return new java.util.ArrayList<>(seen);
    }

    /** True if docId's grade meets the relevance threshold. Unjudged -> not relevant. */
    static boolean isRelevant(String docId, Map<String, Integer> grades, int threshold) {
        Integer g = grades.get(docId);
        return g != null && g >= threshold;
    }

    /** Number of judged-relevant documents for the query (recall denominator). */
    public static int numRelevant(Map<String, Integer> grades, int threshold) {
        int n = 0;
        for (int g : grades.values()) {
            if (g >= threshold) {
                n++;
            }
        }
        return n;
    }

    /** Precision@k = (relevant retrieved in top-k) / k. */
    public static double precisionAtK(List<String> ranking, Map<String, Integer> grades,
            int k, int threshold) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0");
        }
        List<String> r = dedup(ranking);
        int limit = Math.min(k, r.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (isRelevant(r.get(i), grades, threshold)) {
                hits++;
            }
        }
        return hits / (double) k;
    }

    /**
     * Recall@k = (relevant retrieved in top-k) / (total judged-relevant). Returns NaN when the
     * query has no judged-relevant documents (recall undefined).
     */
    public static double recallAtK(List<String> ranking, Map<String, Integer> grades,
            int k, int threshold) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0");
        }
        int totalRel = numRelevant(grades, threshold);
        if (totalRel == 0) {
            return Double.NaN;
        }
        List<String> r = dedup(ranking);
        int limit = Math.min(k, r.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (isRelevant(r.get(i), grades, threshold)) {
                hits++;
            }
        }
        return hits / (double) totalRel;
    }

    /**
     * F1@k = harmonic mean of Precision@k and Recall@k. NaN when recall is NaN (no relevant
     * docs). 0 when both precision and recall are 0.
     */
    public static double f1AtK(List<String> ranking, Map<String, Integer> grades,
            int k, int threshold) {
        double p = precisionAtK(ranking, grades, k, threshold);
        double rec = recallAtK(ranking, grades, k, threshold);
        if (Double.isNaN(rec)) {
            return Double.NaN;
        }
        if (p + rec == 0.0) {
            return 0.0;
        }
        return 2 * p * rec / (p + rec);
    }

    /**
     * Average Precision (AP) for one query: mean of precision values taken at each rank where a
     * relevant document is retrieved, divided by the total number of judged-relevant documents.
     * Returns NaN when there are no judged-relevant documents. The full ranking is used (AP is
     * not cut off), which is the standard definition behind MAP.
     */
    public static double averagePrecision(List<String> ranking, Map<String, Integer> grades,
            int threshold) {
        int totalRel = numRelevant(grades, threshold);
        if (totalRel == 0) {
            return Double.NaN;
        }
        List<String> r = dedup(ranking);
        int hits = 0;
        double sumPrecision = 0.0;
        for (int i = 0; i < r.size(); i++) {
            if (isRelevant(r.get(i), grades, threshold)) {
                hits++;
                sumPrecision += hits / (double) (i + 1);
            }
        }
        return sumPrecision / totalRel;
    }

    /**
     * NDCG@k with graded relevance. DCG uses gain = grade and the standard
     * {@code 1/log2(rank+1)} discount; the ideal DCG (IDCG) is computed from the best possible
     * ordering of all judged grades for the query. Unjudged retrieved documents contribute gain
     * 0. Returns 0.0 when IDCG is 0 (no positive grades). Not NaN, because NDCG is conventionally
     * defined as 0 for a query with no gain available (callers may still exclude such queries).
     */
    public static double ndcgAtK(List<String> ranking, Map<String, Integer> grades, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0");
        }
        List<String> r = dedup(ranking);
        int limit = Math.min(k, r.size());
        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            Integer g = grades.get(r.get(i));
            int gain = (g == null) ? 0 : Math.max(0, g);
            if (gain > 0) {
                dcg += gain / (Math.log(i + 2) / Math.log(2)); // rank i (0-based) -> log2(i+2)
            }
        }
        double idcg = idealDcg(grades, k);
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    /** IDCG@k: DCG of the best ordering (grades sorted descending), gains >= 0. */
    static double idealDcg(Map<String, Integer> grades, int k) {
        List<Integer> gains = new java.util.ArrayList<>();
        for (int g : grades.values()) {
            if (g > 0) {
                gains.add(g);
            }
        }
        gains.sort(java.util.Collections.reverseOrder());
        int limit = Math.min(k, gains.size());
        double idcg = 0.0;
        for (int i = 0; i < limit; i++) {
            idcg += gains.get(i) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg;
    }
}
