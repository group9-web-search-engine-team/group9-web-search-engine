package com.group9.websearch.index;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * One normalised document record exported for indexing, serialised as a single JSON Lines
 * record (exactly one physical line, so Hadoop's line-oriented splitting delivers it intact).
 *
 * <p>Jackson handles all escaping, so newlines, tabs, quotes, Unicode and punctuation inside
 * any field (title/body/url) cannot break the record boundary or lose the document id.
 *
 * <p>Fields: {@code docId} (identity), {@code url}, {@code title}, {@code body} (extracted
 * text used for the body index), and {@code s3Key} (traceability). title/url are retained for
 * later ranking features even though the first body index only tokenises {@code body}.
 */
public record ExportedDocument(
        @JsonProperty("docId") long docId,
        @JsonProperty("url") String url,
        @JsonProperty("title") String title,
        @JsonProperty("body") String body,
        @JsonProperty("s3Key") String s3Key) {

    @JsonCreator
    public ExportedDocument {
        // Jackson uses this canonical constructor via @JsonCreator; no extra validation here
        // so malformed-record handling stays in the reader/mapper with a documented policy.
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Serialise to a single-line JSON string (no embedded newlines). */
    public String toJsonLine() {
        try {
            String json = MAPPER.writeValueAsString(this);
            // Defensive: Jackson does not emit raw newlines in default mode, but guarantee
            // one physical line regardless.
            return json.replace("\r", "").replace("\n", " ");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise ExportedDocument " + docId, e);
        }
    }

    /** Parse one JSONL record. Throws on malformed JSON (callers count/skip per policy). */
    public static ExportedDocument fromJsonLine(String line) {
        try {
            return MAPPER.readValue(line, ExportedDocument.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("malformed JSONL document record", e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
