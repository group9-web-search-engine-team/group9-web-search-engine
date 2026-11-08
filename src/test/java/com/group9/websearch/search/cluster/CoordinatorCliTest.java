package com.group9.websearch.search.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.ExportedDocument;
import com.group9.websearch.index.HitType;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import com.group9.websearch.search.CorpusStatistics;
import com.group9.websearch.search.CorpusStatsArtifact;
import com.group9.websearch.search.InvertedIndex;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
 * Focused tests for the distributed coordinator entry point: cluster-config loading,
 * stats-artifact loading, startup initialisation against independent worker HTTP services, and
 * an end-to-end distributed query. Reuses the {@link WorkerServer} + {@link HttpWorkerClient}
 * integration infrastructure (local ephemeral ports, no AWS).
 */
class CoordinatorCliTest {

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

    private int serve(String id, List<String> partitions, InvertedIndex idx, String version)
            throws IOException {
        WorkerServer server = new WorkerServer(
                new LocalWorkerSearch(id, idx), partitions, version, idx.termCount());
        int port = server.start("127.0.0.1", 0, 2);
        started.add(server);
        return port;
    }

    /** Bring up five local workers (one term per partition) and return their ports P1..P5. */
    private int[] fiveWorkers(Path dir) throws IOException {
        Map<String, int[][]> p1 = Map.of("apple", new int[][] {{1, 0, 3}, {2, 0}});
        Map<String, int[][]> p2 = Map.of("item", new int[][] {{2, 1}});
        Map<String, int[][]> p3 = Map.of("ninja", new int[][] {{2, 2}, {3, 0, 1}});
        Map<String, int[][]> p4 = Map.of("tiger", new int[][] {{1, 1}});
        Map<String, int[][]> p5 = Map.of("42", new int[][] {{3, 2}});
        // Publish global stats from the full (all-terms) index.
        Map<String, int[][]> all = new LinkedHashMap<>();
        all.putAll(p1);
        all.putAll(p2);
        all.putAll(p3);
        all.putAll(p4);
        all.putAll(p5);
        indexWith(dir, "full", all);
        // Each worker gets a DIFFERENT version hash on purpose (different content) to prove the
        // coordinator does NOT reject a healthy cluster just because versions differ.
        int[] ports = new int[5];
        ports[0] = serve("P1", List.of("P1"), indexWith(dir, "p1", p1), "vP1");
        ports[1] = serve("P2", List.of("P2"), indexWith(dir, "p2", p2), "vP2");
        ports[2] = serve("P3", List.of("P3"), indexWith(dir, "p3", p3), "vP3");
        ports[3] = serve("P4", List.of("P4"), indexWith(dir, "p4", p4), "vP4");
        ports[4] = serve("P5", List.of("P5"), indexWith(dir, "p5", p5), "vP5");
        return ports;
    }

    private Path writeClusterConfig(Path dir, int[] ports) throws IOException {
        String props = "worker.P1 = 127.0.0.1:" + ports[0] + "\n"
                + "worker.P2 = 127.0.0.1:" + ports[1] + "\n"
                + "worker.P3 = 127.0.0.1:" + ports[2] + "\n"
                + "worker.P4 = 127.0.0.1:" + ports[3] + "\n"
                + "worker.P5 = 127.0.0.1:" + ports[4] + "\n"
                + "query.timeoutMillis = 4000\n";
        Path f = dir.resolve("cluster.properties");
        Files.writeString(f, props, StandardCharsets.UTF_8);
        return f;
    }

    private Path writeStats(Path dir) throws IOException {
        // Matches the fiveWorkers corpus: docs 1,2,3 with the given indexed token lengths.
        //  doc1: apple(2) + tiger(1) = 3 ; doc2: apple(1)+item(1)+ninja(1)=3 ; doc3: ninja(2)+42(1)=3
        CorpusStatsArtifact art = new CorpusStatsArtifact();
        art.complete = true;
        art.numDocs = 3;
        art.numTerms = 5;
        art.totalTokens = 9;
        art.avgDocLength = 3.0;
        art.minDocLength = 3;
        art.maxDocLength = 3;
        art.medianDocLength = 3.0;
        art.indexPath = "test";
        art.docLengths = new TreeMap<>();
        art.docLengths.put("1", 3L);
        art.docLengths.put("2", 3L);
        art.docLengths.put("3", 3L);
        Path f = dir.resolve("corpus-stats.json");
        Files.writeString(f, art.toJson(), StandardCharsets.UTF_8);
        return f;
    }

