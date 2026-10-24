package com.group9.websearch.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * K-way merge of sorted SPIMI block runs into a single sorted term index.
 *
 * <p>Each input is a lexicographically term-sorted run (as produced by
 * {@link SpimiBlock#flushSorted}). The merge advances all runs in lock-step using a min-heap
 * over the current term of each run: the globally smallest term is taken from every run that
 * currently exposes it, their postings are merged (summing duplicate {@code (term, docId)}
 * frequencies), and one final record is emitted. Memory is bounded by the number of runs plus
 * the postings of a single term at a time -- the whole corpus is never held in memory.
 *
 * <p>This is the explicit SPIMI merge step; it is also exactly what the reducer performs over
 * the partial postings it receives for each term (see SpimiIndexReducer).
 */
public final class BlockMerger {

    private BlockMerger() {
    }

    /** A cursor over one sorted run, exposing its current (term, postings) line. */
    private static final class RunCursor {
        final BufferedReader reader;
        String term;
        PostingList postings;

        RunCursor(BufferedReader reader) throws IOException {
            this.reader = reader;
            advance();
        }

        boolean advance() throws IOException {
            String line = reader.readLine();
            while (line != null && line.isEmpty()) {
                line = reader.readLine();
            }
            if (line == null) {
                term = null;
                postings = null;
                return false;
            }
            IndexRecordCodec.DecodedRecord rec = IndexRecordCodec.decode(line);
            term = rec.term();
            postings = rec.postings();
            return true;
        }
    }

    /**
     * Merge the given sorted runs into {@code out}, writing one escaping-safe record per term
     * in lexicographic order with postings ordered by docId. Readers are advanced to EOF but
     * not closed (the caller owns them).
     */
    public static void merge(List<BufferedReader> runs, Writer out) throws IOException {
        PriorityQueue<RunCursor> heap = new PriorityQueue<>(
                Math.max(1, runs.size()), Comparator.comparing(c -> c.term));
        for (BufferedReader r : runs) {
            RunCursor c = new RunCursor(r);
            if (c.term != null) {
                heap.add(c);
            }
        }

        java.io.BufferedWriter bw = (out instanceof java.io.BufferedWriter b)
                ? b : new java.io.BufferedWriter(out);

        while (!heap.isEmpty()) {
            String smallest = heap.peek().term;
            PostingList merged = new PostingList();
            // Pull every run currently positioned on the smallest term.
            List<RunCursor> advanced = new ArrayList<>();
            while (!heap.isEmpty() && heap.peek().term.equals(smallest)) {
                RunCursor c = heap.poll();
                merged.merge(c.postings);
                advanced.add(c);
            }
            bw.write(IndexRecordCodec.encode(smallest, merged));
            bw.write('\n');
            // Advance those runs and re-insert the ones that still have data.
            for (RunCursor c : advanced) {
                if (c.advance()) {
                    heap.add(c);
                }
            }
        }
        bw.flush();
    }
}
