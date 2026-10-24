package com.group9.websearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.HitType;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Okapi BM25 ranker and its corpus statistics, using small hand-calculable
 * examples. These tests do not run the application; they exercise the formula directly.
 */
class Bm25RankerTest {

    private static final double EPS = 1e-9;

    // ---- IDF ----

    @Test
    void idfFormulaMatchesHandCalculation() {
        // N=10, df=1 -> ln(1 + (10-1+0.5)/(1+0.5)) = ln(1 + 9.5/1.5) = ln(1 + 6.333...) = ln(7.333..)
        double expected = Math.log(1.0 + (9.5 / 1.5));
        assertEquals(expected, Bm25Ranker.idf(10, 1), EPS);
    }

    @Test
    void idfIsNonNegativeEvenWhenTermInEveryDoc() {
        // df == N -> ln(1 + 0.5/(N+0.5)) > 0
        double idf = Bm25Ranker.idf(5, 5);
        assertTrue(idf > 0.0, "ln(1+...) form is always non-negative");
    }

    @Test
    void idfDecreasesAsDfIncreases() {
        assertTrue(Bm25Ranker.idf(100, 1) > Bm25Ranker.idf(100, 50));
    }

    // ---- full score, hand-calculated ----

    private static PostingList pl(Object... docFieldPos) {
        PostingList p = new PostingList();
        for (int i = 0; i < docFieldPos.length; i += 3) {
            p.add((Long) docFieldPos[i], (HitType) docFieldPos[i + 1], (Integer) docFieldPos[i + 2]);
        }
        return p;
    }

    /** Build a CorpusStatistics from an in-memory index written to a temp dir. */
    private static CorpusStatistics statsFrom(Path tmp, List<String> partLines) throws IOException {
        Path dir = tmp.resolve("idx-" + System.nanoTime());
        Files.createDirectories(dir);
        Files.write(dir.resolve("part-r-00000"), partLines, StandardCharsets.UTF_8);
        InvertedIndex index = InvertedIndex.load(dir);
        return CorpusStatistics.fromIndex(index);
    }

    @Test
    void singleTermScoreMatchesManualBm25(@TempDir Path tmp) throws Exception {
        // Corpus: doc1 body "cat" (len 1), doc2 body "dog dog" (len 2). N=2, avgdl=1.5.
        List<String> part = new ArrayList<>();
        part.add(IndexRecordCodec.encode("cat", pl(1L, HitType.BODY, 0)));
        part.add(IndexRecordCodec.encode("dog", pl(2L, HitType.BODY, 0, 2L, HitType.BODY, 1)));
        CorpusStatistics stats = statsFrom(tmp, part);
        assertEquals(2, stats.numDocs());
        assertEquals(1.5, stats.avgDocLength(), EPS);
        assertEquals(1L, stats.docLength(1L));
        assertEquals(2L, stats.docLength(2L));

        Bm25Ranker ranker = new Bm25Ranker(stats, Bm25Params.defaults());
        Map<String, PostingList> tp = new LinkedHashMap<>();
        tp.put("cat", pl(1L, HitType.BODY, 0));

        // Manual BM25 for term "cat" on doc1: N=2, df=1, tf=1, dl=1, avgdl=1.5, k1=1.2, b=0.75
        double idf = Math.log(1.0 + ((2 - 1 + 0.5) / (1 + 0.5)));
        double lengthNorm = 1 - 0.75 + 0.75 * (1.0 / 1.5);
        double expected = idf * (1 * (1.2 + 1)) / (1 + 1.2 * lengthNorm);
        double got = ranker.score(1L, List.of("cat"), tp).score;
        assertEquals(expected, got, 1e-9);
    }

    @Test
    void higherTermFrequencyScoresHigher(@TempDir Path tmp) throws Exception {
        // doc1 body "x" (tf=1), doc2 body "x x x" (tf=3), equal field. Same term, same df.
        List<String> part = new ArrayList<>();
        part.add(IndexRecordCodec.encode("x",
                pl(1L, HitType.BODY, 0, 2L, HitType.BODY, 0, 2L, HitType.BODY, 1, 2L, HitType.BODY, 2)));
        CorpusStatistics stats = statsFrom(tmp, part);
        Bm25Ranker ranker = new Bm25Ranker(stats, Bm25Params.defaults());

        PostingList xpl = IndexRecordCodec.decode(part.get(0)).postings();
        Map<String, PostingList> tp = Map.of("x", xpl);
        double s1 = ranker.score(1L, List.of("x"), tp).score;
        double s2 = ranker.score(2L, List.of("x"), tp).score;
        assertTrue(s2 > s1, "doc with tf=3 should outscore doc with tf=1 for the same term");
    }

