package com.group9.websearch.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the judged-only ("condensed list") evaluation policy for the BEIR audit: an unmapped
 * result (a placeholder that is never in the qrels) is UNJUDGED and is therefore EXCLUDED from
 * the ranking before metrics are computed, exactly like any other unjudged document. The
 * relative order of the surviving JUDGED documents is preserved. Uses the real
 * {@link RetrievalMetrics} with hand-computed expectations (threshold 1).
 */
class RankPreservationTest {

    private static final double EPS = 1e-9;

    private static Map<String, Integer> grades(Object... kv) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    void unmappedPlaceholderIsExcludedFromRanking() {
        // BM25 ranking: rank1 = unmapped (placeholder, unjudged), rank2 = relevant "R" (grade 2).
        // Under judged-only condensing, the placeholder is dropped and R is the only judged doc.
        Map<String, Integer> g = grades("R", 2);
        List<String> withPlaceholder = List.of("__UNMAPPED__#9@0", "R");

        // P@1 over condensed [R] = 1 relevant / min(1,1) = 1.0 (placeholder does not occupy rank1).
        assertEquals(1.0, RetrievalMetrics.precisionAtK(withPlaceholder, g, 1, 1), EPS);
        // AP over condensed [R] = R at condensed rank 1 -> 1/1 ; /1 relevant = 1.0.
        assertEquals(1.0, RetrievalMetrics.averagePrecision(withPlaceholder, g, 1), EPS);
    }

    @Test
    void placeholderAndUnjudgedDocAreBothExcludedIdentically() {
        Map<String, Integer> g = grades("R", 1);
        // A placeholder and an unjudged real doc are both unjudged -> both condensed out.
        List<String> withPlaceholder = List.of("__UNMAPPED__#5@0", "R");
        List<String> withUnjudged = List.of("someUnjudgedDoc", "R");
        assertEquals(
                RetrievalMetrics.precisionAtK(withUnjudged, g, 2, 1),
                RetrievalMetrics.precisionAtK(withPlaceholder, g, 2, 1), EPS);
        assertEquals(
                RetrievalMetrics.ndcgAtK(withUnjudged, g, 2),
                RetrievalMetrics.ndcgAtK(withPlaceholder, g, 2), EPS);
    }

    @Test
    void judgedOrderPreservedAfterExcludingUnjudged() {
        // Judged docs A(rel) and B(non-rel) with an unjudged doc interleaved between them.
        // Condensing must keep A before B. P@2 over [A,B] = 1 relevant / 2 = 0.5.
        Map<String, Integer> g = grades("A", 1, "B", 0);
        List<String> ranking = List.of("A", "__UNMAPPED__#3@1", "B");
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranking, g, 2, 1), EPS);
        // nDCG: condensed [A,B]; DCG = 1/log2(2) = 1 ; IDCG (grades 1) = 1 -> 1.0.
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(ranking, g, 2), EPS);
        assertTrue(RetrievalMetrics.averagePrecision(ranking, g, 1) > 0.0);
    }
}
