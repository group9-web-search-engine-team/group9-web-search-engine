package com.group9.websearch.index.mapreduce;

import com.group9.websearch.index.ExportedDocument;
import com.group9.websearch.index.HitType;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.SpimiBlock;
import com.group9.websearch.index.Tokenizer;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPIMI mapper. Input is one JSONL {@link ExportedDocument} per line (via TextInputFormat's
 * LineRecordReader, which handles HDFS split boundaries correctly). The mapper builds an
 * in-memory SPIMI block one document at a time; when the block reaches its entry threshold it
 * is flushed and its partial postings are emitted into the shuffle, keyed by term. Any
 * remaining entries are flushed in {@link #cleanup}.
 *
 * <p>Each document is indexed positionally, field by field: title, url and body are tokenized
 * independently (reusing {@link Tokenizer}) and their zero-based token positions recorded under
 * the matching {@link HitType}. Anchor and metadata hit types are modelled but not populated
 * because the current {@link ExportedDocument} input does not carry that text (documented
 * limitation).
 *
 * <p>Output per flushed block: {@code (Text term, Text "<posting>,<posting>,...")} where each
 * posting is this block's positional contribution to that term for one document (see
 * {@link com.group9.websearch.index.Posting}). Partitioning is by term, so all partial postings
 * for a term reach the same reducer, which merges them (union of per-field position sets). This
 * design emits only through the normal Mapper context, so it is fully task-attempt-safe (no
 * unmanaged side files that could be lost/duplicated on retry or speculative execution).
 *
 * <p>Note: SPIMI block flushing is distinct from Hadoop input splitting. A split decides which
 * documents a mapper reads; a block flush bounds the mapper's in-memory dictionary. A document
 * is never dropped by a flush -- its postings are emitted before the dictionary is cleared.
 */
public class SpimiIndexMapper extends Mapper<LongWritable, Text, Text, Text> {

    private static final Logger LOG = LoggerFactory.getLogger(SpimiIndexMapper.class);

    /** Config key: approximate SPIMI block entry budget (distinct terms + postings). */
    public static final String SPIMI_MAX_ENTRIES = "index.spimi.maxEntries";
    public static final long DEFAULT_MAX_ENTRIES = 1_000_000L;

    private SpimiBlock block;
    private long maxEntries;
    private final Text outKey = new Text();
    private final Text outVal = new Text();
    private long blocksFlushed;

    @Override
    protected void setup(Context context) {
        maxEntries = context.getConfiguration().getLong(SPIMI_MAX_ENTRIES, DEFAULT_MAX_ENTRIES);
        block = new SpimiBlock(maxEntries);
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String line = value.toString();
        if (line.isEmpty()) {
            return;
        }
        final ExportedDocument doc;
        try {
            doc = ExportedDocument.fromJsonLine(line);
        } catch (IllegalArgumentException e) {
            // Documented policy: count malformed records and continue (do not misparse).
            context.getCounter("spimi", "malformedRecords").increment(1);
            return;
        }
        context.getCounter("spimi", "documents").increment(1);

        long docId = doc.docId();
        // Index each available field independently and positionally. Positions are zero-based
        // within each field (the fields are tokenized separately, so positions do not leak
        // across fields). Fields the input does not supply (anchor, metadata) are skipped.
        int dl = 0;
        dl += indexField(docId, HitType.TITLE, doc.title());
        dl += indexField(docId, HitType.URL, doc.url());
        dl += indexField(docId, HitType.BODY, doc.body());

        // BM25 statistics collected cheaply during indexing (framework-aggregated counters).
        // Each JSONL line is exactly one document (LineRecordReader never splits a line), so
        // "documents" counts documents without cross-split double counting, and "indexedTokens"
        // sums dl(d) across the corpus. These give an in-job cross-check of N and total tokens;
        // the authoritative per-document dl(d) and distinct-N come from the post-indexing
        // StatsCalculator reading the final merged index. (Counters cannot track DISTINCT docIds
        // if the same docId appeared on multiple input lines; the calculator handles that.)
        context.getCounter("bm25stats", "indexedTokens").increment(dl);
        if (dl > 0) {
            context.getCounter("bm25stats", "documentsWithTokens").increment(1);
        }

        if (block.shouldFlush()) {
            flushBlock(context);
        }
    }

    /**
     * Tokenize one field positionally and add its occurrences to the current SPIMI block.
     * Returns the number of indexed tokens contributed by this field (its positions count),
     * so the caller can accumulate the document's indexed length dl(d).
     */
    private int indexField(long docId, HitType field, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Map<String, List<Integer>> positions = Tokenizer.positions(text);
        if (positions.isEmpty()) {
            return 0;
        }
        block.addFieldPositions(docId, field, positions);
        int tokens = 0;
        for (List<Integer> p : positions.values()) {
            tokens += p.size();
        }
        return tokens;
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        if (!block.isEmpty()) {
            flushBlock(context);
        }
        context.getCounter("spimi", "blocksFlushed").increment(blocksFlushed);
    }

    /**
     * Flush the current SPIMI block: emit each (term, this-block-postings) pair into the
     * shuffle, then clear the dictionary. We reuse {@link SpimiBlock#flushSorted} to a string
     * buffer to obtain the block's sorted records, then re-emit them as context pairs.
     */
    private void flushBlock(Context context) throws IOException, InterruptedException {
        StringWriter sw = new StringWriter();
        block.flushSorted(sw);
        String[] lines = sw.toString().split("\n");
        for (String rec : lines) {
            if (rec.isEmpty()) {
                continue;
            }
            IndexRecordCodec.DecodedRecord decoded = IndexRecordCodec.decode(rec);
            outKey.set(decoded.term());
            // Emit this block's partial postings for the term in the positional format; the
            // reducer merges partials for the same term across blocks/mappers.
            outVal.set(decoded.postings().serialize());
            context.write(outKey, outVal);
        }
        block.clear();
        blocksFlushed++;
    }
}
