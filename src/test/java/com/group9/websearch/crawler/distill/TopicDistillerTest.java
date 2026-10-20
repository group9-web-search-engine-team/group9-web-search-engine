package com.group9.websearch.crawler.distill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.crawler.persistence.FrontierRepository.LinkEdge;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the weighted-HITS distiller on small, known graphs. No database.
 */
class TopicDistillerTest {

    private static LinkEdge e(String s, String d) {
        return new LinkEdge(s, d);
    }

    @Test
    void identifiesHubPointingToManyAuthorities() {
        // "hub" points to three authorities; each authority is also pointed at by a distinct
        // single-link page. The node pointing to the most (good) authorities should score
        // highest as a hub -- this is HITS behaviour, not raw out-degree alone.
        List<LinkEdge> edges = List.of(
                e("hub", "a1"), e("hub", "a2"), e("hub", "a3"),
                e("p1", "a1"), e("p2", "a2"), e("p3", "a3"));
        Map<String, Double> quality = Map.of(); // all neutral weight

        Map<String, Double> hub = TopicDistiller.computeHubScores(edges, quality);
        List<String> top = TopicDistiller.topHubs(hub, 1);
        assertEquals("hub", top.get(0), "the page linking to the most authorities is the top hub");
        assertTrue(hub.get("hub") > hub.getOrDefault("p1", 0.0));
    }

    @Test
    void relevanceWeightingFavoursHubsLinkingToHighQualityAuthorities() {
        // Two hubs each link to two authorities. hubGood links to high-QT5 authorities;
        // hubBad links to low-QT5 authorities. Relevance weighting should rank hubGood above
        // hubBad even though out-degree is identical (so this is NOT raw out-degree sorting).
        List<LinkEdge> edges = List.of(
                e("hubGood", "ga1"), e("hubGood", "ga2"),
                e("hubBad", "ba1"), e("hubBad", "ba2"));
        Map<String, Double> quality = Map.of(
                "ga1", -0.1, "ga2", -0.1,   // high quality (log-prob close to 0)
                "ba1", -8.0, "ba2", -8.0);  // low quality

        Map<String, Double> hub = TopicDistiller.computeHubScores(edges, quality);
        assertTrue(hub.get("hubGood") > hub.get("hubBad"),
                "hub linking to higher-QT5 authorities must score higher");
    }

    @Test
    void weightMapsLogProbToUnitInterval() {
        assertEquals(TopicDistiller.DEFAULT_WEIGHT, TopicDistiller.weight("x", Map.of()));
        double w0 = TopicDistiller.weight("x", Map.of("x", 0.0));   // exp(0)=1
        double wNeg = TopicDistiller.weight("x", Map.of("x", -2.0)); // exp(-2)~0.135
        assertEquals(1.0, w0, 1e-9);
        assertTrue(wNeg > 0.0 && wNeg < 1.0 && wNeg < w0);
    }

    @Test
    void emptyGraphYieldsNoHubs() {
        Map<String, Double> hub = TopicDistiller.computeHubScores(List.of(), Map.of());
        assertTrue(hub.isEmpty());
        assertTrue(TopicDistiller.topHubs(hub, 5).isEmpty());
    }
}
