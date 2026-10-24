package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.search.CorpusStatsArtifact;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Tests the read-only StatsCalculator over a hand-built final index directory. Uses the Hadoop
 * local filesystem, so it is gated on the native-FS probe (skipped cleanly on a bare Windows
 * dev box; runs on the WSL2/Linux Hadoop runtime), matching the repo convention.
 */
class StatsCalculatorTest {

    @BeforeEach
    void requireHadoopLocalFs() {
        Assumptions.assumeTrue(HadoopLocalFsSupport.hadoopLocalFsUsable(),
                "Hadoop local FS native layer unavailable; runs on the WSL2/Linux Hadoop runtime.");
    }

    private static Configuration localConf() {
        Configuration c = new Configuration();
        c.set("fs.defaultFS", "file:///");
        return c;
    }

    private static String line(String term, Object... triples) {
        PostingList pl = new PostingList();
        for (int i = 0; i < triples.length; i += 3) {
            pl.add((Long) triples[i], (HitType) triples[i + 1], (Integer) triples[i + 2]);
        }
        return IndexRecordCodec.encode(term, pl);
    }

    private static Path buildIndex(Path tmp, boolean withSuccess) throws Exception {
        Path dir = tmp.resolve("index");
        Files.createDirectories(dir);
        List<String> part = new ArrayList<>();
        // doc1 dl=1 ("cat"), doc2 dl=2 ("dog dog"), doc3 dl=3 ("cat fish fish")
        part.add(line("cat", 1L, HitType.BODY, 0, 3L, HitType.BODY, 0));
        part.add(line("dog", 2L, HitType.BODY, 0, 2L, HitType.BODY, 1));
        part.add(line("fish", 3L, HitType.BODY, 1, 3L, HitType.BODY, 2));
        Files.write(dir.resolve("part-r-00000"), part, StandardCharsets.UTF_8);
        if (withSuccess) {
            Files.write(dir.resolve("_SUCCESS"), new byte[0]);
        }
        return dir;
    }

    @Test
    void computesAccurateStatsFromFinalIndex(@TempDir Path tmp) throws Exception {
        Path dir = buildIndex(tmp, true);
        StatsCalculator calc = new StatsCalculator(localConf());
        StatsCalculator.Result r = calc.compute(dir.toUri().toString());

        assertTrue(r.complete());
        assertEquals(3, r.artifact.numDocs);       // N
        assertEquals(3, r.artifact.numTerms);      // cat, dog, fish
        assertEquals(6, r.artifact.totalTokens);   // 1 + 2 + 3
        assertEquals(2.0, r.artifact.avgDocLength, 1e-9);  // 6 / 3
        assertEquals(1, r.artifact.minDocLength);
        assertEquals(3, r.artifact.maxDocLength);
        assertEquals(2.0, r.artifact.medianDocLength, 1e-9);
        // df: cat in doc1,doc3 -> 2 ; dog in doc2 -> 1 ; fish in doc3 -> 1
        assertEquals(2, r.documentFrequencies.get("cat"));
        assertEquals(1, r.documentFrequencies.get("dog"));
        assertEquals(1, r.documentFrequencies.get("fish"));
    }

    @Test
    void missingSuccessMarkerMarksIncomplete(@TempDir Path tmp) throws Exception {
        Path dir = buildIndex(tmp, false); // no _SUCCESS
        StatsCalculator calc = new StatsCalculator(localConf());
        StatsCalculator.Result r = calc.compute(dir.toUri().toString());
        assertFalse(r.complete());
        assertTrue(r.problems.stream().anyMatch(p -> p.contains("_SUCCESS")));
    }

    @Test
    void publishWritesArtifactAndDfSidecarWithoutTouchingIndex(@TempDir Path tmp) throws Exception {
        Path dir = buildIndex(tmp, true);
        long partBytesBefore = Files.size(dir.resolve("part-r-00000"));

        StatsCalculator calc = new StatsCalculator(localConf());
        StatsCalculator.Result r = calc.compute(dir.toUri().toString());
        Path statsJson = tmp.resolve("stats/corpus-stats.json");
        Path dfTsv = tmp.resolve("stats/df.tsv");
        calc.publish(r, statsJson.toString(), dfTsv.toString());

        assertTrue(Files.isRegularFile(statsJson));
        assertTrue(Files.isRegularFile(dfTsv));
        CorpusStatsArtifact loaded = CorpusStatsArtifact.load(statsJson);
        assertEquals(3, loaded.numDocs);
        assertTrue(loaded.complete);

        // Index file is untouched (same size, still present).
        assertEquals(partBytesBefore, Files.size(dir.resolve("part-r-00000")));
        assertTrue(Files.isRegularFile(dir.resolve("_SUCCESS")));
    }

    @Test
    void rerunIsSafeAndDeterministic(@TempDir Path tmp) throws Exception {
        Path dir = buildIndex(tmp, true);
        StatsCalculator calc = new StatsCalculator(localConf());
        StatsCalculator.Result r1 = calc.compute(dir.toUri().toString());
        StatsCalculator.Result r2 = calc.compute(dir.toUri().toString());
        assertEquals(r1.artifact.numDocs, r2.artifact.numDocs);
        assertEquals(r1.artifact.totalTokens, r2.artifact.totalTokens);
        assertEquals(r1.artifact.avgDocLength, r2.artifact.avgDocLength, 1e-9);
    }

    @Test
    void missingIndexPathReportedNotFatal(@TempDir Path tmp) throws Exception {
        StatsCalculator calc = new StatsCalculator(localConf());
        StatsCalculator.Result r = calc.compute(tmp.resolve("does-not-exist").toUri().toString());
        assertFalse(r.complete());
        assertTrue(r.problems.stream().anyMatch(p -> p.contains("does not exist")));
    }
}