    @Test
    void lengthNormalizationPenalizesLongerDocs(@TempDir Path tmp) throws Exception {
        // Two docs with the SAME tf for "x" (tf=1) but very different lengths.
        // doc1 body "x" (len 1). doc2 body "x a b c d e f g h i" (len 10, x tf=1).
        List<String> part = new ArrayList<>();
        PostingList xpl = new PostingList();
        xpl.add(1L, HitType.BODY, 0);
        xpl.add(2L, HitType.BODY, 0);
        part.add(IndexRecordCodec.encode("x", xpl));
        // filler terms only in doc2 to inflate its length to 10
        PostingList filler = new PostingList();
        for (int pos = 1; pos < 10; pos++) {
            filler.add(2L, HitType.BODY, pos);
        }
        part.add(IndexRecordCodec.encode("filler", filler));
        CorpusStatistics stats = statsFrom(tmp, part);
        assertEquals(1L, stats.docLength(1L));
        assertEquals(10L, stats.docLength(2L));

        Bm25Ranker ranker = new Bm25Ranker(stats, Bm25Params.defaults());
        Map<String, PostingList> tp = Map.of("x", xpl);
        double short1 = ranker.score(1L, List.of("x"), tp).score;
        double long2 = ranker.score(2L, List.of("x"), tp).score;
        assertTrue(short1 > long2, "shorter doc with same tf should score higher (length norm)");
    }

    @Test
    void unknownAndMissingTermsContributeNothing(@TempDir Path tmp) throws Exception {
        List<String> part = List.of(IndexRecordCodec.encode("cat", pl(1L, HitType.BODY, 0)));
        CorpusStatistics stats = statsFrom(tmp, part);
        Bm25Ranker ranker = new Bm25Ranker(stats, Bm25Params.defaults());
        // "zzz" not in termPostings at all -> ignored; score is 0.
        double score = ranker.score(1L, List.of("zzz"), Map.of()).score;
        assertEquals(0.0, score, EPS);
    }

    @Test
    void repeatedQueryTermCountedOnce(@TempDir Path tmp) throws Exception {
        List<String> part = List.of(IndexRecordCodec.encode("cat", pl(1L, HitType.BODY, 0)));
        CorpusStatistics stats = statsFrom(tmp, part);
        Bm25Ranker ranker = new Bm25Ranker(stats, Bm25Params.defaults());
        PostingList catPl = IndexRecordCodec.decode(part.get(0)).postings();
        Map<String, PostingList> tp = Map.of("cat", catPl);

        double once = ranker.score(1L, List.of("cat"), tp).score;
        double twice = ranker.score(1L, List.of("cat", "cat"), tp).score;
        assertEquals(once, twice, EPS, "repeated query term must not double-count");
    }

    @Test
    void emptyIndexGivesZeroAvgdlAndSafeScore(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("empty-idx");
        Files.createDirectories(dir);
        Files.write(dir.resolve("_SUCCESS"), new byte[0]); // no part files
        CorpusStatistics stats = CorpusStatistics.fromIndex(InvertedIndex.load(dir));
        assertEquals(0, stats.numDocs());
        assertEquals(0.0, stats.avgDocLength(), EPS);
        Bm25Ranker ranker = new Bm25Ranker(stats, Bm25Params.defaults());
        // No postings -> no contribution, no divide-by-zero.
        double score = ranker.score(1L, List.of("cat"), Map.of()).score;
        assertEquals(0.0, score, EPS);
    }

    @Test
    void multiTermScoreIsSumOfTermScores(@TempDir Path tmp) throws Exception {
        List<String> part = new ArrayList<>();
        part.add(IndexRecordCodec.encode("cat", pl(1L, HitType.BODY, 0)));
        part.add(IndexRecordCodec.encode("dog", pl(1L, HitType.BODY, 1)));
        CorpusStatistics stats = statsFrom(tmp, part);
        Bm25Ranker ranker = new Bm25Ranker(stats, Bm25Params.defaults());
        PostingList catPl = IndexRecordCodec.decode(part.get(0)).postings();
        PostingList dogPl = IndexRecordCodec.decode(part.get(1)).postings();
        Map<String, PostingList> tp = new LinkedHashMap<>();
        tp.put("cat", catPl);
        tp.put("dog", dogPl);

        double catOnly = ranker.score(1L, List.of("cat"), Map.of("cat", catPl)).score;
        double dogOnly = ranker.score(1L, List.of("dog"), Map.of("dog", dogPl)).score;
        double both = ranker.score(1L, List.of("cat", "dog"), tp).score;
        assertEquals(catOnly + dogOnly, both, 1e-9);
    }

    @Test
    void breakdownExposesPerTermContributions(@TempDir Path tmp) throws Exception {
        List<String> part = new ArrayList<>();
        part.add(IndexRecordCodec.encode("cat", pl(1L, HitType.BODY, 0)));
        CorpusStatistics stats = statsFrom(tmp, part);
        Bm25Ranker ranker = new Bm25Ranker(stats, Bm25Params.defaults());
        PostingList catPl = IndexRecordCodec.decode(part.get(0)).postings();
        Bm25Ranker.Scored s = ranker.score(1L, List.of("cat"), Map.of("cat", catPl));
        assertTrue(s.breakdown.containsKey("cat"));
        assertEquals(s.score, s.breakdown.get("cat"), 1e-9);
    }
}
