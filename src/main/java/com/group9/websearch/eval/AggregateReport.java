package com.group9.websearch.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates per-query evaluations at one cutoff k into macro averages.
 *
 * <p>Macro-averaging convention (documented, consistent): a metric's macro average is the mean
 * over the queries for which that metric is <em>defined</em> (not NaN and not skipped). So:
 * <ul>
 *   <li>Precision@k / NDCG@k are averaged over all evaluated (non-skipped) queries.</li>
 *   <li>Recall@k / F1@k / AP (-> MAP) are averaged only over evaluated queries that have at
 *       least one judged-relevant document (NaN queries are excluded, not counted as 0).</li>
 * </ul>
 * This avoids both penalising the system for queries with no positives and silently treating an
 * undefined metric as zero.
 */
public final class AggregateReport {

    public final int k;
    public final int relevanceThreshold;
    public final List<QueryEvaluation> perQuery = new ArrayList<>();

    public AggregateReport(int k, int relevanceThreshold) {
        this.k = k;
        this.relevanceThreshold = relevanceThreshold;
    }

    public void add(QueryEvaluation q) {
        perQuery.add(q);
    }

    public int evaluatedCount() {
        int n = 0;
        for (QueryEvaluation q : perQuery) {
            if (!q.skipped) {
                n++;
            }
        }
        return n;
    }

    public int skippedCount() {
        return perQuery.size() - evaluatedCount();
    }

    public double meanPrecision() {
        return mean(true, q -> q.precision);
    }

    public double meanRecall() {
        return mean(false, q -> q.recall);
    }

    public double meanF1() {
        return mean(false, q -> q.f1);
    }

    /** MAP = mean average precision over queries that have judged-relevant documents. */
    public double meanAveragePrecision() {
        return mean(false, q -> q.averagePrecision);
    }

    public double meanNdcg() {
        return mean(true, q -> q.ndcg);
    }

    private interface Getter {
        double get(QueryEvaluation q);
    }

    /**
     * @param includeZeroRelevant when true, queries with no relevant docs still contribute
     *                            (used for precision/NDCG); when false they are excluded.
     */
    private double mean(boolean includeZeroRelevant, Getter g) {
        double sum = 0.0;
        int n = 0;
        for (QueryEvaluation q : perQuery) {
            if (q.skipped) {
                continue;
            }
            double v = g.get(q);
            if (Double.isNaN(v)) {
                continue;
            }
            if (!includeZeroRelevant && q.judgedRelevant == 0) {
                continue;
            }
            sum += v;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
