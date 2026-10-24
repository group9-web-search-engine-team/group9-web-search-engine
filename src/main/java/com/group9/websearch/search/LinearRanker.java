package com.group9.websearch.search;

import java.util.EnumMap;
import java.util.Map;

/**
 * Deterministic linear ranker: {@code score = sum over features of (featureValue * weight)}.
 *
 * <p>The ranker is intentionally trivial and transparent. It produces, alongside the scalar
 * score, a per-feature breakdown (value, weight, contribution) so ranking decisions are fully
 * explainable for debugging. It is a prototype scoring function with hand-set weights -- it is
 * not a learned model. The design (feature vector in, score + breakdown out) is exactly the
 * seam a future learned or neural reranker would plug into without changing retrieval.
 */
public final class LinearRanker {

    private final RankingWeights weights;

    public LinearRanker(RankingWeights weights) {
        this.weights = weights;
    }

    /** One feature's contribution to the final score. */
    public record Contribution(Feature feature, double value, double weight, double contribution) {}

    /** The ranker's output for one document: total score + ordered per-feature breakdown. */
    public static final class Scored {
        public final double score;
        public final Map<Feature, Contribution> breakdown;

        Scored(double score, Map<Feature, Contribution> breakdown) {
            this.score = score;
            this.breakdown = breakdown;
        }
    }

    public Scored score(FeatureVector features) {
        double total = 0.0;
        EnumMap<Feature, Contribution> breakdown = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            double v = features.get(f);
            double w = weights.weight(f);
            double c = v * w;
            total += c;
            if (v != 0.0 || w != 0.0) {
                breakdown.put(f, new Contribution(f, v, w, c));
            }
        }
        return new Scored(total, breakdown);
    }
}
