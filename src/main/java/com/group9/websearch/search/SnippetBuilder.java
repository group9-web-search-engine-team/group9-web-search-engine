package com.group9.websearch.search;

import com.group9.websearch.index.Tokenizer;
import java.util.List;
import java.util.Set;

/**
 * Builds a concise, query-biased snippet from a document's indexed body text.
 *
 * <p>It tokenises the body with the same {@link Tokenizer} used for indexing (so "query term
 * present" aligns with how the term was indexed), finds the first body window containing a
 * query term, and returns a short raw-text excerpt around it. If no query term is found in the
 * body (e.g. the match was in the title/url), it falls back to the body prefix. Everything is
 * derived from genuinely available text; nothing is fabricated.
 */
public final class SnippetBuilder {

    private final int maxChars;
    private final int contextTokens;

    public SnippetBuilder(int maxChars, int contextTokens) {
        this.maxChars = Math.max(40, maxChars);
        this.contextTokens = Math.max(3, contextTokens);
    }

    public String build(String body, List<String> queryTerms) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalizedBody = body.strip();
        Set<String> qset = Set.copyOf(queryTerms);

        // Token stream of the body (lower-cased) to locate the first query hit.
        List<String> tokens = Tokenizer.tokenize(normalizedBody);
        int hitIdx = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (qset.contains(tokens.get(i))) {
                hitIdx = i;
                break;
            }
        }
        if (hitIdx < 0) {
            // No body hit: show the body prefix.
            return truncate(normalizedBody);
        }

        // Map a token window back to an approximate raw-text excerpt. Since the tokenizer drops
        // punctuation, we re-scan the raw text to find a character window around the hit token.
        String hitToken = tokens.get(hitIdx);
        int charPos = indexOfTokenIgnoreCase(normalizedBody, hitToken);
        if (charPos < 0) {
            return truncate(normalizedBody);
        }
        int approxCharsPerToken = 6;
        int start = Math.max(0, charPos - contextTokens * approxCharsPerToken);
        int end = Math.min(normalizedBody.length(), charPos + contextTokens * approxCharsPerToken + hitToken.length());
        String excerpt = normalizedBody.substring(start, end).strip();
        if (start > 0) {
            excerpt = "… " + excerpt;
        }
        if (end < normalizedBody.length()) {
            excerpt = excerpt + " …";
        }
        return truncate(excerpt);
    }

    private String truncate(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        if (flat.length() <= maxChars) {
            return flat;
        }
        return flat.substring(0, maxChars - 1).strip() + "…";
    }

    /** First case-insensitive occurrence of a whole-ish token in the raw text. */
    private static int indexOfTokenIgnoreCase(String haystack, String token) {
        return haystack.toLowerCase(java.util.Locale.ROOT).indexOf(token.toLowerCase(java.util.Locale.ROOT));
    }
}
