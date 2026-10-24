package com.group9.websearch.index;

import com.group9.websearch.search.CorpusStatsArtifact;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone, read-only BM25 corpus-statistics calculator. It is run manually AFTER the crawler
 * + Hadoop indexing pipeline have produced the final merged index. It reads the index from any
 * Hadoop-supported filesystem (HDFS or local), computes the BM25 statistics from the ACTUAL
 * final corpus, and publishes a standalone metadata artifact. It never writes into, modifies, or
 * reindexes the index directory, and it is safe to rerun.
 *
 * <p>Statistics computed from the final merged index (which is already the deduplicated result
 * of all Hadoop partitions and SPIMI blocks, so no cross-partition/cross-block double counting
 * is possible here):
 * <ul>
 *   <li><b>N</b> -- distinct docIds observed across all postings.</li>
 *   <li><b>unique terms</b> -- one index record per term (partitioning is by term).</li>
 *   <li><b>df(t)</b> -- distinct docIds in each term's postings (written to a sidecar).</li>
 *   <li><b>dl(d)</b> -- per document, the sum of tf(t,d) over all terms (exact indexed-token
 *       length). A docId seen in multiple terms accumulates; it is counted once as a document.</li>
 *   <li><b>Lave</b> -- sum(dl) / N.</li>
 *   <li>min / max / median dl.</li>
 * </ul>
 *
 * <p>Completeness: the index is considered complete only if a {@code _SUCCESS} marker exists and
 * every record decoded. The published artifact records this flag; BM25 refuses to consume an
 * artifact that is not complete.
 */
