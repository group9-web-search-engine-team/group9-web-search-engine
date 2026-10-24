package com.group9.websearch.search;

import com.group9.websearch.index.Tokenizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a raw user query string into a normalised {@link Query}.
 *
 * <p>Normalisation reuses the indexing {@link Tokenizer} so query terms are lower-cased and
 * split on the same letter/digit rule as index terms (no divergent query-time tokenisation).
 *
 * <p>Lightweight operator syntax (prototype-level, deterministic, no external parser):
 * <ul>
 *   <li>A leading {@code AND:} / {@code OR:} / {@code NOT:} / {@code NEAR:} (case-insensitive)
 *       selects the retrieval {@link Query.Mode}. Without a prefix the mode defaults to OR.</li>
 *   <li>In {@code NOT:} mode, a literal {@code -} prefix on a token (e.g. {@code -spam}) marks it
 *       for exclusion; the remaining tokens are the positive terms.</li>
 *   <li>In {@code NEAR:} mode, an optional {@code /N} immediately after the prefix sets the
 *       proximity window (e.g. {@code NEAR/5: quick fox}); the default window is used otherwise.</li>
 * </ul>
 *
 * <p>Everything else is treated as ordinary query text. The operator markers themselves are
 * never indexed terms, so they are consumed, not searched.
 */
public final class QueryParser {

    private final int defaultProximityWindow;

    public QueryParser(int defaultProximityWindow) {
        this.defaultProximityWindow = Math.max(1, defaultProximityWindow);
    }

    public Query parse(String rawQuery) {
        String raw = rawQuery == null ? "" : rawQuery.trim();
        if (raw.isEmpty()) {
            return new Query(raw, Query.Mode.OR, List.of(), List.of(), defaultProximityWindow);
        }

        Query.Mode mode = Query.Mode.OR;
        int window = defaultProximityWindow;
        String body = raw;

        // Detect a leading MODE: prefix (optionally MODE/N: for NEAR).
        int colon = raw.indexOf(':');
        if (colon > 0) {
            String head = raw.substring(0, colon).trim();
            String op = head;
            int slash = head.indexOf('/');
            if (slash > 0) {
                op = head.substring(0, slash).trim();
                try {
                    window = Math.max(1, Integer.parseInt(head.substring(slash + 1).trim()));
                } catch (NumberFormatException ignore) {
                    window = defaultProximityWindow;
                }
            }
            Query.Mode parsed = modeFor(op);
            if (parsed != null) {
                mode = parsed;
                body = raw.substring(colon + 1).trim();
            }
        }

        if (mode == Query.Mode.NOT) {
            return parseNot(raw, body);
        }

        List<String> terms = Tokenizer.tokenize(body);
        return new Query(raw, mode, terms, List.of(), window);
    }

    private Query parseNot(String raw, String body) {
        // Split on whitespace first so the '-' exclusion marker is visible before the tokenizer
        // (which would otherwise drop punctuation). Each whitespace chunk is then tokenised.
        List<String> positive = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (String chunk : body.split("\\s+")) {
            if (chunk.isEmpty()) {
                continue;
            }
            boolean exclude = chunk.startsWith("-");
            String text = exclude ? chunk.substring(1) : chunk;
            List<String> toks = Tokenizer.tokenize(text);
            if (exclude) {
                excluded.addAll(toks);
            } else {
                positive.addAll(toks);
            }
        }
        return new Query(raw, Query.Mode.NOT, positive, excluded, defaultProximityWindow);
    }

    private static Query.Mode modeFor(String op) {
        return switch (op.toUpperCase(java.util.Locale.ROOT)) {
            case "AND" -> Query.Mode.AND;
            case "OR" -> Query.Mode.OR;
            case "NOT" -> Query.Mode.NOT;
            case "NEAR", "PROX", "PROXIMITY" -> Query.Mode.PROXIMITY;
            default -> null;
        };
    }
}
