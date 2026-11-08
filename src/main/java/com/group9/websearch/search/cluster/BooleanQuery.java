package com.group9.websearch.search.cluster;

import com.group9.websearch.index.Tokenizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Infix Boolean + positional-proximity query grammar for DISTRIBUTED search, with the MANDATED
 * operator precedence (highest to lowest):
 *
 * <pre>
 *   NOT   (highest)
 *   AND
 *   OR
 *   NEAR/n (lowest)
 * </pre>
 *
 * This is deliberately NOT the single-node {@link com.group9.websearch.search.QueryParser}
 * precedence; that parser only understands one leading-mode prefix and is left untouched. This
 * class is a self-contained recursive-descent parser whose grammar levels encode the required
 * precedence directly (a lower-precedence operator is parsed by an OUTER level, so it binds more
 * loosely and is evaluated last):
 *
 * <pre>
 *   near   := or   ( NEAR/n  or   )*       // loosest -> outermost
 *   or     := and  ( OR      and  )*
 *   and    := not  ( AND     not  )*
 *   not    := NOT not | primary            // tightest unary -> innermost
 *   primary:= '(' near ')' | TERM          // parentheses override precedence
 * </pre>
 *
 * <p>The binary form {@code X NOT Y} (as in "coronavirus NOT animal") is accepted at the AND
 * level and desugared to {@code X AND (NOT Y)}, so both {@code A AND NOT B} and {@code A NOT B}
 * mean "documents matching A, excluding documents matching B".
 *
 * <p>Tokens are terms (normalised with the indexing {@link Tokenizer}, so routing/matching align
 * with the index). Operator keywords are matched case-insensitively; {@code NEAR/n} carries its
 * window. A bare space between two terms with no operator defaults to OR (preserving the existing
 * OR-query behaviour for plain multi-term queries).
 */
public final class BooleanQuery {

    /** Parsed expression AST node types. */
    public enum Op { TERM, NOT, AND, OR, NEAR }

    /** One AST node. For TERM, {@code term} is set. For NEAR, {@code window} is the n in NEAR/n. */
    public static final class Node {
        public final Op op;
        public final String term;        // TERM only
        public final int window;         // NEAR only
        public final Node left;
        public final Node right;         // null for NOT (unary) and TERM

        private Node(Op op, String term, int window, Node left, Node right) {
            this.op = op;
            this.term = term;
            this.window = window;
            this.left = left;
            this.right = right;
        }

        static Node term(String t) {
            return new Node(Op.TERM, t, 0, null, null);
        }

        static Node not(Node child) {
            return new Node(Op.NOT, null, 0, child, null);
        }

        static Node binary(Op op, Node l, Node r) {
            return new Node(op, null, 0, l, r);
        }

        static Node near(int window, Node l, Node r) {
            return new Node(Op.NEAR, null, window, l, r);
        }
    }

    /** All distinct TERM leaves in the AST (the terms that must be fetched from workers). */
    public Set<String> terms() {
        Set<String> out = new LinkedHashSet<>();
        collectTerms(root, out);
        return out;
    }

    private static void collectTerms(Node n, Set<String> out) {
        if (n == null) {
            return;
        }
        if (n.op == Op.TERM) {
            out.add(n.term);
        }
        collectTerms(n.left, out);
        collectTerms(n.right, out);
    }

    public Node root() {
        return root;
    }

    private final Node root;
    private final String raw;

    private BooleanQuery(Node root, String raw) {
        this.root = root;
        this.raw = raw;
    }

    public String raw() {
        return raw;
    }

    // ---- lexer ----

    private enum TokType { TERM, AND, OR, NOT, NEAR, LPAREN, RPAREN }

    private static final class Tok {
        final TokType type;
        final String text;   // TERM text
        final int window;    // NEAR window

        Tok(TokType type, String text, int window) {
            this.type = type;
            this.text = text;
            this.window = window;
        }
    }

    /**
     * Returns {@code true} if the raw query uses any Boolean/NEAR operator (or parentheses). A
     * plain multi-term query (no operators) is left to the existing OR path unchanged.
     */
    public static boolean isBooleanExpression(String raw) {
        if (raw == null) {
            return false;
        }
        for (String w : raw.trim().split("\\s+")) {
            String u = w.toUpperCase(Locale.ROOT);
            if (u.equals("AND") || u.equals("OR") || u.equals("NOT")
                    || u.equals("NEAR") || u.startsWith("NEAR/")
                    || w.contains("(") || w.contains(")")) {
                return true;
            }
        }
        return false;
    }

