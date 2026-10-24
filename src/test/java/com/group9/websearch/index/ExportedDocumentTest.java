package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExportedDocumentTest {

    @Test
    void jsonLineRoundTrip() {
        ExportedDocument doc = new ExportedDocument(
                99L, "https://example.com/p?q=1", "Title Here",
                "body text with words", "crawl/raw/smoke-test/abc.html");
        String line = doc.toJsonLine();
        assertFalse(line.contains("\n"), "JSONL record must be a single physical line");
        ExportedDocument back = ExportedDocument.fromJsonLine(line);
        assertEquals(doc, back);
    }

    @Test
    void specialCharactersSurviveRoundTrip() {
        ExportedDocument doc = new ExportedDocument(
                1L, "https://x.test",
                "Tab\tNewline\nQuote\"Backslash\\",
                "unicode café 日本語, comma: colon",
                "k");
        String line = doc.toJsonLine();
        assertFalse(line.contains("\n"));
        ExportedDocument back = ExportedDocument.fromJsonLine(line);
        assertEquals(doc.title(), back.title());
        assertEquals(doc.body(), back.body());
        assertEquals(doc.docId(), back.docId());
    }

    @Test
    void emptyBodyAllowed() {
        ExportedDocument doc = new ExportedDocument(2L, "u", "t", "", "k");
        ExportedDocument back = ExportedDocument.fromJsonLine(doc.toJsonLine());
        assertEquals("", back.body());
    }

    @Test
    void malformedLineThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ExportedDocument.fromJsonLine("not json at all"));
    }
}
