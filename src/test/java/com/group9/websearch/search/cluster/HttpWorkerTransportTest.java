package com.group9.websearch.search.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.HitType;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import com.group9.websearch.index.TermPartitioner;
import com.group9.websearch.search.Bm25Params;
import com.group9.websearch.search.Bm25Ranker;
import com.group9.websearch.search.CorpusStatistics;
import com.group9.websearch.search.InvertedIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the REAL coordinator&lt;-&gt;worker HTTP transport. Each test starts one or
 * more {@link WorkerServer}s on {@code 127.0.0.1:0} (ephemeral port, no AWS), talks to them with
 * {@link HttpWorkerClient}, and drives the already-tested {@link DistributedCoordinator}. This
 * proves the production transport is a faithful, drop-in replacement for {@link LocalWorkerSearch}.
 */
class HttpWorkerTransportTest {

    private final List<WorkerServer> started = new java.util.ArrayList<>();

    @AfterEach
    void stopAll() {
        for (WorkerServer s : started) {
            s.stop();
        }
        started.clear();
    }

    private InvertedIndex indexWith(Path dir, String name,
            Map<String, int[][]> termToDocPositions) throws IOException {
        Path d = Files.createDirectories(dir.resolve(name));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[][]> e : termToDocPositions.entrySet()) {
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

    /** Start a worker HTTP server over the given index and return a client pointed at it. */
    private HttpWorkerClient serve(String id, List<String> partitions, InvertedIndex idx,
            String version) throws IOException {
        WorkerServer server = new WorkerServer(
                new LocalWorkerSearch(id, idx), partitions, version, idx.termCount());
        int port = server.start("127.0.0.1", 0, 2);
        started.add(server);
        return HttpWorkerClient.fromAddress(id, "127.0.0.1:" + port, 3000);
    }

    @Test
    void coordinatorOverHttpMatchesSingleNodeAndRoutesAllFivePartitions(@TempDir Path dir)
            throws IOException {
        // One term per logical partition: apple(P1) item(P2) ninja(P3) tiger(P4) 42(P5).
        Map<String, int[][]> p1 = Map.of("apple", new int[][] {{1, 0, 3}, {2, 0}});
        Map<String, int[][]> p2 = Map.of("item", new int[][] {{2, 1}});
        Map<String, int[][]> p3 = Map.of("ninja", new int[][] {{2, 2}, {3, 0, 1, 2}});
        Map<String, int[][]> p4 = Map.of("tiger", new int[][] {{1, 1}});
        Map<String, int[][]> p5 = Map.of("42", new int[][] {{3, 3}});

        // Sanity: each term routes to the expected partition.
        assertEquals(0, TermPartitioner.partitionFor("apple"));
        assertEquals(1, TermPartitioner.partitionFor("item"));
        assertEquals(2, TermPartitioner.partitionFor("ninja"));
        assertEquals(3, TermPartitioner.partitionFor("tiger"));
        assertEquals(4, TermPartitioner.partitionFor("42"));

        Map<String, int[][]> all = new LinkedHashMap<>();
        all.putAll(p1);
        all.putAll(p2);
        all.putAll(p3);
        all.putAll(p4);
        all.putAll(p5);
        InvertedIndex full = indexWith(dir, "full", all);
        CorpusStatistics stats = CorpusStatistics.fromIndex(full);

        String ver = "v1";
        WorkerSearch[] workers = new WorkerSearch[5];
        workers[0] = serve("P1", List.of("P1"), indexWith(dir, "p1", p1), ver);
        workers[1] = serve("P2", List.of("P2"), indexWith(dir, "p2", p2), ver);
        workers[2] = serve("P3", List.of("P3"), indexWith(dir, "p3", p3), ver);
        workers[3] = serve("P4", List.of("P4"), indexWith(dir, "p4", p4), ver);
        workers[4] = serve("P5", List.of("P5"), indexWith(dir, "p5", p5), ver);

        Bm25Params params = Bm25Params.defaults();
        DistributedCoordinator coord = new DistributedCoordinator(workers, stats, params, 5000);
        List<String> query = List.of("apple", "item", "ninja", "tiger", "42");
        DistributedCoordinator.Response resp = coord.search(query, 10);
        assertTrue(resp.failedPartitions.isEmpty(), "all five workers must respond");

        // Compare each hit to the single-node reference over the full index.
        Bm25Ranker ref = new Bm25Ranker(stats, params);
        Map<String, PostingList> termPostings = new LinkedHashMap<>();
        for (String t : query) {
            termPostings.put(t, full.postings(t));
        }
        for (DistributedCoordinator.Hit h : resp.hits) {
            double expected = ref.score(h.docId, query, termPostings).score;
            assertEquals(expected, h.score, 1e-9, "HTTP global BM25 mismatch for doc " + h.docId);
        }
    }

    @Test
    void workerFourServesBothP4AndP5FromOneIndex(@TempDir Path dir) throws IOException {
        // Worker 4 owns P4 (tiger) AND P5 (42) in a single merged index dir.
        Map<String, int[][]> p4p5 = new LinkedHashMap<>();
        p4p5.put("tiger", new int[][] {{1, 0}});   // P4
        p4p5.put("42", new int[][] {{2, 0}});       // P5
        InvertedIndex w4 = indexWith(dir, "w4", p4p5);
        CorpusStatistics stats = CorpusStatistics.fromIndex(w4);

        HttpWorkerClient c4 = serve("W4", List.of("P4", "P5"), w4, "v1");
        WorkerSearch empty = serve("E", List.of("P1"),
                indexWith(dir, "e", new LinkedHashMap<>()), "v1");
        WorkerSearch[] workers = new WorkerSearch[5];
        workers[0] = empty;
        workers[1] = empty;
        workers[2] = empty;
        workers[3] = c4; // P4 -> worker 4
        workers[4] = c4; // P5 -> same worker 4 (co-located)

        DistributedCoordinator coord =
                new DistributedCoordinator(workers, stats, Bm25Params.defaults(), 5000);
        DistributedCoordinator.Response resp = coord.search(List.of("tiger", "42"), 10);
        assertTrue(resp.failedPartitions.isEmpty());
        assertTrue(resp.hits.stream().anyMatch(h -> h.docId == 1)); // tiger -> P4
        assertTrue(resp.hits.stream().anyMatch(h -> h.docId == 2)); // 42 -> P5
    }

    @Test
    void partitionVersionVerificationDetectsConsistentAndSkew(@TempDir Path dir)
            throws IOException {
        HttpWorkerClient[] clients = new HttpWorkerClient[5];
        for (int p = 0; p < 5; p++) {
            clients[p] = serve("P" + (p + 1), List.of("P" + (p + 1)),
                    indexWith(dir, "ok" + p, Map.of("apple", new int[][] {{1, 0}})), "vSAME");
        }
        PartitionVersionVerifier.Result ok = new PartitionVersionVerifier(clients).verify();
        assertTrue(ok.consistent);
        assertEquals("vSAME", ok.authoritativeVersion());

        // Now give P3 a different version -> skew must be detected.
        clients[2] = serve("P3b", List.of("P3"),
                indexWith(dir, "skew", Map.of("ninja", new int[][] {{1, 0}})), "vDIFFERENT");
        PartitionVersionVerifier.Result skew = new PartitionVersionVerifier(clients).verify();
        assertFalse(skew.consistent);
        assertEquals(2, skew.distinctVersions.size());
    }

    @Test
    void unavailableWorkerThrowsAndCoordinatorRecordsFailedPartition(@TempDir Path dir)
            throws IOException {
        Map<String, int[][]> p1 = Map.of("apple", new int[][] {{1, 0}});
        InvertedIndex full = indexWith(dir, "full", p1);
        CorpusStatistics stats = CorpusStatistics.fromIndex(full);

        HttpWorkerClient p1client = serve("P1", List.of("P1"), indexWith(dir, "p1", p1), "v1");
        // A client pointed at a dead port: must throw WorkerUnavailableException on lookup.
        HttpWorkerClient dead = HttpWorkerClient.fromAddress("P3", "127.0.0.1:1", 500);
        assertThrows(HttpWorkerClient.WorkerUnavailableException.class,
                () -> dead.lookup(List.of("ninja")));

        WorkerSearch empty = serve("E", List.of("Px"),
                indexWith(dir, "e2", new LinkedHashMap<>()), "v1");
        WorkerSearch[] workers = new WorkerSearch[5];
        workers[0] = p1client;
        workers[1] = empty;
        workers[2] = dead;   // P3 is down
        workers[3] = empty;
        workers[4] = empty;
        DistributedCoordinator coord =
                new DistributedCoordinator(workers, stats, Bm25Params.defaults(), 2000);
        // "apple"(P1) healthy, "nurse"(P3) hits the dead worker.
        DistributedCoordinator.Response resp = coord.search(List.of("apple", "nurse"), 10);
        assertTrue(resp.failedPartitions.contains("P3"), "dead partition must be flagged");
        assertTrue(resp.hits.stream().anyMatch(h -> h.docId == 1), "healthy term still served");
    }
}
