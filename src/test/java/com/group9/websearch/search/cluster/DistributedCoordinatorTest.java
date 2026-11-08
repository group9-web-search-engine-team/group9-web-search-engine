package com.group9.websearch.search.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Distributed query execution: routing, dedup, deterministic order, worker failure, and the
 * key correctness property -- distributed GLOBAL BM25 equals the single-node reference on the
 * same small corpus.
 */
class DistributedCoordinatorTest {

    /** Write a one-term index part file and load it as an InvertedIndex partition. */
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
        return InvertedIndex.load(d);
    }

    /** Build the full single-node index (all terms together) for the reference comparison. */
    private InvertedIndex fullIndex(Path dir, Map<String, int[][]> allTerms) throws IOException {
        return indexWith(dir, "full", allTerms);
    }

    @Test
    void globalBm25MatchesSingleNodeReference(@TempDir Path dir) throws IOException {
        // Corpus: term "apple"(P1) in docs 1,2 ; "ninja"(P3) in docs 2,3 ; "tiger"(P4) in doc 1.
        Map<String, int[][]> p1 = new LinkedHashMap<>();
        p1.put("apple", new int[][] {{1, 0, 3}, {2, 0}});          // tf: d1=2, d2=1
        Map<String, int[][]> p3 = new LinkedHashMap<>();
        p3.put("ninja", new int[][] {{2, 1}, {3, 0, 2, 4}});        // tf: d2=1, d3=3
        Map<String, int[][]> p4 = new LinkedHashMap<>();
        p4.put("tiger", new int[][] {{1, 1}});                      // tf: d1=1

        Map<String, int[][]> all = new LinkedHashMap<>();
        all.putAll(p1);
        all.putAll(p3);
        all.putAll(p4);
        InvertedIndex full = fullIndex(dir, all);
        CorpusStatistics stats = CorpusStatistics.fromIndex(full); // GLOBAL stats

        // Partition workers: P1 owns apple, P3 owns ninja, P4 owns tiger; P2/P5 empty.
        InvertedIndex ip1 = indexWith(dir, "p1", p1);
        InvertedIndex ip3 = indexWith(dir, "p3", p3);
        InvertedIndex ip4 = indexWith(dir, "p4", p4);
        InvertedIndex empty = indexWith(dir, "empty", new LinkedHashMap<>());
        WorkerSearch[] workers = new WorkerSearch[TermPartitioner.NUM_PARTITIONS];
        workers[0] = new LocalWorkerSearch("P1", ip1);
        workers[1] = new LocalWorkerSearch("P2", empty);
        workers[2] = new LocalWorkerSearch("P3", ip3);
        workers[3] = new LocalWorkerSearch("P4", ip4);
        workers[4] = new LocalWorkerSearch("P5", empty);

        Bm25Params params = Bm25Params.defaults();
        DistributedCoordinator coord =
                new DistributedCoordinator(workers, stats, params, 5000);

        List<String> query = List.of("apple", "ninja", "tiger");
        DistributedCoordinator.Response resp = coord.search(query, 10);
        assertTrue(resp.failedPartitions.isEmpty());

        // Reference: single-node Bm25Ranker over the full index for the same candidates.
        Bm25Ranker ref = new Bm25Ranker(stats, params);
        Map<String, PostingList> termPostings = new LinkedHashMap<>();
        for (String t : query) {
            termPostings.put(t, full.postings(t));
        }
        Map<Long, Double> refScores = new LinkedHashMap<>();
        for (long docId : new long[] {1, 2, 3}) {
            refScores.put(docId, ref.score(docId, query, termPostings).score);
        }

        // Every distributed hit must equal the single-node score for that doc.
        for (DistributedCoordinator.Hit h : resp.hits) {
            assertEquals(refScores.get(h.docId), h.score, 1e-9,
                    "global BM25 mismatch for doc " + h.docId);
        }
        // Deterministic order: scores non-increasing, docId tie-break ascending.
        for (int i = 1; i < resp.hits.size(); i++) {
            DistributedCoordinator.Hit a = resp.hits.get(i - 1);
            DistributedCoordinator.Hit b = resp.hits.get(i);
            assertTrue(a.score > b.score || (a.score == b.score && a.docId < b.docId),
                    "non-deterministic order at " + i);
        }
    }

    @Test
    void docMatchedByMultipleTermsAppearsOnce(@TempDir Path dir) throws IOException {
        // "apple"(P1) and "nimbus"(P2) both in doc 2 -> doc 2 must appear exactly once.
        Map<String, int[][]> p1 = Map.of("apple", new int[][] {{2, 0}});
        Map<String, int[][]> p2 = Map.of("nimbus", new int[][] {{2, 1}});
        Map<String, int[][]> all = new LinkedHashMap<>();
        all.putAll(p1);
        all.putAll(p2);
        CorpusStatistics stats = CorpusStatistics.fromIndex(fullIndex(dir, all));
        WorkerSearch[] w = new WorkerSearch[5];
        InvertedIndex empty = indexWith(dir, "e", new LinkedHashMap<>());
        w[0] = new LocalWorkerSearch("P1", indexWith(dir, "a", p1));
        w[1] = new LocalWorkerSearch("P2", indexWith(dir, "b", p2));
        w[2] = new LocalWorkerSearch("P3", empty);
        w[3] = new LocalWorkerSearch("P4", empty);
        w[4] = new LocalWorkerSearch("P5", empty);
        DistributedCoordinator coord =
                new DistributedCoordinator(w, stats, Bm25Params.defaults(), 5000);
        DistributedCoordinator.Response resp = coord.search(List.of("apple", "nimbus"), 10);
        long count = resp.hits.stream().filter(h -> h.docId == 2).count();
        assertEquals(1, count, "doc matched by two partitions must be deduplicated");
    }

    @Test
    void workerFailureIsRecordedAndDoesNotCorruptResults(@TempDir Path dir) throws IOException {
        Map<String, int[][]> p1 = Map.of("apple", new int[][] {{1, 0}});
        CorpusStatistics stats = CorpusStatistics.fromIndex(fullIndex(dir, p1));
        WorkerSearch[] w = new WorkerSearch[5];
        InvertedIndex empty = indexWith(dir, "e2", new LinkedHashMap<>());
        w[0] = new LocalWorkerSearch("P1", indexWith(dir, "a2", p1));
        // P3 worker always throws (simulated failure) -> partition recorded, query continues.
        w[2] = new WorkerSearch() {
            @Override
            public List<TermPostingsResult> lookup(List<String> terms) {
                throw new RuntimeException("simulated worker down");
            }

            @Override
            public String id() {
                return "P3-down";
            }
        };
        w[1] = new LocalWorkerSearch("P2", empty);
        w[3] = new LocalWorkerSearch("P4", empty);
        w[4] = new LocalWorkerSearch("P5", empty);
        DistributedCoordinator coord =
                new DistributedCoordinator(w, stats, Bm25Params.defaults(), 2000);
        // "apple"(P1) ok, "nurse"(P3) hits the dead worker.
        DistributedCoordinator.Response resp = coord.search(List.of("apple", "nurse"), 10);
        assertTrue(resp.failedPartitions.contains("P3"));
        // The healthy term still returns its document; no corruption, no hang.
        assertTrue(resp.hits.stream().anyMatch(h -> h.docId == 1));
    }
}
