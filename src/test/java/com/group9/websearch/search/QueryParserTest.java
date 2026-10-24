package com.group9.websearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryParserTest {

    private final QueryParser parser = new QueryParser(8);

    @Test
    void defaultsToOrAndNormalisesLikeIndexer() {
        Query q = parser.parse("The Quick, BROWN fox!");
        assertEquals(Query.Mode.OR, q.mode());
        assertEquals(List.of("the", "quick", "brown", "fox"), q.terms());
    }

    @Test
    void blankQueryIsEmpty() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void andPrefix() {
        Query q = parser.parse("AND: cat dog");
        assertEquals(Query.Mode.AND, q.mode());
        assertEquals(List.of("cat", "dog"), q.terms());
    }

    @Test
    void notPrefixSplitsPositiveAndExcluded() {
        Query q = parser.parse("NOT: cat -dog -spam");
        assertEquals(Query.Mode.NOT, q.mode());
        assertEquals(List.of("cat"), q.terms());
        assertEquals(List.of("dog", "spam"), q.excludedTerms());
    }

    @Test
    void nearPrefixWithWindow() {
        Query q = parser.parse("NEAR/5: quick fox");
        assertEquals(Query.Mode.PROXIMITY, q.mode());
        assertEquals(5, q.proximityWindow());
        assertEquals(List.of("quick", "fox"), q.terms());
    }

    @Test
    void nearPrefixDefaultWindow() {
        Query q = parser.parse("NEAR: quick fox");
        assertEquals(Query.Mode.PROXIMITY, q.mode());
        assertEquals(8, q.proximityWindow());
    }

    @Test
    void unknownPrefixTreatedAsPlainText() {
        // "http://example.com cat" has a colon but "http" is not an operator.
        Query q = parser.parse("http://example.com cat");
        assertEquals(Query.Mode.OR, q.mode());
        assertTrue(q.terms().contains("cat"));
        assertTrue(q.terms().contains("http"));
    }
}
