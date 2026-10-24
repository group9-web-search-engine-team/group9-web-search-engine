package com.group9.websearch.eval;

/**
 * Metric scores for a single evaluated query at a single cutoff k. NaN marks an undefined
 * metric for that query (e.g. recall/AP when the query has no judged-relevant documents); the
 * aggregator excludes NaN values from macro averages per the documented convention.
 */
public final class QueryEvaluation {

    public final String queryId;
    public final int k;
    public final int retrieved;       // number of retrieved (deduped) results
    public final int judgedRelevant;  // judged-relevant docs for this query (recall denominator)
    public final double precision;
    public final double recall;
    public final double f1;
    public final double averagePrecision; // cutoff-independent (used for MAP)
    public final double ndcg;
    public final boolean skipped;     // true if the query was not evaluated
    public final String note;         // reason for skip / caveat (may be null)

    public QueryEvaluation(String queryId, int k, int retrieved, int judgedRelevant,
            double precision, double recall, double f1, double averagePrecision, double ndcg,
            boolean skipped, String note) {
        this.queryId = queryId;
        this.k = k;
        this.retrieved = retrieved;
        this.judgedRelevant = judgedRelevant;
        this.precision = precision;
        this.recall = recall;
        this.f1 = f1;
        this.averagePrecision = averagePrecision;
        this.ndcg = ndcg;
        this.skipped = skipped;
        this.note = note;
    }

    public static QueryEvaluation skipped(String queryId, int k, String note) {
        return new QueryEvaluation(queryId, k, 0, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, true, note);
    }
}
