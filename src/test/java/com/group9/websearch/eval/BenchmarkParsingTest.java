package com.group9.websearch.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Parser tests for qrels, queries (jsonl + tsv), and the doc-id resolver, with small fixtures.
 */
class BenchmarkParsingTest {

    @Test
    void qrelsTsvWithHeaderParsed(@TempDir Path tmp) throws Exception {
        Path qrels = tmp.resolve("qrels.tsv");
        Files.write(qrels, List.of(
                "query-id\tcorpus-id\tscore",   // header skipped
                "1\tdocA\t2",
                "1\tdocB\t0",
                "2\tdocC\t1"), StandardCharsets.UTF_8);
        RelevanceJudgments j = RelevanceJudgments.loadTsv(qrels);
        assertEquals(2, j.judgedQueryIds().size());
        assertEquals(3, j.totalJudgements());
        assertEquals(2, (int) j.gradesFor("1").get("docA"));
        assertEquals(1, RetrievalMetrics.numRelevant(j.gradesFor("1"), 1)); // docA(2) rel, docB(0) not
        assertTrue(j.problems().isEmpty());
    }

    @Test
    void qrelsMalformedLineReported(@TempDir Path tmp) throws Exception {
        Path qrels = tmp.resolve("qrels.tsv");
        Files.write(qrels, List.of(
                "1\tdocA\t1",
                "bad-line-only-one-col",
                "2\tdocB\tnotanumber"), StandardCharsets.UTF_8);
        RelevanceJudgments j = RelevanceJudgments.loadTsv(qrels);
        assertFalse(j.problems().isEmpty());
        assertEquals(1, j.totalJudgements()); // only the valid row
    }

    @Test
    void queriesJsonlParsed(@TempDir Path tmp) throws Exception {
        Path q = tmp.resolve("queries.jsonl");
        Files.write(q, List.of(
                "{\"_id\": \"1\", \"text\": \"coronavirus origin\"}",
                "{\"_id\": \"2\", \"text\": \"vaccine efficacy\"}"), StandardCharsets.UTF_8);
        BenchmarkQueries bq = BenchmarkQueries.load(q);
        assertEquals(2, bq.size());
        assertEquals("coronavirus origin", bq.all().get("1"));
    }

    @Test
    void queriesTsvParsed(@TempDir Path tmp) throws Exception {
        Path q = tmp.resolve("queries.tsv");
        Files.write(q, List.of("1\tcoronavirus origin", "2\tvaccine efficacy"),
                StandardCharsets.UTF_8);
        BenchmarkQueries bq = BenchmarkQueries.load(q);
        assertEquals(2, bq.size());
        assertEquals("vaccine efficacy", bq.all().get("2"));
    }

    @Test
    void idResolverExplicitNumericMapping(@TempDir Path tmp) throws Exception {
        Path map = tmp.resolve("idmap.tsv");
        Files.write(map, List.of("docA\t100", "docB\t200"), StandardCharsets.UTF_8);
        DocIdResolver r = DocIdResolver.build(map, null);
        assertEquals(100L, r.toOurDocId("docA").orElseThrow());
        assertEquals("docB", r.toBenchmarkDocId(200L).orElseThrow());
        assertEquals(1.0, r.coverage(java.util.Set.of("docA", "docB")), 1e-9);
        assertEquals(0.5, r.coverage(java.util.Set.of("docA", "docZ")), 1e-9);
    }

    @Test
    void emptyResolverHasZeroCoverage() {
        DocIdResolver r = DocIdResolver.empty();
        assertTrue(r.toOurDocId("docA").isEmpty());
        assertEquals(0.0, r.coverage(java.util.Set.of("docA")), 1e-9);
    }
}
