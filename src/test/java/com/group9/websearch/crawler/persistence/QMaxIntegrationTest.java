package com.group9.websearch.crawler.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Database INTEGRATION tests for QMax propagation and frontier prioritisation, run against
 * the live {@code websearch} PostgreSQL. Everything runs inside a single transaction that
 * is ALWAYS rolled back, so no row persists and the current database is never mutated.
 *
 * <p>Gated: the whole class is skipped (never a false pass) unless BOTH are set:
 * <pre>
 *   WEBSEARCH_IT=1
 *   WEBSEARCH_PG_PASSWORD=&lt;password for the role in WEBSEARCH_PG_USER (default websearch_crawler)&gt;
 * </pre>
 * Optional overrides: {@code WEBSEARCH_PG_URL}, {@code WEBSEARCH_PG_USER}.
 *
 * <p>These tests use unique sentinel URLs and assert the full QMax semantics the unit
 * tests (mocked connection) cannot: MAX over multiple parents, monotonic increase,
 * lower-parent-does-not-reduce, null/missing parents, duplicate-link idempotency,
 * descending frontier order with negative scores, priority refresh, and seed/unknown
 * defaults.
 */
class QMaxIntegrationTest {

    private Connection conn;
    private FrontierRepository repo;
    private String ns;

    private static boolean enabled() {
        return "1".equals(System.getenv("WEBSEARCH_IT"))
                && System.getenv("WEBSEARCH_PG_PASSWORD") != null;
    }

