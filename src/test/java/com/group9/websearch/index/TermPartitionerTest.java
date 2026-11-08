package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Boundary and special-term coverage for the shared five-way term partitioner. */
class TermPartitionerTest {

    @Test
    void everyLowercaseLetterMapsToExpectedPartition() {
        for (char c = 'a'; c <= 'h'; c++) {
            assertEquals(0, TermPartitioner.partitionFor(c + "x"), "P1 for " + c);
        }
        for (char c = 'i'; c <= 'm'; c++) {
            assertEquals(1, TermPartitioner.partitionFor(c + "x"), "P2 for " + c);
        }
        for (char c = 'n'; c <= 's'; c++) {
            assertEquals(2, TermPartitioner.partitionFor(c + "x"), "P3 for " + c);
        }
        for (char c = 't'; c <= 'z'; c++) {
            assertEquals(3, TermPartitioner.partitionFor(c + "x"), "P4 for " + c);
        }
    }

    @Test
    void boundaryTermsAreCorrect() {
        assertEquals(0, TermPartitioner.partitionFor("h")); // last of P1
        assertEquals(1, TermPartitioner.partitionFor("i")); // first of P2
        assertEquals(1, TermPartitioner.partitionFor("m")); // last of P2
        assertEquals(2, TermPartitioner.partitionFor("n")); // first of P3
        assertEquals(2, TermPartitioner.partitionFor("s")); // last of P3
        assertEquals(3, TermPartitioner.partitionFor("t")); // first of P4
        assertEquals(3, TermPartitioner.partitionFor("z")); // last of P4
    }

    @Test
    void numbersSymbolsAndNonAsciiGoToP5() {
        assertEquals(4, TermPartitioner.partitionFor("123"));
        assertEquals(4, TermPartitioner.partitionFor("9lives"));
        assertEquals(4, TermPartitioner.partitionFor("\u00e9cole"));   // accented Latin 'é'
        assertEquals(4, TermPartitioner.partitionFor("\u4e2d\u6587")); // CJK
        assertEquals(4, TermPartitioner.partitionFor("\u0440\u0443"));  // Cyrillic
        assertEquals("P5", TermPartitioner.partitionIdFor("2019"));
    }

    @Test
    void caseIsNormalisedLikeTheTokenizer() {
        assertEquals(TermPartitioner.partitionFor("apple"),
                TermPartitioner.partitionFor("APPLE"));
        assertEquals(0, TermPartitioner.partitionFor("Zebra".substring(0, 0) + "Covid"));
        assertEquals(TermPartitioner.partitionFor("virus"),
                TermPartitioner.partitionFor("VIRUS"));
    }

    @Test
    void indexingAndRoutingAgreeForTokenizedTerms() {
        // Whatever the tokenizer emits must partition identically wherever it is used.
        for (String token : Tokenizer.tokenize("COVID-19 transmission Éxample 42 symbols!!")) {
            int viaString = TermPartitioner.partitionFor(token);
            int viaId = java.util.Arrays.asList(TermPartitioner.PARTITION_IDS)
                    .indexOf(TermPartitioner.partitionIdFor(token));
            assertEquals(viaString, viaId, "routing disagreement for '" + token + "'");
        }
    }

    @Test
    void emptyOrNullTermIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TermPartitioner.partitionFor(""));
        assertThrows(IllegalArgumentException.class, () -> TermPartitioner.partitionFor(null));
    }
}
