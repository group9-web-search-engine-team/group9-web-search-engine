package com.group9.websearch.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the BEIR audit requirement #1 (judged-only / condensed-list evaluation)
 * and #3 (macro-averaging, zero-relevant query handling). Small deterministic fixtures only.
 */
class JudgedOnlyEvaluationTest {

    private static final double EPS = 1e-9;

    private static Map<String, Integer> grades(Object... kv) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    void unjudgedRetrievedDocIsExcludedNotTreatedNonRelevant() {
        // ranking: rel, UNJUDGED, rel -> condensed [rel,rel]; both top-2 relevant -> P@2 = 1.0.
        List<String> ranking = List.of("rel1", "unjudged", "rel2");
        Map<String, Integer> g = grades("rel1", 1, "rel2", 1); // "unjudged" absent
        assertEquals(1.0, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
        // If it were counted as non-relevant in-slot, P@2 would be 0.5 -- prove it is NOT.
        assertFalse(0.5 == RetrievalMetrics.precisionAtK(ranking, g, 2, 1));
    }

    @Test
    void judgedNonRelevantStillCountsAsNonRelevant() {
        // "nr" is judged grade 0 -> kept in condensed list and counts as a non-relevant slot.
        List<String> ranking = List.of("nr", "rel");
        Map<String, Integer> g = grades("nr", 0, "rel", 1);
        // condensed [nr,rel]; P@1 over [nr] = 0 relevant / 1 = 0.0.
        assertEquals(0.0, RetrievalMetrics.precisionAtK(ranking, g, 1, 1), EPS);
        // P@2 = 1 relevant / 2 = 0.5.
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
    }

    @Test
    void judgedRelevantIsCountedCorrectly() {
        List<String> ranking = List.of("rel");
        Map<String, Integer> g = grades("rel", 2);
        assertEquals(1.0, RetrievalMetrics.precisionAtK(ranking, g, 1, 1), EPS);
        assertEquals(1.0, RetrievalMetrics.recallAtK(ranking, g, 1, 1), EPS);
        assertEquals(1.0, RetrievalMetrics.averagePrecision(ranking, g, 1), EPS);
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(ranking, g, 1), EPS);
    }

    @Test
    void precisionAtKWithUnjudgedInOriginalRanking() {
        // Many unjudged docs between two judged ones; they must not consume rank slots.
        List<String> ranking = List.of("u1", "rel", "u2", "u3", "nr");
        Map<String, Integer> g = grades("rel", 1, "nr", 0); // u1,u2,u3 unjudged
        // condensed [rel,nr]; P@1=1.0, P@2=0.5.
        assertEquals(1.0, RetrievalMetrics.precisionAtK(ranking, g, 1, 1), EPS);
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
    }

    @Test
    void recallAtKWithJudgedRelevant() {
        List<String> ranking = List.of("u1", "rel1", "u2", "rel2");
        Map<String, Integer> g = grades("rel1", 1, "rel2", 1, "rel3", 1); // 3 relevant total
        // condensed [rel1,rel2]; Recall@2 = 2/3.
        assertEquals(2.0 / 3.0, RetrievalMetrics.recallAtK(ranking, g, 2, 1), EPS);
    }

    @Test
    void mapWithUnjudgedRemoved() {
        // rel at original rank 2 (behind 1 unjudged) -> condensed rank 1 -> AP = 1.0.
        List<String> ranking = List.of("u1", "rel");
        Map<String, Integer> g = grades("rel", 1);
        assertEquals(1.0, RetrievalMetrics.averagePrecision(ranking, g, 1), EPS);
    }

    @Test
    void ndcgUsesJudgedGradesOnly() {
        // ranking: rel(grade2), unjudged, rel(grade1) -> condensed [g2,g1].
        // DCG = 2/log2(2) + 1/log2(3) ; IDCG (order 2,1) same -> 1.0.
        List<String> ranking = List.of("a", "u", "b");
        Map<String, Integer> g = grades("a", 2, "b", 1);
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(ranking, g, 3), EPS);
    }

    @Test
    void queryWithNoJudgedRelevantIsNaNForRecallAndAp() {
        List<String> ranking = List.of("nr1", "nr2");
        Map<String, Integer> g = grades("nr1", 0, "nr2", 0); // judged but none relevant
        assertTrue(Double.isNaN(RetrievalMetrics.recallAtK(ranking, g, 10, 1)));
        assertTrue(Double.isNaN(RetrievalMetrics.averagePrecision(ranking, g, 1)));
        // precision is still defined (0 relevant over condensed list).
        assertEquals(0.0, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
    }

    @Test
    void macroAverageExcludesZeroRelevantQueriesFromRecallMapButNotPrecision() {
        AggregateReport r = new AggregateReport(10, 1);
        // Query A: has relevant, P=1.0 R=1.0 AP=1.0 nDCG=1.0.
        r.add(new QueryEvaluation("A", 10, 1, 1, 1.0, 1.0, 1.0, 1.0, 1.0, false, null));
        // Query B: has relevant, P=0.5 R=0.5 AP=0.5 nDCG=0.5.
        r.add(new QueryEvaluation("B", 10, 2, 2, 0.5, 0.5, 0.5, 0.5, 0.5, false, null));
        // Query C: NO judged relevant -> recall/F1/AP NaN, precision 0.0, nDCG 0.0.
        r.add(new QueryEvaluation("C", 10, 1, 0, 0.0, Double.NaN, Double.NaN, Double.NaN, 0.0,
                false, "no relevant judgements (recall/MAP undefined)"));

        // Precision/nDCG macro over all 3 evaluated queries: (1.0+0.5+0.0)/3 and (1.0+0.5+0.0)/3.
        assertEquals((1.0 + 0.5 + 0.0) / 3.0, r.meanPrecision(), EPS);
        assertEquals((1.0 + 0.5 + 0.0) / 3.0, r.meanNdcg(), EPS);
        // Recall/MAP macro over the 2 queries with relevant docs only: (1.0+0.5)/2.
        assertEquals((1.0 + 0.5) / 2.0, r.meanRecall(), EPS);
        assertEquals((1.0 + 0.5) / 2.0, r.meanAveragePrecision(), EPS);
        // Count bookkeeping.
        assertEquals(3, r.evaluatedCount());
    }

    @Test
    void macroAverageIsNotMicroAverage() {
        // Two queries with very different judged-relevant counts; macro must weight queries
        // equally, not documents. Q1 P=1.0 (1 rel of 1), Q2 P=0.0 (0 rel of 100 judged).
        AggregateReport r = new AggregateReport(10, 1);
        r.add(new QueryEvaluation("Q1", 10, 1, 1, 1.0, 1.0, 1.0, 1.0, 1.0, false, null));
        r.add(new QueryEvaluation("Q2", 10, 100, 1, 0.0, 0.0, 0.0, 0.0, 0.0, false, null));
        // Macro precision = (1.0 + 0.0)/2 = 0.5 (equal per-query weight).
        assertEquals(0.5, r.meanPrecision(), EPS);
    }
}
