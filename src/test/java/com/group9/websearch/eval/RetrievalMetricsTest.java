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
    void precisionShortRankingDividesByK() {
        List<String> ranking = List.of("d1"); // only one retrieved
        Map<String, Integer> g = grades("d1", 1);
        // P@10 = 1 relevant / 10 = 0.1
        assertEquals(0.1, RetrievalMetrics.precisionAtK(ranking, g, 10, 1), EPS);
    }

    // ---- Recall@k ----

    @Test
    void recallAtKHandCalc() {
        List<String> ranking = List.of("d1", "d2", "d3");
        Map<String, Integer> g = grades("d1", 1, "d3", 1, "d9", 1); // 3 relevant total
        // top-2 retrieves d1 only among relevant -> 1/3
        assertEquals(1.0 / 3.0, RetrievalMetrics.recallAtK(ranking, g, 2, 1), EPS);
        // top-3 retrieves d1,d3 -> 2/3
        assertEquals(2.0 / 3.0, RetrievalMetrics.recallAtK(ranking, g, 3, 1), EPS);
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
        List<String> ranking = List.of("d1", "d2", "d3", "d4");
        Map<String, Integer> g = grades("d1", 1, "d3", 1); // 2 relevant total
        // P@2 = 1/2 ; R@2 = 1/2 ; F1 = 2*.5*.5/(1) = .5
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
        // relevant at ranks 1 and 3 (d1,d3); total relevant = 2
        // AP = (1/1 + 2/3) / 2 = (1 + 0.6667)/2 = 0.8333...
        List<String> ranking = List.of("d1", "d2", "d3", "d4");
        Map<String, Integer> g = grades("d1", 1, "d3", 1);
        double ap = RetrievalMetrics.averagePrecision(ranking, g, 1);
        assertEquals((1.0 + 2.0 / 3.0) / 2.0, ap, EPS);
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
    void unjudgedRetrievedDocsAreNotRelevant() {
        // d2 is not in grades (unjudged) -> treated as not relevant, never fabricated as rel
        List<String> ranking = List.of("d1", "d2");
        Map<String, Integer> g = grades("d1", 1); // d2 unjudged
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
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
