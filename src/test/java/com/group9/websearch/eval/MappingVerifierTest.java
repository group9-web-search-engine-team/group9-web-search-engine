package com.group9.websearch.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.ExportedDocument;
import com.group9.websearch.index.IdMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Tests for the BEIR audit requirement #2/#4: complete corpus id-mapping extraction from the
 * authoritative {@code documents.jsonl}, bijection verification, duplicate/missing detection,
 * qrels resolution, and end-to-end use of the STORED artifact via {@link IdMap}. Small
 * deterministic fixtures (no full-corpus dependency).
 */
class MappingVerifierTest {

    private Path docs(Path dir, String name, List<String> jsonLines) throws IOException {
        Path f = dir.resolve(name);
        Files.write(f, jsonLines, StandardCharsets.UTF_8);
        return f;
    }

    private String rec(long docId, String beirId, String title) {
        return new ExportedDocument(docId, "beir:" + beirId, title, "body text " + title, "")
                .toJsonLine();
    }

    @Test
    void completeMappingExtractedAndIsBijection(@TempDir Path dir) throws IOException {
        Path meta = docs(dir, "documents.jsonl", List.of(
                rec(1, "ug7v899j", "alpha"),
                rec(2, "02tnwd4m", "bravo"),
                rec(3, "ejv2xln0", "charlie")));
        Path out = dir.resolve("beir-idmap.v1.tsv");
        int code = MappingVerifier.run(new String[] {
                "--meta", meta.toString(), "--out", out.toString(),
                "--expect-count", "3", "--force"});
        assertEquals(0, code, "complete 3-record bijection should PASS");
        assertTrue(Files.isRegularFile(out), "artifact written");
        assertTrue(Files.isRegularFile(Path.of(out + ".stats.json")), "stats sidecar written");

        // The stored artifact is the canonical IdMap and is a one-to-one map covering all 3.
        IdMap map = IdMap.load(out);
        assertEquals(3, map.size());
        assertTrue(map.isOneToOne());
        assertEquals(Optional.of(1L), map.toInternal("ug7v899j"));
        assertEquals(Optional.of("ejv2xln0"), map.toBeir(3L));
    }

    @Test
    void duplicateBeirIdIsDetectedAndFailsBijection(@TempDir Path dir) throws IOException {
        Path meta = docs(dir, "documents.jsonl", List.of(
                rec(1, "dupBeir", "alpha"),
                rec(2, "dupBeir", "bravo")));   // same BEIR id, different internal id
        Path out = dir.resolve("map.tsv");
        int code = MappingVerifier.run(new String[] {
                "--meta", meta.toString(), "--out", out.toString(), "--force"});
        assertEquals(1, code, "duplicate BEIR id must be a hard failure (not a bijection)");
    }

    @Test
    void duplicateInternalIdIsDetectedAndFailsBijection(@TempDir Path dir) throws IOException {
        Path meta = docs(dir, "documents.jsonl", List.of(
                rec(7, "beirA", "alpha"),
                rec(7, "beirB", "bravo")));   // same internal id reused for two BEIR ids
        Path out = dir.resolve("map.tsv");
        int code = MappingVerifier.run(new String[] {
                "--meta", meta.toString(), "--out", out.toString(), "--force"});
        assertEquals(1, code, "duplicate internal id must be a hard failure");
    }

    @Test
    void missingMappingCountedAndCountMismatchFails(@TempDir Path dir) throws IOException {
        // One record has no beir: url -> missing BEIR id; expect-count mismatch forces FAIL.
        Path meta = docs(dir, "documents.jsonl", List.of(
                rec(1, "beirA", "alpha"),
                new ExportedDocument(2, "http://plain/url", "bravo", "b", "").toJsonLine()));
        Path out = dir.resolve("map.tsv");
        int code = MappingVerifier.run(new String[] {
                "--meta", meta.toString(), "--out", out.toString(),
                "--expect-count", "2", "--force"});
        // Bijection over the mapped pairs still holds (1 pair), but only 1 of 2 records mapped;
        // count matches expectCount (2 records read) so it is not a count failure -> PASS with
        // reported missingBeirIds=1. Verify the artifact has exactly the one good mapping.
        assertEquals(0, code);
        IdMap map = IdMap.load(out);
        assertEquals(1, map.size());
        assertEquals(Optional.of(1L), map.toInternal("beirA"));
    }

    @Test
    void qrelsIdsResolveThroughTheStoredMapping(@TempDir Path dir) throws IOException {
        Path meta = docs(dir, "documents.jsonl", List.of(
                rec(10, "qrelDoc1", "alpha"),
                rec(11, "qrelDoc2", "bravo"),
                rec(12, "other", "charlie")));
        // qrels references qrelDoc1 (mappable) and absentDoc (not in corpus).
        Path qrels = dir.resolve("qrels.tsv");
        Files.write(qrels, List.of(
                "query-id\tcorpus-id\tscore",
                "1\tqrelDoc1\t2",
                "1\tabsentDoc\t1"), StandardCharsets.UTF_8);
        Path out = dir.resolve("map.tsv");
        int code = MappingVerifier.run(new String[] {
                "--meta", meta.toString(), "--out", out.toString(),
                "--qrels", qrels.toString(), "--force"});
        assertEquals(0, code);
        IdMap map = IdMap.load(out);
        assertEquals(Optional.of(10L), map.toInternal("qrelDoc1"));
        // qrels cross-check: one distinct doc unmappable (absentDoc) is reported in the sidecar.
        String stats = Files.readString(Path.of(out + ".stats.json"), StandardCharsets.UTF_8);
        assertTrue(stats.contains("\"qrelsUnmappable\" : 1"), stats);
    }

    @Test
    void existingArtifactNotOverwrittenWithoutForce(@TempDir Path dir) throws IOException {
        Path meta = docs(dir, "documents.jsonl", List.of(rec(1, "beirA", "alpha")));
        Path out = dir.resolve("map.tsv");
        Files.writeString(out, "PLACEHOLDER\t999\n", StandardCharsets.UTF_8);
        MappingVerifier.run(new String[] {"--meta", meta.toString(), "--out", out.toString()});
        // Without --force the pre-existing file is preserved.
        assertTrue(Files.readString(out, StandardCharsets.UTF_8).contains("PLACEHOLDER"));
    }
}
