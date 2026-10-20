package com.group9.websearch.seeds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeedGeneratorCliTest {

    private static PrintStream ps(ByteArrayOutputStream b) {
        return new PrintStream(b, true, StandardCharsets.UTF_8);
    }

    @Test
    void usageErrorWhenWrongArgCount(@TempDir Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SeedGeneratorCli.run(new String[]{"only-one"}, ps(out), ps(err));
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    @Test
    void errorWhenInputMissing(@TempDir Path dir) {
        Path input = dir.resolve("does-not-exist.jsonl");
        Path output = dir.resolve("seeds.txt");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SeedGeneratorCli.run(
                new String[]{input.toString(), output.toString()}, ps(out), ps(err));
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("not found"));
    }

    @Test
    void errorWhenInputEqualsOutput(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("corpus.jsonl");
        Files.writeString(input, "{\"_id\":\"1\",\"metadata\":{\"url\":\"https://example.com/a\"}}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SeedGeneratorCli.run(
                new String[]{input.toString(), input.toString()}, ps(out), ps(err));
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("must differ"));
    }

    @Test
    void endToEndWritesUniqueSeeds(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("corpus.jsonl");
        Files.writeString(input, String.join("\n",
            "{\"_id\":\"1\",\"metadata\":{\"url\":\"https://example.com/a\"}}",
            "{\"_id\":\"2\",\"metadata\":{\"url\":\"https://example.com/a#dup\"}}",
            "{\"_id\":\"3\",\"metadata\":{\"url\":\"ftp://example.com/skip\"}}",
            "{\"_id\":\"4\",\"metadata\":{\"url\":\"https://example.com/b\"}}",
            "") + "\n", StandardCharsets.UTF_8);
        Path output = dir.resolve("out").resolve("seeds.txt");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SeedGeneratorCli.run(
                new String[]{input.toString(), output.toString()}, ps(out), ps(err));

        assertEquals(0, code);
        assertTrue(Files.exists(output), "output file should be created (including parent dir)");
        List<String> seeds = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(List.of("https://example.com/a", "https://example.com/b"), seeds);

        String summary = out.toString(StandardCharsets.UTF_8);
        assertTrue(summary.contains("Unique URLs written     : 2"), summary);
        assertTrue(summary.contains("Duplicate URLs skipped  : 1"), summary);
    }

    @Test
    void doesNotWriteOutputEqualToInputFile(@TempDir Path dir) throws IOException {
        // Guard regression: ensure the original input is never truncated by same-path output.
        Path input = dir.resolve("corpus.jsonl");
        String original = "{\"_id\":\"1\",\"metadata\":{\"url\":\"https://example.com/a\"}}\n";
        Files.writeString(input, original);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        SeedGeneratorCli.run(new String[]{input.toString(), input.toString()},
                ps(new ByteArrayOutputStream()), ps(err));
        assertEquals(original, Files.readString(input));
        assertFalse(Files.readString(input).isEmpty());
    }
}
