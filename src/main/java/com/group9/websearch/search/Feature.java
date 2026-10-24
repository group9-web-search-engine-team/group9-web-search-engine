package com.group9.websearch.search;

/**
 * The ranking features extracted per candidate document for a query.
 *
 * <p>Each feature is a real, explainable signal derived from the positional index and the
 * document metadata. Features are kept separate from the weights that combine them (see
 * {@link RankingWeights}) so the scoring is transparent and the weights are configurable.
 *
 * <p>Availability notes (no signal is fabricated):
 * <ul>
 *   <li>{@link #TITLE_MATCHES}, {@link #URL_MATCHES}, {@link #BODY_MATCHES},
 *       {@link #TITLE_COVERAGE}, {@link #BODY_TF}, {@link #PROXIMITY} are computed from the
 *       field-specific positions actually stored in the index.</li>
 *   <li>{@link #ANCHOR_MATCHES} is defined but will be 0 until the indexer is fed anchor text
 *       (the current export does not carry it).</li>
 *   <li>{@link #PAGERANK} is defined but will be 0 until PageRank data is available (the crawler
 *       slice does not populate it); it is read only if a metadata source provides it.</li>
 * </ul>
 */
public enum Feature {
    /** Number of distinct query terms that hit the TITLE field. */
    TITLE_MATCHES,
    /** Number of distinct query terms that hit the URL field. */
    URL_MATCHES,
    /** Number of distinct query terms that hit the ANCHOR field (0 unless anchor text indexed). */
    ANCHOR_MATCHES,
    /** Number of distinct query terms that hit the BODY field. */
    BODY_MATCHES,
    /** Fraction of query terms present in the TITLE (0..1). */
    TITLE_COVERAGE,
    /** Fraction of query terms present anywhere in the document (0..1). */
    QUERY_COVERAGE,
    /** Log-dampened total body term frequency of query terms. */
    BODY_TF,
    /** Proximity signal: higher when query terms occur close together (0..1). */
    PROXIMITY,
    /** PageRank of the document (0 unless a metadata source provides it). */
    PAGERANK
}
