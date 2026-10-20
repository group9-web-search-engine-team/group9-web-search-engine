package com.group9.websearch.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Metadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Unit tests for {@link S3RawHtmlBolt}. Pure key/prefix logic is tested directly; the
 * execute() behaviour is tested with mocked {@link OutputCollector} and {@link S3Client}
 * (no live AWS). The S3 client and collector are injected via reflection into the bolt's
 * transient fields, mirroring what {@code prepare()} would set.
 */
class S3RawHtmlBoltTest {

    @Test
    void keyIsDeterministicForSameUrl() {
        String a = S3RawHtmlBolt.keyFor("https://example.com/page");
        String b = S3RawHtmlBolt.keyFor("https://example.com/page");
        assertEquals(a, b);
    }

    @Test
    void keyDiffersForDifferentUrls() {
        assertFalse(S3RawHtmlBolt.keyFor("https://example.com/a")
                .equals(S3RawHtmlBolt.keyFor("https://example.com/b")));
    }

    @Test
    void keyIsHex64Sha256() {
        String k = S3RawHtmlBolt.keyFor("https://example.com/");
        assertEquals(64, k.length());
        assertTrue(k.matches("[0-9a-f]{64}"));
    }

    @Test
    void prefixGetsTrailingSlash() {
        assertEquals("crawl/raw/smoke-test/", S3RawHtmlBolt.normalizePrefix("crawl/raw/smoke-test"));
        assertEquals("crawl/raw/smoke-test/", S3RawHtmlBolt.normalizePrefix("crawl/raw/smoke-test/"));
    }

    @Test
    void prefixDefaultsWhenBlank() {
        assertEquals("crawl/raw/smoke-test/", S3RawHtmlBolt.normalizePrefix(null));
        assertEquals("crawl/raw/smoke-test/", S3RawHtmlBolt.normalizePrefix("   "));
    }

    @Test
    void fullKeyShapeUsesPrefixAndHtmlSuffix() {
        String prefix = S3RawHtmlBolt.normalizePrefix("crawl/raw/smoke-test");
        String key = prefix + S3RawHtmlBolt.keyFor("https://example.com/") + ".html";
        assertTrue(key.startsWith("crawl/raw/smoke-test/"));
        assertTrue(key.endsWith(".html"));
    }

    // ---------------------------------------------------------------------------------
    // execute() behaviour tests (mocked collector + S3 client)
    // ---------------------------------------------------------------------------------

    /** Build a bolt with injected mocks (no cap: the artificial page cap was removed). */
    private static S3RawHtmlBolt newBolt(OutputCollector collector, S3Client s3) throws Exception {
        S3RawHtmlBolt bolt = new S3RawHtmlBolt();
        setField(bolt, "collector", collector);
        setField(bolt, "s3", s3);
        setField(bolt, "bucket", "test-bucket");
        setField(bolt, "prefix", "crawl/raw/smoke-test/");
        setField(bolt, "region", "us-east-1");
        return bolt;
    }

    private static Tuple tupleWith(String url, byte[] content, Metadata md) {
        Tuple t = mock(Tuple.class);
        when(t.getStringByField("url")).thenReturn(url);
        when(t.getBinaryByField("content")).thenReturn(content);
        when(t.getValueByField("metadata")).thenReturn(md);
        return t;
    }

    /** Metadata populated as the fetcher/parser would, for the success-path tests. */
    private static Metadata fetchedMetadata() {
        Metadata md = new Metadata();
        md.setValue("fetch.statusCode", "200");
        md.setValue("fetch.byteLength", "1234");
        return md;
    }

    @Test
    void successfulUploadEmitsScalarStatusWithS3Key() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        S3Client s3 = mock(S3Client.class);
        S3RawHtmlBolt bolt = newBolt(collector, s3);

        Tuple t = tupleWith("https://example.com/",
                "<html>hi</html>".getBytes(StandardCharsets.UTF_8), fetchedMetadata());

        bolt.execute(t);

        // Exactly one emit on the dedicated scalar status stream; no main-stream emit.
        ArgumentCaptor<Values> values = ArgumentCaptor.forClass(Values.class);
        verify(collector, times(1))
                .emit(eq(S3RawHtmlBolt.S3_STATUS_STREAM), eq(t), values.capture());
        verify(collector, never()).emit(eq(t), any(Values.class));
        verify(collector, times(1)).ack(t);

