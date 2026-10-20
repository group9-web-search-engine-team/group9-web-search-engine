package com.group9.websearch.crawler.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Data-access object for the crawler's PostgreSQL tables, matching the VERIFIED live
 * {@code websearch} schema (no schema changes are performed here):
 *
 * <pre>
 * crawl_state(url PK, priority DOUBLE, state TEXT DEFAULT 'pending',
 *             last_attempt TIMESTAMPTZ, next_attempt TIMESTAMPTZ, retry_count INT)
 * documents(doc_id IDENTITY PK, url UNIQUE, crawl_time TIMESTAMPTZ, status INT,
 *           content_length BIGINT, s3_key TEXT, quality_score DOUBLE, pagerank DOUBLE)
 * links(source_doc_id BIGINT FK->documents.doc_id, destination_doc_id BIGINT NULL FK,
 *       destination_url TEXT, anchor_text TEXT, PK(source_doc_id, destination_url))
 * </pre>
 *
 * <p>All statements are parameterized (no concatenation of page data) to avoid SQL
 * injection. The DAO takes an already-open {@link Connection} so it can be unit-tested
 * with a mocked connection and so the owning bolt controls the connection lifecycle. It
 * performs no DDL and never alters the schema.
 *
 * <p>Deduplication is delegated to the database via {@code ON CONFLICT} against the
 * existing primary-key / unique constraints, keeping repeat runs idempotent.
 */
public final class FrontierRepository {

    /** Frontier state values stored in {@code crawl_state.state} (plain TEXT column). */
    public static final String STATE_PENDING = "pending";
    public static final String STATE_FETCHED = "fetched";
    public static final String STATE_FAILED = "failed";
    /** Transient state while a URL is claimed by the frontier spout and in flight. */
    public static final String STATE_IN_PROGRESS = "in_progress";

    private final Connection connection;

    public FrontierRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    // crawl_state: insert a newly discovered URL if not already present.
    private static final String INSERT_FRONTIER_SQL =
            "INSERT INTO crawl_state (url, priority, state, retry_count) "
            + "VALUES (?, ?, ?, 0) "
            + "ON CONFLICT (url) DO NOTHING";

    // crawl_state: move a URL to a terminal state after a fetch completes.
    private static final String MARK_TERMINAL_SQL =
            "UPDATE crawl_state "
            + "SET state = ?, last_attempt = now() "
            + "WHERE url = ?";

    // crawl_state: schedule a retry (stays/returns to pending with a future next_attempt).
    private static final String SCHEDULE_RETRY_SQL =
            "UPDATE crawl_state "
            + "SET state = ?, last_attempt = now(), next_attempt = ?, "
            + "    retry_count = retry_count + 1 "
            + "WHERE url = ?";

    // documents: upsert metadata for a fetched page (idempotent on url), returning doc_id.
    private static final String UPSERT_DOCUMENT_SQL =
            "INSERT INTO documents (url, crawl_time, status, content_length, s3_key) "
            + "VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (url) DO UPDATE SET "
            + "  crawl_time = EXCLUDED.crawl_time, "
            + "  status = COALESCE(EXCLUDED.status, documents.status), "
            + "  content_length = COALESCE(EXCLUDED.content_length, documents.content_length), "
            + "  s3_key = COALESCE(EXCLUDED.s3_key, documents.s3_key) "
            + "RETURNING doc_id";

    private static final String SELECT_DOC_ID_SQL =
            "SELECT doc_id FROM documents WHERE url = ?";

