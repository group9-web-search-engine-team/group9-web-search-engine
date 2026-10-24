package com.group9.websearch.search;

import java.util.EnumMap;
import java.util.Map;

/**
 * A document's feature values for one query: {@link Feature} -> double. Missing features read
 * as 0.0. Immutable once built.
 */
public final class FeatureVector {

    private final EnumMap<Feature, Double> values;

    private FeatureVector(EnumMap<Feature, Double> values) {
        this.values = values;
    }

    public double get(Feature f) {
        return values.getOrDefault(f, 0.0);
    }

    public Map<Feature, Double> asMap() {
        return new EnumMap<>(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; set only the features that apply, the rest default to 0. */
    public static final class Builder {
        private final EnumMap<Feature, Double> values = new EnumMap<>(Feature.class);

        public Builder set(Feature f, double v) {
            values.put(f, v);
            return this;
        }

        public FeatureVector build() {
            return new FeatureVector(values);
        }
    }
}
