package com.group9.websearch.seeds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UrlNormalizerTest {

    @Test
    void keepsSimpleHttpsUrl() {
        assertEquals(Optional.of("https://www.ncbi.nlm.nih.gov/pmc/articles/PMC35282/"),
                UrlNormalizer.normalize("https://www.ncbi.nlm.nih.gov/pmc/articles/PMC35282/"));
    }

    @Test
    void lowercasesSchemeAndHostOnly() {
        assertEquals(Optional.of("https://example.com/Path/With/Case?Q=Value"),
                UrlNormalizer.normalize("HTTPS://EXAMPLE.COM/Path/With/Case?Q=Value"));
    }

    @Test
    void removesFragment() {
        assertEquals(Optional.of("https://example.com/page"),
                UrlNormalizer.normalize("https://example.com/page#section-2"));
    }

    @Test
    void removesDefaultHttpPort() {
        assertEquals(Optional.of("http://example.com/a"),
                UrlNormalizer.normalize("http://example.com:80/a"));
    }

    @Test
    void removesDefaultHttpsPort() {
        assertEquals(Optional.of("https://example.com/a"),
                UrlNormalizer.normalize("https://example.com:443/a"));
    }

    @Test
    void retainsNonDefaultPort() {
        assertEquals(Optional.of("https://example.com:8443/a"),
                UrlNormalizer.normalize("https://example.com:8443/a"));
    }

    @Test
    void preservesQueryParameterOrderAndCase() {
        assertEquals(Optional.of("https://example.com/s?b=2&a=1&B=X"),
                UrlNormalizer.normalize("https://example.com/s?b=2&a=1&B=X"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals(Optional.of("https://example.com/"),
                UrlNormalizer.normalize("   https://example.com/   "));
    }

    @Test
    void rejectsNull() {
        assertTrue(UrlNormalizer.normalize(null).isEmpty());
    }

    @Test
    void rejectsBlank() {
        assertTrue(UrlNormalizer.normalize("   ").isEmpty());
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertTrue(UrlNormalizer.normalize("ftp://example.com/file").isEmpty());
        assertTrue(UrlNormalizer.normalize("mailto:someone@example.com").isEmpty());
        assertTrue(UrlNormalizer.normalize("file:///etc/passwd").isEmpty());
        assertTrue(UrlNormalizer.normalize("javascript:alert(1)").isEmpty());
    }

    @Test
    void rejectsRelativeUrl() {
        assertTrue(UrlNormalizer.normalize("/relative/path").isEmpty());
        assertTrue(UrlNormalizer.normalize("example.com/no-scheme").isEmpty());
    }

    @Test
    void rejectsMalformedUrl() {
        assertTrue(UrlNormalizer.normalize("http://exa mple.com/space").isEmpty());
        assertTrue(UrlNormalizer.normalize("ht!tp://bad").isEmpty());
    }

    @Test
    void rejectsSchemeWithoutHost() {
        assertTrue(UrlNormalizer.normalize("http:foo").isEmpty());
        assertTrue(UrlNormalizer.normalize("https://").isEmpty());
    }

    @Test
    void normalizationIsIdempotent() {
        String[] inputs = {
            "HTTPS://EXAMPLE.COM:443/Path?Q=1#frag",
            "http://Host.Example.com:80/x/y/z",
            "https://example.com/s?b=2&a=1"
        };
        for (String input : inputs) {
            Optional<String> once = UrlNormalizer.normalize(input);
            assertTrue(once.isPresent(), "expected valid URL for " + input);
            Optional<String> twice = UrlNormalizer.normalize(once.get());
            assertEquals(once, twice, "normalization must be idempotent for " + input);
        }
    }

    @Test
    void equivalentUrlsCollapseToSameCanonicalForm() {
        Optional<String> a = UrlNormalizer.normalize("HTTPS://Example.com:443/page#top");
        Optional<String> b = UrlNormalizer.normalize("https://example.com/page");
        assertTrue(a.isPresent() && b.isPresent());
        assertEquals(a, b);
    }

    @Test
    void differentPathsDoNotCollapse() {
        Optional<String> a = UrlNormalizer.normalize("https://example.com/a");
        Optional<String> b = UrlNormalizer.normalize("https://example.com/b");
        assertFalse(a.equals(b));
    }
}
