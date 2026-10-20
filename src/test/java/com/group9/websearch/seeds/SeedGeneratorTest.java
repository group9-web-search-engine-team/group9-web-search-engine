package com.group9.websearch.seeds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SeedGeneratorTest {

    private final SeedGenerator generator = new SeedGenerator();

    private SeedExtractionStats run(String corpus, StringWriter out) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(corpus))) {
            return generator.generate(reader, out);
        }
    }

    private List<String> lines(String written) {
        if (written.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(written.split("\n"));
    }

    @Test
    void extractsUrlFromWellFormedDocument() {
        SeedExtractionStats stats = new SeedExtractionStats();
        String line = "{\"_id\":\"a\",\"metadata\":{\"url\":\"https://example.com/x\"}}";
        Optional<String> url = generator.extractUrlFromLine(line, stats);
        assertEquals(Optional.of("https://example.com/x"), url);
        assertEquals(1, stats.documentsProcessed());
        assertEquals(1, stats.urlsFound());
    }

    @Test
    void deduplicatesEquivalentUrlsDeterministically() throws IOException {
        String corpus = String.join("\n",
            "{\"_id\":\"1\",\"metadata\":{\"url\":\"https://example.com/a\"}}",
            "{\"_id\":\"2\",\"metadata\":{\"url\":\"HTTPS://EXAMPLE.COM/a#frag\"}}",
            "{\"_id\":\"3\",\"metadata\":{\"url\":\"https://example.com:443/a\"}}",
            "{\"_id\":\"4\",\"metadata\":{\"url\":\"https://example.com/b\"}}");
        StringWriter out = new StringWriter();
        SeedExtractionStats stats = run(corpus, out);

        List<String> written = lines(out.toString());
        assertEquals(List.of("https://example.com/a", "https://example.com/b"), written);
        assertEquals(4, stats.documentsProcessed());
        assertEquals(4, stats.urlsFound());
        assertEquals(2, stats.uniqueUrlsWritten());
        assertEquals(2, stats.duplicateUrls());
    }

    @Test
    void skipsMissingMetadataAndMissingUrl() throws IOException {
        String corpus = String.join("\n",
            "{\"_id\":\"1\",\"title\":\"no metadata object\"}",
            "{\"_id\":\"2\",\"metadata\":{\"pubmed_id\":\"123\"}}",
            "{\"_id\":\"3\",\"metadata\":{\"url\":null}}",
            "{\"_id\":\"4\",\"metadata\":{\"url\":\"https://example.com/ok\"}}");
        StringWriter out = new StringWriter();
        SeedExtractionStats stats = run(corpus, out);

        assertEquals(4, stats.documentsProcessed());
        assertEquals(3, stats.missingUrl());
        assertEquals(1, stats.uniqueUrlsWritten());
        assertEquals(List.of("https://example.com/ok"), lines(out.toString()));
    }

    @Test
    void skipsBlankUrl() throws IOException {
        String corpus = String.join("\n",
            "{\"_id\":\"1\",\"metadata\":{\"url\":\"\"}}",
            "{\"_id\":\"2\",\"metadata\":{\"url\":\"   \"}}",
            "{\"_id\":\"3\",\"metadata\":{\"url\":\"https://example.com/ok\"}}");
        StringWriter out = new StringWriter();
        SeedExtractionStats stats = run(corpus, out);

        assertEquals(3, stats.documentsProcessed());
        assertEquals(2, stats.blankUrl());
        assertEquals(1, stats.uniqueUrlsWritten());
    }

    @Test
    void skipsUnsupportedAndInvalidUrls() throws IOException {
        String corpus = String.join("\n",
            "{\"_id\":\"1\",\"metadata\":{\"url\":\"ftp://example.com/f\"}}",
            "{\"_id\":\"2\",\"metadata\":{\"url\":\"mailto:x@example.com\"}}",
            "{\"_id\":\"3\",\"metadata\":{\"url\":\"/relative/only\"}}",
            "{\"_id\":\"4\",\"metadata\":{\"url\":\"http://exa mple.com/space\"}}",
            "{\"_id\":\"5\",\"metadata\":{\"url\":\"https://example.com/good\"}}");
        StringWriter out = new StringWriter();
        SeedExtractionStats stats = run(corpus, out);

        assertEquals(5, stats.documentsProcessed());
        assertEquals(4, stats.unsupportedOrInvalidUrl());
        assertEquals(1, stats.uniqueUrlsWritten());
        assertEquals(4, stats.invalidUrlsSkipped());
    }

    @Test
    void countsMalformedJsonLinesWithoutCrashing() throws IOException {
        String corpus = String.join("\n",
            "{\"_id\":\"1\",\"metadata\":{\"url\":\"https://example.com/a\"}}",
            "this is not json",
            "{\"_id\":\"2\", broken",
            "[1,2,3]",
            "{\"_id\":\"3\",\"metadata\":{\"url\":\"https://example.com/b\"}}");
        StringWriter out = new StringWriter();
        SeedExtractionStats stats = run(corpus, out);

        assertEquals(3, stats.malformedJsonLines());
        assertEquals(2, stats.documentsProcessed());
        assertEquals(2, stats.uniqueUrlsWritten());
        assertEquals(List.of("https://example.com/a", "https://example.com/b"), lines(out.toString()));
    }

    @Test
    void ignoresBlankLinesEntirely() throws IOException {
        String corpus = String.join("\n",
            "",
            "   ",
            "{\"_id\":\"1\",\"metadata\":{\"url\":\"https://example.com/a\"}}",
            "");
        StringWriter out = new StringWriter();
        SeedExtractionStats stats = run(corpus, out);

        assertEquals(1, stats.documentsProcessed());
        assertEquals(0, stats.malformedJsonLines());
        assertEquals(1, stats.uniqueUrlsWritten());
    }

    @Test
    void emptyCorpusProducesNoOutput() throws IOException {
        StringWriter out = new StringWriter();
        SeedExtractionStats stats = run("", out);
        assertEquals(0, stats.documentsProcessed());
        assertEquals(0, stats.uniqueUrlsWritten());
        assertTrue(out.toString().isEmpty());
    }
}
