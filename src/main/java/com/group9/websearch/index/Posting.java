package com.group9.websearch.index;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A single posting: everything the index knows about one term occurring in one document.
 *
 * <p>Unlike a frequency-only posting, this is <em>position-aware and field-aware</em>. For each
 * {@link HitType field} the term appears in, the posting stores the zero-based token positions
 * of every occurrence within that independently tokenized field. Positions within a field are
 * kept sorted and unique; the field's term frequency is derived from the number of positions
 * (never stored as a separate, potentially inconsistent number).
 *
 * <p>Positions from different fields are kept in separate lists (an {@link EnumMap} keyed by
 * {@link HitType}) so title/url/anchor/metadata/body hits stay distinguishable for field-aware
 * ranking and so merges never collapse distinct fields into one indistinguishable list.
 *
 * <p>Persisted form (all numeric + fixed punctuation, collision-free because tokens are numbers
 * and field tags are letters): {@code docId:<fieldTag><pos>-<pos>...;<fieldTag><pos>...}. For
 * example a doc 12 with body positions 0,2 and title position 0 serialises as
 * {@code 12:t0;b0-2}. Fields are emitted in {@link HitType} declaration order for determinism.
 */
public final class Posting {

    private final long docId;
    /** field -> sorted, unique positions. */
    private final EnumMap<HitType, TreeSet<Integer>> positionsByField = new EnumMap<>(HitType.class);

    public Posting(long docId) {
        if (docId < 0) {
            throw new IllegalArgumentException("docId must be >= 0");
        }
        this.docId = docId;
    }

    public long docId() {
        return docId;
    }

    /** Record one occurrence of the term in {@code field} at zero-based {@code position}. */
    public void addPosition(HitType field, int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position must be >= 0");
        }
        positionsByField.computeIfAbsent(field, f -> new TreeSet<>()).add(position);
    }

    /** Record several occurrences in one field (duplicates collapse; order normalised). */
    public void addPositions(HitType field, Iterable<Integer> positions) {
        TreeSet<Integer> set = positionsByField.computeIfAbsent(field, f -> new TreeSet<>());
        for (int p : positions) {
            if (p < 0) {
                throw new IllegalArgumentException("position must be >= 0");
            }
            set.add(p);
        }
    }

    /** Fields in which this term occurs, in {@link HitType} declaration order. */
    public List<HitType> fields() {
        List<HitType> out = new ArrayList<>(positionsByField.keySet());
        return out;
    }

    /** Sorted, unique positions for a field (empty if the term does not occur in it). */
    public List<Integer> positions(HitType field) {
        TreeSet<Integer> set = positionsByField.get(field);
        if (set == null) {
            return List.of();
        }
        return new ArrayList<>(set);
    }

    /** Term frequency within a single field, derived from its position count. */
    public int termFrequency(HitType field) {
        TreeSet<Integer> set = positionsByField.get(field);
        return set == null ? 0 : set.size();
    }

    /** Total term frequency across all fields (sum of per-field position counts). */
    public int totalTermFrequency() {
        int sum = 0;
        for (TreeSet<Integer> set : positionsByField.values()) {
            sum += set.size();
        }
        return sum;
    }

    public boolean isEmpty() {
        return positionsByField.isEmpty();
    }

    /**
     * Merge another posting for the <em>same</em> docId into this one, unioning per-field
     * position sets (duplicates collapse, nothing is lost or double-counted).
     */
    public void merge(Posting other) {
        if (other.docId != this.docId) {
            throw new IllegalArgumentException(
                    "cannot merge postings for different docIds: " + this.docId + " vs " + other.docId);
        }
        for (Map.Entry<HitType, TreeSet<Integer>> e : other.positionsByField.entrySet()) {
            positionsByField.computeIfAbsent(e.getKey(), f -> new TreeSet<>()).addAll(e.getValue());
        }
    }

    /**
     * Serialise as {@code docId:<fieldTag><pos>-<pos>...;...}. Fields are written in
     * {@link HitType} declaration order; positions ascending. A posting with no fields (should
     * not occur) serialises as just the docId.
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(docId).append(':');
        boolean firstField = true;
        for (HitType field : HitType.values()) { // declaration order == deterministic
            TreeSet<Integer> set = positionsByField.get(field);
            if (set == null || set.isEmpty()) {
                continue;
            }
            if (!firstField) {
                sb.append(';');
            }
            sb.append(field.code());
            boolean firstPos = true;
            for (int p : set) {
                if (!firstPos) {
                    sb.append('-');
                }
                sb.append(p);
                firstPos = false;
            }
            firstField = false;
        }
        return sb.toString();
    }

    /** Parse a {@code docId:<fieldTag><positions>;...} token produced by {@link #serialize()}. */
    public static Posting parse(String token) {
        int colon = token.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("malformed posting (no docId): " + token);
        }
        final long docId;
        try {
            docId = Long.parseLong(token.substring(0, colon));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed posting docId: " + token, e);
        }
        Posting posting = new Posting(docId);
        String rest = token.substring(colon + 1);
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("malformed posting (no field hits): " + token);
        }
        for (String fieldGroup : rest.split(";")) {
            if (fieldGroup.isEmpty()) {
                throw new IllegalArgumentException("malformed posting (empty field group): " + token);
            }
            HitType field = HitType.fromCode(fieldGroup.charAt(0));
            String posList = fieldGroup.substring(1);
            if (posList.isEmpty()) {
                throw new IllegalArgumentException("malformed posting (field with no positions): " + token);
            }
            for (String p : posList.split("-")) {
                try {
                    posting.addPosition(field, Integer.parseInt(p));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("malformed posting position: " + token, e);
                }
            }
        }
        return posting;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Posting other)) {
            return false;
        }
        return docId == other.docId && positionsByField.equals(other.positionsByField);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(docId) * 31 + positionsByField.hashCode();
    }

    @Override
    public String toString() {
        return serialize();
    }

    // Package-visible defensive copy for diagnostics/tests.
    EnumMap<HitType, TreeSet<Integer>> rawPositions() {
        EnumMap<HitType, TreeSet<Integer>> copy = new EnumMap<>(HitType.class);
        for (Map.Entry<HitType, TreeSet<Integer>> e : positionsByField.entrySet()) {
            copy.put(e.getKey(), new TreeSet<>(e.getValue()));
        }
        return copy;
    }
}