    // crawl_state: atomically claim up to N pending URLs that are due (next_attempt null or
    // in the past), flipping them to in_progress so no other worker / later poll re-emits
    // them. FOR UPDATE SKIP LOCKED makes concurrent claims safe; the CTE updates exactly the
    // selected rows and returns the claimed URLs. This is what prevents infinite recrawl:
    // a URL leaves 'pending' the moment it is claimed and only returns via an explicit retry.
    private static final String CLAIM_PENDING_SQL =
            "WITH due AS ( "
            + "  SELECT url FROM crawl_state "
            + "  WHERE state = '" + STATE_PENDING + "' "
            + "    AND (next_attempt IS NULL OR next_attempt <= now()) "
            + "  ORDER BY priority DESC, next_attempt NULLS FIRST, url "
            + "  LIMIT ? "
            + "  FOR UPDATE SKIP LOCKED "
            + ") "
            + "UPDATE crawl_state cs "
            + "SET state = '" + STATE_IN_PROGRESS + "', last_attempt = now() "
            + "FROM due "
            + "WHERE cs.url = due.url "
            + "RETURNING cs.url";

    // links: directed edge keyed by (source_doc_id, destination_url).
    private static final String INSERT_LINK_SQL =
            "INSERT INTO links (source_doc_id, destination_doc_id, destination_url, anchor_text) "
            + "VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (source_doc_id, destination_url) DO NOTHING";

    // Clean-start reset. Order respects FKs: links reference documents (both FK columns),
    // so links must be deleted before documents. crawl_state has no FK dependency. All
    // three run inside one transaction (see FrontierRepository#resetCrawlTables).
    private static final String DELETE_LINKS_SQL = "DELETE FROM links";
    private static final String DELETE_DOCUMENTS_SQL = "DELETE FROM documents";
    private static final String DELETE_CRAWL_STATE_SQL = "DELETE FROM crawl_state";

    // Resume recovery: return any stale in_progress rows to pending so their work resumes.
    private static final String RECOVER_IN_PROGRESS_SQL =
            "UPDATE crawl_state SET state = '" + STATE_PENDING + "', next_attempt = NULL "
            + "WHERE state = '" + STATE_IN_PROGRESS + "'";

