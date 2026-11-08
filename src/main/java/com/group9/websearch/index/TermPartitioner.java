package com.group9.websearch.index;

import java.util.Locale;

/**
 * Deterministic five-way term-range partitioner shared by BOTH distributed indexing (the
 * reducer partition assignment) and query routing (which worker owns a term). Using one class
 * for both guarantees indexing and querying agree on every term's partition.
 *
 * <p>Logical partitions (term ranges over the first normalised character):
 * <pre>
 *   P1  a-h   (0)
 *   P2  i-m   (1)
 *   P3  n-s   (2)
 *   P4  t-z   (3)
 *   P5  everything else: numbers, symbols, non-a-z (incl. non-ASCII letters) (4)
 * </pre>
 *
 * <p>Normalisation MUST match {@link Tokenizer}: terms reaching the index/router are already
 * lower-cased ROOT-locale tokens of Unicode letters/digits. This class re-applies the same
 * lower-casing defensively and keys on the FIRST code point:
 * <ul>
 *   <li>ASCII {@code a-z} -> P1..P4 by letter range.</li>
 *   <li>Everything else (ASCII digits, and any non-ASCII letter/digit such as accented Latin,
 *       CJK, Cyrillic, Greek, etc.) -> P5. This is the explicit, documented "special terms"
 *       bucket; it is never silently merged into an a-z partition.</li>
 * </ul>
 *
 * <p>Empty/blank/null terms are invalid for partitioning and throw, because a token should
 * never be empty by the time it reaches partitioning (the tokenizer never emits empties). This
 * fail-fast surfaces upstream normalisation bugs instead of silently bucketing them.
 */
public final class TermPartitioner {

    /** Number of logical partitions. */
    public static final int NUM_PARTITIONS = 5;

    /** Stable partition identifiers (index == partition number). */
    public static final String[] PARTITION_IDS = {"P1", "P2", "P3", "P4", "P5"};

    private TermPartitioner() {
    }

    /**
     * Return the partition index [0,4] for a term. The term is lower-cased (ROOT) to match the
     * tokenizer; the FIRST code point decides the partition.
     *
     * @throws IllegalArgumentException if the term is null or empty.
     */
    public static int partitionFor(String term) {
        if (term == null || term.isEmpty()) {
            throw new IllegalArgumentException("cannot partition a null/empty term");
        }
        String t = term.toLowerCase(Locale.ROOT);
        int first = t.codePointAt(0);
        // Only ASCII a-z route to P1..P4; everything else is P5 (explicit special bucket).
        if (first >= 'a' && first <= 'h') {
            return 0; // P1
        }
        if (first >= 'i' && first <= 'm') {
            return 1; // P2
        }
        if (first >= 'n' && first <= 's') {
            return 2; // P3
        }
        if (first >= 't' && first <= 'z') {
            return 3; // P4
        }
        return 4; // P5: digits, symbols, non-ASCII letters, anything outside a-z
    }

    /** Stable partition id string ("P1".."P5") for a term. */
    public static String partitionIdFor(String term) {
        return PARTITION_IDS[partitionFor(term)];
    }
}
