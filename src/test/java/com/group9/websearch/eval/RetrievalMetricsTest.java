package com.group9.websearch.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hand-calculated unit tests for the retrieval metrics. Relevance threshold is 1 unless noted.
 */
class RetrievalMetricsTest {

    private static final double EPS = 1e-9;

    private static Map<String, Integer> grades(Object... kv) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    // ---- Precision@k ----

    @Test
    void precisionAtKHandCalc() {
        // ranking: d1(rel) d2(not) d3(rel) d4(not) ; grades: d1=1,d3=1,d2=0,d4=0
        List<String> ranking = List.of("d1", "d2", "d3", "d4");
        Map<String, Integer> g = grades("d1", 1, "d2", 0, "d3", 1, "d4", 0);
        // top-2: d1 rel, d2 not -> 1/2
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
        // top-4: 2 rel of 4 -> 0.5
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranking, g, 4, 1), EPS);
    }

    @Test
    void precisionShortRankingDividesByJudgedRetrieved() {
        List<String> ranking = List.of("d1"); // only one retrieved, and it is judged relevant
        Map<String, Integer> g = grades("d1", 1);
        // Judged-only convention: P@10 = 1 relevant / min(10, 1 judged) = 1.0
        assertEquals(1.0, RetrievalMetrics.precisionAtK(ranking, g, 10, 1), EPS);
    }

    // ---- Recall@k ----

    @Test
    void recallAtKHandCalc() {
        // All judged docs so condensing does not change anything; d9 is relevant but not retrieved.
        List<String> ranking = List.of("d1", "d2", "d3");
        Map<String, Integer> g = grades("d1", 1, "d2", 0, "d3", 1, "d9", 1); // 3 relevant total
        // condensed = [d1,d2,d3]; top-2 (d1,d2) retrieves d1 among relevant -> 1/3
        assertEquals(1.0 / 3.0, RetrievalMetrics.recallAtK(ranking, g, 2, 1), EPS);
        // top-3 (d1,d2,d3) retrieves d1,d3 -> 2/3
        assertEquals(2.0 / 3.0, RetrievalMetrics.recallAtK(ranking, g, 3, 1), EPS);
    }

    @Test
    void recallExcludesUnjudgedFromRankSlots() {
        // d2 unjudged between two relevant judged docs; total relevant = 3 (d1,d3,d9).
        List<String> ranking = List.of("d1", "d2", "d3");
        Map<String, Integer> g = grades("d1", 1, "d3", 1, "d9", 1);
        // condensed = [d1,d3]; Recall@2 = d1,d3 relevant retrieved / 3 total = 2/3.
        assertEquals(2.0 / 3.0, RetrievalMetrics.recallAtK(ranking, g, 2, 1), EPS);
    }

    @Test
    void recallNaNWhenNoRelevant() {
        List<String> ranking = List.of("d1", "d2");
        Map<String, Integer> g = grades("d1", 0, "d2", 0); // none relevant
        assertTrue(Double.isNaN(RetrievalMetrics.recallAtK(ranking, g, 10, 1)));
    }

    // ---- F1@k ----

    @Test
    void f1AtKHarmonicMean() {
        // d2,d4 judged non-relevant (not unjudged) so they remain as rank slots.
        List<String> ranking = List.of("d1", "d2", "d3", "d4");
        Map<String, Integer> g = grades("d1", 1, "d2", 0, "d3", 1, "d4", 0); // 2 relevant total
        // condensed = [d1,d2,d3,d4]; P@2 = 1/2 ; R@2 = 1/2 ; F1 = 2*.5*.5/1 = .5
        assertEquals(0.5, RetrievalMetrics.f1AtK(ranking, g, 2, 1), EPS);
    }

    @Test
    void f1NaNWhenNoRelevant() {
        assertTrue(Double.isNaN(RetrievalMetrics.f1AtK(List.of("d1"), grades("d1", 0), 5, 1)));
    }

    @Test
    void f1ZeroWhenNoHits() {
        // relevant exists (d9) but not retrieved -> precision 0, recall 0 -> F1 0
        assertEquals(0.0, RetrievalMetrics.f1AtK(List.of("d1"), grades("d1", 0, "d9", 1), 5, 1), EPS);
    }

    // ---- MAP / AP ----

    @Test
    void averagePrecisionHandCalc() {
        // All judged (d2,d4 non-relevant). relevant at ranks 1 and 3 (d1,d3); total relevant = 2.
        // condensed = [d1,d2,d3,d4]; AP = (1/1 + 2/3) / 2 = 0.8333...
        List<String> ranking = List.of("d1", "d2", "d3", "d4");
        Map<String, Integer> g = grades("d1", 1, "d2", 0, "d3", 1, "d4", 0);
        double ap = RetrievalMetrics.averagePrecision(ranking, g, 1);
        assertEquals((1.0 + 2.0 / 3.0) / 2.0, ap, EPS);
    }

    @Test
    void averagePrecisionWithUnjudgedRemoved() {
        // d2,d4 UNJUDGED (not in grades). Condensed = [d1,d3] with both relevant.
        // AP = (1/1 + 2/2)/2 = 1.0 (unjudged docs do not depress AP).
        List<String> ranking = List.of("d1", "d2", "d3", "d4");
        Map<String, Integer> g = grades("d1", 1, "d3", 1);
        assertEquals(1.0, RetrievalMetrics.averagePrecision(ranking, g, 1), EPS);
    }

    @Test
    void averagePrecisionNaNWhenNoRelevant() {
        assertTrue(Double.isNaN(RetrievalMetrics.averagePrecision(List.of("d1"), grades("d1", 0), 1)));
    }

    // ---- NDCG@k (graded) ----

    @Test
    void ndcgGradedHandCalc() {
        // ranking d1,d2,d3 with grades d1=2, d2=0, d3=1
        // DCG = 2/log2(2) + 0 + 1/log2(4) = 2/1 + 1/2 = 2.5
        // ideal order by grade desc: 2,1 -> IDCG = 2/log2(2) + 1/log2(3) = 2 + 1/1.585 = 2.6309
        List<String> ranking = List.of("d1", "d2", "d3");
        Map<String, Integer> g = grades("d1", 2, "d2", 0, "d3", 1);
        double log2of3 = Math.log(3) / Math.log(2);
        double dcg = 2.0 / 1.0 + 1.0 / 2.0;
        double idcg = 2.0 / 1.0 + 1.0 / log2of3;
        assertEquals(dcg / idcg, RetrievalMetrics.ndcgAtK(ranking, g, 3), 1e-9);
    }

    @Test
    void ndcgPerfectRankingIsOne() {
        // ranking already in ideal order: grades 3,2,1
        List<String> ranking = List.of("a", "b", "c");
        Map<String, Integer> g = grades("a", 3, "b", 2, "c", 1);
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(ranking, g, 3), 1e-9);
    }

    @Test
    void ndcgZeroWhenNoPositiveGrades() {
        assertEquals(0.0, RetrievalMetrics.ndcgAtK(List.of("a", "b"), grades("a", 0, "b", 0), 10), EPS);
    }

    // ---- edge cases ----

    @Test
    void emptyRankingYieldsZeroPrecisionAndRecall() {
        Map<String, Integer> g = grades("d1", 1);
        assertEquals(0.0, RetrievalMetrics.precisionAtK(List.of(), g, 10, 1), EPS);
        assertEquals(0.0, RetrievalMetrics.recallAtK(List.of(), g, 10, 1), EPS);
        assertEquals(0.0, RetrievalMetrics.ndcgAtK(List.of(), g, 10), EPS);
    }

    @Test
    void duplicateResultsCountedOnce() {
        // d1 appears twice; it is relevant. Dedup keeps one -> P@2 over [d1,d2]
        List<String> ranking = List.of("d1", "d1", "d2");
        Map<String, Integer> g = grades("d1", 1, "d2", 0);
        // deduped ranking: d1,d2 -> top-2: 1 relevant / 2 = 0.5
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
    }

    @Test
    void unjudgedRetrievedDocsAreExcludedNotCountedNonRelevant() {
        // d2 is not in grades (unjudged) -> EXCLUDED from the ranking (condensed), never
        // fabricated as relevant and never counted as a non-relevant rank slot.
        List<String> ranking = List.of("d1", "d2");
        Map<String, Integer> g = grades("d1", 1); // d2 unjudged
        // Condensed ranking = [d1]; P@2 = 1 relevant / min(2, 1 judged) = 1.0
        assertEquals(1.0, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
        assertEquals(1.0, RetrievalMetrics.recallAtK(ranking, g, 2, 1), EPS); // 1 of 1 relevant
    }

    @Test
    void configurableThresholdChangesRelevance() {
        // grade 1 counts as relevant at threshold 1 but NOT at threshold 2
        List<String> ranking = List.of("d1");
        Map<String, Integer> g = grades("d1", 1);
        assertEquals(1.0, RetrievalMetrics.precisionAtK(ranking, g, 1, 1), EPS);
        assertEquals(0.0, RetrievalMetrics.precisionAtK(ranking, g, 1, 2), EPS);
    }
}
