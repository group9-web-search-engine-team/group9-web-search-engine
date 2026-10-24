package com.group9.websearch.index.mapreduce;

import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import java.io.IOException;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/**
 * SPIMI reducer: the final merge step of distributed SPIMI.
 *
 * <p>The shuffle delivers to this reducer every partial postings value produced by any mapper
 * for a single term (partitioning is by term, so a term is wholly owned by one reducer). Each
 * value is a positional postings fragment ({@code <posting>,<posting>,...}) contributed by one
 * flushed SPIMI block. The reducer aggregates them into a single {@link PostingList}: partial
 * postings for the same {@code (term, docId)} are merged by UNIONING their per-field position
 * sets (a document can appear in several blocks because of block flushes and multiple input
 * splits), so occurrences are neither lost nor double-counted and per-field positions stay
 * distinct and ordered. Postings end up ordered by docId.
 *
 * <p>Output is one escaping-safe line per term via {@link IndexRecordCodec}
 * ({@code <percent-encoded-term>\t<posting>,<posting>,...}), written with a {@link NullWritable}
 * key so the output file contains exactly the index record and nothing else. Because
 * partitioning is by term and each term is emitted exactly once, there are never duplicate
 * (term,docId) rows across the output.
 *
 * <p>Bounded-memory limitation (documented): this reducer holds one term's full postings list
 * in memory at a time. That is the standard SPIMI/inverted-index assumption -- a single term's
 * postings fit in memory even though the whole index does not. Extremely common terms on a web
 * corpus could stress this; a future refinement would stream postings using a secondary sort on
 * docId instead of buffering, but that is out of scope for this first implementation.
 */
public class SpimiIndexReducer extends Reducer<Text, Text, NullWritable, Text> {

    private final Text out = new Text();

    @Override
    protected void reduce(Text term, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {
        PostingList merged = new PostingList();
        for (Text v : values) {
            // Each value is this-block's partial positional postings for the term; merging
            // unions per-doc, per-field position sets.
            merged.merge(PostingList.parse(v.toString()));
        }
        if (merged.isEmpty()) {
            return;
        }
        context.getCounter("spimi", "terms").increment(1);
        context.getCounter("spimi", "postings").increment(merged.documentFrequency());
        out.set(IndexRecordCodec.encode(term.toString(), merged));
        context.write(NullWritable.get(), out);
    }
}
