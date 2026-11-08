package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Canonical identity map: bijection, duplicate/malformed detection, coverage, stability. */
class IdMapTest {

    private Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void cleanMapIsOneToOneAndResolvesBothWays(@TempDir Path dir) throws IOException {
        Path f = write(dir, "idmap.tsv", "ug7v899j\t1\nabc123\t2\nxyz789\t3\n");
        IdMap m = IdMap.load(f);
        assertEquals(3, m.size());
        assertTrue(m.isOneToOne());
        assertTrue(m.malformed().isEmpty());
        assertTrue(m.conflicts().isEmpty());
        assertEquals(1L, m.toInternal("ug7v899j").orElseThrow());
        assertEquals("abc123", m.toBeir(2).orElseThrow());
    }

    @Test
    void duplicateBeirIdIsDetectedFirstWins(@TempDir Path dir) throws IOException {
        Path f = write(dir, "idmap.tsv", "dup\t1\ndup\t2\n");
        IdMap m = IdMap.load(f);
        assertEquals(1L, m.toInternal("dup").orElseThrow()); // first wins
        assertFalse(m.conflicts().isEmpty());
        assertFalse(m.isOneToOne());
    }

    @Test
    void reusedInternalIdIsDetected(@TempDir Path dir) throws IOException {
        Path f = write(dir, "idmap.tsv", "a\t5\nb\t5\n");
        IdMap m = IdMap.load(f);
        assertEquals("a", m.toBeir(5).orElseThrow());
        assertFalse(m.conflicts().isEmpty());
        assertFalse(m.isOneToOne());
    }

    @Test
    void malformedLinesAreReportedNotGuessed(@TempDir Path dir) throws IOException {
        Path f = write(dir, "idmap.tsv", "good\t1\nnotanumber\tzz\nnotab\n\nok2\t2\n");
        IdMap m = IdMap.load(f);
        assertEquals(2, m.size());                 // only the two valid rows
        assertEquals(2, m.malformed().size());     // 'zz' and 'notab'
        assertTrue(m.isOneToOne());
    }

    @Test
    void coverageAndUnresolvedAreExact(@TempDir Path dir) throws IOException {
        Path f = write(dir, "idmap.tsv", "a\t1\nb\t2\nc\t3\n");
        IdMap m = IdMap.load(f);
        Set<String> judged = Set.of("a", "b", "missing1", "missing2");
        assertEquals(0.5, m.coverage(judged), 1e-9);  // 2 of 4
        assertEquals(Set.of("missing1", "missing2"),
                Set.copyOf(m.unresolved(judged)));
    }

    @Test
    void reindexingSameCorpusProducesStableMapping(@TempDir Path dir) throws IOException {
        // CorpusAdapter assigns ids in corpus order; re-running on the same corpus is stable.
        String content = "a\t1\nb\t2\nc\t3\n";
        IdMap first = IdMap.load(write(dir, "run1.tsv", content));
        IdMap second = IdMap.load(write(dir, "run2.tsv", content));
        for (String b : first.beirIds()) {
            assertEquals(first.toInternal(b), second.toInternal(b),
                    "mapping must be stable across re-indexing for " + b);
        }
    }
}
