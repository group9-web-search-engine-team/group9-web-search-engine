package com.group9.websearch.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Serializable BM25 corpus-statistics artifact: the completed, published summary of the final
 * merged index. It is written as a standalone JSON metadata file (e.g.
 * {@code stats/corpus-stats.json}); it is NOT part of the inverted index and never overwrites
 * index data.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code version} -- schema version so future changes are explicit.</li>
 *   <li>{@code complete} -- true only when the source index carried a {@code _SUCCESS} marker
 *       and every record decoded; BM25 refuses to consume an incomplete artifact.</li>
 *   <li>{@code numDocs} (N), {@code numTerms}, {@code totalTokens}, {@code avgDocLength} (Lave).</li>
 *   <li>{@code minDocLength}, {@code maxDocLength}, {@code medianDocLength} -- length spread.</li>
 *   <li>{@code docLengths} -- per-document indexed length dl(d), keyed by docId as a string
 *       (JSON object keys are strings). Needed by BM25's length normalisation. Written as a
 *       sorted map for deterministic output.</li>
 *   <li>{@code generatedAtEpochMs}, {@code indexPath} -- provenance.</li>
 * </ul>
 *
 * <p>Per-term document frequencies df(t) are intentionally NOT embedded here (there can be
 * millions of terms); they are written to a separate sidecar by the calculator and, at query
 * time, df(t) is read directly and exactly from each term's postings. This artifact carries
 * only what BM25 cannot recover cheaply from a single term's postings: N, dl(d) and Lave.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class CorpusStatsArtifact {

    /** Current artifact schema version. */
    public static final int SCHEMA_VERSION = 1;

    @JsonProperty("version") public int version = SCHEMA_VERSION;
    @JsonProperty("complete") public boolean complete;
    @JsonProperty("numDocs") public int numDocs;
    @JsonProperty("numTerms") public long numTerms;
    @JsonProperty("totalTokens") public long totalTokens;
    @JsonProperty("avgDocLength") public double avgDocLength;
    @JsonProperty("minDocLength") public long minDocLength;
    @JsonProperty("maxDocLength") public long maxDocLength;
    @JsonProperty("medianDocLength") public double medianDocLength;
    @JsonProperty("indexPath") public String indexPath;
    @JsonProperty("generatedAtEpochMs") public long generatedAtEpochMs;
    /** docId (as string key) -> indexed document length dl(d). */
    @JsonProperty("docLengths") public Map<String, Long> docLengths = new TreeMap<>();

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** Parse an artifact from JSON text. */
    public static CorpusStatsArtifact fromJson(String json) throws IOException {
        return MAPPER.readValue(json, CorpusStatsArtifact.class);
    }

    /** Load an artifact from a local JSON file. */
    public static CorpusStatsArtifact load(Path jsonPath) throws IOException {
        return fromJson(Files.readString(jsonPath, StandardCharsets.UTF_8));
    }

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Atomically publish this artifact to {@code target}: write to a temp file in the same
     * directory, then atomically move it into place. A concurrent reader therefore always sees
     * either the previous complete file or the new complete file -- never a partially written
     * one. This is the safe publish mechanism for a growing corpus.
     */
    public void publishAtomically(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = parent == null
                ? Path.of(target + ".tmp")
                : parent.resolve(target.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
