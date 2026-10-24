package com.group9.websearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class LinearRankerTest {

    @Test
    void scoreIsSumOfFeatureTimesWeight() {
        EnumMap<Feature, Double> w = new EnumMap<>(Feature.class);
        w.put(Feature.TITLE_MATCHES, 3.0);
        w.put(Feature.BODY_TF, 0.5);
        LinearRanker ranker = new LinearRanker(new RankingWeights(w));

        FeatureVector fv = FeatureVector.builder()
                .set(Feature.TITLE_MATCHES, 2.0)   // 2 * 3 = 6
                .set(Feature.BODY_TF, 4.0)          // 4 * 0.5 = 2
                .build();
        LinearRanker.Scored s = ranker.score(fv);
        assertEquals(8.0, s.score, 1e-9);
    }

    @Test
    void breakdownExposesPerFeatureContribution() {
        LinearRanker ranker = new LinearRanker(RankingWeights.defaults());
        FeatureVector fv = FeatureVector.builder()
                .set(Feature.QUERY_COVERAGE, 1.0)
                .build();
        LinearRanker.Scored s = ranker.score(fv);
        LinearRanker.Contribution c = s.breakdown.get(Feature.QUERY_COVERAGE);
        assertEquals(1.0, c.value(), 1e-9);
        assertEquals(5.0, c.weight(), 1e-9);   // default QUERY_COVERAGE weight
        assertEquals(5.0, c.contribution(), 1e-9);
    }

    @Test
    void absentPagerankContributesNothing() {
        LinearRanker ranker = new LinearRanker(RankingWeights.defaults());
        // No PAGERANK set -> value 0 -> contribution 0 even though weight is non-zero.
        LinearRanker.Scored s = ranker.score(FeatureVector.builder().build());
        assertEquals(0.0, s.score, 1e-9);
        assertTrue(s.breakdown.get(Feature.PAGERANK) == null
                || s.breakdown.get(Feature.PAGERANK).contribution() == 0.0);
    }
}
