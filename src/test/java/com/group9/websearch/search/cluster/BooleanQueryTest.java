package com.group9.websearch.search.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.search.cluster.BooleanQuery.Node;
import com.group9.websearch.search.cluster.BooleanQuery.Op;
import org.junit.jupiter.api.Test;

/**
 * Parser + precedence tests (no network). Verifies the MANDATED precedence
 * NOT &gt; AND &gt; OR &gt; NEAR/n is encoded by the grammar's tree shape, plus parentheses
 * override.
 */
class BooleanQueryTest {

    private static Node root(String q) {
        return BooleanQuery.parse(q).root();
    }

    @Test
    void detectsBooleanExpressions() {
        assertTrue(BooleanQuery.isBooleanExpression("a AND b"));
        assertTrue(BooleanQuery.isBooleanExpression("a OR b"));
        assertTrue(BooleanQuery.isBooleanExpression("a NOT b"));
        assertTrue(BooleanQuery.isBooleanExpression("a NEAR/5 b"));
        assertTrue(BooleanQuery.isBooleanExpression("(a b) OR c"));
        assertFalse(BooleanQuery.isBooleanExpression("coronavirus infection"));
        assertFalse(BooleanQuery.isBooleanExpression("single"));
    }

    @Test
    void andBindsTighterThanOr() {
        // a OR b AND c  ==  a OR (b AND c)
        Node r = root("a OR b AND c");
        assertEquals(Op.OR, r.op);
        assertEquals(Op.TERM, r.left.op);
        assertEquals("a", r.left.term);
        assertEquals(Op.AND, r.right.op);
    }

    @Test
    void notBindsTighterThanAnd() {
        // NOT a AND b  ==  (NOT a) AND b
        Node r = root("NOT a AND b");
        assertEquals(Op.AND, r.op);
        assertEquals(Op.NOT, r.left.op);
        assertEquals("a", r.left.left.term);
        assertEquals("b", r.right.term);
    }

    @Test
    void nearIsLowestPrecedence() {
        // a AND b NEAR/3 c  ==  (a AND b) NEAR/3 c
        Node r = root("a AND b NEAR/3 c");
        assertEquals(Op.NEAR, r.op);
        assertEquals(3, r.window);
        assertEquals(Op.AND, r.left.op);
        assertEquals(Op.TERM, r.right.op);
        assertEquals("c", r.right.term);
    }

    @Test
    void binaryNotDesugarsToAndNot() {
        // "coronavirus NOT animal"  ==  coronavirus AND (NOT animal)
        Node r = root("coronavirus NOT animal");
        assertEquals(Op.AND, r.op);
        assertEquals("coronavirus", r.left.term);
        assertEquals(Op.NOT, r.right.op);
        assertEquals("animal", r.right.left.term);
    }

    @Test
    void allFourOperatorsRespectPrecedence() {
        // coronavirus OR infection AND NOT animal NEAR/5 treatment
        //  precedence: NOT > AND > OR > NEAR
        //  == ( coronavirus OR ( infection AND (NOT animal) ) ) NEAR/5 treatment
        Node r = root("coronavirus OR infection AND NOT animal NEAR/5 treatment");
        assertEquals(Op.NEAR, r.op, "NEAR is outermost (lowest precedence)");
        assertEquals(5, r.window);
        assertEquals("treatment", r.right.term);
        Node orNode = r.left;
        assertEquals(Op.OR, orNode.op, "OR below NEAR");
        assertEquals("coronavirus", orNode.left.term);
        Node andNode = orNode.right;
        assertEquals(Op.AND, andNode.op, "AND below OR");
        assertEquals("infection", andNode.left.term);
        assertEquals(Op.NOT, andNode.right.op, "NOT tightest");
        assertEquals("animal", andNode.right.left.term);
    }

    @Test
    void parenthesesOverridePrecedence() {
        // (a OR b) AND c  -- parentheses force OR under AND
        Node r = root("(a OR b) AND c");
        assertEquals(Op.AND, r.op);
        assertEquals(Op.OR, r.left.op);
        assertEquals("c", r.right.term);
    }

    @Test
    void plainMultiTermIsImplicitOr() {
        Node r = root("coronavirus infection");
        assertEquals(Op.OR, r.op);
        assertEquals("coronavirus", r.left.term);
        assertEquals("infection", r.right.term);
    }
}
