package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockMergerTest {

    private static BufferedReader run(String content) {
        return new BufferedReader(new StringReader(content));
    }

    @Test
    void mergesTwoSortedBlocks() throws Exception {
        // block1 terms: apple, cherry ; block2 terms: apple, banana (positional postings)
        String b1 = "apple\t1:b0\ncherry\t3:b0\n";
        String b2 = "apple\t2:b0\nbanana\t4:b0\n";
        StringWriter out = new StringWriter();
        BlockMerger.merge(List.of(run(b1), run(b2)), out);
        String[] lines = out.toString().split("\n");
        // apple appears in both -> merged by docId; result sorted lexicographically
        assertEquals("apple\t1:b0,2:b0", lines[0]);
        assertEquals("banana\t4:b0", lines[1]);
        assertEquals("cherry\t3:b0", lines[2]);
        assertEquals(3, lines.length);
    }

    @Test
    void unionsPositionsForDuplicateTermDocField() throws Exception {
        // same (term, docId, field) in two blocks -> positions unioned, not lost/duplicated
        String b1 = "term\t5:b0\n";
        String b2 = "term\t5:b3\n";
        StringWriter out = new StringWriter();
        BlockMerger.merge(List.of(run(b1), run(b2)), out);
        assertEquals("term\t5:b0-3", out.toString().trim());
    }

    @Test
    void keepsFieldsSeparateWhenMerging() throws Exception {
        String b1 = "term\t5:t0\n";
        String b2 = "term\t5:b2\n";
        StringWriter out = new StringWriter();
        BlockMerger.merge(List.of(run(b1), run(b2)), out);
        // title and body positions stay in separate field groups
        assertEquals("term\t5:t0;b2", out.toString().trim());
    }

    @Test
    void docIdsOrderedInMergedPostings() throws Exception {
        String b1 = "t\t9:b0\n";
        String b2 = "t\t1:b0\n";
        String b3 = "t\t5:b0\n";
        StringWriter out = new StringWriter();
        BlockMerger.merge(List.of(run(b1), run(b2), run(b3)), out);
        assertEquals("t\t1:b0,5:b0,9:b0", out.toString().trim());
    }

    @Test
    void mergeThreeBlocksWithDisjointAndOverlappingTerms() throws Exception {
        String b1 = "a\t1:b0\nm\t2:b0\nz\t3:b0\n";
        String b2 = "a\t4:b0\nn\t5:b0\n";
        String b3 = "m\t6:b0\nz\t7:b0\n";
        StringWriter out = new StringWriter();
        BlockMerger.merge(List.of(run(b1), run(b2), run(b3)), out);
        String[] lines = out.toString().split("\n");
        assertEquals("a\t1:b0,4:b0", lines[0]);
        assertEquals("m\t2:b0,6:b0", lines[1]);
        assertEquals("n\t5:b0", lines[2]);
        assertEquals("z\t3:b0,7:b0", lines[3]);
    }

    @Test
    void emptyInputsProduceNoOutput() throws Exception {
        StringWriter out = new StringWriter();
        BlockMerger.merge(List.of(run(""), run("")), out);
        assertEquals("", out.toString());
    }
}
