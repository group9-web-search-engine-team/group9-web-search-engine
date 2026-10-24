package com.group9.websearch.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AggregateReportTest {

    @Test
    void macroAveragesExcludeNaNAndNoRelevantForRecall() {
        AggregateReport r = new AggregateReport(10, 1);
        // q1: normal, precision .5, recall .5, f1 .5, ap .5, ndcg .8, judgedRel=2
        r.add(new QueryEvaluation("q1", 10, 10, 2, 0.5, 0.5, 0.5, 0.5, 0.8, false, null));
        // q2: no relevant judgements -> recall/f1/ap NaN, judgedRel=0, precision/ndcg defined
        r.add(new QueryEvaluation("q2", 10, 10, 0, 0.2, Double.NaN, Double.NaN, Double.NaN, 0.0, false, "no rel"));

        // precision averaged over both (incl. zero-relevant): (0.5 + 0.2)/2 = 0.35
        assertEquals(0.35, r.meanPrecision(), 1e-9);
        // ndcg averaged over both: (0.8 + 0.0)/2 = 0.4
        assertEquals(0.4, r.meanNdcg(), 1e-9);
        // recall averaged only over q1 (q2 excluded): 0.5
        assertEquals(0.5, r.meanRecall(), 1e-9);
        // MAP only over q1: 0.5
        assertEquals(0.5, r.meanAveragePrecision(), 1e-9);
    }

    @Test
    void skippedQueriesExcludedEverywhere() {
        AggregateReport r = new AggregateReport(10, 1);
        r.add(new QueryEvaluation("q1", 10, 10, 1, 1.0, 1.0, 1.0, 1.0, 1.0, false, null));
        r.add(QueryEvaluation.skipped("q2", 10, "no qrels"));
        assertEquals(1, r.evaluatedCount());
        assertEquals(1, r.skippedCount());
        assertEquals(1.0, r.meanPrecision(), 1e-9); // only q1
    }

    @Test
    void allSkippedGivesNaN() {
        AggregateReport r = new AggregateReport(10, 1);
        r.add(QueryEvaluation.skipped("q1", 10, "no qrels"));
        assertTrue(Double.isNaN(r.meanPrecision()));
        assertTrue(Double.isNaN(r.meanNdcg()));
    }
}
