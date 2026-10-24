package com.group9.websearch.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Benchmark relevance judgements (qrels): {@code queryId -> (benchmarkDocId -> grade)}.
 *
 * <p>Parses the standard BEIR qrels TSV format: {@code query-id<TAB>corpus-id<TAB>score} with a
 * header line. The score is an integer relevance grade (TREC-COVID uses 0/1/2). Document IDs
 * here are the <em>benchmark</em> (BEIR) ids as strings; they are mapped to our index docIds
 * separately (see {@link DocIdResolver}) -- this class does no mapping and never fabricates
 * labels. Malformed lines are collected as problems and skipped, not guessed.
 */
public final class RelevanceJudgments {

    private static final Logger LOG = LoggerFactory.getLogger(RelevanceJudgments.class);

    private final Map<String, Map<String, Integer>> byQuery;
    private final List<String> problems;

    private RelevanceJudgments(Map<String, Map<String, Integer>> byQuery, List<String> problems) {
        this.byQuery = byQuery;
        this.problems = problems;
    }

    /** Grades for a query ({@code benchmarkDocId -> grade}); empty map if the query is unjudged. */
    public Map<String, Integer> gradesFor(String queryId) {
        return byQuery.getOrDefault(queryId, Collections.emptyMap());
    }

    /** All query IDs that have at least one judgement. */
    public Set<String> judgedQueryIds() {
        return Collections.unmodifiableSet(byQuery.keySet());
    }

    public boolean hasJudgements(String queryId) {
        Map<String, Integer> g = byQuery.get(queryId);
        return g != null && !g.isEmpty();
    }

    /** Total number of (query, doc) judgement rows. */
    public long totalJudgements() {
        long n = 0;
        for (Map<String, Integer> m : byQuery.values()) {
            n += m.size();
        }
        return n;
    }

    /** Every distinct benchmark docId referenced anywhere in the qrels. */
    public Set<String> allJudgedDocIds() {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (Map<String, Integer> m : byQuery.values()) {
            ids.addAll(m.keySet());
        }
        return ids;
    }

    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }

    /**
     * Load BEIR-style qrels TSV. A header line (first line that is not {@code qid<TAB>docid...}
     * numeric) is tolerated and skipped. Columns: queryId, docId, grade.
     */
    public static RelevanceJudgments loadTsv(Path qrelsPath) throws IOException {
        if (!Files.isRegularFile(qrelsPath)) {
            throw new IOException("qrels file not found: " + qrelsPath);
        }
        Map<String, Map<String, Integer>> byQuery = new HashMap<>();
        List<String> problems = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(qrelsPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length < 3) {
                    // Some qrels use spaces; try whitespace split as a fallback.
                    cols = line.trim().split("\\s+");
                }
                if (cols.length < 3) {
                    problems.add("qrels:" + lineNo + " expected 3 columns, got " + cols.length);
                    continue;
                }
                String qid = cols[0].trim();
                String docId = cols[1].trim();
                String gradeStr = cols[2].trim();
                if (lineNo == 1 && isHeader(qid, gradeStr)) {
                    continue; // skip the "query-id corpus-id score" header
                }
                final int grade;
                try {
                    grade = Integer.parseInt(gradeStr);
                } catch (NumberFormatException e) {
                    problems.add("qrels:" + lineNo + " non-integer grade '" + gradeStr + "'");
                    continue;
                }
                byQuery.computeIfAbsent(qid, q -> new HashMap<>()).put(docId, grade);
            }
        }
        LOG.info("Loaded qrels: {} queries, {} judgements, {} problem line(s) from {}",
                byQuery.size(), countJudgements(byQuery), problems.size(), qrelsPath);
        return new RelevanceJudgments(byQuery, problems);
    }

    private static boolean isHeader(String qid, String gradeStr) {
        // Header when the grade column is not an integer (e.g. the literal "score").
        if (qid.equalsIgnoreCase("query-id") || qid.equalsIgnoreCase("qid")) {
            return true;
        }
        try {
            Integer.parseInt(gradeStr);
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static long countJudgements(Map<String, Map<String, Integer>> byQuery) {
        long n = 0;
        for (Map<String, Integer> m : byQuery.values()) {
            n += m.size();
        }
        return n;
    }
}
