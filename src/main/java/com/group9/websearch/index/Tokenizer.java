package com.group9.websearch.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, Unicode-aware tokenizer for SPIMI indexing.
 *
 * <p>Policy (documented and stable so the index is reproducible):
 * <ul>
 *   <li>Case is normalised to lower-case using {@link Locale#ROOT} (locale-independent).</li>
 *   <li>A token is a maximal run of Unicode <em>letters or digits</em>
 *       ({@link Character#isLetterOrDigit(int)}), iterated by code point so non-BMP
 *       characters and non-ASCII scripts are preserved (e.g. accented Latin, CJK, Cyrillic).</li>
 *   <li>All other characters (punctuation, whitespace, symbols) are token separators.</li>
 *   <li>Empty tokens are never produced.</li>
 * </ul>
 *
 * <p>No stemming or stopword removal is applied in this first implementation. This keeps
 * tokenisation simple, reproducible and language-neutral; stemming/stopwords can be layered
 * on later as a documented, separately tested policy change.
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    /**
     * Tokenise text into an ordered list of normalised tokens (duplicates preserved so the
     * caller can count term frequencies).
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(cp);
            } else if (current.length() > 0) {
                tokens.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
            i += charCount;
        }
        if (current.length() > 0) {
            tokens.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    /**
     * Count term frequencies within a single document's text. Returns an insertion-ordered
     * map of term -> frequency (ordering is first-seen; callers that need lexicographic
     * order sort explicitly).
     */
    public static java.util.LinkedHashMap<String, Integer> termFrequencies(String text) {
        java.util.LinkedHashMap<String, Integer> tf = new java.util.LinkedHashMap<>();
        for (String token : tokenize(text)) {
            tf.merge(token, 1, Integer::sum);
        }
        return tf;
    }

    /**
     * Compute zero-based token positions of each term within a single independently tokenized
     * field. The i-th token produced by {@link #tokenize(String)} is assigned position {@code i}
     * (positions therefore correspond exactly to the normalised token sequence). Every
     * occurrence is preserved, so {@code "cat sat cat"} yields {@code cat -> [0, 2]} and the
     * caller can derive term frequency from the number of positions.
     *
     * <p>Returned lists are in ascending position order (tokens are visited left to right).
     * The map is insertion-ordered (first-seen term order).
     */
    public static java.util.LinkedHashMap<String, java.util.List<Integer>> positions(String text) {
        java.util.LinkedHashMap<String, java.util.List<Integer>> out = new java.util.LinkedHashMap<>();
        List<String> tokens = tokenize(text);
        for (int i = 0; i < tokens.size(); i++) {
            out.computeIfAbsent(tokens.get(i), t -> new ArrayList<>()).add(i);
        }
        return out;
    }
}
