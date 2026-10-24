package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PostingListTest {

    @Test
    void postingSerializeRoundTripSingleField() {
        Posting p = new Posting(42L);
        p.addPosition(HitType.BODY, 0);
        p.addPosition(HitType.BODY, 3);
        assertEquals("42:b0-3", p.serialize());
        assertEquals(p, Posting.parse("42:b0-3"));
        assertEquals(2, p.termFrequency(HitType.BODY));
    }

    @Test
    void postingMultipleFieldsDeterministicOrder() {
        Posting p = new Posting(7L);
        p.addPosition(HitType.BODY, 2);
        p.addPosition(HitType.TITLE, 0);
        // Fields emitted in HitType declaration order: title(t) before body(b).
        assertEquals("7:t0;b2", p.serialize());
        assertEquals(p, Posting.parse("7:t0;b2"));
    }

    @Test
    void positionsAreSortedAndUnique() {
        Posting p = new Posting(1L);
        p.addPosition(HitType.BODY, 5);
        p.addPosition(HitType.BODY, 1);
        p.addPosition(HitType.BODY, 5); // duplicate collapses
        assertEquals(List.of(1, 5), p.positions(HitType.BODY));
        assertEquals(2, p.termFrequency(HitType.BODY));
    }

    @Test
    void postingRejectsNegativePositionAndDocId() {
        assertThrows(IllegalArgumentException.class, () -> new Posting(-1L));
        Posting p = new Posting(1L);
        assertThrows(IllegalArgumentException.class, () -> p.addPosition(HitType.BODY, -1));
    }

    @Test
    void postingRejectsMalformedToken() {
        assertThrows(IllegalArgumentException.class, () -> Posting.parse("nocolon"));
        assertThrows(IllegalArgumentException.class, () -> Posting.parse(":b0"));
        assertThrows(IllegalArgumentException.class, () -> Posting.parse("5:"));
        assertThrows(IllegalArgumentException.class, () -> Posting.parse("5:b")); // field, no positions
        assertThrows(IllegalArgumentException.class, () -> Posting.parse("5:z0")); // unknown field tag
    }

    @Test
    void addUnionsPositionsForSameDocAndField() {
        PostingList list = new PostingList();
        list.add(1L, HitType.BODY, 0);
        list.add(1L, HitType.BODY, 2);
        list.add(2L, HitType.BODY, 0);
        List<Posting> out = list.postings();
        assertEquals(2, out.size());
        assertEquals("1:b0-2", out.get(0).serialize());
        assertEquals("2:b0", out.get(1).serialize());
    }

    @Test
    void postingsOrderedByDocId() {
        PostingList list = new PostingList();
        list.add(5L, HitType.BODY, 0);
        list.add(1L, HitType.BODY, 0);
        list.add(3L, HitType.BODY, 0);
        assertEquals(List.of(1L, 3L, 5L),
                list.postings().stream().map(Posting::docId).toList());
    }

    @Test
    void serializeParseRoundTrip() {
        PostingList list = new PostingList();
        list.add(1L, HitType.TITLE, 0);
        list.add(1L, HitType.BODY, 2);
        list.add(7L, HitType.BODY, 1);
        String s = list.serialize();
        assertEquals("1:t0;b2,7:b1", s);
        PostingList parsed = PostingList.parse(s);
        assertEquals(s, parsed.serialize());
    }

    @Test
    void parseEmptyIsEmpty() {
        assertTrue(PostingList.parse("").isEmpty());
        assertTrue(PostingList.parse(null).isEmpty());
    }

    @Test
    void mergeUnionsPositionsPreservingFields() {
        PostingList a = new PostingList();
        a.add(1L, HitType.BODY, 0);
        a.add(2L, HitType.BODY, 0);
        PostingList b = new PostingList();
        b.add(2L, HitType.BODY, 1);      // same doc+field -> union
        b.add(2L, HitType.TITLE, 0);     // same doc, different field -> kept separate
        b.add(3L, HitType.BODY, 0);
        a.merge(b);
        assertEquals("1:b0,2:t0;b0-1,3:b0", a.serialize());
        assertEquals(3, a.documentFrequency());
    }
}
