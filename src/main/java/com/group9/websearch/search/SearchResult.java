package com.group9.websearch.search;

import java.util.Map;

/**
 * One ranked result row: rank, docId, score, url, title, a concise snippet, and the per-term
 * BM25 score breakdown for debugging (term -> contribution). url/title/snippet may be
 * {@code null}/empty when the metadata store has no entry for the docId (the result still shows
 * docId and score).
 */
public final class SearchResult {

    private final int rank;
    private final long docId;
    private final double score;
    private final String url;
    private final String title;
    private final String snippet;
    private final Map<String, Double> breakdown;

    public SearchResult(int rank, long docId, double score, String url, String title,
            String snippet, Map<String, Double> breakdown) {
        this.rank = rank;
        this.docId = docId;
        this.score = score;
        this.url = url;
        this.title = title;
        this.snippet = snippet;
        this.breakdown = breakdown;
    }

    public int rank() {
        return rank;
    }

    public long docId() {
        return docId;
    }

    public double score() {
        return score;
    }

    public String url() {
        return url;
    }

    public String title() {
        return title;
    }

    public String snippet() {
        return snippet;
    }

    /** Per-term BM25 contributions (term -> score), for --explain. */
    public Map<String, Double> breakdown() {
        return breakdown;
    }
}
