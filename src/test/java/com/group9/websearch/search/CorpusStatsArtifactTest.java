package com.group9.websearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class CorpusStatsArtifactTest {

    private static CorpusStatsArtifact sample(boolean complete) {
        CorpusStatsArtifact a = new CorpusStatsArtifact();
        a.complete = complete;
        a.numDocs = 3;
        a.numTerms = 5;
        a.totalTokens = 12;
        a.avgDocLength = 4.0;
        a.minDocLength = 2;
        a.maxDocLength = 6;
        a.medianDocLength = 4.0;
        a.indexPath = "index-local";
        a.docLengths = new TreeMap<>();
        a.docLengths.put("1", 2L);
        a.docLengths.put("2", 4L);
        a.docLengths.put("3", 6L);
        return a;
    }

    @Test
    void jsonRoundTrip() throws Exception {
        CorpusStatsArtifact a = sample(true);
        CorpusStatsArtifact b = CorpusStatsArtifact.fromJson(a.toJson());
        assertEquals(a.numDocs, b.numDocs);
        assertEquals(a.avgDocLength, b.avgDocLength, 1e-9);
        assertEquals(a.docLengths, b.docLengths);
        assertEquals(CorpusStatsArtifact.SCHEMA_VERSION, b.version);
    }

    @Test
    void atomicPublishWritesReadableFile(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("stats/corpus-stats.json");
        sample(true).publishAtomically(target);
        assertTrue(Files.isRegularFile(target));
        CorpusStatsArtifact loaded = CorpusStatsArtifact.load(target);
        assertEquals(3, loaded.numDocs);
    }

    @Test
    void fromArtifactExposesStats() {
        CorpusStatistics s = CorpusStatistics.fromArtifact(sample(true));
        assertEquals(3, s.numDocs());
        assertEquals(4.0, s.avgDocLength(), 1e-9);
        assertEquals(2L, s.docLength(1L));
        assertEquals(6L, s.docLength(3L));
        assertTrue(s.isComplete());
        assertTrue(s.source().startsWith("artifact:"));
    }

    @Test
    void incompleteArtifactRejected() {
        assertThrows(IllegalStateException.class,
                () -> CorpusStatistics.fromArtifact(sample(false)));
    }

    @Test
    void nullArtifactRejected() {
        assertThrows(IllegalArgumentException.class, () -> CorpusStatistics.fromArtifact(null));
    }
}
