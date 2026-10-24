package com.group9.websearch.search;

import com.group9.websearch.index.Posting;
import com.group9.websearch.index.PostingList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Corpus-level statistics required by BM25.
 *
 * <p>Two sources, same meaning:
 * <ol>
 *   <li>{@link #fromIndex(InvertedIndex)} -- derive N, per-doc dl(d), and Lave by a single pass
 *       over the already-loaded positional index (no re-crawl / re-download / reindex).</li>
 *   <li>{@link #fromArtifact(CorpusStatsArtifact)} -- load the completed, published statistics
 *       artifact produced by the standalone calculator after indexing finishes. BM25 prefers
 *       this so it uses the authoritative full-corpus numbers rather than whatever subset the
 *       online process happens to have loaded.</li>
 * </ol>
 *
 * <p>How each statistic is obtained:
 * <ul>
 *   <li><b>N</b> = number of <em>distinct</em> docIds across all postings (dedup-safe).</li>
 *   <li><b>df(t)</b> = {@link PostingList#documentFrequency()} (distinct docIds in the term's
 *       postings) -- read directly and exactly from postings at scoring time.</li>
 *   <li><b>tf(t,d)</b> = {@link Posting#totalTermFrequency()} (count of stored positions across
 *       fields) -- read directly from postings.</li>
 *   <li><b>dl(d)</b> = sum over terms of tf(t,d) = total indexed tokens in d.</li>
 *   <li><b>Lave / avgdl</b> = (sum of dl) / N.</li>
 * </ul>
 *
 * <p>dl(d) uses the exact indexed-token rule (one stored position per indexed token, across the
 * indexed fields), so it is consistent with tf(t,d). It is not the raw source word count, which
 * the index does not retain -- documented, consistent definition.
 */
public final class CorpusStatistics {

    private static final Logger LOG = LoggerFactory.getLogger(CorpusStatistics.class);

    private final int numDocs;
    private final double avgDocLength;
    private final Map<Long, Long> docLengths;
    private final boolean complete;
    private final String source;

    private CorpusStatistics(int numDocs, double avgDocLength, Map<Long, Long> docLengths,
            boolean complete, String source) {
        this.numDocs = numDocs;
        this.avgDocLength = avgDocLength;
        this.docLengths = docLengths;
        this.complete = complete;
        this.source = source;
    }

    /** N: number of distinct indexed documents. */
    public int numDocs() {
        return numDocs;
    }

    /** avgdl (Lave): average indexed document length (0.0 for an empty index). */
    public double avgDocLength() {
        return avgDocLength;
    }

    /** dl(d): indexed length of a document; 0 if the doc is unknown to the statistics. */
    public long docLength(long docId) {
        return docLengths.getOrDefault(docId, 0L);
    }

    /** Whether these statistics came from a source considered complete. */
    public boolean isComplete() {
        return complete;
    }

    /** Human-readable provenance ("index" or "artifact:<path>"). */
    public String source() {
        return source;
    }

    /** Immutable view of per-document lengths (used by the calculator to build an artifact). */
    public Map<Long, Long> docLengths() {
        return Collections.unmodifiableMap(docLengths);
    }

    /**
     * Build the statistics by a single aggregation pass over the already-loaded index. Each
     * posting contributes its total term frequency to its document's running length. Documents
     * are keyed by docId, so a docId appearing in many terms' postings is counted once (no
     * double counting across terms, blocks, or Hadoop partitions -- the index is already the
     * merged, deduplicated result).
     */
    public static CorpusStatistics fromIndex(InvertedIndex index) {
        Map<Long, Long> lengths = new HashMap<>();
        for (String term : index.terms()) {
            PostingList pl = index.postings(term);
            if (pl == null) {
                continue;
            }
            for (Posting p : pl.postings()) {
                lengths.merge(p.docId(), (long) p.totalTermFrequency(), Long::sum);
            }
        }
        int n = lengths.size();
        long totalTokens = 0L;
        for (long len : lengths.values()) {
            totalTokens += len;
        }
        double avg = n == 0 ? 0.0 : (double) totalTokens / n;
        LOG.info("BM25 corpus stats (from index): N={} totalIndexedTokens={} avgdl={}",
                n, totalTokens, avg);
        return new CorpusStatistics(n, avg, lengths, true, "index");
    }

    /**
     * Load statistics from a published {@link CorpusStatsArtifact}. The artifact must be marked
     * complete; an incomplete artifact is rejected so BM25 never silently uses partial numbers.
     */
    public static CorpusStatistics fromArtifact(CorpusStatsArtifact art) {
        if (art == null) {
            throw new IllegalArgumentException("null statistics artifact");
        }
        if (!art.complete) {
            throw new IllegalStateException(
                    "statistics artifact is marked incomplete; refusing to use it for BM25");
        }
        Map<Long, Long> lengths = new HashMap<>();
        if (art.docLengths != null) {
            for (Map.Entry<String, Long> e : art.docLengths.entrySet()) {
                lengths.put(Long.parseLong(e.getKey()), e.getValue());
            }
        }
        LOG.info("BM25 corpus stats (from artifact): N={} avgdl={} complete={}",
                art.numDocs, art.avgDocLength, art.complete);
        return new CorpusStatistics(art.numDocs, art.avgDocLength, lengths, art.complete,
                "artifact:" + art.indexPath);
    }

    /** Minimum indexed document length (0 if no documents). */
    public long minDocLength() {
        long min = Long.MAX_VALUE;
        for (long v : docLengths.values()) {
            min = Math.min(min, v);
        }
        return docLengths.isEmpty() ? 0L : min;
    }

    /** Maximum indexed document length (0 if no documents). */
    public long maxDocLength() {
        long max = 0L;
        for (long v : docLengths.values()) {
            max = Math.max(max, v);
        }
        return max;
    }

    /** Median indexed document length (0.0 if no documents). */
    public double medianDocLength() {
        if (docLengths.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(docLengths.values());
        Collections.sort(sorted);
        int m = sorted.size();
        if (m % 2 == 1) {
            return sorted.get(m / 2);
        }
        return (sorted.get(m / 2 - 1) + sorted.get(m / 2)) / 2.0;
    }

    public long totalTokens() {
        long total = 0L;
        for (long v : docLengths.values()) {
            total += v;
        }
        return total;
    }
}
