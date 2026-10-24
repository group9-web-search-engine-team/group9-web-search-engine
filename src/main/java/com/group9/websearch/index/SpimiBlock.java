package com.group9.websearch.index;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * An in-memory SPIMI (Single-Pass In-Memory Indexing) block: a term dictionary mapping each
 * term to its {@link PostingList}, built one document at a time.
 *
 * <p>SPIMI, explicitly (Manning, Raghavan & Schütze, IIR §4.3):
 * <ol>
 *   <li>For each document, add each (term, docId, tf) to the dictionary. If the term is new,
 *       a new postings list is created on demand; otherwise the posting is appended/aggregated
 *       into the existing list -- no separate sort of (term, docId) pairs and no global
 *       vocabulary is required (that is the "single pass, in memory" property).</li>
 *   <li>When the block's size reaches a configured threshold, the block is flushed: its terms
 *       are sorted lexicographically and written out as a self-contained sorted run, then the
 *       in-memory dictionary is cleared and indexing continues.</li>
 * </ol>
 *
 * <p>The flush threshold here is an <em>approximate entry count</em> (number of distinct
 * terms plus total postings), documented as an approximation of memory pressure rather than
 * exact bytes. {@link #shouldFlush()} reports when the threshold is reached; the owner
 * flushes via {@link #flushSorted(Writer)} and then {@link #clear()}s.
 */
public final class SpimiBlock {

    /** Sentinel for "no threshold" (flush only at end of task). */
    public static final long NO_LIMIT = -1L;

    private final Map<String, PostingList> dictionary = new HashMap<>();
    private final long maxEntries;
    private long approxEntries;

    public SpimiBlock() {
        this(NO_LIMIT);
    }

    /**
     * @param maxEntries approximate entry budget (distinct terms + total postings) after which
     *                   {@link #shouldFlush()} returns true; {@link #NO_LIMIT} disables it.
     */
    public SpimiBlock(long maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * Index one field of one document positionally. {@code termPositions} maps each normalised
     * term to its zero-based positions within this independently tokenized field (as produced
     * by {@link Tokenizer#positions(String)}). Each term's postings list is created on first
     * sight, then the (docId, field, positions) occurrences are aggregated into it.
     *
     * <p>Positions from different fields of the same document stay in separate per-field lists
     * inside the {@link Posting}; calling this once per field accumulates them correctly.
     */
    public void addFieldPositions(long docId, HitType field,
            Map<String, ? extends java.util.List<Integer>> termPositions) {
        for (Map.Entry<String, ? extends java.util.List<Integer>> e : termPositions.entrySet()) {
            String term = e.getKey();
            PostingList list = dictionary.get(term);
            if (list == null) {
                list = new PostingList();
                dictionary.put(term, list);
                approxEntries++; // new distinct term
            }
            list.addPositions(docId, field, e.getValue());
            // Count each stored position as an entry so the memory estimate tracks positional
            // growth, not just distinct (term, doc) pairs.
            approxEntries += e.getValue().size();
        }
    }

    /** True when the configured entry budget has been reached (approximate). */
    public boolean shouldFlush() {
        return maxEntries != NO_LIMIT && approxEntries >= maxEntries && !dictionary.isEmpty();
    }

    public boolean isEmpty() {
        return dictionary.isEmpty();
    }

    public int termCount() {
        return dictionary.size();
    }

    long approxEntries() {
        return approxEntries;
    }

    /**
     * Write the block as a sorted run: terms in lexicographic order, one escaping-safe record
     * per line ({@link IndexRecordCodec}), postings ordered by docId. The {@link Writer} is
     * flushed but not closed (the caller owns its lifecycle).
     */
    public void flushSorted(Writer writer) throws IOException {
        // TreeMap gives lexicographic term order deterministically.
        TreeMap<String, PostingList> sorted = new TreeMap<>(dictionary);
        BufferedWriter bw = (writer instanceof BufferedWriter b) ? b : new BufferedWriter(writer);
        for (Map.Entry<String, PostingList> e : sorted.entrySet()) {
            bw.write(IndexRecordCodec.encode(e.getKey(), e.getValue()));
            bw.write('\n');
        }
        bw.flush();
    }

    /** Clear the in-memory dictionary after a flush so indexing can continue. */
    public void clear() {
        dictionary.clear();
        approxEntries = 0;
    }

    /** Package-visible for tests: direct access to a term's postings. */
    PostingList get(String term) {
        return dictionary.get(term);
    }
}