public final class StatsCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(StatsCalculator.class);

    private final Configuration conf;

    public StatsCalculator(Configuration conf) {
        this.conf = conf;
    }

    /** Full result of a calculation: the artifact plus per-term df and any problems found. */
    public static final class Result {
        public final CorpusStatsArtifact artifact;
        public final Map<String, Integer> documentFrequencies; // term -> df(t)
        public final List<String> problems;

        Result(CorpusStatsArtifact artifact, Map<String, Integer> df, List<String> problems) {
            this.artifact = artifact;
            this.documentFrequencies = df;
            this.problems = problems;
        }

        public boolean complete() {
            return artifact.complete;
        }
    }

    /**
     * Compute statistics over the final merged index at {@code indexPath} (read-only). Does not
     * write anything; call {@link #publish} to persist the artifact + df sidecar.
     */
    public Result compute(String indexPath) throws IOException {
        List<String> problems = new ArrayList<>();
        Path dir = new Path(indexPath);
        FileSystem fs = dir.getFileSystem(conf);

        if (!fs.exists(dir)) {
            problems.add("index path does not exist: " + indexPath);
            CorpusStatsArtifact empty = baseArtifact(indexPath, false);
            return new Result(empty, Collections.emptyMap(), problems);
        }

        boolean successMarker = fs.exists(new Path(dir, "_SUCCESS"));
        if (!successMarker) {
            problems.add("_SUCCESS marker missing (indexing may be incomplete or in progress)");
        }

        Map<Long, Long> docLengths = new HashMap<>();
        Map<String, Integer> df = new TreeMap<>();
        long terms = 0;
        long malformed = 0;
        int partFiles = 0;

        FileStatus[] statuses = fs.listStatus(dir);
        for (FileStatus st : statuses) {
            String name = st.getPath().getName();
            if (!name.startsWith("part-")) {
                continue; // ignore _SUCCESS and any non-part files; never modify them
            }
            partFiles++;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(fs.open(st.getPath()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    final IndexRecordCodec.DecodedRecord rec;
                    try {
                        rec = IndexRecordCodec.decode(line);
                    } catch (RuntimeException e) {
                        malformed++;
                        continue;
                    }
                    terms++;
                    PostingList postings = rec.postings();
                    df.put(rec.term(), postings.documentFrequency());
                    for (Posting p : postings.postings()) {
                        // dl(d) accumulates tf(t,d) across terms; a docId is a key, so it is
                        // counted once as a document regardless of how many terms reference it.
                        docLengths.merge(p.docId(), (long) p.totalTermFrequency(), Long::sum);
                    }
                }
            }
        }
        if (partFiles == 0) {
            problems.add("no part-* files found in index directory");
        }
        if (malformed > 0) {
            problems.add(malformed + " malformed index record(s) skipped");
        }

        boolean complete = successMarker && malformed == 0 && partFiles > 0;

        CorpusStatsArtifact art = baseArtifact(indexPath, complete);
        art.numTerms = terms;
        int n = docLengths.size();
        art.numDocs = n;
        long totalTokens = 0L;
        for (long v : docLengths.values()) {
            totalTokens += v;
        }
        art.totalTokens = totalTokens;
        art.avgDocLength = n == 0 ? 0.0 : (double) totalTokens / n;
        fillLengthSpread(art, docLengths);
        TreeMap<String, Long> dlJson = new TreeMap<>();
        for (Map.Entry<Long, Long> e : docLengths.entrySet()) {
            dlJson.put(Long.toString(e.getKey()), e.getValue());
        }
        art.docLengths = dlJson;

        LOG.info("Stats computed: N={} terms={} totalTokens={} avgdl={} complete={} problems={}",
                art.numDocs, art.numTerms, art.totalTokens, art.avgDocLength, art.complete,
                problems.size());
        return new Result(art, df, problems);
    }

    private static void fillLengthSpread(CorpusStatsArtifact art, Map<Long, Long> docLengths) {
        if (docLengths.isEmpty()) {
            art.minDocLength = 0;
            art.maxDocLength = 0;
            art.medianDocLength = 0.0;
            return;
        }
        List<Long> sorted = new ArrayList<>(docLengths.values());
        Collections.sort(sorted);
        art.minDocLength = sorted.get(0);
        art.maxDocLength = sorted.get(sorted.size() - 1);
        int m = sorted.size();
        art.medianDocLength = (m % 2 == 1)
                ? sorted.get(m / 2)
                : (sorted.get(m / 2 - 1) + sorted.get(m / 2)) / 2.0;
    }

    private static CorpusStatsArtifact baseArtifact(String indexPath, boolean complete) {
        CorpusStatsArtifact art = new CorpusStatsArtifact();
        art.version = CorpusStatsArtifact.SCHEMA_VERSION;
        art.indexPath = indexPath;
        art.generatedAtEpochMs = System.currentTimeMillis();
        art.complete = complete;
        return art;
    }

    /**
     * Atomically publish the artifact JSON to {@code statsJsonPath} and the per-term df sidecar
     * (TSV: {@code term<TAB>df}) to {@code dfTsvPath}. Both are written via temp-then-rename so a
     * concurrent reader never sees a partial file. Neither path is inside the index directory.
     */
    public void publish(Result result, String statsJsonPath, String dfTsvPath) throws IOException {
        // Artifact JSON (local filesystem; atomic publish).
        result.artifact.publishAtomically(java.nio.file.Path.of(statsJsonPath));

        // df sidecar via Hadoop FS so it can also target HDFS if desired; temp + rename.
        if (dfTsvPath != null && !dfTsvPath.isBlank()) {
            Path target = new Path(dfTsvPath);
            FileSystem fs = target.getFileSystem(conf);
            Path parent = target.getParent();
            if (parent != null) {
                fs.mkdirs(parent);
            }
            Path tmp = new Path(dfTsvPath + ".tmp");
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(fs.create(tmp, true), StandardCharsets.UTF_8))) {
                for (Map.Entry<String, Integer> e : result.documentFrequencies.entrySet()) {
                    // term is percent-encoded already in the index; write it verbatim so the
                    // sidecar stays delimiter-safe (no raw TAB/newline in the encoded term).
                    w.write(IndexRecordCodec.encodeTerm(e.getKey()));
                    w.write('\t');
                    w.write(Integer.toString(e.getValue()));
                    w.write('\n');
                }
            }
            fs.delete(target, false);
            fs.rename(tmp, target);
        }
    }
}
