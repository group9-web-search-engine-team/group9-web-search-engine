package com.group9.websearch.search;

import com.group9.websearch.index.ExportedDocument;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only document metadata store keyed by docId, used to render results (URL, title,
 * snippet) and to provide the document universe for NOT queries.
 *
 * <p>Source of truth is the same exported JSONL the indexer consumed
 * ({@link ExportedDocument}: docId, url, title, body, s3Key). Reusing the export means the
 * online engine needs no live database or crawl to show results, and the metadata is exactly
 * the text that was indexed (so snippets reflect the indexed body). This is the project's
 * existing data-access artifact for the indexing path; a future enhancement could instead read
 * metadata (and PageRank/quality) from PostgreSQL via the crawler's persistence layer.
 *
 * <p>PageRank and quality scores are intentionally NOT loaded here: the crawler slice does not
 * populate {@code documents.pagerank}, so that signal is genuinely unavailable and must not be
 * fabricated. The feature extractor treats it as absent.
 */
public final class DocumentStore {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentStore.class);

    private final Map<Long, ExportedDocument> byId;
    private final long malformedLines;

    private DocumentStore(Map<Long, ExportedDocument> byId, long malformedLines) {
        this.byId = byId;
        this.malformedLines = malformedLines;
    }

    public Optional<ExportedDocument> get(long docId) {
        return Optional.ofNullable(byId.get(docId));
    }

    /** The full set of known docIds (the universe for NOT queries). */
    public Set<Long> universe() {
        return Collections.unmodifiableSet(byId.keySet());
    }

    public int size() {
        return byId.size();
    }

    public long malformedLines() {
        return malformedLines;
    }

    /** Load a JSONL export ({@link ExportedDocument} per line). Malformed lines are skipped. */
    public static DocumentStore loadJsonl(Path jsonlPath) throws IOException {
        if (!Files.isRegularFile(jsonlPath)) {
            throw new IOException("metadata JSONL not found: " + jsonlPath);
        }
        Map<Long, ExportedDocument> byId = new HashMap<>();
        long malformed = 0L;
        try (BufferedReader br = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    ExportedDocument doc = ExportedDocument.fromJsonLine(line);
                    byId.put(doc.docId(), doc);
                } catch (IllegalArgumentException e) {
                    malformed++;
                }
            }
        }
        LOG.info("Loaded {} document(s) from {}, {} malformed line(s) skipped",
                byId.size(), jsonlPath, malformed);
        return new DocumentStore(byId, malformed);
    }

    /** An empty store (results will show docId only; NOT has no universe). */
    public static DocumentStore empty() {
        return new DocumentStore(new HashMap<>(), 0L);
    }
}