    /** Write an ExportedDocument JSONL metadata export for docs 1,2,3 (same ids as the corpus). */
    private Path writeMeta(Path dir) throws IOException {
        List<String> lines = List.of(
                new ExportedDocument(1L, "beir:doc-one", "Apple and Tiger Study",
                        "an apple a day keeps the tiger away in this study", "").toJsonLine(),
                new ExportedDocument(2L, "beir:doc-two", "Item and Ninja Report",
                        "the item was retrieved by a ninja apple vendor", "").toJsonLine(),
                new ExportedDocument(3L, "beir:doc-three", "Ninja Numbers 42",
                        "ninja ninja counting to 42 in the dojo", "").toJsonLine());
        Path f = dir.resolve("documents.jsonl");
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void clusterConfigLoadsAllFivePartitions(@TempDir Path dir) throws IOException {
        int[] ports = {9301, 9302, 9303, 9304, 9305};
        Path cfg = writeClusterConfig(dir, ports);
        ClusterConfig cc = ClusterConfig.load(cfg);
        assertEquals("127.0.0.1:9301", cc.addressForPartition(0));
        assertEquals("127.0.0.1:9305", cc.addressForPartition(4));
        assertEquals(4000, cc.queryTimeoutMillis());
        // Term routing resolves to the right worker address.
        assertEquals("127.0.0.1:9301", cc.addressForTerm("apple")); // P1
        assertEquals("127.0.0.1:9305", cc.addressForTerm("42"));     // P5
    }

    @Test
    void statsArtifactLoadsAsGlobalCorpusStatistics(@TempDir Path dir) throws IOException {
        Path stats = writeStats(dir);
        CorpusStatistics s = CorpusStatistics.fromArtifact(CorpusStatsArtifact.load(stats));
        assertEquals(3, s.numDocs());
        assertEquals(3.0, s.avgDocLength(), 1e-9);
        assertTrue(s.isComplete());
    }

    @Test
    void endToEndInlineQueryRanksAcrossAllFivePartitions(@TempDir Path dir) throws IOException {
        int[] ports = fiveWorkers(dir);
        Path cfg = writeClusterConfig(dir, ports);
        Path stats = writeStats(dir);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        int code = CoordinatorCli.run(new String[] {
                "--config", cfg.toString(),
                "--stats", stats.toString(),
                "--topk", "10",
                "--query", "apple ninja tiger 42 item"
        }, out, err);

        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, "coordinator should exit 0; stderr=" + errBuf);
        // Health gate passed for all five, versions differ but cluster is accepted.
        assertTrue(stdout.contains("5/5 partitions healthy."), stdout);
        // Ranked docIds printed with scores; all three docs matched across partitions.
        assertTrue(stdout.contains("[doc 1]"), stdout);
        assertTrue(stdout.contains("[doc 2]"), stdout);
        assertTrue(stdout.contains("[doc 3]"), stdout);
        // No failed partitions for a fully-healthy query.
        assertTrue(stdout.contains("failedPartitions=[]"), stdout);
    }

    @Test
    void resultsShowTitleUrlAndSnippetWhenMetadataProvided(@TempDir Path dir) throws IOException {
        int[] ports = fiveWorkers(dir);
        Path cfg = writeClusterConfig(dir, ports);
        Path stats = writeStats(dir);
        Path meta = writeMeta(dir);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        int code = CoordinatorCli.run(new String[] {
                "--config", cfg.toString(),
                "--stats", stats.toString(),
                "--meta", meta.toString(),
                "--query", "apple tiger"
        }, out, err);

        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, stdout);
        assertTrue(stdout.contains("Loaded document metadata: 3 doc(s)"), stdout);
        // doc1 (apple+tiger) and doc2 (apple) match; their titles/urls/snippets must render.
        assertTrue(stdout.contains("title: Apple and Tiger Study"), stdout);
        assertTrue(stdout.contains("url:   beir:doc-one"), stdout);
        assertTrue(stdout.contains("title: Item and Ninja Report"), stdout);
        assertTrue(stdout.contains("url:   beir:doc-two"), stdout);
        // Snippet should include a matched query term from the body.
        assertTrue(stdout.toLowerCase(java.util.Locale.ROOT).contains("apple"), stdout);
    }

    @Test
    void gracefulFallbackToDocIdAndScoreWhenMetadataMissing(@TempDir Path dir) throws IOException {
        int[] ports = fiveWorkers(dir);
        Path cfg = writeClusterConfig(dir, ports);
        Path stats = writeStats(dir);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        // No --meta, and a bad --meta path should both fall back without breaking retrieval.
        int code = CoordinatorCli.run(new String[] {
                "--config", cfg.toString(),
                "--stats", stats.toString(),
                "--meta", dir.resolve("does-not-exist.jsonl").toString(),
                "--query", "apple"
        }, out, err);

        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, stdout);
        assertTrue(stdout.contains("[doc 1]"), stdout);   // still ranks and shows docId + score
        assertFalse(stdout.contains("title:"), stdout);   // no metadata rendered
    }

    @Test
    void unhealthyPartitionReportedAsFailedButQueryStillRuns(@TempDir Path dir)
            throws IOException {
        int[] ports = fiveWorkers(dir);
        // Point P3 at a dead port so a term routed to P3 fails, but the query still returns.
        ports[2] = 1; // unused/closed
        Path cfg = writeClusterConfig(dir, ports);
        Path stats = writeStats(dir);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        // "ninja" routes to P3 (dead); "apple" routes to P1 (healthy).
        int code = CoordinatorCli.run(new String[] {
                "--config", cfg.toString(),
                "--stats", stats.toString(),
                "--connect-timeout", "400",
                "--query", "apple ninja"
        }, out, err);

        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, stdout);
        assertTrue(stdout.contains("P3"), "P3 should be reported unhealthy/failed: " + stdout);
        assertTrue(stdout.contains("[doc 1]"), "healthy P1 term still served: " + stdout);
    }
}
