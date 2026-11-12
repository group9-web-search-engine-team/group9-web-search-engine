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
 *       A document NOT present in this map is <em>unjudged</em>.</li>
 * </ul>
 *
 * <p><b>Unjudged-document policy (judged-only / "condensed list" evaluation).</b> Per the BEIR
 * TREC-COVID evaluation requirement, an UNJUDGED retrieved document is EXCLUDED from the ranking
 * before any metric is computed -- it is NOT treated as a non-relevant document that occupies a
 * rank slot. Concretely, every metric first {@link #condense(List, Map) condenses} the ranking to
 * only the documents that carry an explicit qrels judgement, PRESERVING the relative order of
 * those judged documents. All metrics are then computed over that condensed, judged-only ranking.
 * This guarantees that interleaved unjudged documents can never silently depress Precision or
 * nDCG by pushing judged-relevant documents down.
 *
 * <p>Relevance threshold: a document is "relevant" for the binary metrics (P@k, R@k, F1@k, MAP)
 * when its grade {@code >= relevanceThreshold}. The threshold is explicit and configurable
 * (BEIR TREC-COVID uses grades 0,1,2; a threshold of 1 counts grades 1 and 2 as relevant).
 * NDCG uses the graded relevance values directly (gain = grade), so it is unaffected by the
 * binary threshold.
 *
 * <p><b>Denominator conventions (documented and consistent), computed over the condensed list:</b>
 * <ul>
 *   <li><b>Precision@k</b> = (judged-relevant in the top-k of the condensed ranking) /
 *       min(k, number of judged documents retrieved). Dividing by the judged count (not raw k)
 *       is what prevents unjudged documents from silently reducing precision.</li>
 *   <li><b>Recall@k</b> = (judged-relevant in the top-k of the condensed ranking) /
 *       (total judged-relevant documents for the query).</li>
 *   <li><b>MAP / AP</b> = mean of precision taken at each condensed rank holding a relevant doc,
 *       divided by the total judged-relevant documents. AP uses the full condensed ranking.</li>
 *   <li><b>nDCG@k</b> = DCG over the top-k of the condensed ranking (gain = judged grade, so
 *       every surviving doc is judged) divided by the IDCG computed from the judged grades only.</li>
 * </ul>
 *
 * <p>Degenerate cases (documented and consistent):
 * <ul>
 *   <li>No judged-relevant documents for a query -> recall and MAP/AP are undefined; reported as
 *       {@link Double#NaN} and excluded from macro averages. Precision/nDCG are still defined.</li>
 *   <li>Empty (or all-unjudged) ranking -> condensed list is empty; precision/nDCG = 0, and
 *       recall/MAP are NaN when the query also has no relevant docs.</li>
 *   <li>Fewer than k judged retrieved -> Precision@k divides by min(k, judgedRetrieved).</li>
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

    /**
     * Judged-only "condensed" ranking: keep ONLY documents that have an explicit qrels judgement
     * (present in {@code grades}), preserving their relative order; unjudged documents (including
     * evaluation placeholders, which are never in the qrels) are removed. Also de-duplicates.
     * This is the single place the unjudged-exclusion policy is enforced for ALL metrics.
     */
    static List<String> condense(List<String> ranking, Map<String, Integer> grades) {
        List<String> deduped = dedup(ranking);
        List<String> out = new java.util.ArrayList<>(deduped.size());
        for (String docId : deduped) {
            if (grades.containsKey(docId)) { // judged (relevant or non-relevant) -> keep
                out.add(docId);
            }
        }
        return out;
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

    /**
     * Precision@k over the condensed (judged-only) ranking =
     * (judged-relevant in top-k) / min(k, judged-documents-retrieved). Unjudged documents are
     * excluded first, so they never occupy a rank slot nor reduce the denominator. Returns 0.0
     * when no judged documents were retrieved.
     */
    public static double precisionAtK(List<String> ranking, Map<String, Integer> grades,
            int k, int threshold) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0");
        }
        List<String> r = condense(ranking, grades);
        if (r.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, r.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (isRelevant(r.get(i), grades, threshold)) {
                hits++;
            }
        }
        return hits / (double) limit; // denominator = judged docs considered (min(k, judged))
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
        List<String> r = condense(ranking, grades);
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
        List<String> r = condense(ranking, grades);
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
     * NDCG@k with graded relevance. DCG uses gain = (2^grade - 1) and the standard
     * {@code 1/log2(rank+1)} discount; the ideal DCG (IDCG) is computed from the best possible
     * ordering of all judged grades for the query. Unjudged retrieved documents are REMOVED
     * before scoring (judged-only condensed list), so they neither add gain nor consume a
     * discounted rank slot. Returns 0.0 when IDCG is 0 (no positive grades). Not NaN, because
     * NDCG is conventionally defined as 0 for a query with no gain available.
     */
    public static double ndcgAtK(List<String> ranking, Map<String, Integer> grades, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0");
        }
        List<String> r = condense(ranking, grades); // judged-only; unjudged never discount gain
        int limit = Math.min(k, r.size());
        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            Integer g = grades.get(r.get(i));
            int grade = (g == null) ? 0 : Math.max(0, g);
            if (grade > 0) {
                double gain = Math.pow(2.0, grade) - 1.0; // exponential gain: 2^grade - 1
                dcg += gain / (Math.log(i + 2) / Math.log(2)); // rank i (0-based) -> log2(i+2)
            }
        }
        double idcg = idealDcg(grades, k);
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    /** IDCG@k: DCG of the best ordering (grades sorted descending), gains = 2^grade - 1. */
    static double idealDcg(Map<String, Integer> grades, int k) {
        List<Integer> gradeList = new java.util.ArrayList<>();
        for (int g : grades.values()) {
            if (g > 0) {
                gradeList.add(g);
            }
        }
        gradeList.sort(java.util.Collections.reverseOrder());
        int limit = Math.min(k, gradeList.size());
        double idcg = 0.0;
        for (int i = 0; i < limit; i++) {
            double gain = Math.pow(2.0, gradeList.get(i)) - 1.0; // exponential gain: 2^grade - 1
            idcg += gain / (Math.log(i + 2) / Math.log(2));
        }
        return idcg;
    }
}
