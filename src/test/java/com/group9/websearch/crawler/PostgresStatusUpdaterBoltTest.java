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

    @Test
    void storeDiscoveredAcksTheTuple() throws Exception {
        // ROOT-CAUSE REGRESSION: AbstractStatusUpdaterBolt.execute() does NOT ack after store();
        // store() must ack, or the anchored spout tuple never completes and the URL is stranded
        // in_progress. Verify store(DISCOVERED) persists AND acks.
        OutputCollector collector = mock(OutputCollector.class);
        FrontierRepository repo = mock(FrontierRepository.class);
        PostgresStatusUpdaterBolt bolt = new PostgresStatusUpdaterBolt();
        setField(bolt, "_collector", collector);
        setField(bolt, "repository", repo);
        setField(bolt, "useCache", false); // ack() skips the (unprepared) dedup cache in tests

        Tuple t = mock(Tuple.class);
        org.apache.stormcrawler.Metadata md = new org.apache.stormcrawler.Metadata();
        bolt.store("https://dst/", org.apache.stormcrawler.persistence.Status.DISCOVERED,
                md, java.util.Optional.empty(), t);

        verify(repo, times(1)).insertDiscovered(eq("https://dst/"),
                org.mockito.ArgumentMatchers.anyDouble());
        verify(collector, times(1)).ack(t); // THE fix: store() acks the tuple
    }

    @Test
    void storeFetchErrorAcksTheTuple() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        FrontierRepository repo = mock(FrontierRepository.class);
        PostgresStatusUpdaterBolt bolt = new PostgresStatusUpdaterBolt();
        setField(bolt, "_collector", collector);
        setField(bolt, "repository", repo);
        setField(bolt, "useCache", false);

        Tuple t = mock(Tuple.class);
        bolt.store("https://x/", org.apache.stormcrawler.persistence.Status.FETCH_ERROR,
                new org.apache.stormcrawler.Metadata(), java.util.Optional.empty(), t);

        verify(repo, times(1)).markTerminal("https://x/", FrontierRepository.STATE_FAILED);
        verify(collector, times(1)).ack(t);
    }

    @Test
    void storeRedirectionMarksTerminalFollowsTargetAndAcks() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.findDocId("https://src/")).thenReturn(java.util.OptionalLong.empty());
        PostgresStatusUpdaterBolt bolt = new PostgresStatusUpdaterBolt();
        setField(bolt, "_collector", collector);
        setField(bolt, "repository", repo);
        setField(bolt, "useCache", false);

        org.apache.stormcrawler.Metadata md = new org.apache.stormcrawler.Metadata();
        md.setValue("_redirTo", "https://target/");
        Tuple t = mock(Tuple.class);
        bolt.store("https://src/", org.apache.stormcrawler.persistence.Status.REDIRECTION,
                md, java.util.Optional.empty(), t);

        // Source taken out of in_progress (terminal) and target queued (redirect followed).
        verify(repo, times(1)).markTerminal("https://src/", FrontierRepository.STATE_FETCHED);
        verify(repo, times(1)).insertDiscovered(eq("https://target/"),
                org.mockito.ArgumentMatchers.anyDouble());
        verify(collector, times(1)).ack(t);
    }

    // ---- dataset crawl policy: outlinks, redirect targets, URL rewrite, retries ----

    private static PostgresStatusUpdaterBolt policyBolt(FrontierRepository repo, OutputCollector c,
            boolean follow) throws Exception {
        PostgresStatusUpdaterBolt bolt = new PostgresStatusUpdaterBolt();
        setField(bolt, "_collector", c);
        setField(bolt, "repository", repo);
        setField(bolt, "useCache", false);
        setField(bolt, "followOutlinks", follow);
        return bolt;
    }

    private static Tuple fromComponent(String component) {
        Tuple t = mock(Tuple.class);
        when(t.getSourceComponent()).thenReturn(component);
        return t;
    }

    @Test
    void redirectTargetIsAlwaysEnqueuedButParserOutlinkOnlyWhenFollowing() throws Exception {
        FrontierRepository repo = mock(FrontierRepository.class);
        PostgresStatusUpdaterBolt bolt = policyBolt(repo, mock(OutputCollector.class), false);
        org.apache.stormcrawler.Metadata md = new org.apache.stormcrawler.Metadata();

        bolt.store("https://publisher.example/a", org.apache.stormcrawler.persistence.Status.DISCOVERED,
                md, java.util.Optional.empty(), fromComponent("fetch"));
        bolt.store("https://pmc.ncbi.nlm.nih.gov/about/", org.apache.stormcrawler.persistence.Status.DISCOVERED,
                md, java.util.Optional.empty(), fromComponent("parse"));

        verify(repo).insertDiscovered(eq("https://publisher.example/a"), org.mockito.ArgumentMatchers.anyDouble());
        verify(repo, org.mockito.Mockito.never()).insertDiscovered(
                eq("https://pmc.ncbi.nlm.nih.gov/about/"), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void redirectTargetIsRewrittenToCrawlableUrl() throws Exception {
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.findDocId(org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.OptionalLong.empty());
        PostgresStatusUpdaterBolt bolt = policyBolt(repo, mock(OutputCollector.class), false);
        org.apache.stormcrawler.Metadata md = new org.apache.stormcrawler.Metadata();
        md.setValue("_redirTo", "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC42/");

        bolt.store("https://doi.org/10.1/x", org.apache.stormcrawler.persistence.Status.REDIRECTION,
                md, java.util.Optional.empty(), fromComponent("fetch"));

        verify(repo).insertDiscovered(eq("https://pmc.ncbi.nlm.nih.gov/articles/PMC42/"),
                org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void s3OutlinkRecordsRewrittenEdgeButIsNotEnqueuedWhenNotFollowing() throws Exception {
        OutputCollector collector = mock(OutputCollector.class);
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.findDocId("https://src/")).thenReturn(java.util.OptionalLong.of(7L));
        when(repo.findDocId(org.mockito.ArgumentMatchers.startsWith("https://pmc."))).thenReturn(java.util.OptionalLong.empty());
        PostgresStatusUpdaterBolt bolt = policyBolt(repo, collector, false);

        Tuple t = mock(Tuple.class);
        when(t.getSourceStreamId()).thenReturn(S3RawHtmlBolt.DISCOVERED_LINK_STREAM);
        when(t.getStringByField("sourceUrl")).thenReturn("https://src/");
        when(t.getStringByField("destUrl")).thenReturn("https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9/");
        when(t.getValueByField("anchor")).thenReturn("ref");
        bolt.execute(t);

        verify(repo, org.mockito.Mockito.never()).insertDiscovered(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble());
        verify(repo).insertLink(eq(7L), eq(java.util.OptionalLong.empty()),
                eq("https://pmc.ncbi.nlm.nih.gov/articles/PMC9/"), eq("ref"));
        verify(collector).ack(t);
    }

    @Test
    void transientFetchErrorIsRetriedUntilBudgetIsSpent() throws Exception {
        FrontierRepository repo = mock(FrontierRepository.class);
        PostgresStatusUpdaterBolt bolt = policyBolt(repo, mock(OutputCollector.class), false);
        setField(bolt, "maxFetchErrors", 2);
        org.apache.stormcrawler.Metadata md = new org.apache.stormcrawler.Metadata();
        md.setValue("fetch.statusCode", "503");

        when(repo.currentRetryCount("https://x/")).thenReturn(0);
        bolt.store("https://x/", org.apache.stormcrawler.persistence.Status.FETCH_ERROR, md,
                java.util.Optional.empty(), fromComponent("fetch"));
        verify(repo).scheduleRetry(eq("https://x/"), org.mockito.ArgumentMatchers.any());
        verify(repo, org.mockito.Mockito.never()).markTerminal("https://x/", FrontierRepository.STATE_FAILED);

        when(repo.currentRetryCount("https://x/")).thenReturn(2);
        bolt.store("https://x/", org.apache.stormcrawler.persistence.Status.FETCH_ERROR, md,
                java.util.Optional.empty(), fromComponent("fetch"));
        verify(repo).markTerminal("https://x/", FrontierRepository.STATE_FAILED);
    }

    @Test
    void permanentFetchErrorFailsImmediately() throws Exception {
        FrontierRepository repo = mock(FrontierRepository.class);
        PostgresStatusUpdaterBolt bolt = policyBolt(repo, mock(OutputCollector.class), false);
        org.apache.stormcrawler.Metadata md = new org.apache.stormcrawler.Metadata();
        md.setValue("fetch.statusCode", "203"); // PubMed bot-check page

        bolt.store("https://pubmed.ncbi.nlm.nih.gov/1/", org.apache.stormcrawler.persistence.Status.FETCH_ERROR,
                md, java.util.Optional.empty(), fromComponent("fetch"));

        verify(repo).markTerminal("https://pubmed.ncbi.nlm.nih.gov/1/", FrontierRepository.STATE_FAILED);
        verify(repo, org.mockito.Mockito.never()).scheduleRetry(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
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
