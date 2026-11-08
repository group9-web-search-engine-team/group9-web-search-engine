package com.group9.websearch.search.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates a {@link BooleanQuery} AST over per-term postings fetched from the distributed
 * workers, producing the set of matching documents. It operates purely on the already-fetched
 * postings (tf + real token positions) and the global document universe; it performs no further
 * network I/O, so Boolean/NEAR evaluation is partition-agnostic -- terms living on different
 * workers are combined here identically to terms on the same worker.
 *
 * <p>Each node evaluates to a {@link Result}: the matching docId set plus, where meaningful, the
 * per-document token positions that satisfy the node (needed so {@code NEAR/n} can chain and use
 * REAL positions). Positions are carried for TERM nodes and for NEAR results (the union of the
 * matched positions). For AND/OR/NOT the positional meaning is not well-defined, so those nodes
 * carry an empty position map; a {@code NEAR} applied to such an operand therefore cannot do true
 * positional matching and falls back to document-level co-occurrence for that side (documented
 * limitation -- true proximity is always used for the normal term-to-term NEAR case).
 *
 * <ul>
 *   <li><b>TERM</b> -> docs where the term occurs, with its positions.</li>
 *   <li><b>NOT</b>  -> universe \ child docs (set complement over the global corpus universe).</li>
 *   <li><b>AND</b>  -> intersection of child doc sets.</li>
 *   <li><b>OR</b>   -> union of child doc sets.</li>
 *   <li><b>NEAR/n</b> -> docs where some left position and some right position are within n
 *       tokens (|lp - rp| &lt;= n), using the real positions from the positional index.</li>
 * </ul>
 */
public final class BooleanEvaluator {

    /** A node's evaluation: matching docs + per-doc positions (empty when not position-bearing). */
    public static final class Result {
        final Set<Long> docs;
        final Map<Long, int[]> positions; // docId -> sorted positions (may be empty)

        Result(Set<Long> docs, Map<Long, int[]> positions) {
            this.docs = docs;
            this.positions = positions;
        }
    }

    private final Map<String, WorkerSearch.TermPostingsResult> termData;
    private final Set<Long> universe;

    /**
     * @param termData  per-term fetched postings (term -> tf/df/positions), from the coordinator.
     * @param universe  the global corpus docId universe (for NOT complement); may be empty, in
     *                  which case NOT degrades to "no complement available" and yields no docs
     *                  for a bare NOT (a binary {@code A NOT B} still excludes correctly).
     */
    public BooleanEvaluator(Map<String, WorkerSearch.TermPostingsResult> termData,
            Set<Long> universe) {
        this.termData = termData;
        this.universe = universe;
    }

    /** Evaluate the whole query; returns the matching docId set (order not significant). */
    public Set<Long> evaluate(BooleanQuery query) {
        Result r = eval(query.root());
        return r == null ? Set.of() : r.docs;
    }

    private Result eval(BooleanQuery.Node n) {
        if (n == null) {
            return new Result(new LinkedHashSet<>(), Map.of());
        }
        switch (n.op) {
            case TERM:
                return evalTerm(n.term);
            case NOT:
                return evalNot(n);
            case AND:
                return evalAnd(n);
            case OR:
                return evalOr(n);
            case NEAR:
                return evalNear(n);
            default:
                return new Result(new LinkedHashSet<>(), Map.of());
        }
    }

    private Result evalTerm(String term) {
        WorkerSearch.TermPostingsResult r = termData.get(term);
        if (r == null) {
            return new Result(new LinkedHashSet<>(), Map.of()); // missing term -> no docs
        }
        Set<Long> docs = new LinkedHashSet<>(r.termFreqByDoc.keySet());
        return new Result(docs, r.positionsByDoc);
    }

    private Result evalNot(BooleanQuery.Node n) {
        Result child = eval(n.left);
        Set<Long> out = new LinkedHashSet<>(universe);
        out.removeAll(child.docs);
        return new Result(out, Map.of());
    }

    private Result evalAnd(BooleanQuery.Node n) {
        Result l = eval(n.left);
        Result r = eval(n.right);
        Set<Long> out = new LinkedHashSet<>(l.docs);
        out.retainAll(r.docs);
        return new Result(out, Map.of());
    }

    private Result evalOr(BooleanQuery.Node n) {
        Result l = eval(n.left);
        Result r = eval(n.right);
        Set<Long> out = new LinkedHashSet<>(l.docs);
        out.addAll(r.docs);
        return new Result(out, Map.of());
    }

    /**
     * NEAR/n: a document matches if some left-operand position and some right-operand position
     * are within {@code n} tokens. Uses the real merged token positions from the positional
     * index. When an operand carries no positions (it is a Boolean sub-result), proximity cannot
     * be computed for that side and the node degrades to document-level co-occurrence
     * (intersection), which is reported but never silently presented as a proximity match.
     */
    private Result evalNear(BooleanQuery.Node n) {
        Result l = eval(n.left);
        Result r = eval(n.right);
        int window = Math.max(1, n.window);

        // Candidate docs: those present on both sides.
        Set<Long> both = new LinkedHashSet<>(l.docs);
        both.retainAll(r.docs);

        Set<Long> out = new LinkedHashSet<>();
        Map<Long, int[]> matchedPositions = new HashMap<>();
        for (long docId : both) {
            int[] lp = l.positions.get(docId);
            int[] rp = r.positions.get(docId);
            if (lp == null || rp == null || lp.length == 0 || rp.length == 0) {
                // No real positions on at least one side: document-level co-occurrence only.
                out.add(docId);
                continue;
            }
            TreeSet<Integer> matched = withinWindow(lp, rp, window);
            if (!matched.isEmpty()) {
                out.add(docId);
                int[] arr = new int[matched.size()];
                int i = 0;
                for (int p : matched) {
                    arr[i++] = p;
                }
                matchedPositions.put(docId, arr);
            }
        }
        return new Result(out, matchedPositions);
    }

    /**
     * Two-pointer scan over two sorted position arrays; returns the union of all positions (from
     * both sides) that participate in at least one pair with {@code |lp - rp| <= window}.
     */
    private static TreeSet<Integer> withinWindow(int[] a, int[] b, int window) {
        TreeSet<Integer> matched = new TreeSet<>();
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            int diff = a[i] - b[j];
            if (Math.abs(diff) <= window) {
                matched.add(a[i]);
                matched.add(b[j]);
                // Advance the smaller to find further pairs.
                if (a[i] <= b[j]) {
                    i++;
                } else {
                    j++;
                }
            } else if (diff < 0) {
                i++;
            } else {
                j++;
            }
        }
        return matched;
    }

    static Set<Long> toSet(Collection<Long> c) {
        return new LinkedHashSet<>(c);
    }

    static List<Long> toList(Collection<Long> c) {
        return new ArrayList<>(c);
    }
}
