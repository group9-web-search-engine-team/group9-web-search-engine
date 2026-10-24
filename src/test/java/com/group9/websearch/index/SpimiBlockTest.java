package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpimiBlockTest {

    /** Build a term -> positions map from (term, position...) style varargs via a single list. */
    private static Map<String, List<Integer>> pos(Object... kv) {
        LinkedHashMap<String, List<Integer>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<Integer> positions = (List<Integer>) kv[i + 1];
            m.put((String) kv[i], positions);
        }
        return m;
    }

    @Test
    void flushSortedProducesLexicographicTermsWithDocOrder() throws Exception {
        SpimiBlock block = new SpimiBlock();
        // doc 2 body: positions for banana@1, apple@0,2
        block.addFieldPositions(2L, HitType.BODY, pos("banana", List.of(1), "apple", List.of(0, 2)));
        // doc 1 body: apple@0, cherry@1,2,3
        block.addFieldPositions(1L, HitType.BODY, pos("apple", List.of(0), "cherry", List.of(1, 2, 3)));
        StringWriter sw = new StringWriter();
        block.flushSorted(sw);
        String[] lines = sw.toString().split("\n");
        // terms sorted: apple, banana, cherry ; postings ordered by docId
        assertEquals("apple\t1:b0,2:b0-2", lines[0]);
        assertEquals("banana\t2:b1", lines[1]);
        assertEquals("cherry\t1:b1-2-3", lines[2]);
        assertEquals(3, lines.length);
    }

    @Test
    void shouldFlushRespectsThreshold() {
        SpimiBlock block = new SpimiBlock(4); // budget: 4 approx entries (new term + each position)
        assertFalse(block.shouldFlush());
        // 2 new terms (a,b) + 2 positions = 4 entries
        block.addFieldPositions(1L, HitType.BODY, pos("a", List.of(0), "b", List.of(1)));
        assertTrue(block.shouldFlush());
    }

    @Test
    void noLimitNeverFlushes() {
        SpimiBlock block = new SpimiBlock(); // NO_LIMIT
        block.addFieldPositions(1L, HitType.BODY,
                pos("a", List.of(0), "b", List.of(1), "c", List.of(2)));
        assertFalse(block.shouldFlush());
    }

    @Test
    void clearResetsBlock() {
        SpimiBlock block = new SpimiBlock(2);
        block.addFieldPositions(1L, HitType.BODY, pos("x", List.of(0)));
        assertFalse(block.isEmpty());
        block.clear();
        assertTrue(block.isEmpty());
        assertFalse(block.shouldFlush());
    }

    @Test
    void multipleFieldsForSameDocKeptSeparate() throws Exception {
        SpimiBlock block = new SpimiBlock();
        block.addFieldPositions(1L, HitType.TITLE, pos("x", List.of(0)));
        block.addFieldPositions(1L, HitType.BODY, pos("x", List.of(5, 9)));
        StringWriter sw = new StringWriter();
        block.flushSorted(sw);
        assertEquals("x\t1:t0;b5-9", sw.toString().trim());
    }

    @Test
    void multipleBlocksEachSelfContainedAfterClear() throws Exception {
        SpimiBlock block = new SpimiBlock();
        block.addFieldPositions(1L, HitType.BODY, pos("alpha", List.of(0)));
        StringWriter first = new StringWriter();
        block.flushSorted(first);
        block.clear();
        block.addFieldPositions(2L, HitType.BODY, pos("beta", List.of(0, 1)));
        StringWriter second = new StringWriter();
        block.flushSorted(second);
        assertEquals("alpha\t1:b0", first.toString().trim());
        assertEquals("beta\t2:b0-1", second.toString().trim());
    }
}
