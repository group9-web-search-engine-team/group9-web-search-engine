package com.group9.websearch.crawler.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FrontierRepository} using a mocked JDBC connection, so no running
 * PostgreSQL server is required. They verify that the SQL binds parameters that match the
 * verified live schema and that dedup/validation semantics hold.
 */
class FrontierRepositoryTest {

    private Connection connection;
    private PreparedStatement ps;
    private FrontierRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        connection = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        repo = new FrontierRepository(connection);
    }

    @Test
    void insertDiscoveredReturnsTrueWhenRowInserted() throws SQLException {
        when(ps.executeUpdate()).thenReturn(1);
        boolean inserted = repo.insertDiscovered("https://example.com/a", 500.0);
        assertTrue(inserted);
        verify(ps).setString(1, "https://example.com/a");
        verify(ps).setDouble(2, 500.0);
        verify(ps).setString(3, FrontierRepository.STATE_PENDING);
        verify(ps).executeUpdate();
    }

    @Test
    void insertDiscoveredReturnsFalseWhenDuplicate() throws SQLException {
        when(ps.executeUpdate()).thenReturn(0); // ON CONFLICT (url) DO NOTHING
        assertFalse(repo.insertDiscovered("https://example.com/a", 500.0));
    }

    @Test
    void insertDiscoveredClampsPriority() throws SQLException {
        when(ps.executeUpdate()).thenReturn(1);
        repo.insertDiscovered("https://example.com/a", 99999.0);
        verify(ps).setDouble(2, 1000.0);
        repo.insertDiscovered("https://example.com/b", -5.0);
        verify(ps).setDouble(2, 0.0);
    }

    @Test
    void markTerminalRejectsNonTerminalState() {
        assertThrows(IllegalArgumentException.class,
                () -> repo.markTerminal("https://example.com/a", "pending"));
    }

    @Test
    void markTerminalBindsFetched() throws SQLException {
        repo.markTerminal("https://example.com/a", FrontierRepository.STATE_FETCHED);
        verify(ps).setString(1, "fetched");
        verify(ps).setString(2, "https://example.com/a");
        verify(ps).executeUpdate();
    }

    @Test
    void markTerminalBindsFailed() throws SQLException {
        repo.markTerminal("https://example.com/a", FrontierRepository.STATE_FAILED);
        verify(ps).setString(1, "failed");
        verify(ps).setString(2, "https://example.com/a");
    }

    @Test
    void scheduleRetrySetsPendingAndNextAttempt() throws SQLException {
        Instant next = Instant.parse("2026-01-01T00:00:00Z");
        repo.scheduleRetry("https://example.com/a", next);
        verify(ps).setString(1, FrontierRepository.STATE_PENDING);
        verify(ps).setString(3, "https://example.com/a");
        verify(ps).executeUpdate();
    }

    @Test
    void upsertDocumentReturnsGeneratedDocId() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(42L);

        DocumentRecord doc = new DocumentRecord(
                "https://example.com/a", 200, 1234L, "crawl/raw/abc", Instant.parse("2026-01-01T00:00:00Z"));
        long docId = repo.upsertDocument(doc);

        assertEquals(42L, docId);
        verify(ps).setString(1, "https://example.com/a");
        verify(ps).setInt(3, 200);
        verify(ps).setLong(4, 1234L);
        verify(ps).setString(5, "crawl/raw/abc");
        verify(ps).executeQuery();
    }

    @Test
    void upsertDocumentHandlesNullableFields() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(7L);

        DocumentRecord doc = new DocumentRecord("https://example.com/a", null, null, null, null);
        assertEquals(7L, repo.upsertDocument(doc));
        verify(ps).setNull(eq(3), anyInt()); // status
        verify(ps).setNull(eq(4), anyInt()); // content_length
        verify(ps).setNull(eq(5), anyInt()); // s3_key
    }

    @Test
    void upsertDocumentThrowsWhenNoDocIdReturned() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        DocumentRecord doc = new DocumentRecord("https://example.com/a", null, null, null, null);
        assertThrows(SQLException.class, () -> repo.upsertDocument(doc));
    }

    @Test
    void findDocIdReturnsValueWhenPresent() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(99L);
        assertEquals(OptionalLong.of(99L), repo.findDocId("https://example.com/a"));
    }

    @Test
    void findDocIdReturnsEmptyWhenAbsent() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        assertTrue(repo.findDocId("https://example.com/missing").isEmpty());
    }

    @Test
    void insertLinkWithKnownDestinationDocId() throws SQLException {
        when(ps.executeUpdate()).thenReturn(1);
        boolean inserted = repo.insertLink(1L, OptionalLong.of(2L), "https://b.com/", "anchor");
        assertTrue(inserted);
        verify(ps).setLong(1, 1L);
        verify(ps).setLong(2, 2L);
        verify(ps).setString(3, "https://b.com/");
        verify(ps).setString(4, "anchor");
    }

    @Test
    void insertLinkWithUnfetchedDestinationLeavesDestDocIdNull() throws SQLException {
        when(ps.executeUpdate()).thenReturn(1);
        boolean inserted = repo.insertLink(1L, "https://b.com/", null);
        assertTrue(inserted);
        verify(ps).setLong(1, 1L);
        verify(ps).setNull(eq(2), anyInt()); // destination_doc_id null
        verify(ps).setString(3, "https://b.com/");
        verify(ps).setNull(eq(4), anyInt()); // anchor null
    }

    @Test
    void insertLinkDeduplicates() throws SQLException {
        when(ps.executeUpdate()).thenReturn(1);
        assertTrue(repo.insertLink(1L, "https://b.com/", "a"));
        when(ps.executeUpdate()).thenReturn(0);
        assertFalse(repo.insertLink(1L, "https://b.com/", "a"));
    }

    @Test
    void claimPendingBindsLimitAndReturnsClaimedUrls() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("https://a/", "https://b/");

        java.util.List<String> claimed = repo.claimPending(10);

        assertEquals(java.util.List.of("https://a/", "https://b/"), claimed);
        verify(ps).setInt(1, 10);
        verify(ps).executeQuery();
    }

    @Test
    void claimPendingReturnsEmptyWhenNothingDue() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        assertTrue(repo.claimPending(5).isEmpty());
    }

    @Test
    void resetCrawlTablesDeletesInFkSafeOrderAndCommits() throws SQLException {
        // Distinct statements per SQL so we can assert FK-safe ordering.
        PreparedStatement delLinks = mock(PreparedStatement.class);
        PreparedStatement delDocs = mock(PreparedStatement.class);
        PreparedStatement delState = mock(PreparedStatement.class);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("DELETE FROM links")))
                .thenReturn(delLinks);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("DELETE FROM documents")))
                .thenReturn(delDocs);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("DELETE FROM crawl_state")))
                .thenReturn(delState);
        when(delLinks.executeUpdate()).thenReturn(193);
        when(delDocs.executeUpdate()).thenReturn(9);
        when(delState.executeUpdate()).thenReturn(224);
        when(connection.getAutoCommit()).thenReturn(true);

        int[] deleted = repo.resetCrawlTables();

        assertArrayEquals(new int[] {193, 9, 224}, deleted);
        // Transaction: autocommit off, explicit commit, restored.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(connection, delLinks, delDocs, delState);
        inOrder.verify(connection).setAutoCommit(false);
        inOrder.verify(delLinks).executeUpdate();   // links before documents (FK-safe)
        inOrder.verify(delDocs).executeUpdate();
        inOrder.verify(delState).executeUpdate();
        inOrder.verify(connection).commit();
        inOrder.verify(connection).setAutoCommit(true);
    }

    @Test
    void resetCrawlTablesRollsBackOnError() throws SQLException {
        PreparedStatement delLinks = mock(PreparedStatement.class);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.contains("DELETE FROM links")))
                .thenReturn(delLinks);
        when(connection.getAutoCommit()).thenReturn(true);
        when(delLinks.executeUpdate()).thenThrow(new SQLException("permission denied for table links"));

        assertThrows(SQLException.class, () -> repo.resetCrawlTables());
        verify(connection).rollback();
        verify(connection).setAutoCommit(true); // restored even on failure
    }

    @Test
    void recoverStaleInProgressReturnsCount() throws SQLException {
        when(ps.executeUpdate()).thenReturn(4);
        assertEquals(4, repo.recoverStaleInProgress());
        verify(connection).prepareStatement(
                org.mockito.ArgumentMatchers.contains("state = 'in_progress'"));
    }
}
