package com.group9.websearch.search.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.HitType;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import com.group9.websearch.index.TermPartitioner;
import com.group9.websearch.search.Bm25Params;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end distributed Boolean + positional NEAR tests over live {@link WorkerServer}s (local
 * ephemeral ports, no AWS), driving the real {@link HttpWorkerClient} transport so positions
 * travel over the wire. Terms are chosen to span different partitions.
 *
 * <p>Corpus (positions are BODY token positions):
 * <pre>
 *   doc1: apple@0 infection@1 treatment@2          (P1 apple, P2 infection, P4 treatment)
 *   doc2: apple@0 animal@5 infection@9             (apple & infection far apart)
 *   doc3: banana@0 infection@1                     (P1 banana, P2 infection) -- no apple
 *   doc4: animal@0                                  (P1 animal only)
 * </pre>
 * Partition routing: apple,animal,banana -> P1(a-h); infection -> P2(i-m); treatment -> P4(t-z).
 */
class BooleanDistributedTest {

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

    private HttpWorkerClient serve(String id, List<String> parts, InvertedIndex idx)
            throws IOException {
        WorkerServer server = new WorkerServer(new LocalWorkerSearch(id, idx), parts, "v1",
                idx.termCount());
        int port = server.start("127.0.0.1", 0, 2);
        started.add(server);
        return HttpWorkerClient.fromAddress(id, "127.0.0.1:" + port, 4000);
    }

    private DistributedCoordinator coordinator(Path dir) throws IOException {
        // P1: apple, animal, banana ; P2: infection ; P4: treatment ; P3/P5: empty
        Map<String, int[][]> p1 = new LinkedHashMap<>();
        p1.put("apple", new int[][] {{1, 0}, {2, 0}});
        p1.put("animal", new int[][] {{2, 5}, {4, 0}});
        p1.put("banana", new int[][] {{3, 0}});
        Map<String, int[][]> p2 = new LinkedHashMap<>();
        p2.put("infection", new int[][] {{1, 1}, {2, 9}, {3, 1}});
        Map<String, int[][]> p4 = new LinkedHashMap<>();
        p4.put("treatment", new int[][] {{1, 2}});

        Map<String, int[][]> all = new LinkedHashMap<>();
        all.putAll(p1);
        all.putAll(p2);
        all.putAll(p4);
        CorpusStatistics stats = CorpusStatistics.fromIndex(indexWith(dir, "full", all));

        // Sanity on routing.
        assertEquals(0, TermPartitioner.partitionFor("apple"));
        assertEquals(0, TermPartitioner.partitionFor("animal"));
        assertEquals(0, TermPartitioner.partitionFor("banana"));
        assertEquals(1, TermPartitioner.partitionFor("infection"));
        assertEquals(3, TermPartitioner.partitionFor("treatment"));

        InvertedIndex empty = indexWith(dir, "empty", new LinkedHashMap<>());
        WorkerSearch[] w = new WorkerSearch[5];
        w[0] = serve("P1", List.of("P1"), indexWith(dir, "p1", p1));
        w[1] = serve("P2", List.of("P2"), indexWith(dir, "p2", p2));
        w[2] = serve("P3", List.of("P3"), empty);
        w[3] = serve("P4", List.of("P4"), indexWith(dir, "p4", p4));
        w[4] = serve("P5", List.of("P5"), empty);
        return new DistributedCoordinator(w, stats, Bm25Params.defaults(), 5000);
    }

    private Set<Long> docs(DistributedCoordinator coord, String query) {
        DistributedCoordinator.Response r = coord.searchBoolean(BooleanQuery.parse(query), 100);
        assertTrue(r.failedPartitions.isEmpty(), "no partition should fail: " + r.failedPartitions);
        return r.hits.stream().map(h -> h.docId).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    @Test
    void andIntersectsAcrossPartitions(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // apple(P1) in doc1,doc2 ; infection(P3) in doc1,doc2,doc3 -> AND = doc1,doc2
        assertEquals(Set.of(1L, 2L), docs(c, "apple AND infection"));
    }

    @Test
    void orUnionsAcrossPartitions(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // banana(P1): doc3 ; treatment(P4): doc1 -> OR = doc1,doc3
        assertEquals(Set.of(1L, 3L), docs(c, "banana OR treatment"));
    }

    @Test
    void notExcludesUsingUniverse(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // apple(doc1,doc2) NOT animal(doc2,doc4) -> apple AND (NOT animal) = doc1
        assertEquals(Set.of(1L), docs(c, "apple NOT animal"));
        assertEquals(Set.of(1L), docs(c, "apple AND NOT animal"));
    }

    @Test
    void nearMatchesWithinWindowUsingRealPositions(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // doc1: apple@0 infection@1 (gap 1) ; doc2: apple@0 infection@9 (gap 9)
        // NEAR/1 -> only doc1 ; NEAR/9 -> doc1 and doc2
        assertEquals(Set.of(1L), docs(c, "apple NEAR/1 infection"));
        assertEquals(Set.of(1L, 2L), docs(c, "apple NEAR/9 infection"));
    }

    @Test
    void nearExcludesWhenOutsideWindow(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // gap in doc2 is 9; NEAR/3 excludes doc2, keeps doc1 (gap 1).
        assertEquals(Set.of(1L), docs(c, "apple NEAR/3 infection"));
    }

    @Test
    void nearTermsInDifferentDocumentsDoNotMatch(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // banana only in doc3; treatment only in doc1 -> no shared doc -> empty.
        assertTrue(docs(c, "banana NEAR/5 treatment").isEmpty());
    }

    @Test
    void missingTermYieldsEmptyForAnd(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // "zzz" absent -> AND empty.
        assertTrue(docs(c, "apple AND zzznotindexed").isEmpty());
    }

    @Test
    void mixedAllFourOperators(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // apple OR banana AND NOT animal NEAR/5 infection
        // precedence: (apple OR (banana AND (NOT animal))) NEAR/5 infection
        //   NOT animal -> {1,3} (universe minus doc2,doc4)
        //   banana AND (NOT animal) -> {3}
        //   apple OR {3} -> {1,2,3}
        //   left positions: only term leaves carry positions; OR result has none, so NEAR
        //     degrades to doc-level co-occurrence with infection {1,2,3}
        //   infection docs: {1,2,3}; intersection -> {1,2,3}
        Set<Long> got = docs(c, "apple OR banana AND NOT animal NEAR/5 infection");
        assertEquals(Set.of(1L, 2L, 3L), got);
    }

    @Test
    void plainOrBehaviourPreserved(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // No operators -> existing OR path. apple(doc1,2) + banana(doc3) = {1,2,3}
        DistributedCoordinator.Response r =
                c.search(List.of("apple", "banana"), 100);
        Set<Long> got = r.hits.stream().map(h -> h.docId)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(Set.of(1L, 2L, 3L), got);
    }

    @Test
    void parenthesesOverrideInDistributedEval(@TempDir Path dir) throws IOException {
        DistributedCoordinator c = coordinator(dir);
        // (apple OR banana) AND infection
        //   apple{1,2} OR banana{3} = {1,2,3} ; AND infection{1,2,3} = {1,2,3}
        assertEquals(Set.of(1L, 2L, 3L), docs(c, "(apple OR banana) AND infection"));
        // Without parens and with precedence: apple OR (banana AND infection)
        //   banana{3} AND infection{1,2,3} = {3} ; apple{1,2} OR {3} = {1,2,3} (same here)
        assertFalse(docs(c, "apple OR banana AND infection").isEmpty());
    }
}
