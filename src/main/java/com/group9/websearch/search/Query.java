package com.group9.websearch.search;

import java.util.List;

/**
 * A parsed, normalised query.
 *
 * <p>The raw user string is tokenised with the <em>same</em> {@link com.group9.websearch.index.Tokenizer}
 * used at indexing time, so query terms match index terms exactly (same lower-casing and
 * letter/digit token rule). The {@link Mode} selects how candidate documents are combined:
 *
 * <ul>
 *   <li>{@link Mode#OR} -- union of per-term postings (default for ordinary queries): a document
 *       matches if it contains any query term. Ranking then orders the candidates.</li>
 *   <li>{@link Mode#AND} -- intersection: a document must contain every query term.</li>
 *   <li>{@link Mode#NOT} -- set difference over a document universe: documents matching the
 *       first term(s) minus documents matching the excluded term(s). Requires a known universe
 *       (the DocumentStore), otherwise it degrades to AND of the non-excluded terms.</li>
 *   <li>{@link Mode#PROXIMITY} -- the query terms must occur within a configured window of token
 *       positions of each other (phrase-like / near queries), using stored positions.</li>
 * </ul>
 */
public final class Query {

    public enum Mode { OR, AND, NOT, PROXIMITY }

    private final String raw;
    private final Mode mode;
    private final List<String> terms;
    /** For NOT mode: the terms to exclude (tokens after the NOT marker). */
    private final List<String> excludedTerms;
    /** For PROXIMITY mode: max allowed span (in token positions) covering all query terms. */
    private final int proximityWindow;

    public Query(String raw, Mode mode, List<String> terms, List<String> excludedTerms,
            int proximityWindow) {
        this.raw = raw == null ? "" : raw;
        this.mode = mode;
        this.terms = List.copyOf(terms);
        this.excludedTerms = excludedTerms == null ? List.of() : List.copyOf(excludedTerms);
        this.proximityWindow = proximityWindow;
    }

    public String raw() {
        return raw;
    }

    public Mode mode() {
        return mode;
    }

    public List<String> terms() {
        return terms;
    }

    public List<String> excludedTerms() {
        return excludedTerms;
    }

    public int proximityWindow() {
        return proximityWindow;
    }

    /** True when there are no positive query terms (blank query or only stop punctuation). */
    public boolean isEmpty() {
        return terms.isEmpty();
    }

    @Override
    public String toString() {
        return "Query{mode=" + mode + ", terms=" + terms
                + (excludedTerms.isEmpty() ? "" : ", not=" + excludedTerms)
                + (mode == Mode.PROXIMITY ? ", window=" + proximityWindow : "") + "}";
    }
}
