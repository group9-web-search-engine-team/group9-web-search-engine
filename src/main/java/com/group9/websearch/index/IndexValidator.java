package com.group9.websearch.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates a produced SPIMI index directory (on HDFS or any Hadoop-supported filesystem).
 *
 * <p>Checks performed:
 * <ul>
 *   <li>{@code _SUCCESS} marker present (job completed).</li>
 *   <li>Every {@code part-*} record decodes via {@link IndexRecordCodec} (well-formed term +
 *       postings).</li>
 *   <li>No duplicate {@code (term, docId)} pair exists anywhere in the index -- within a term
 *       this would indicate a reducer merge bug; across parts it would indicate a partitioning
 *       bug (a term appearing in more than one reducer output).</li>
 *   <li>Within each term, docIds are strictly increasing (the documented ordering invariant).</li>
 *   <li>Each posting is position-aware: it has at least one field hit, and within every field
 *       positions are present, sorted, unique and consistent with the field term frequency.</li>
 *   <li>Explicit format identification: a legacy frequency-only record is flagged rather than
 *       silently mis-read as positional.</li>
 *   <li>Counts terms and total postings for a reportable summary.</li>
 * </ul>
 *
 * <p>Because partitioning is by term, a given term must appear in exactly one part file; the
 * validator enforces this by tracking every term seen globally.
 */
public final class IndexValidator {

    private static final Logger LOG = LoggerFactory.getLogger(IndexValidator.class);

    private IndexValidator() {
    }

    /** Outcome of validation: counts plus a list of human-readable problems (empty == valid). */
    public static final class Result {
        public boolean successMarker;
        public long termCount;
        public long postingCount;
        public long partFiles;
        public final List<String> problems = new ArrayList<>();

        public boolean valid() {
            return successMarker && problems.isEmpty();
        }

        @Override
        public String toString() {
            return "valid=" + valid() + " successMarker=" + successMarker
                    + " partFiles=" + partFiles + " terms=" + termCount
                    + " postings=" + postingCount + " problems=" + problems.size();
        }
    }

    public static Result validate(Configuration conf, String indexPath) throws IOException {
        Result r = new Result();
        Path dir = new Path(indexPath);
        FileSystem fs = dir.getFileSystem(conf);

        if (!fs.exists(dir)) {
            r.problems.add("index path does not exist: " + indexPath);
            return r;
        }
        r.successMarker = fs.exists(new Path(dir, "_SUCCESS"));
        if (!r.successMarker) {
            r.problems.add("_SUCCESS marker missing (job may not have completed)");
        }

        // Track every (term -> which part file) to detect cross-part term duplication, and a
        // per-term set is unnecessary because each term is read contiguously within one file;
        // we only need the global term set to catch a term split across parts.
        java.util.HashSet<String> seenTerms = new java.util.HashSet<>();

        FileStatus[] statuses = fs.listStatus(dir);
        for (FileStatus st : statuses) {
            String name = st.getPath().getName();
            if (!name.startsWith("part-")) {
                continue;
            }
            r.partFiles++;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(fs.open(st.getPath()), StandardCharsets.UTF_8))) {
                String line;
                int lineNo = 0;
                while ((line = br.readLine()) != null) {
                    lineNo++;
                    if (line.isEmpty()) {
                        continue;
                    }
                    // Explicit format identification: the positional format requires every
                    // posting after the docId to carry a field tag + positions ('docId:<tag>..').
                    // A legacy frequency-only record ('docId:tf') has no field tag; flag it
                    // rather than silently mis-reading it as positional.
                    if (looksLikeFrequencyOnly(line)) {
                        r.problems.add(name + ":" + lineNo
                                + " record looks like the legacy frequency-only format "
                                + "(expected position-aware postings)");
                    }
                    final IndexRecordCodec.DecodedRecord rec;
                    try {
                        rec = IndexRecordCodec.decode(line);
                    } catch (RuntimeException e) {
                        r.problems.add(name + ":" + lineNo + " undecodable record: " + e.getMessage());
                        continue;
                    }
                    if (!seenTerms.add(rec.term())) {
                        r.problems.add("term appears in more than one part file (partition bug): "
                                + rec.term());
                    }
                    r.termCount++;
                    long prevDoc = Long.MIN_VALUE;
                    for (Posting p : rec.postings().postings()) {
                        if (p.docId() <= prevDoc) {
                            r.problems.add(name + ":" + lineNo
                                    + " non-increasing/duplicate docId in term '" + rec.term()
                                    + "' at docId=" + p.docId());
                        }
                        prevDoc = p.docId();
                        r.postingCount++;
                        validatePositions(r, name, lineNo, rec.term(), p);
                    }
                }
            }
        }
        if (r.partFiles == 0) {
            r.problems.add("no part-* files found in index directory");
        }
        LOG.info("Index validation: {}", r);
        return r;
    }

    /**
     * Positional structural invariants for one posting: it must carry at least one field hit,
     * and within every field the positions must be present, sorted ascending, unique, and the
     * field's term frequency must equal the number of positions.
     */
    private static void validatePositions(Result r, String part, int lineNo, String term, Posting p) {
        java.util.List<HitType> fields = p.fields();
        if (fields.isEmpty()) {
            r.problems.add(part + ":" + lineNo + " posting with no field hits for term '"
                    + term + "' docId=" + p.docId() + " (not position-aware)");
            return;
        }
        for (HitType field : fields) {
            java.util.List<Integer> pos = p.positions(field);
            if (pos.isEmpty()) {
                r.problems.add(part + ":" + lineNo + " empty position list for term '" + term
                        + "' docId=" + p.docId() + " field=" + field);
                continue;
            }
            int prev = Integer.MIN_VALUE;
            for (int x : pos) {
                if (x < 0) {
                    r.problems.add(part + ":" + lineNo + " negative position for term '" + term
                            + "' docId=" + p.docId() + " field=" + field);
                }
                if (x <= prev) {
                    r.problems.add(part + ":" + lineNo + " non-increasing/duplicate position for term '"
                            + term + "' docId=" + p.docId() + " field=" + field + " at " + x);
                }
                prev = x;
            }
            if (p.termFrequency(field) != pos.size()) {
                r.problems.add(part + ":" + lineNo + " tf != position count for term '" + term
                        + "' docId=" + p.docId() + " field=" + field);
            }
        }
    }

    /**
     * Heuristic format identification. A positional posting body is {@code docId:<tag>pos-...}
     * where the character after the first {@code ':'} is a non-digit field tag. A legacy
     * frequency-only posting body is {@code docId:tf} where that character is a digit. This
     * inspects the first posting of the record's postings field.
     */
    static boolean looksLikeFrequencyOnly(String line) {
        int tab = line.indexOf('\t');
        if (tab < 0 || tab + 1 >= line.length()) {
            return false;
        }
        String postings = line.substring(tab + 1);
        int colon = postings.indexOf(':');
        if (colon < 0 || colon + 1 >= postings.length()) {
            return false;
        }
        char afterColon = postings.charAt(colon + 1);
        // Positional format always has a letter field tag right after the colon; a digit there
        // means the value is a bare term frequency (legacy format).
        return Character.isDigit(afterColon);
    }
}
