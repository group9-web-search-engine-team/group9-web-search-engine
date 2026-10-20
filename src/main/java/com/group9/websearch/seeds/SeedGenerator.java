package com.group9.websearch.seeds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streaming extractor that reads a BEIR-style {@code corpus.jsonl} file one line at a
 * time, pulls {@code metadata.url} from each document, normalizes and deduplicates the
 * URLs, and writes the unique, canonical HTTP/HTTPS seeds to an output writer.
 *
 * <p>The corpus is processed line-by-line via a {@link BufferedReader}; the entire file
 * is never loaded into memory. Only the set of unique canonical URLs is retained, which
 * is bounded by the number of distinct URLs (far smaller than the raw corpus bytes).
 *
 * <p>Malformed JSON lines, missing/blank URLs and unsupported/invalid URLs are skipped
 * and counted rather than aborting the whole run.
 */
public final class SeedGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(SeedGenerator.class);

    /** Log progress every N documents so large runs show liveness without flooding logs. */
    private static final long PROGRESS_INTERVAL = 25_000L;

    private final ObjectMapper mapper;

    public SeedGenerator() {
        this(new ObjectMapper());
    }

    public SeedGenerator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Extract {@code metadata.url} from a single JSONL line and normalize it.
     *
     * <p>Outcomes (counted into {@code stats}):
     * <ul>
     *   <li>blank line → ignored, returns empty (no document counted)</li>
     *   <li>malformed JSON → counted as malformed, returns empty</li>
     *   <li>document counted; missing/blank/invalid url → counted, returns empty</li>
     *   <li>valid url → counted as found, returns the canonical URL</li>
     * </ul>
     *
     * @param line  a single line from the corpus file
     * @param stats the running tally to update
     * @return the canonical URL if one was successfully extracted and normalized
     */
    public Optional<String> extractUrlFromLine(String line, SeedExtractionStats stats) {
        if (line == null) {
            return Optional.empty();
        }
        String trimmedLine = line.trim();
        if (trimmedLine.isEmpty()) {
            // Blank lines are structural noise, not documents.
            return Optional.empty();
        }

        final JsonNode root;
        try {
            root = mapper.readTree(trimmedLine);
        } catch (IOException e) {
            stats.incrementMalformedJsonLines();
            return Optional.empty();
        }

        if (root == null || !root.isObject()) {
            // A JSON scalar/array on its own line is not a valid corpus document.
            stats.incrementMalformedJsonLines();
            return Optional.empty();
        }

        // From here on we have a document to account for.
        stats.incrementDocumentsProcessed();

        JsonNode metadata = root.get("metadata");
        if (metadata == null || !metadata.isObject()) {
            stats.incrementMissingUrl();
            return Optional.empty();
        }

        JsonNode urlNode = metadata.get("url");
        if (urlNode == null || urlNode.isNull() || !urlNode.isTextual()) {
            stats.incrementMissingUrl();
            return Optional.empty();
        }

        String rawUrl = urlNode.asText();
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            stats.incrementBlankUrl();
            return Optional.empty();
        }

        Optional<String> canonical = UrlNormalizer.normalize(rawUrl);
        if (canonical.isEmpty()) {
            stats.incrementUnsupportedOrInvalidUrl();
            return Optional.empty();
        }

        stats.incrementUrlsFound();
        return canonical;
    }

    /**
     * Stream the corpus from {@code reader}, writing one unique canonical seed URL per
     * line to {@code writer}. Deduplication is deterministic: the first occurrence of a
     * canonical URL is written in encounter order; later duplicates are counted and
     * skipped.
     *
     * @param reader source of corpus JSONL lines (caller owns closing it)
     * @param writer destination for seed URLs (caller owns closing it)
     * @return the final stats tally
     * @throws IOException if reading or writing fails
     */
    public SeedExtractionStats generate(BufferedReader reader, Writer writer) throws IOException {
        SeedExtractionStats stats = new SeedExtractionStats();
        // LinkedHashSet gives deterministic encounter-order output and O(1) dedup.
        Set<String> seen = new LinkedHashSet<>();

        String line;
        while ((line = reader.readLine()) != null) {
            Optional<String> canonical = extractUrlFromLine(line, stats);
            if (canonical.isPresent()) {
                String url = canonical.get();
                if (seen.add(url)) {
                    writer.write(url);
                    writer.write('\n');
                    stats.incrementUniqueUrlsWritten();
                } else {
                    stats.incrementDuplicateUrls();
                }
            }

            if (stats.documentsProcessed() > 0
                    && stats.documentsProcessed() % PROGRESS_INTERVAL == 0) {
                LOG.info("Progress: {} documents processed, {} unique URLs so far",
                        stats.documentsProcessed(), stats.uniqueUrlsWritten());
            }
        }

        writer.flush();
        return stats;
    }
}
