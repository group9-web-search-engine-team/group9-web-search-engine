package com.group9.websearch.index;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * A term's postings list: docId -> {@link Posting}, where each posting is position-aware and
 * field-aware (see {@link Posting}).
 *
 * <p>Aggregation rule: adding an occurrence (docId, field, position) or merging a posting for a
 * docId already present UNIONS the per-field position sets, so duplicate occurrences collapse
 * and nothing is lost or double-counted. A {@link TreeMap} keeps postings ordered by docId for
 * deterministic output.
 */
public final class PostingList {

    private final TreeMap<Long, Posting> byDoc = new TreeMap<>();

    /** Record one occurrence of the term: (docId, field, zero-based position). */
    public void add(long docId, HitType field, int position) {
        byDoc.computeIfAbsent(docId, Posting::new).addPosition(field, position);
    }

    /** Record several positions of the term in one field for a document. */
    public void addPositions(long docId, HitType field, Iterable<Integer> positions) {
        byDoc.computeIfAbsent(docId, Posting::new).addPositions(field, positions);
    }

    /** Add/merge a whole posting (its docId is taken from the posting). */
    public void add(Posting posting) {
        byDoc.merge(posting.docId(), posting, (existing, incoming) -> {
            existing.merge(incoming);
            return existing;
        });
    }

    /** Merge another postings list into this one (unioning per-doc, per-field positions). */
    public void merge(PostingList other) {
        for (Posting p : other.byDoc.values()) {
            add(p);
        }
    }

    /** Postings ordered by ascending docId. */
    public List<Posting> postings() {
        return new ArrayList<>(byDoc.values());
    }

    /** Number of documents containing the term. */
    public int documentFrequency() {
        return byDoc.size();
    }

    public boolean isEmpty() {
        return byDoc.isEmpty();
    }

    /**
     * Serialise postings as {@code posting,posting,...} ordered by docId, where each posting is
     * {@link Posting#serialize()}. Safe because posting tokens contain only digits, field-tag
     * letters and the fixed punctuation {@code : ; -}; the comma separator never collides. The
     * final index format keeps the term in a separate, escaping-safe field (see
     * {@link IndexRecordCodec}).
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Posting p : byDoc.values()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(p.serialize());
            first = false;
        }
        return sb.toString();
    }

    /** Parse a {@code posting,posting,...} string into a {@link PostingList}. */
    public static PostingList parse(String s) {
        PostingList list = new PostingList();
        if (s == null || s.isEmpty()) {
            return list;
        }
        for (String token : s.split(",")) {
            if (!token.isEmpty()) {
                list.add(Posting.parse(token));
            }
        }
        return list;
    }
}
