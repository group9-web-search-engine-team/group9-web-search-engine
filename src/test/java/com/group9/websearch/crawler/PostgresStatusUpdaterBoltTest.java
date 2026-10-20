package com.group9.websearch.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group9.websearch.crawler.persistence.DocumentRecord;
import com.group9.websearch.crawler.persistence.FrontierRepository;
import java.lang.reflect.Field;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.tuple.Tuple;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests that {@link PostgresStatusUpdaterBolt} persists the fetched-document scalars that
 * arrive on the S3 bolt's dedicated scalar stream directly from tuple fields — proving the
 * values survive even when the Metadata handoff would deliver an empty Metadata.
 */
class PostgresStatusUpdaterBoltTest {

    private static Tuple s3ScalarTuple(String url, Integer httpStatus, Long contentLength, String s3Key) {
        return s3ScalarTuple(url, httpStatus, contentLength, s3Key, null);
    }

    private static Tuple s3ScalarTuple(String url, Integer httpStatus, Long contentLength,
            String s3Key, Double qualityScore) {
        Tuple t = mock(Tuple.class);
        when(t.getSourceStreamId()).thenReturn(S3RawHtmlBolt.S3_STATUS_STREAM);
        when(t.getStringByField("url")).thenReturn(url);
        when(t.getValueByField("httpStatus")).thenReturn(httpStatus);
        when(t.getValueByField("contentLength")).thenReturn(contentLength);
        when(t.getValueByField("s3Key")).thenReturn(s3Key);
        when(t.getValueByField("qualityScore")).thenReturn(qualityScore);
        return t;
    }

    @Test
    void qualityScorePersistedWhenPresentAndSkippedWhenNull() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.upsertDocument(org.mockito.ArgumentMatchers.any(DocumentRecord.class))).thenReturn(1L);

        PostgresStatusUpdaterBolt bolt = new PostgresStatusUpdaterBolt();
        setField(bolt, "_collector", collector);
        setField(bolt, "repository", repo);

        // With a score present: it is persisted to the correct URL via updateQualityScore.
        Tuple scored = s3ScalarTuple("https://scored/", 200, 100L, "k1", -2.5);
        bolt.execute(scored);
        verify(repo, times(1)).updateQualityScore("https://scored/", -2.5);
        verify(collector, times(1)).ack(scored);

        // Without a score: updateQualityScore is NOT called (document still persisted).
        Tuple unscored = s3ScalarTuple("https://unscored/", 200, 100L, "k2", null);
        bolt.execute(unscored);
        verify(repo, org.mockito.Mockito.never())
                .updateQualityScore(eq("https://unscored/"), org.mockito.ArgumentMatchers.anyDouble());
        verify(collector, times(1)).ack(unscored);
    }

    @Test
    void scalarStreamPersistsNonNullStatusLengthAndKeyWithoutMetadata() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.upsertDocument(org.mockito.ArgumentMatchers.any(DocumentRecord.class))).thenReturn(42L);

        PostgresStatusUpdaterBolt bolt = new PostgresStatusUpdaterBolt();
        setField(bolt, "_collector", collector);
        setField(bolt, "repository", repo);

        // No Metadata is involved at all on this path: the scalars come from tuple fields.
        Tuple t = s3ScalarTuple("https://example.com/", 200, 2048L,
                "crawl/raw/smoke-test/abc123.html");

        bolt.execute(t);

        ArgumentCaptor<DocumentRecord> doc = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(repo, times(1)).upsertDocument(doc.capture());
        verify(repo, times(1)).markTerminal("https://example.com/", FrontierRepository.STATE_FETCHED);
        verify(collector, times(1)).ack(t);

        DocumentRecord persisted = doc.getValue();
        assertEquals("https://example.com/", persisted.url());
        assertNotNull(persisted.status(), "HTTP status must be non-null");
        assertEquals(Integer.valueOf(200), persisted.status());
        assertNotNull(persisted.contentLength(), "content length must be non-null");
        assertTrue(persisted.contentLength() > 0, "content length must be positive");
        assertNotNull(persisted.s3Key(), "s3 key must be non-null");
        assertTrue(!persisted.s3Key().isBlank(), "s3 key must be non-empty");
    }

    @Test
    void discoveredLinkSchedulesDestinationAndRecordsEdge() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        FrontierRepository repo = mock(FrontierRepository.class);
        // Source document already persisted (doc_id 7); destination not yet fetched.
        when(repo.findDocId("https://src/")).thenReturn(java.util.OptionalLong.of(7L));
        when(repo.findDocId("https://dst/")).thenReturn(java.util.OptionalLong.empty());

        PostgresStatusUpdaterBolt bolt = new PostgresStatusUpdaterBolt();
        setField(bolt, "_collector", collector);
        setField(bolt, "repository", repo);

        Tuple t = mock(Tuple.class);
        when(t.getSourceStreamId()).thenReturn(S3RawHtmlBolt.DISCOVERED_LINK_STREAM);
        when(t.getStringByField("sourceUrl")).thenReturn("https://src/");
        when(t.getStringByField("destUrl")).thenReturn("https://dst/");
        when(t.getValueByField("anchor")).thenReturn("anchor text");

        bolt.execute(t);

        // Destination scheduled into the frontier (pending) ...
        verify(repo, times(1)).insertDiscovered(eq("https://dst/"), org.mockito.ArgumentMatchers.anyDouble());
        // ... and the parent->child edge recorded from the known source doc_id.
        verify(repo, times(1)).insertLink(eq(7L),
                eq(java.util.OptionalLong.empty()), eq("https://dst/"), eq("anchor text"));
        verify(collector, times(1)).ack(t);
    }

    @Test
    void failedUpsertFailsTheTuple() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.upsertDocument(org.mockito.ArgumentMatchers.any(DocumentRecord.class)))
                .thenThrow(new java.sql.SQLException("boom"));

        PostgresStatusUpdaterBolt bolt = new PostgresStatusUpdaterBolt();
        setField(bolt, "_collector", collector);
        setField(bolt, "repository", repo);

        Tuple t = s3ScalarTuple("https://example.com/", 200, 10L, "crawl/raw/smoke-test/k.html");
        bolt.execute(t);

        verify(collector, times(1)).fail(t);
    }

    // ---- reflection helper (fields live on the bolt or its StormCrawler superclass) ----

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
