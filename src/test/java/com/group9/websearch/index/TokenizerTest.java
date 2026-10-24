package com.group9.websearch.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void lowercasesAndSplitsOnPunctuationAndWhitespace() {
        List<String> tokens = Tokenizer.tokenize("Hello, WORLD!  foo-bar_baz");
        // '-' and '_' are NOT letters/digits so they split tokens.
        assertEquals(List.of("hello", "world", "foo", "bar", "baz"), tokens);
    }

    @Test
    void emptyAndNullProduceNoTokens() {
        assertTrue(Tokenizer.tokenize("").isEmpty());
        assertTrue(Tokenizer.tokenize(null).isEmpty());
        assertTrue(Tokenizer.tokenize("   \t\n  ").isEmpty());
    }

    @Test
    void keepsUnicodeLettersAndDigits() {
        // Accented Latin, Cyrillic, CJK, and digits are all letters/digits.
        List<String> tokens = Tokenizer.tokenize("café Привет 日本語 2026");
        assertEquals(List.of("café", "привет", "日本語", "2026"), tokens);
    }

    @Test
    void digitsAreTokens() {
        assertEquals(List.of("abc123", "456"), Tokenizer.tokenize("abc123 456"));
    }

    @Test
    void termFrequenciesCountRepeats() {
        Map<String, Integer> tf = Tokenizer.termFrequencies("the cat the dog the");
        assertEquals(3, tf.get("the"));
        assertEquals(1, tf.get("cat"));
        assertEquals(1, tf.get("dog"));
        assertEquals(3, tf.size());
    }

    @Test
    void termFrequenciesEmptyForBlank() {
        assertTrue(Tokenizer.termFrequencies("").isEmpty());
    }

    @Test
    void positionsAreZeroBasedAndPreserveEveryOccurrence() {
        // "cat sat cat" -> cat@0,2 ; sat@1 (the spec's illustrative example)
        var pos = Tokenizer.positions("cat sat cat");
        assertEquals(List.of(0, 2), pos.get("cat"));
        assertEquals(List.of(1), pos.get("sat"));
    }

    @Test
    void positionsFollowNormalizedTokenSequence() {
        // Punctuation splits tokens; positions index the normalized token stream.
        var pos = Tokenizer.positions("Hello, HELLO world");
        assertEquals(List.of(0, 1), pos.get("hello"));
        assertEquals(List.of(2), pos.get("world"));
    }

    @Test
    void positionsEmptyForBlank() {
        assertTrue(Tokenizer.positions("").isEmpty());
        assertTrue(Tokenizer.positions(null).isEmpty());
    }
}
