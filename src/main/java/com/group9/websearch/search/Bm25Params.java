package com.group9.websearch.search;

/**
 * Okapi BM25 free parameters.
 *
 * <ul>
 *   <li>{@code k1} controls term-frequency saturation (how quickly extra occurrences stop
 *       adding score). The standard default is {@code 1.2}.</li>
 *   <li>{@code b} controls document-length normalisation ({@code 0} = none, {@code 1} = full).
 *       The standard default is {@code 0.75}.</li>
 * </ul>
 *
 * <p>Defaults match the values requested for the initial implementation. They are configurable
 * so experiments can override them, but no configuration is required to run.
 */
public final class Bm25Params {

    public static final double DEFAULT_K1 = 1.2;
    public static final double DEFAULT_B = 0.75;

    public final double k1;
    public final double b;

    public Bm25Params(double k1, double b) {
        if (k1 < 0) {
            throw new IllegalArgumentException("k1 must be >= 0");
        }
        if (b < 0 || b > 1) {
            throw new IllegalArgumentException("b must be in [0, 1]");
        }
        this.k1 = k1;
        this.b = b;
    }

    /** k1 = 1.2, b = 0.75. */
    public static Bm25Params defaults() {
        return new Bm25Params(DEFAULT_K1, DEFAULT_B);
    }

    @Override
    public String toString() {
        return "Bm25Params{k1=" + k1 + ", b=" + b + "}";
    }
}
