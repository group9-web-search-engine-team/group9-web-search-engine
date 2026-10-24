package com.group9.websearch.index.mapreduce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.ExportedDocument;
import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.Posting;
import com.group9.websearch.index.PostingList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.group9.websearch.index.HadoopLocalFsSupport;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full SPIMI MapReduce job (mapper -> shuffle/sort -> reducer) in-process via
 * Hadoop's LocalJobRunner over the local filesystem. No live YARN/HDFS cluster is required.
 * Skipped cleanly when the Hadoop native local-FS layer is unavailable (e.g. a bare Windows
 * dev box without winutils); runs on the WSL2/Linux Hadoop runtime.
 */
class SpimiJobLocalTest {

    @BeforeEach
    void requireHadoopLocalFs() {
        Assumptions.assumeTrue(HadoopLocalFsSupport.hadoopLocalFsUsable(),
                "Hadoop local FS native layer unavailable; runs on the WSL2/Linux Hadoop runtime.");
    }

    private static Configuration localConf() {
        Configuration conf = new Configuration();
        conf.set("mapreduce.framework.name", "local");
        conf.set("fs.defaultFS", "file:///");
        // Keep the local runner single-threaded and quiet.
        conf.set("mapreduce.jobtracker.address", "local");
        return conf;
    }

    private static void writeJsonl(Path file, ExportedDocument... docs) throws IOException {
        List<String> lines = new ArrayList<>();
        for (ExportedDocument d : docs) {
            lines.add(d.toJsonLine());
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Read the whole produced index into term -> serialized positional postings map. */
    private static Map<String, String> readIndex(Path outDir) throws IOException {
        Map<String, String> index = new HashMap<>();
        try (Stream<Path> parts = Files.list(outDir)) {
            for (Path p : parts.filter(x -> x.getFileName().toString().startsWith("part-")).toList()) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    IndexRecordCodec.DecodedRecord rec = IndexRecordCodec.decode(line);
                    // A term must never be split across part files.
                    assertFalse(index.containsKey(rec.term()),
                            "term appears in more than one part file: " + rec.term());
                    index.put(rec.term(), rec.postings().serialize());
                }
            }
        }
        return index;
    }

    private IndexJob.Params params(Path in, Path out, int reducers) {
        IndexJob.Params p = new IndexJob.Params();
        p.inputPath = in.toUri().toString();
        p.outputPath = out.toUri().toString();
        p.numReducers = reducers;
        p.spimiMaxEntries = 1_000_000L;
        return p;
    }

    @Test
    void endToEndSingleReducerPositional(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("docs.jsonl");
        Path out = tmp.resolve("index");
        // Empty title/url so we isolate body positions in the assertions.
        writeJsonl(in,
                new ExportedDocument(1L, "", "", "the cat sat the cat", "k1"),
                new ExportedDocument(2L, "", "", "the dog ran", "k2"));

        int code = IndexJob.run(localConf(), params(in, out, 1));
        assertEquals(0, code);
        assertTrue(Files.exists(out.resolve("_SUCCESS")));

        Map<String, String> index = readIndex(out);
        // body positions (zero-based): doc1 "the cat sat the cat" -> the@0,3 cat@1,4 sat@2
        //                              doc2 "the dog ran"         -> the@0 dog@1 ran@2
        assertEquals("1:b0-3,2:b0", index.get("the"));
        assertEquals("1:b1-4", index.get("cat"));
        assertEquals("2:b1", index.get("dog"));
        assertEquals("2:b2", index.get("ran"));
        assertEquals("1:b2", index.get("sat"));
    }

    @Test
    void indexesTitleUrlAndBodyAsSeparateFields(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("docs.jsonl");
        Path out = tmp.resolve("indexFields");
        // "cat" appears in title@0 and body@0,2 ; url token "cat" at position 0.
        writeJsonl(in,
                new ExportedDocument(1L, "http://cat", "cat", "cat sat cat", "k"));
        assertEquals(0, IndexJob.run(localConf(), params(in, out, 1)));
        Map<String, String> index = readIndex(out);
        // url "http://cat" tokenizes to [http, cat]; title [cat]; body [cat, sat, cat]
        // field order in serialization: title(t), url(u), body(b)
        assertEquals("1:t0;u1;b0-2", index.get("cat"));
        assertEquals("1:u0", index.get("http"));
        assertEquals("1:b1", index.get("sat"));
    }

    @Test
    void malformedRecordsAreSkippedNotFatal(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("docs.jsonl");
        Path out = tmp.resolve("index3");
        // one good record, one malformed line, one blank line
        List<String> lines = List.of(
                new ExportedDocument(1L, "", "", "hello world", "k").toJsonLine(),
                "this is not json",
                "",
                new ExportedDocument(2L, "", "", "hello", "k").toJsonLine());
        Files.write(in, lines, StandardCharsets.UTF_8);

        int code = IndexJob.run(localConf(), params(in, out, 1));
        assertEquals(0, code);
        Map<String, String> index = readIndex(out);
        assertEquals("1:b0,2:b0", index.get("hello"));
        assertEquals("1:b1", index.get("world"));
    }

    @Test
    void emptyCorpusProducesEmptyIndexWithSuccess(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("empty.jsonl");
        Files.write(in, List.of());
        Path out = tmp.resolve("index4");
        int code = IndexJob.run(localConf(), params(in, out, 1));
        assertEquals(0, code);
        assertTrue(Files.exists(out.resolve("_SUCCESS")));
        assertTrue(readIndex(out).isEmpty());
    }

    @Test
    void existingOutputDirFailsWithoutOverwrite(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("docs.jsonl");
        writeJsonl(in, new ExportedDocument(1L, "u", "t", "a", "k"));
        Path out = tmp.resolve("index5");
        Files.createDirectories(out); // pre-existing output dir

        IndexJob.Params p = params(in, out, 1);
        assertThrows(IllegalStateException.class, () -> IndexJob.configure(localConf(), p));

        p.overwrite = true;
        int code = IndexJob.run(localConf(), p);
        assertEquals(0, code);
    }

    @Test
    void duplicateTermDocAcrossBlocksAggregated(@TempDir Path tmp) throws Exception {
        // Force many small SPIMI blocks so one doc's term flushes in several blocks, then
        // verify the reducer unions the positions correctly (no loss, no duplication).
        Path in = tmp.resolve("docs.jsonl");
        writeJsonl(in, new ExportedDocument(1L, "", "", "x x x x x", "k"));
        Path out = tmp.resolve("index6");
        IndexJob.Params p = params(in, out, 1);
        p.spimiMaxEntries = 2; // tiny budget -> frequent flushes
        int code = IndexJob.run(localConf(), p);
        assertEquals(0, code);
        // Single doc, body positions 0..4 regardless of how blocks flushed.
        assertEquals("1:b0-1-2-3-4", readIndex(out).get("x"));
    }

    @Test
    void postingsWithinTermAreDocIdOrdered(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("docs.jsonl");
        writeJsonl(in,
                new ExportedDocument(30L, "", "", "term", "k"),
                new ExportedDocument(10L, "", "", "term", "k"),
                new ExportedDocument(20L, "", "", "term", "k"));
        Path out = tmp.resolve("index7");
        assertEquals(0, IndexJob.run(localConf(), params(in, out, 1)));
        PostingList pl = PostingList.parse(readIndex(out).get("term"));
        List<Long> docs = pl.postings().stream().map(Posting::docId).toList();
        assertEquals(List.of(10L, 20L, 30L), docs);
    }
}
