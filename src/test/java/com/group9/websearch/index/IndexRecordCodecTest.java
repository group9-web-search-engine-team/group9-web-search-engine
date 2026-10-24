package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class IndexRecordCodecTest {

    private static PostingList postings(String s) {
        return PostingList.parse(s);
    }

    @Test
    void plainTermRoundTrip() {
        String line = IndexRecordCodec.encode("hello", postings("1:b0-1,3:t0"));
        assertEquals("hello\t1:b0-1,3:t0", line);
        IndexRecordCodec.DecodedRecord rec = IndexRecordCodec.decode(line);
        assertEquals("hello", rec.term());
        assertEquals("1:b0-1,3:t0", rec.postings().serialize());
    }

    @Test
    void termWithTabIsEscaped() {
        String term = "a\tb";
        String enc = IndexRecordCodec.encodeTerm(term);
        assertFalse(enc.contains("\t"), "encoded term must not contain a raw tab");
        assertEquals(term, IndexRecordCodec.decodeTerm(enc));
    }

    @Test
    void termWithNewlineCommaColonEscaped() {
        for (String term : new String[] {"a\nb", "a,b", "a:b", "a%b", "a b"}) {
            String enc = IndexRecordCodec.encodeTerm(term);
            assertFalse(enc.contains("\n"));
            assertFalse(enc.contains(","));
            assertFalse(enc.contains(":"));
            assertFalse(enc.contains(" "));
            assertEquals(term, IndexRecordCodec.decodeTerm(enc));
        }
    }

    @Test
    void unicodeTermRoundTrip() {
        for (String term : new String[] {"café", "日本語", "Привет", "😀emoji"}) {
            String line = IndexRecordCodec.encode(term, postings("1:b0"));
            IndexRecordCodec.DecodedRecord rec = IndexRecordCodec.decode(line);
            assertEquals(term, rec.term());
        }
    }

    @Test
    void fullRecordRoundTripWithTrickyTerm() {
        String term = "ni\tce,\n:weird";
        PostingList pl = new PostingList();
        pl.add(10L, HitType.BODY, 0);
        pl.add(10L, HitType.BODY, 2);
        pl.add(20L, HitType.TITLE, 0);
        String line = IndexRecordCodec.encode(term, pl);
        // exactly one tab separates term and postings (positional postings never add a tab)
        assertEquals(1, line.chars().filter(c -> c == '\t').count());
        IndexRecordCodec.DecodedRecord rec = IndexRecordCodec.decode(line);
        assertEquals(term, rec.term());
        assertEquals("10:b0-2,20:t0", rec.postings().serialize());
    }
}
