package com.group9.websearch.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.ExportedDocument;
import com.group9.websearch.index.HitType;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import com.group9.websearch.search.CorpusStatistics;
import com.group9.websearch.search.CorpusStatsArtifact;
import com.group9.websearch.search.InvertedIndex;
import com.group9.websearch.search.cluster.LocalWorkerSearch;
import com.group9.websearch.search.cluster.WorkerServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of DISTRIBUTED evaluation: it starts five real {@link WorkerServer}s on local
 * ephemeral ports (no AWS), points a cluster.properties at them, and runs
 * {@link EvaluationRunner#runDistributed()}. This proves evaluation goes through the production
 * coordinator->worker HTTP path and reuses the same global BM25 + the existing metrics/mapping.
 */
class DistributedEvaluationTest {

    private final List<WorkerServer> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        for (WorkerServer s : started) {
            s.stop();
        }
        started.clear();
    }

    private InvertedIndex indexWith(Path dir, String name, Map<String, int[][]> terms)
            throws IOException {
        Path d = Files.createDirectories(dir.resolve(name));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[][]> e : terms.entrySet()) {
            PostingList pl = new PostingList();
            for (int[] docPos : e.getValue()) {
                long docId = docPos[0];
                for (int i = 1; i < docPos.length; i++) {
                    pl.add(docId, HitType.BODY, docPos[i]);
                }
            }
            sb.append(IndexRecordCodec.encode(e.getKey(), pl)).append('\n');
        }
        Files.writeString(d.resolve("part-r-00000"), sb.toString(), StandardCharsets.UTF_8);
        Files.writeString(d.resolve("_SUCCESS"), "", StandardCharsets.UTF_8);
        return InvertedIndex.load(d);
    }

    private int serve(String id, InvertedIndex idx) throws IOException {
        WorkerServer server = new WorkerServer(new LocalWorkerSearch(id, idx), List.of(id), "v1",
                idx.termCount());
        int port = server.start("127.0.0.1", 0, 2);
        started.add(server);
        return port;
    }

    /** Build the full test corpus, start 5 workers, and return an EvalConfig wired to them. */
    private EvalConfig wireCluster(Path dir, boolean breakP3) throws IOException {
        // docs 1,2,3 ; terms: apple(P1) banana(P1) infection(P2) ninja(P3) treatment(P4)
        Map<String, int[][]> p1 = new LinkedHashMap<>();
        p1.put("apple", new int[][] {{1, 0}, {2, 0}});
        p1.put("banana", new int[][] {{3, 0}});
        Map<String, int[][]> p2 = new LinkedHashMap<>();
        p2.put("infection", new int[][] {{1, 1}, {2, 1}, {3, 1}});
        Map<String, int[][]> p3 = new LinkedHashMap<>();
        p3.put("ninja", new int[][] {{3, 2}});
        Map<String, int[][]> p4 = new LinkedHashMap<>();
        p4.put("treatment", new int[][] {{1, 2}});

        Map<String, int[][]> all = new LinkedHashMap<>();
        all.putAll(p1);
        all.putAll(p2);
        all.putAll(p3);
        all.putAll(p4);
        CorpusStatistics stats = CorpusStatistics.fromIndex(indexWith(dir, "full", all));

        InvertedIndex empty = indexWith(dir, "empty", new LinkedHashMap<>());
        int[] ports = new int[5];
        ports[0] = serve("P1", indexWith(dir, "p1", p1));
        ports[1] = serve("P2", indexWith(dir, "p2", p2));
        ports[2] = breakP3 ? 1 : serve("P3", indexWith(dir, "p3", p3)); // port 1 = unreachable
        ports[3] = serve("P4", indexWith(dir, "p4", p4));
        ports[4] = serve("P5", empty);

        // cluster.properties
        String props = "worker.P1 = 127.0.0.1:" + ports[0] + "\n"
                + "worker.P2 = 127.0.0.1:" + ports[1] + "\n"
                + "worker.P3 = 127.0.0.1:" + ports[2] + "\n"
                + "worker.P4 = 127.0.0.1:" + ports[3] + "\n"
                + "worker.P5 = 127.0.0.1:" + ports[4] + "\n"
                + "query.timeoutMillis = 4000\n";
        Path cfgFile = dir.resolve("cluster.properties");
        Files.writeString(cfgFile, props, StandardCharsets.UTF_8);

        // stats artifact (global corpus statistics)
        CorpusStatsArtifact art = new CorpusStatsArtifact();
        art.complete = true;
        art.numDocs = stats.numDocs();
        art.avgDocLength = stats.avgDocLength();
        art.docLengths = new TreeMap<>();
        for (Map.Entry<Long, Long> e : stats.docLengths().entrySet()) {
            art.docLengths.put(Long.toString(e.getKey()), e.getValue());
        }
        art.totalTokens = stats.totalTokens();
        Path statsFile = dir.resolve("corpus-stats.json");
        Files.writeString(statsFile, art.toJson(), StandardCharsets.UTF_8);

        // idmap: internal docId -> beir id (beirA/B/C)
        Path idmap = dir.resolve("idmap.tsv");
        Files.write(idmap, List.of("beirA\t1", "beirB\t2", "beirC\t3"), StandardCharsets.UTF_8);

        // metadata (optional)
        Path meta = dir.resolve("documents.jsonl");
        Files.write(meta, List.of(
                new ExportedDocument(1, "beir:beirA", "t1", "apple infection treatment", "").toJsonLine(),
                new ExportedDocument(2, "beir:beirB", "t2", "apple infection", "").toJsonLine(),
                new ExportedDocument(3, "beir:beirC", "t3", "banana infection ninja", "").toJsonLine()),
                StandardCharsets.UTF_8);

        // queries + qrels: query q1 "apple infection"; relevant = beirA(2), beirB(1); beirC judged non-rel(0)
        Path queries = dir.resolve("queries.jsonl");
        Files.write(queries, List.of("{\"_id\":\"q1\",\"text\":\"apple infection\"}"),
                StandardCharsets.UTF_8);
        Path qrels = dir.resolve("qrels.tsv");
        Files.write(qrels, List.of(
                "query-id\tcorpus-id\tscore",
                "q1\tbeirA\t2",
                "q1\tbeirB\t1",
                "q1\tbeirC\t0"), StandardCharsets.UTF_8);

        EvalConfig cfg = new EvalConfig();
        cfg.distributed = true;
        cfg.clusterConfig = cfgFile.toString();
        cfg.statsArtifact = statsFile.toString();
        cfg.idMapPath = idmap.toString();
        cfg.metadataJsonl = meta.toString();
        cfg.queriesPath = queries.toString();
        cfg.qrelsPath = qrels.toString();
        cfg.cutoffs = List.of(10);
        cfg.retrievalDepth = 100;
        cfg.jsonOut = dir.resolve("report.json").toString();
        cfg.csvOut = dir.resolve("per-query.csv").toString();
        return cfg;
    }

    @Test
    void distributedEvaluationRunsThroughCoordinatorAndScores(@TempDir Path dir) throws IOException {
        EvalConfig cfg = wireCluster(dir, false);
        EvaluationRunner.Outcome out = new EvaluationRunner(cfg).runDistributed();

        assertTrue(out.distributed);
        assertEquals(5, out.partitionsHealthy, "all five workers healthy");
        assertEquals(1, out.queriesEvaluated);
        assertEquals(0, out.queriesWithFailedPartitions);
        assertEquals(3, out.indexedDocCount);
        // beirA,beirB,beirC all judged and mapped -> coverage 1.0.
        assertEquals(1.0, out.benchmarkCoverage, 1e-9);

        // "apple infection" retrieves doc1,doc2 (apple) and doc1,doc2,doc3 (infection) -> {1,2,3}.
        // Judged: beirA(2),beirB(1) relevant, beirC(0) non-rel. All judged -> condensed = all 3.
        // So Recall@10 = 2 relevant retrieved / 2 total relevant = 1.0 for q1.
        AggregateReport r = out.reports.get(0);
        assertEquals(1.0, r.meanRecall(), 1e-9);
        assertTrue(r.meanPrecision() > 0.0);
    }

    @Test
    void failedPartitionIsReportedNotTreatedAsEmpty(@TempDir Path dir) throws IOException {
        // Break P3 (ninja). Query "apple infection" does NOT route to P3, so it should still
        // succeed with no failed partitions. Use a query that DOES hit P3 to force a partial.
        EvalConfig cfg = wireCluster(dir, true);
        // Replace the query with one that touches P3 (ninja -> P3 which is down).
        Files.write(Path.of(cfg.queriesPath),
                List.of("{\"_id\":\"q1\",\"text\":\"apple ninja\"}"), StandardCharsets.UTF_8);

        EvaluationRunner.Outcome out = new EvaluationRunner(cfg).runDistributed();
        assertEquals(4, out.partitionsHealthy, "P3 is down");
        assertEquals(1, out.queriesEvaluated, "query still evaluated (not aborted)");
        assertEquals(1, out.queriesWithFailedPartitions, "P3 failure recorded as partial");
        // An issue must explicitly flag the partial result (never silently empty).
        assertTrue(out.issues.stream().anyMatch(s -> s.contains("PARTIAL")
                && s.contains("P3")), "partial/failed partition must be reported: " + out.issues);
    }

    @Test
    void distributedModeRequiresStatsArtifact(@TempDir Path dir) throws IOException {
        EvalConfig cfg = wireCluster(dir, false);
        cfg.statsArtifact = null; // remove global stats
        try {
            new EvaluationRunner(cfg).runDistributed();
            org.junit.jupiter.api.Assertions.fail("expected IOException for missing --stats");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("requires --stats"));
        }
    }
}
