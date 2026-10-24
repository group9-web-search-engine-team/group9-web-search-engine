package com.group9.websearch.index;

import java.nio.charset.StandardCharsets;

/**
 * Escaping-safe codec for one final index record: {@code <encodedTerm>\t<postings>}.
 *
 * <p>The term is percent-encoded (RFC-3986-style) over its UTF-8 bytes so that TAB, newline,
 * comma, colon and any other delimiter can never appear literally in the term field and
 * corrupt the record. Only unreserved ASCII letters/digits and {@code - _ .} are left as-is;
 * everything else becomes {@code %XX}. Postings are {@code docId:tf,...} (numeric, no
 * escaping needed). This is the schema future query-processing code reads.
 *
 * <p>Example line (term "café" with postings):
 * <pre>caf%C3%A9\t12:3,57:1</pre>
 */
public final class IndexRecordCodec {

    private static final char SEP = '\t';
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private IndexRecordCodec() {
    }

    /** Serialise a term + postings list into one escaping-safe line (no trailing newline). */
    public static String encode(String term, PostingList postings) {
        return encodeTerm(term) + SEP + postings.serialize();
    }

    /** Decode a line produced by {@link #encode}. Returns [term, postingsString]. */
    public static DecodedRecord decode(String line) {
        int tab = line.indexOf(SEP);
        if (tab < 0) {
            throw new IllegalArgumentException("no tab separator in index record");
        }
        String term = decodeTerm(line.substring(0, tab));
        PostingList postings = PostingList.parse(line.substring(tab + 1));
        return new DecodedRecord(term, postings);
    }

    public record DecodedRecord(String term, PostingList postings) {}

    /** Percent-encode a term's UTF-8 bytes, leaving only unreserved chars literal. */
    public static String encodeTerm(String term) {
        byte[] bytes = term.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    /** Inverse of {@link #encodeTerm}. */
    public static String decodeTerm(String encoded) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(encoded.length());
        int i = 0;
        while (i < encoded.length()) {
            char c = encoded.charAt(i);
            if (c == '%') {
                int hi = Character.digit(encoded.charAt(i + 1), 16);
                int lo = Character.digit(encoded.charAt(i + 2), 16);
                out.write((hi << 4) + lo);
                i += 3;
            } else {
                out.write(c);
                i += 1;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';
    }
}
