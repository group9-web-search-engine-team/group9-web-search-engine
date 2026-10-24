package com.group9.websearch.search;

import com.group9.websearch.index.HitType;
import com.group9.websearch.index.Posting;
import com.group9.websearch.index.PostingList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Efficient set/position operations over positional posting lists.
 *
 * <p>Posting lists are docId-sorted (see {@link PostingList#postings()}), so the boolean
 * operations use linear merges over the sorted docId sequences (classic IIR posting-list
 * intersection/union), not quadratic scans.
 */
public final class PostingOps {

    private PostingOps() {
    }

    /** Union of docIds appearing in any of the given posting lists (OR candidate set). */
    public static Set<Long> unionDocIds(List<PostingList> lists) {
        TreeSet<Long> out = new TreeSet<>();
        for (PostingList pl : lists) {
            if (pl == null) {
                continue;
            }
            for (Posting p : pl.postings()) {
                out.add(p.docId());
            }
        }
        return out;
    }

    /**
     * Intersection of docIds present in <em>every</em> list (AND candidate set). An empty input
     * or any {@code null}/empty list yields an empty intersection (a term absent from the index
     * means no document can satisfy an AND over it).
     */
    public static Set<Long> intersectDocIds(List<PostingList> lists) {
        if (lists.isEmpty()) {
            return new TreeSet<>();
        }
        for (PostingList pl : lists) {
            if (pl == null || pl.isEmpty()) {
                return new TreeSet<>();
            }
        }
        // Start from the smallest list for efficiency, then whittle down.
        List<PostingList> ordered = new ArrayList<>(lists);
        ordered.sort(java.util.Comparator.comparingInt(PostingList::documentFrequency));
        TreeSet<Long> acc = new TreeSet<>(docIds(ordered.get(0)));
        for (int i = 1; i < ordered.size() && !acc.isEmpty(); i++) {
            Set<Long> next = docIds(ordered.get(i));
            acc.retainAll(next);
        }
        return acc;
    }

    /**
     * Set difference over a known universe: {@code base} docIds minus {@code excluded} docIds.
     * The universe constrains the result so NOT is meaningful (IIR: NOT needs a document set).
     */
    public static Set<Long> difference(Set<Long> base, Set<Long> excluded) {
        TreeSet<Long> out = new TreeSet<>(base);
        out.removeAll(excluded);
        return out;
    }

    private static Set<Long> docIds(PostingList pl) {
        TreeSet<Long> ids = new TreeSet<>();
        for (Posting p : pl.postings()) {
            ids.add(p.docId());
        }
        return ids;
    }

    /**
     * Proximity match: return docIds in which all {@code lists} (one posting list per query
     * term, same order) have at least one occurrence within {@code window} token positions of
     * each other, within a single field. Positions are only comparable within the same field
     * (the index keeps per-field position lists separate), so proximity is evaluated per
     * {@link HitType} and a document matches if any field satisfies the window.
     *
     * <p>"Within {@code window}" means the span (max position - min position) covering one
     * chosen occurrence of every term is {@code <= window}. This is a standard bounded-span
     * proximity test; it is order-independent (NEAR), which is the safe default for a prototype.
     */
    public static Set<Long> proximityDocIds(List<PostingList> lists, int window) {
        TreeSet<Long> out = new TreeSet<>();
        if (lists.isEmpty()) {
            return out;
        }
        for (PostingList pl : lists) {
            if (pl == null || pl.isEmpty()) {
                return out; // a missing term cannot satisfy proximity
            }
        }
        // Candidate docs = intersection (all terms must be present to be "near").
        Set<Long> candidates = intersectDocIds(lists);
        for (long docId : candidates) {
            if (satisfiesProximity(lists, docId, window)) {
                out.add(docId);
            }
        }
        return out;
    }

    private static boolean satisfiesProximity(List<PostingList> lists, long docId, int window) {
        // Evaluate each field independently; positions across fields are not comparable.
        Set<HitType> fieldsToTry = fieldsPresentForDoc(lists, docId);
        for (HitType field : fieldsToTry) {
            List<List<Integer>> perTermPositions = new ArrayList<>(lists.size());
            boolean allPresent = true;
            for (PostingList pl : lists) {
                Posting p = postingFor(pl, docId);
                List<Integer> pos = (p == null) ? List.of() : p.positions(field);
                if (pos.isEmpty()) {
                    allPresent = false;
                    break;
                }
                perTermPositions.add(pos);
            }
            if (allPresent && minSpan(perTermPositions) <= window) {
                return true;
            }
        }
        return false;
    }

    /** Fields in which every term has at least one position for this doc are worth testing. */
    private static Set<HitType> fieldsPresentForDoc(List<PostingList> lists, long docId) {
        Set<HitType> common = new LinkedHashSet<>();
        boolean first = true;
        for (PostingList pl : lists) {
            Posting p = postingFor(pl, docId);
            if (p == null) {
                return Set.of();
            }
            Set<HitType> f = new LinkedHashSet<>(p.fields());
            if (first) {
                common.addAll(f);
                first = false;
            } else {
                common.retainAll(f);
            }
        }
        return common;
    }

    private static Posting postingFor(PostingList pl, long docId) {
        if (pl == null) {
            return null;
        }
        for (Posting p : pl.postings()) {
            if (p.docId() == docId) {
                return p;
            }
        }
        return null;
    }

    /**
     * Minimum span covering one position from each term's sorted position list. Uses the
     * classic "smallest range covering elements from k lists" sweep: advance the pointer on the
     * list holding the current minimum until any list is exhausted.
     */
    static int minSpan(List<List<Integer>> lists) {
        int k = lists.size();
        int[] idx = new int[k];
        int best = Integer.MAX_VALUE;
        while (true) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            int minList = -1;
            for (int i = 0; i < k; i++) {
                int v = lists.get(i).get(idx[i]);
                if (v < min) {
                    min = v;
                    minList = i;
                }
                if (v > max) {
                    max = v;
                }
            }
            best = Math.min(best, max - min);
            if (best == 0) {
                return 0;
            }
            // Advance the list that currently holds the minimum.
            idx[minList]++;
            if (idx[minList] == lists.get(minList).size()) {
                break; // one list exhausted; no better span possible
            }
        }
        return best;
    }
}
