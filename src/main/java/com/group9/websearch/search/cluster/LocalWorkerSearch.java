package com.group9.websearch.search.cluster;

import com.group9.websearch.index.Posting;
import com.group9.websearch.index.PostingList;
import com.group9.websearch.search.InvertedIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process {@link WorkerSearch} backed by an {@link InvertedIndex} partition. Used for unit
 * tests and single-JVM distributed-logic verification without any network. A production RPC
 * worker would implement the same interface over the wire; the coordinator is agnostic.
 *
 * <p>It returns raw df(t) and tf(t,d) from the local postings (no BM25), exactly as the
 * global-scoring design requires. A term this partition does not own simply yields an empty
 * result; the coordinator never sends a worker a term outside its partition.
 */
public final class LocalWorkerSearch implements WorkerSearch {

    private final String id;
    private final InvertedIndex index;

    public LocalWorkerSearch(String id, InvertedIndex index) {
        this.id = id;
        this.index = index;
    }

    @Override
    public List<TermPostingsResult> lookup(List<String> terms) {
        List<TermPostingsResult> out = new ArrayList<>(terms.size());
        for (String term : terms) {
            PostingList pl = index.postings(term);
            if (pl == null || pl.isEmpty()) {
                out.add(TermPostingsResult.empty(term));
                continue;
            }
            Map<Long, Integer> tf = new HashMap<>();
            Map<Long, int[]> positions = new HashMap<>();
            for (Posting p : pl.postings()) {
                int t = p.totalTermFrequency();
                if (t > 0) {
                    tf.put(p.docId(), t);
                    positions.put(p.docId(), mergedPositions(p));
                }
            }
            out.add(new TermPostingsResult(term, pl.documentFrequency(), tf, positions));
        }
        return out;
    }

    /**
     * Merge a term's positions across ALL index fields (title/url/anchor/metadata/body) into one
     * sorted, de-duplicated token-position array representing the document's token stream. NEAR/n
     * proximity uses these real positions.
     */
    private static int[] mergedPositions(Posting p) {
        java.util.TreeSet<Integer> all = new java.util.TreeSet<>();
        for (com.group9.websearch.index.HitType field : p.fields()) {
            all.addAll(p.positions(field));
        }
        int[] arr = new int[all.size()];
        int i = 0;
        for (int pos : all) {
            arr[i++] = pos;
        }
        return arr;
    }

    @Override
    public String id() {
        return id;
    }
}