        Values v = values.getValue();
        assertEquals("https://example.com/", v.get(0));
        assertEquals(Integer.valueOf(200), v.get(1));
        assertEquals(Long.valueOf(1234L), v.get(2));
        String s3Key = (String) v.get(3);
        assertTrue(s3Key != null && s3Key.startsWith("crawl/raw/smoke-test/") && s3Key.endsWith(".html"),
                "s3Key should be set on success, was: " + s3Key);
        // qualityScore (field 4) is null here because no QT5 client is injected (scoring
        // is fail-safe/optional); it is populated only when the scorer is enabled+reachable.
        assertNull(v.get(4), "qualityScore should be null when the QT5 client is absent");

        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void successfulUploadEmitsDiscoveredLinks() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        S3Client s3 = mock(S3Client.class);
        S3RawHtmlBolt bolt = newBolt(collector, s3);

        String html = "<html><body>"
                + "<a href=\"https://example.com/a\">A</a>"
                + "<a href=\"/relative\">Rel</a>"
                + "<a href=\"mailto:x@example.com\">mail</a>"  // non-http, skipped
                + "<a href=\"https://example.com/a#frag\">dup</a>"  // dup after fragment strip
                + "</body></html>";
        Tuple t = tupleWith("https://example.com/", html.getBytes(StandardCharsets.UTF_8), fetchedMetadata());

        bolt.execute(t);

        ArgumentCaptor<Values> links = ArgumentCaptor.forClass(Values.class);
        verify(collector, org.mockito.Mockito.atLeastOnce())
                .emit(eq(S3RawHtmlBolt.DISCOVERED_LINK_STREAM), eq(t), links.capture());

        java.util.List<String> dests = new java.util.ArrayList<>();
        for (Values v : links.getAllValues()) {
            assertEquals("https://example.com/", v.get(0)); // sourceUrl
            dests.add((String) v.get(1));                   // destUrl
        }
        assertTrue(dests.contains("https://example.com/a"), "absolute http link should be discovered");
        assertTrue(dests.contains("https://example.com/relative"), "relative link should be resolved");
        assertFalse(dests.stream().anyMatch(d -> d.startsWith("mailto:")), "non-http links excluded");
        // Fragment-only duplicate collapses to the same destination (deduped within page).
        assertEquals(dests.size(), new java.util.HashSet<>(dests).size(), "no duplicate destinations");
        verify(collector, times(1)).ack(t);
    }

    @Test
    void failedUploadDoesNotEmitFetchedButAcks() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("simulated S3 failure"));
        S3RawHtmlBolt bolt = newBolt(collector, s3);

        Tuple t = tupleWith("https://example.com/", "<html/>".getBytes(StandardCharsets.UTF_8), fetchedMetadata());
        bolt.execute(t);

        verify(collector, never()).emit(anyString(), any(Tuple.class), any(Values.class));
        verify(collector, never()).emit(any(Tuple.class), any(Values.class));
        verify(collector, times(1)).ack(t);
    }

    @Test
    void emptyContentDoesNotEmitFetchedButAcks() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        S3Client s3 = mock(S3Client.class);
        S3RawHtmlBolt bolt = newBolt(collector, s3);

        Tuple t = tupleWith("https://example.com/", new byte[0], new Metadata());
        bolt.execute(t);

        verify(collector, never()).emit(anyString(), any(Tuple.class), any(Values.class));
        verify(collector, times(1)).ack(t);
        // No upload attempted for empty content.
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void noArtificialCapAcrossManyUploads() throws Exception {
        // The former 5-page cap is removed: many successive uploads all emit FETCHED.
        OutputCollector collector = mock(OutputCollector.class);
        S3Client s3 = mock(S3Client.class);
        S3RawHtmlBolt bolt = newBolt(collector, s3);

        for (int i = 0; i < 20; i++) {
            Tuple t = tupleWith("https://example.com/p" + i,
                    "<html/>".getBytes(StandardCharsets.UTF_8), fetchedMetadata());
            bolt.execute(t);
        }

        // All 20 uploads happened and all emitted a FETCHED scalar status tuple.
        verify(s3, times(20)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(collector, times(20))
                .emit(eq(S3RawHtmlBolt.S3_STATUS_STREAM), any(Tuple.class), any(Values.class));
    }

    @Test
    void everyPathAcksExactlyOnce() throws Exception {
        // Success path
        OutputCollector c1 = mock(OutputCollector.class);
        S3Client s1 = mock(S3Client.class);
        S3RawHtmlBolt b1 = newBolt(c1, s1);
        Tuple t1 = tupleWith("https://a/", "x".getBytes(StandardCharsets.UTF_8), new Metadata());
        b1.execute(t1);
        verify(c1, times(1)).ack(t1);

        // Empty-content path
        OutputCollector c2 = mock(OutputCollector.class);
        S3RawHtmlBolt b2 = newBolt(c2, mock(S3Client.class));
        Tuple t2 = tupleWith("https://b/", null, new Metadata());
        b2.execute(t2);
        verify(c2, times(1)).ack(t2);
    }

    // ---- reflection helpers ----

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
