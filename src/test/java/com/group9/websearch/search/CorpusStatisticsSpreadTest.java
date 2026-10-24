package com.group9.websearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.group9.websearch.index.HitType;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Verifies the length-spread statistics (min/max/median/total) derived from the index, using a
 * tiny hand-built corpus. Document lengths: doc1=1, doc2=2, doc3=4 -> min 1, max 4, median 2,
 * total 7, avgdl 7/3.
 */
class CorpusStatisticsSpreadTest {

    private static String line(String term, Object... triples) {
        PostingList pl = new PostingList();
        for (int i = 0; i < triples.length; i += 3) {
            pl.add((Long) triples[i], (HitType) triples[i + 1], (Integer) triples[i + 2]);
        }
        return IndexRecordCodec.encode(term, pl);
    }

    @Test
    void minMaxMedianTotal(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("idx");
        Files.createDirectories(dir);
        List<String> part = new ArrayList<>();
        // doc1: "a"            -> dl 1
        // doc2: "a b"          -> dl 2  (a@0, b@1)
        // doc3: "b b c d"      -> dl 4
        part.add(line("a", 1L, HitType.BODY, 0, 2L, HitType.BODY, 0));
        part.add(line("b", 2L, HitType.BODY, 1, 3L, HitType.BODY, 0, 3L, HitType.BODY, 1));
        part.add(line("c", 3L, HitType.BODY, 2));
        part.add(line("d", 3L, HitType.BODY, 3));
        Files.write(dir.resolve("part-r-00000"), part, StandardCharsets.UTF_8);
        Files.write(dir.resolve("_SUCCESS"), new byte[0]);

        CorpusStatistics s = CorpusStatistics.fromIndex(InvertedIndex.load(dir));
        assertEquals(3, s.numDocs());
        assertEquals(1L, s.docLength(1L));
        assertEquals(2L, s.docLength(2L));
        assertEquals(4L, s.docLength(3L));
        assertEquals(1L, s.minDocLength());
        assertEquals(4L, s.maxDocLength());
        assertEquals(2.0, s.medianDocLength(), 1e-9);
        assertEquals(7L, s.totalTokens());
        assertEquals(7.0 / 3.0, s.avgDocLength(), 1e-9);
    }
}