    @BeforeEach
    void setUp() throws SQLException {
        org.junit.jupiter.api.Assumptions.assumeTrue(enabled(),
                "QMax integration test skipped: set WEBSEARCH_IT=1 and WEBSEARCH_PG_PASSWORD to run.");
        String url = env("WEBSEARCH_PG_URL", "jdbc:postgresql://localhost:5432/websearch");
        String user = env("WEBSEARCH_PG_USER", "websearch_crawler");
        String pw = System.getenv("WEBSEARCH_PG_PASSWORD");
        conn = DriverManager.getConnection(url, user, pw);
        conn.setAutoCommit(false); // everything rolls back in tearDown
        repo = new FrontierRepository(conn);
        ns = "https://qmax-it.invalid/" + System.nanoTime() + "/";
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) {
            try {
                conn.rollback(); // undo all sentinel rows
            } finally {
                conn.close();
            }
        }
    }

    // --- helpers that insert sentinel rows within the rolled-back transaction ---

    private long insertDoc(String url, Double quality) throws SQLException {
        long docId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO documents (url, crawl_time) VALUES (?, ?) RETURNING doc_id")) {
            ps.setString(1, url);
            ps.setTimestamp(2, java.sql.Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                docId = rs.getLong(1);
            }
        }
        if (quality != null) {
            repo.updateQualityScore(url, quality);
        }
        return docId;
    }

    private void link(long srcDocId, String destUrl) throws SQLException {
        repo.insertLink(srcDocId, OptionalLong.empty(), destUrl, "a");
    }

    private Double qmaxColumn(String url) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT qmax_score FROM documents WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double v = rs.getDouble(1);
                    return rs.wasNull() ? null : v;
                }
                return null;
            }
        }
    }

    private Double priority(String url) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT priority FROM crawl_state WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : null;
            }
        }
    }

    // ---------------------------------- tests ----------------------------------

    @Test
    void singleParent() throws SQLException {
        String parent = ns + "p1";
        String dest = ns + "d";
        long p1 = insertDoc(parent, -2.0);
        repo.insertDiscovered(dest, 500.0);
        insertDoc(dest, null);
        link(p1, dest);

        OptionalDouble q = repo.recomputeQMaxForDestination(dest);
        assertTrue(q.isPresent());
        assertEquals(-2.0, q.getAsDouble());
        assertEquals(-2.0, qmaxColumn(dest));
        assertEquals(-2.0, priority(dest)); // frontier priority reflects QMax
    }

    @Test
    void multipleParentsTakesMax() throws SQLException {
        String dest = ns + "d";
        repo.insertDiscovered(dest, 500.0);
        insertDoc(dest, null);
        link(insertDoc(ns + "p1", -5.0), dest);
        link(insertDoc(ns + "p2", -1.5), dest); // highest (closest to 0)
        link(insertDoc(ns + "p3", -3.0), dest);

        OptionalDouble q = repo.recomputeQMaxForDestination(dest);
        assertEquals(-1.5, q.getAsDouble());
    }

    @Test
    void qmaxIncreasesWhenHigherParentFound() throws SQLException {
        String dest = ns + "d";
        repo.insertDiscovered(dest, 500.0);
        insertDoc(dest, null);
        link(insertDoc(ns + "p1", -4.0), dest);
        assertEquals(-4.0, repo.recomputeQMaxForDestination(dest).getAsDouble());
        link(insertDoc(ns + "p2", -1.0), dest); // better parent
        assertEquals(-1.0, repo.recomputeQMaxForDestination(dest).getAsDouble());
        assertEquals(-1.0, priority(dest));
    }

    @Test
    void lowerNewParentDoesNotReduceQMax() throws SQLException {
        String dest = ns + "d";
        repo.insertDiscovered(dest, 500.0);
        insertDoc(dest, null);
        link(insertDoc(ns + "p1", -1.0), dest);
        assertEquals(-1.0, repo.recomputeQMaxForDestination(dest).getAsDouble());
        link(insertDoc(ns + "p2", -9.0), dest); // worse parent
        assertEquals(-1.0, repo.recomputeQMaxForDestination(dest).getAsDouble()); // unchanged
    }

    @Test
    void missingAndNullParentScoresAreIgnored() throws SQLException {
        String dest = ns + "d";
        repo.insertDiscovered(dest, 500.0);
        insertDoc(dest, null);
        link(insertDoc(ns + "p1", null), dest); // parent with no QT5 score
        assertTrue(repo.recomputeQMaxForDestination(dest).isEmpty());
        assertEquals(null, qmaxColumn(dest));
        assertEquals(500.0, priority(dest)); // safe default preserved (not forced)

        link(insertDoc(ns + "p2", -2.0), dest); // now a scored parent exists
        assertEquals(-2.0, repo.recomputeQMaxForDestination(dest).getAsDouble());
    }

    @Test
    void duplicateLinksAndRetriesAreIdempotent() throws SQLException {
        String dest = ns + "d";
        repo.insertDiscovered(dest, 500.0);
        insertDoc(dest, null);
        long p1 = insertDoc(ns + "p1", -2.0);
        link(p1, dest);
        link(p1, dest); // duplicate (ON CONFLICT DO NOTHING)
        double first = repo.recomputeQMaxForDestination(dest).getAsDouble();
        double second = repo.recomputeQMaxForDestination(dest).getAsDouble(); // retry
        assertEquals(-2.0, first);
        assertEquals(first, second);
    }

    @Test
    void propagateFromSourceToChildren() throws SQLException {
        String parent = ns + "p";
        String c1 = ns + "c1";
        String c2 = ns + "c2";
        long pId = insertDoc(parent, -2.0);
        repo.insertDiscovered(c1, 500.0);
        repo.insertDiscovered(c2, 500.0);
        insertDoc(c1, null);
        insertDoc(c2, null);
        link(pId, c1);
        link(pId, c2);

        int applied = repo.recomputeQMaxForChildrenOf(parent);
        assertEquals(2, applied);
        assertEquals(-2.0, priority(c1));
        assertEquals(-2.0, priority(c2));
    }

    @Test
    void frontierClaimsInDescendingQMaxOrderWithNegativeScores() throws SQLException {
        String hi = ns + "hi";   // QMax -1.0
        String mid = ns + "mid"; // QMax -3.0
        String lo = ns + "lo";   // QMax -8.0
        for (String d : List.of(hi, mid, lo)) {
            repo.insertDiscovered(d, 500.0);
            insertDoc(d, null);
        }
        link(insertDoc(ns + "ph", -1.0), hi);
        link(insertDoc(ns + "pm", -3.0), mid);
        link(insertDoc(ns + "pl", -8.0), lo);
        repo.recomputeQMaxForDestination(hi);
        repo.recomputeQMaxForDestination(mid);
        repo.recomputeQMaxForDestination(lo);

        // Claim only within our namespace, in the same order the frontier uses.
        List<String> claimedOrder = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT url FROM crawl_state WHERE url LIKE ? AND state='pending' "
                        + "ORDER BY priority DESC, next_attempt NULLS FIRST, url")) {
            ps.setString(1, ns + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claimedOrder.add(rs.getString(1));
                }
            }
        }
        int iHi = claimedOrder.indexOf(hi);
        int iMid = claimedOrder.indexOf(mid);
        int iLo = claimedOrder.indexOf(lo);
        assertTrue(iHi < iMid && iMid < iLo,
                "expected descending QMax order hi<mid<lo, got " + claimedOrder);
    }

    @Test
    void priorityRefreshForAlreadyQueuedUrl() throws SQLException {
        String dest = ns + "d";
        repo.insertDiscovered(dest, 500.0); // already queued at default priority
        insertDoc(dest, null);
        assertEquals(500.0, priority(dest));
        link(insertDoc(ns + "p1", -2.5), dest);
        repo.recomputeQMaxForDestination(dest);
        assertEquals(-2.5, priority(dest), "already-queued URL's priority must refresh to QMax");
    }

    @Test
    void seedAndUnknownQMaxKeepSafeDefault() throws SQLException {
        String seed = ns + "seed";
        repo.insertDiscovered(seed, 500.0); // no parents -> QMax unknown
        assertTrue(repo.computeQMax(seed).isEmpty());
        assertEquals(500.0, priority(seed), "unknown-QMax URL keeps the high safe-default priority");
        assertFalse(repo.recomputeQMaxForDestination(seed).isPresent());
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
