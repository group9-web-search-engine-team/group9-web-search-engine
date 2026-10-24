package com.group9.websearch.search;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-feature weights for the linear ranker, kept separate from the features themselves.
 *
 * <p>These are hand-set <em>prototype</em> weights, NOT learned parameters. They encode the
 * usual web-search intuition that title and URL hits and full query coverage matter more than
 * raw body frequency, and that term proximity is a positive signal. They are fully configurable
 * (see {@link SearchConfig}); nothing here claims to be a trained model. PageRank is given a
 * non-zero weight so that when PageRank data becomes available it contributes, but it is 0 in
 * the feature vector until then, so it has no effect on current scores.
 */
public final class RankingWeights {

    private final EnumMap<Feature, Double> weights;

    public RankingWeights(Map<Feature, Double> weights) {
        this.weights = new EnumMap<>(Feature.class);
        this.weights.putAll(weights);
    }

    public double weight(Feature f) {
        return weights.getOrDefault(f, 0.0);
    }

    public Map<Feature, Double> asMap() {
        return new EnumMap<>(weights);
    }

    /** Sensible default prototype weights (documented as hand-tuned, not learned). */
    public static RankingWeights defaults() {
        EnumMap<Feature, Double> w = new EnumMap<>(Feature.class);
        w.put(Feature.TITLE_MATCHES, 3.0);
        w.put(Feature.URL_MATCHES, 2.0);
        w.put(Feature.ANCHOR_MATCHES, 2.5);
        w.put(Feature.BODY_MATCHES, 1.0);
        w.put(Feature.TITLE_COVERAGE, 4.0);
        w.put(Feature.QUERY_COVERAGE, 5.0);
        w.put(Feature.BODY_TF, 0.5);
        w.put(Feature.PROXIMITY, 2.0);
        w.put(Feature.PAGERANK, 1.0);
        return new RankingWeights(w);
    }
}
