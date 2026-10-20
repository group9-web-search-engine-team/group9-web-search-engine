package com.group9.websearch.seeds;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the BEIR seed-generation utility.
 *
 * <p>Reads a BEIR-style {@code corpus.jsonl} file, extracts and normalizes
 * {@code metadata.url} values, deduplicates them, and writes the unique seeds to a new
 * local output file. It never touches S3 or any existing seed object.
 *
 * <pre>
 * Usage:
 *   java -cp target/classes com.group9.websearch.seeds.SeedGeneratorCli &lt;input-corpus.jsonl&gt; &lt;output-seeds.txt&gt;
 * </pre>
 */
public final class SeedGeneratorCli {

    private static final Logger LOG = LoggerFactory.getLogger(SeedGeneratorCli.class);

    private SeedGeneratorCli() {
        // entry-point holder
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Testable run method: parses args, validates paths, streams the corpus and reports.
     *
     * @return process exit code (0 success, 2 usage error, 1 runtime failure)
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            printUsage(err);
            return 2;
        }

        Path inputPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);

        if (!Files.exists(inputPath) || !Files.isRegularFile(inputPath)) {
            err.println("ERROR: input corpus file not found or not a regular file: " + inputPath);
            printUsage(err);
            return 2;
        }

        Path inputAbs = inputPath.toAbsolutePath().normalize();
        Path outputAbs = outputPath.toAbsolutePath().normalize();
        if (inputAbs.equals(outputAbs)) {
            err.println("ERROR: output path must differ from input path: " + outputAbs);
            return 2;
        }

        LOG.info("Seed generation starting. input={} output={}", inputAbs, outputAbs);

        SeedGenerator generator = new SeedGenerator();
        SeedExtractionStats stats;
        try {
            Path parent = outputAbs.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                stats = generator.generate(reader, writer);
            }
        } catch (IOException e) {
            LOG.error("Seed generation failed due to an I/O error", e);
            err.println("ERROR: I/O failure during seed generation: " + e.getMessage());
            return 1;
        }

        reportSummary(out, stats, outputAbs);
        LOG.info("Seed generation complete. {}", stats);
        return 0;
    }

    private static void reportSummary(PrintStream out, SeedExtractionStats stats, Path outputAbs) {
        out.println("==================== Seed Generation Summary ====================");
        out.println("Documents processed     : " + stats.documentsProcessed());
        out.println("Malformed JSON lines    : " + stats.malformedJsonLines());
        out.println("URLs found (valid)      : " + stats.urlsFound());
        out.println("Invalid URLs skipped    : " + stats.invalidUrlsSkipped());
        out.println("  - missing metadata.url: " + stats.missingUrl());
        out.println("  - blank url           : " + stats.blankUrl());
        out.println("  - unsupported/invalid : " + stats.unsupportedOrInvalidUrl());
        out.println("Duplicate URLs skipped  : " + stats.duplicateUrls());
        out.println("Unique URLs written     : " + stats.uniqueUrlsWritten());
        out.println("Output file             : " + outputAbs);
        out.println("=================================================================");
    }

    private static void printUsage(PrintStream err) {
        err.println();
        err.println("BEIR seed-generation utility");
        err.println("Usage:");
        err.println("  SeedGeneratorCli <input-corpus.jsonl> <output-seeds.txt>");
        err.println();
        err.println("Arguments:");
        err.println("  <input-corpus.jsonl>  Path to a BEIR-style corpus.jsonl file (read streaming).");
        err.println("  <output-seeds.txt>    Path to a NEW local output file for unique seed URLs.");
        err.println();
        err.println("Notes:");
        err.println("  - Only http/https URLs are kept; fragments are removed.");
        err.println("  - The output path must differ from the input path.");
        err.println("  - This tool writes locally only; it never modifies S3 seed objects.");
        err.println();
    }
}
