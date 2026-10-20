package com.group9.websearch.crawler.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DocumentRecordTest {

    @Test
    void acceptsValidRecord() {
        DocumentRecord doc = new DocumentRecord(
                "https://example.com/a", 200, 10L, "crawl/raw/k", Instant.now());
        assertEquals("https://example.com/a", doc.url());
        assertEquals(200, doc.status());
        assertEquals(10L, doc.contentLength());
        assertEquals("crawl/raw/k", doc.s3Key());
    }

    @Test
    void acceptsNullableFields() {
        DocumentRecord doc = new DocumentRecord("https://example.com/a", null, null, null, null);
        assertEquals("https://example.com/a", doc.url());
    }

    @Test
    void rejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentRecord("  ", null, null, null, null));
    }

    @Test
    void rejectsNullUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentRecord(null, null, null, null, null));
    }
}