    private static List<Tok> lex(String raw) {
        List<Tok> toks = new ArrayList<>();
        if (raw == null) {
            return toks;
        }
        // Split on whitespace but keep parentheses as separate tokens.
        for (String wsChunk : raw.trim().split("\\s+")) {
            if (wsChunk.isEmpty()) {
                continue;
            }
            // Peel leading/trailing parentheses so "(a" and "b)" lex correctly.
            int i = 0;
            while (i < wsChunk.length() && wsChunk.charAt(i) == '(') {
                toks.add(new Tok(TokType.LPAREN, null, 0));
                i++;
            }
            int j = wsChunk.length();
            int trailing = 0;
            while (j > i && wsChunk.charAt(j - 1) == ')') {
                trailing++;
                j--;
            }
            String core = wsChunk.substring(i, j);
            if (!core.isEmpty()) {
                toks.add(classify(core));
            }
            for (int k = 0; k < trailing; k++) {
                toks.add(new Tok(TokType.RPAREN, null, 0));
            }
        }
        return toks;
    }

    private static Tok classify(String word) {
        String u = word.toUpperCase(Locale.ROOT);
        switch (u) {
            case "AND":
                return new Tok(TokType.AND, null, 0);
            case "OR":
                return new Tok(TokType.OR, null, 0);
            case "NOT":
                return new Tok(TokType.NOT, null, 0);
            case "NEAR":
                return new Tok(TokType.NEAR, null, 1);
            default:
                break;
        }
        if (u.startsWith("NEAR/")) {
            int window = 1;
            try {
                window = Math.max(1, Integer.parseInt(word.substring(5).trim()));
            } catch (NumberFormatException ignore) {
                window = 1;
            }
            return new Tok(TokType.NEAR, null, window);
        }
        // Ordinary term: normalise with the indexing tokenizer. A chunk that tokenises to
        // multiple tokens (rare, e.g. punctuation-joined) keeps only the first; empty -> skipped.
        List<String> norm = Tokenizer.tokenize(word);
        return new Tok(TokType.TERM, norm.isEmpty() ? "" : norm.get(0), 0);
    }

    // ---- recursive-descent parser (grammar levels encode precedence) ----

    private static final class Parser {
        private final List<Tok> toks;
        private int pos;

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        Node parse() {
            Node n = parseNear();
            return n;
        }

        private Tok peek() {
            return pos < toks.size() ? toks.get(pos) : null;
        }

        private boolean hasTermLike() {
            Tok t = peek();
            return t != null && (t.type == TokType.TERM || t.type == TokType.LPAREN
                    || t.type == TokType.NOT);
        }

        // near := or ( NEAR/n or )*   -- lowest precedence, outermost
        private Node parseNear() {
            Node left = parseOr();
            while (peek() != null && peek().type == TokType.NEAR) {
                int window = peek().window;
                pos++;
                Node right = parseOr();
                left = Node.near(window, left, right);
            }
            return left;
        }

        // or := and ( OR and )*  (also implicit OR when two operands are adjacent)
        private Node parseOr() {
            Node left = parseAnd();
            while (peek() != null) {
                if (peek().type == TokType.OR) {
                    pos++;
                    Node right = parseAnd();
                    left = Node.binary(Op.OR, left, right);
                } else if (hasTermLike()) {
                    // Implicit OR between adjacent operands (preserves plain multi-term OR).
                    Node right = parseAnd();
                    left = Node.binary(Op.OR, left, right);
                } else {
                    break;
                }
            }
            return left;
        }

        // and := not ( AND not | NOT not )*   -- binary NOT desugars to AND (NOT y)
        private Node parseAnd() {
            Node left = parseNot();
            while (peek() != null && (peek().type == TokType.AND || peek().type == TokType.NOT)) {
                TokType t = peek().type;
                pos++;
                Node right = parseNot();
                if (t == TokType.NOT) {
                    left = Node.binary(Op.AND, left, Node.not(right));
                } else {
                    left = Node.binary(Op.AND, left, right);
                }
            }
            return left;
        }

        // not := NOT not | primary   -- highest precedence (tightest), innermost
        private Node parseNot() {
            if (peek() != null && peek().type == TokType.NOT) {
                pos++;
                return Node.not(parseNot());
            }
            return parsePrimary();
        }

        // primary := '(' near ')' | TERM
        private Node parsePrimary() {
            Tok t = peek();
            if (t == null) {
                return null;
            }
            if (t.type == TokType.LPAREN) {
                pos++;
                Node inner = parseNear();
                if (peek() != null && peek().type == TokType.RPAREN) {
                    pos++;
                }
                return inner;
            }
            if (t.type == TokType.TERM) {
                pos++;
                return t.text.isEmpty() ? null : Node.term(t.text);
            }
            // Unexpected operator in operand position: skip it defensively.
            pos++;
            return parsePrimary();
        }
    }

    /** Parse a raw Boolean/NEAR query string into an AST. */
    public static BooleanQuery parse(String raw) {
        List<Tok> toks = lex(raw);
        Node root = new Parser(toks).parse();
        return new BooleanQuery(root, raw == null ? "" : raw);
    }

    /** Distinct terms as a List (routing helper). */
    public List<String> termList() {
        return new ArrayList<>(terms());
    }

    static Set<Long> union(Collection<Long> a, Collection<Long> b) {
        Set<Long> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }
}
