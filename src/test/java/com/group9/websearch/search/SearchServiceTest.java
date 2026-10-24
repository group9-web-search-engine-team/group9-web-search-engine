package com.group9.websearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.ExportedDocument;
import com.group9.websearch.index.HitType;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test of the online flow over a tiny, hand-built index + metadata, using only the
 * local filesystem (no Hadoop, no DB). Builds index part files in the exact on-disk codec
 * format and a matching JSONL export, then exercises OR/AND/NOT/NEAR and edge cases.
 */
class SearchServiceTest {

    /** Build an index part line for a term from (docId, field, position) triples. */
    private static String indexLine(String term, Object... triples) {
        PostingList pl = new PostingList();
        for (int i = 0; i < triples.length; i += 3) {
            pl.add((Long) triples[i], (HitType) triples[i + 1], (Integer) triples[i + 2]);
        }
        return IndexRecordCodec.encode(term, pl);
    }

    private static SearchService build(Path tmp, SearchConfig cfg) throws IOException {
        Path indexDir = tmp.resolve("index");
        Files.createDirectories(indexDir);
        // Corpus:
        //  doc1 title "cat", body "the cat sat on the mat"
        //  doc2 body "a quick brown fox"
        //  doc3 body "cat and dog together"
        List<String> part = new ArrayList<>();
        part.add(indexLine("cat", 1L, HitType.TITLE, 0, 1L, HitType.BODY, 1, 3L, HitType.BODY, 0));
        part.add(indexLine("the", 1L, HitType.BODY, 0, 1L, HitType.BODY, 4));
        part.add(indexLine("sat", 1L, HitType.BODY, 2));
        part.add(indexLine("mat", 1L, HitType.BODY, 5));
        part.add(indexLine("quick", 2L, HitType.BODY, 1));
        part.add(indexLine("brown", 2L, HitType.BODY, 2));
        part.add(indexLine("fox", 2L, HitType.BODY, 3));
        part.add(indexLine("dog", 3L, HitType.BODY, 2));
        Files.write(indexDir.resolve("part-r-00000"), part, StandardCharsets.UTF_8);
        Files.write(indexDir.resolve("_SUCCESS"), new byte[0]);

        Path meta = tmp.resolve("docs.jsonl");
        List<String> lines = List.of(
                new ExportedDocument(1L, "http://x/cat", "cat", "the cat sat on the mat", "k1").toJsonLine(),
                new ExportedDocument(2L, "http://x/fox", "fox page", "a quick brown fox", "k2").toJsonLine(),
                new ExportedDocument(3L, "http://x/zoo", "zoo", "cat and dog together", "k3").toJsonLine());
        Files.write(meta, lines, StandardCharsets.UTF_8);

        cfg.indexDir = indexDir.toString();
        cfg.metadataJsonl = meta.toString();
        return new SearchService(
                com.group9.websearch.search.InvertedIndex.load(indexDir),
                com.group9.websearch.search.DocumentStore.loadJsonl(meta),
                cfg);
    }

    @Test
    void orRanksTitleMatchHigher(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        SearchService.Response resp = svc.search("cat");
        // doc1 (cat in title+body) and doc3 (cat in body) both match; doc1 ranks first.
        assertEquals(2, resp.results.size());
        assertEquals(1L, resp.results.get(0).docId());
        assertTrue(resp.results.get(0).score() > resp.results.get(1).score());
        assertEquals("http://x/cat", resp.results.get(0).url());
    }

    @Test
    void andRequiresAllTerms(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        // "cat" in doc1,doc3 ; "dog" only in doc3 -> AND = doc3 only
        SearchService.Response resp = svc.search("AND: cat dog");
        assertEquals(1, resp.results.size());
        assertEquals(3L, resp.results.get(0).docId());
    }

    @Test
    void notExcludesTermOverUniverse(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        // positive "cat" (doc1,doc3) minus "dog" (doc3) -> doc1
        SearchService.Response resp = svc.search("NOT: cat -dog");
        assertEquals(1, resp.results.size());
        assertEquals(1L, resp.results.get(0).docId());
    }

    @Test
    void nearMatchesWithinWindow(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        // quick@1 brown@2 in doc2 body -> near
        SearchService.Response resp = svc.search("NEAR/2: quick brown");
        assertEquals(1, resp.results.size());
        assertEquals(2L, resp.results.get(0).docId());
    }

    @Test
    void blankQueryReturnsNoResults(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        assertTrue(svc.search("   ").results.isEmpty());
    }

    @Test
    void unknownTermReported(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        SearchService.Response resp = svc.search("cat zzzznotinindex");
        assertTrue(resp.unknownTerms.contains("zzzznotinindex"));
        // still returns cat matches
        assertFalse(resp.results.isEmpty());
    }

    @Test
    void emptyResultForAllUnknownTerms(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        SearchService.Response resp = svc.search("nowheretobefound");
        assertTrue(resp.results.isEmpty());
    }

    @Test
    void resultsCarrySnippetFromBody(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        SearchResult top = svc.search("sat").results.get(0);
        assertEquals(1L, top.docId());
        assertFalse(top.snippet().isBlank());
        assertTrue(top.snippet().toLowerCase().contains("sat"));
    }

    @Test
    void resultsAreInDescendingBm25Order(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        // OR query over two terms; verify scores are non-increasing down the ranking.
        SearchService.Response resp = svc.search("cat fox");
        double prev = Double.POSITIVE_INFINITY;
        for (SearchResult r : resp.results) {
            assertTrue(r.score() <= prev, "results must be in descending BM25 score order");
            prev = r.score();
        }
    }

    @Test
    void bm25BreakdownPresentPerTerm(@TempDir Path tmp) throws Exception {
        SearchService svc = build(tmp, new SearchConfig());
        SearchResult top = svc.search("cat").results.get(0);
        assertTrue(top.breakdown().containsKey("cat"));
    }
}