    /**
     * Insert a discovered URL into the frontier in the {@code pending} state. Returns true
     * if a new row was inserted, false if it already existed (deduplicated by PK on url).
     */
    public boolean insertDiscovered(String url, double priority) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_FRONTIER_SQL)) {
            ps.setString(1, url);
            ps.setDouble(2, clampPriority(priority));
            ps.setString(3, STATE_PENDING);
            return ps.executeUpdate() > 0;
        }
    }

    /** Move a URL to a terminal state ({@code fetched} or {@code failed}). */
    public void markTerminal(String url, String terminalState) throws SQLException {
        if (!STATE_FETCHED.equals(terminalState) && !STATE_FAILED.equals(terminalState)) {
            throw new IllegalArgumentException(
                    "terminal state must be '" + STATE_FETCHED + "' or '" + STATE_FAILED + "': " + terminalState);
        }
        try (PreparedStatement ps = connection.prepareStatement(MARK_TERMINAL_SQL)) {
            ps.setString(1, terminalState);
            ps.setString(2, url);
            ps.executeUpdate();
        }
    }

    /**
     * Schedule a bounded retry: keep the URL {@code pending} but set a future
     * {@code next_attempt} and increment {@code retry_count}.
     */
    public void scheduleRetry(String url, Instant nextAttempt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SCHEDULE_RETRY_SQL)) {
            ps.setString(1, STATE_PENDING);
            ps.setTimestamp(2, Timestamp.from(Objects.requireNonNull(nextAttempt, "nextAttempt")));
            ps.setString(3, url);
            ps.executeUpdate();
        }
    }

    /**
     * Upsert a document row for a successfully fetched page and return its {@code doc_id}.
     * The returned id is required to persist outgoing {@code links} edges.
     */
    public long upsertDocument(DocumentRecord doc) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_DOCUMENT_SQL)) {
            ps.setString(1, doc.url());
            Instant ts = doc.crawlTime() == null ? Instant.now() : doc.crawlTime();
            ps.setTimestamp(2, Timestamp.from(ts));
            setNullableInt(ps, 3, doc.status());
            setNullableLong(ps, 4, doc.contentLength());
            if (doc.s3Key() == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, doc.s3Key());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("upsertDocument returned no doc_id for url=" + doc.url());
            }
        }
    }

    /** Look up the {@code doc_id} for a URL, if a document row exists. */
    public OptionalLong findDocId(String url) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_DOC_ID_SQL)) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return OptionalLong.of(rs.getLong(1));
                }
                return OptionalLong.empty();
            }
        }
    }

    /**
     * Atomically claim up to {@code limit} due {@code pending} URLs, flipping them to
     * {@code in_progress}, and return them. Safe under concurrency via
     * {@code FOR UPDATE SKIP LOCKED}. Claimed URLs will not be re-claimed by a later poll
     * unless they are explicitly scheduled for retry, which prevents infinite recrawl.
     */
    public java.util.List<String> claimPending(int limit) throws SQLException {
        java.util.List<String> claimed = new java.util.ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(CLAIM_PENDING_SQL)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claimed.add(rs.getString(1));
                }
            }
        }
        return claimed;
    }

    /**
     * CLEAN-START reset: delete all crawl rows in a single transaction, respecting foreign
     * keys (links reference documents, so links are deleted first), then crawl_state.
     * Does NOT touch S3 objects and performs NO DDL / schema change. Requires a DB role
     * with DELETE privilege on all three tables (the dedicated crawler role is read/write
     * but typically lacks DELETE — see docs).
     *
     * @return a 3-element array of rows deleted from [links, documents, crawl_state]
     */
    public int[] resetCrawlTables() throws SQLException {
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int links;
            int docs;
            int state;
            try (PreparedStatement ps = connection.prepareStatement(DELETE_LINKS_SQL)) {
                links = ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(DELETE_DOCUMENTS_SQL)) {
                docs = ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(DELETE_CRAWL_STATE_SQL)) {
                state = ps.executeUpdate();
            }
            connection.commit();
            return new int[] {links, docs, state};
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rb) {
                e.addSuppressed(rb);
            }
            throw e;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    /**
     * RESUME recovery: move any stale {@code in_progress} rows (left over from a previous
     * run that stopped mid-flight) back to {@code pending} with a cleared next_attempt, so
     * their work is safely retried. Existing {@code fetched}/{@code failed}/{@code pending}
     * rows are untouched. Returns the number of rows recovered.
     */
    public int recoverStaleInProgress() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(RECOVER_IN_PROGRESS_SQL)) {
            return ps.executeUpdate();
        }
    }

    /**
     * Insert a directed link edge from a source document to a destination URL. The
     * destination may be discovered-but-unfetched, in which case {@code destinationDocId}
     * is empty and only {@code destination_url} is recorded. Deduplicated on the
     * {@code (source_doc_id, destination_url)} primary key.
     *
     * @return true if a new edge row was inserted, false if it already existed
     */
    public boolean insertLink(long sourceDocId, OptionalLong destinationDocId,
                              String destinationUrl, String anchorText) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_LINK_SQL)) {
            ps.setLong(1, sourceDocId);
            if (destinationDocId != null && destinationDocId.isPresent()) {
                ps.setLong(2, destinationDocId.getAsLong());
            } else {
                ps.setNull(2, Types.BIGINT);
            }
            ps.setString(3, destinationUrl);
            if (anchorText == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, truncate(anchorText, 2000));
            }
            return ps.executeUpdate() > 0;
        }
    }

    /** Convenience overload for a destination whose document row does not exist yet. */
    public boolean insertLink(long sourceDocId, String destinationUrl, String anchorText)
            throws SQLException {
        return insertLink(sourceDocId, OptionalLong.empty(), destinationUrl, anchorText);
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, value);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    /** Priority is a DOUBLE in the live schema; clamp to the documented [0, 1000] band. */
    static double clampPriority(double priority) {
        if (priority < 0.0) {
            return 0.0;
        }
        return Math.min(priority, 1000.0);
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    /** Package-visible for the integration check; never used to leak the connection. */
    Optional<Connection> connectionForDiagnostics() {
        return Optional.of(connection);
    }
}
