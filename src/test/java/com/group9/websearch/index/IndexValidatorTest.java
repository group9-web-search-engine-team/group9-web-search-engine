package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class IndexValidatorTest {

    @BeforeEach
    void requireHadoopLocalFs() {
        Assumptions.assumeTrue(HadoopLocalFsSupport.hadoopLocalFsUsable(),
                "Hadoop local FS native layer unavailable (e.g. Windows without winutils); "
                        + "runs on the WSL2/Linux Hadoop runtime.");
    }

    private static Configuration localConf() {
        Configuration c = new Configuration();
        c.set("fs.defaultFS", "file:///");
        return c;
    }

    private void writePart(Path dir, String name, String... records) throws Exception {
        Files.write(dir.resolve(name), List.of(records), StandardCharsets.UTF_8);
    }

    private void touchSuccess(Path dir) throws Exception {
        Files.write(dir.resolve("_SUCCESS"), new byte[0]);
    }

    @Test
    void validPositionalIndexPasses(@TempDir Path dir) throws Exception {
        // alpha: doc1 body@0,3 + doc3 title@1 ; beta: doc2 body@0
        writePart(dir, "part-r-00000", "alpha\t1:b0-3,3:t1", "beta\t2:b0");
        touchSuccess(dir);
        IndexValidator.Result r = IndexValidator.validate(localConf(), dir.toUri().toString());
        assertTrue(r.valid(), r.toString());
        assertEquals(2, r.termCount);
        assertEquals(3, r.postingCount);
    }

    @Test
    void missingSuccessMarkerFails(@TempDir Path dir) throws Exception {
        writePart(dir, "part-r-00000", "alpha\t1:b0");
        IndexValidator.Result r = IndexValidator.validate(localConf(), dir.toUri().toString());
        assertFalse(r.valid());
        assertFalse(r.successMarker);
    }

    @Test
    void duplicateDocIdWithinTermDetected(@TempDir Path dir) throws Exception {
        // record manually corrupted: docId 1 twice (non-increasing across postings)
        writePart(dir, "part-r-00000", "alpha\t1:b0,1:b3");
        touchSuccess(dir);
        IndexValidator.Result r = IndexValidator.validate(localConf(), dir.toUri().toString());
        assertFalse(r.valid());
        assertTrue(r.problems.stream().anyMatch(p -> p.contains("docId")));
    }

    @Test
    void legacyFrequencyOnlyFormatFlagged(@TempDir Path dir) throws Exception {
        // 'alpha\t1:2' is the legacy frequency-only format (digit right after the colon).
        writePart(dir, "part-r-00000", "alpha\t1:2,3:1");
        touchSuccess(dir);
        IndexValidator.Result r = IndexValidator.validate(localConf(), dir.toUri().toString());
        assertFalse(r.valid());
        assertTrue(r.problems.stream().anyMatch(p -> p.contains("legacy frequency-only")));
    }

    @Test
    void termInTwoPartsDetected(@TempDir Path dir) throws Exception {
        writePart(dir, "part-r-00000", "alpha\t1:b0");
        writePart(dir, "part-r-00001", "alpha\t2:b0");
        touchSuccess(dir);
        IndexValidator.Result r = IndexValidator.validate(localConf(), dir.toUri().toString());
        assertFalse(r.valid());
        assertTrue(r.problems.stream().anyMatch(p -> p.contains("more than one part")));
    }

    @Test
    void noPartFilesFails(@TempDir Path dir) throws Exception {
        touchSuccess(dir);
        IndexValidator.Result r = IndexValidator.validate(localConf(), dir.toUri().toString());
        assertFalse(r.valid());
        assertTrue(r.problems.stream().anyMatch(p -> p.contains("no part")));
    }
}
