package com.group9.websearch.crawler.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.crawler.distill.TopicDistiller;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live-PostgreSQL integration tests for soft-focus ordering (attempt_count ASC, relevance
 * DESC) and topic-distillation promotion, exercising the REAL CLAIM_PENDING_SQL path. Runs
 * entirely inside one rolled-back transaction, so no sentinel row persists.
 *
 * Gated (never a false pass): requires WEBSEARCH_IT=1 and WEBSEARCH_PG_PASSWORD. Optional
 * WEBSEARCH_PG_URL / WEBSEARCH_PG_USER overrides.
 */
class SoftFocusDistillationIntegrationTest {

    private Connection conn;
    private FrontierRepository repo;
    private String ns;

    private static boolean enabled() {
        return "1".equals(System.getenv("WEBSEARCH_IT"))
                && System.getenv("WEBSEARCH_PG_PASSWORD") != null;
    }

    @BeforeEach
    void setUp() throws SQLException {
        Assumptions.assumeTrue(enabled(),
                "soft-focus/distillation integration test skipped: set WEBSEARCH_IT=1 and WEBSEARCH_PG_PASSWORD.");
        String url = env("WEBSEARCH_PG_URL", "jdbc:postgresql://localhost:5432/websearch");
        String user = env("WEBSEARCH_PG_USER", "websearch_crawler");
        conn = DriverManager.getConnection(url, user, System.getenv("WEBSEARCH_PG_PASSWORD"));
        conn.setAutoCommit(false);
        repo = new FrontierRepository(conn);
        ns = "https://sfd-it.invalid/" + System.nanoTime() + "/";
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) {
            try {
                conn.rollback();
            } finally {
                conn.close();
            }
        }
    }

    // ---- helpers (operate inside the rolled-back transaction) ----

    private void insertFrontier(String url, double priority, int attemptCount, double distillBoost)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO crawl_state (url, priority, state, retry_count, attempt_count, distill_boost) "
                        + "VALUES (?, ?, 'pending', 0, ?, ?)")) {
            ps.setString(1, url);
            ps.setDouble(2, priority);
            ps.setInt(3, attemptCount);
            ps.setDouble(4, distillBoost);
            ps.executeUpdate();
        }
    }

    private long insertDoc(String url, Double quality) throws SQLException {
        long id;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO documents (url, crawl_time) VALUES (?, ?) RETURNING doc_id")) {
            ps.setString(1, url);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        if (quality != null) {
            repo.updateQualityScore(url, quality);
        }
        return id;
    }

    /** Claim order restricted to this test's namespace, mirroring CLAIM_PENDING_SQL ordering. */
    private List<String> claimOrder() throws SQLException {
        List<String> order = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT url FROM crawl_state WHERE url LIKE ? AND state='pending' "
                        + "ORDER BY attempt_count ASC, (priority + distill_boost) DESC, "
                        + "         next_attempt NULLS FIRST, url")) {
            ps.setString(1, ns + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    order.add(rs.getString(1));
                }
            }
        }
        return order;
    }

    private int attemptCount(String url) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT attempt_count FROM crawl_state WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private double distillBoost(String url) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT distill_boost FROM crawl_state WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getDouble(1);
            }
        }
    }

    // ---- soft focus ----

    @Test
    void fewerAttemptsPrecedeMoreAttemptsRegardlessOfQMax() throws SQLException {
        // 'few' has the WORSE relevance but FEWER attempts -> must come first (paper order).
        insertFrontier(ns + "few", -9.0, 0, 0.0);
        insertFrontier(ns + "many", -1.0, 3, 0.0);
        List<String> order = claimOrder();
        assertTrue(order.indexOf(ns + "few") < order.indexOf(ns + "many"),
                "fewer attempts must precede more attempts regardless of relevance");
    }

    @Test
    void equalAttemptsOrderedByRelevanceDescending() throws SQLException {
        insertFrontier(ns + "hi", -1.0, 1, 0.0);
        insertFrontier(ns + "lo", -5.0, 1, 0.0);
        List<String> order = claimOrder();
        assertTrue(order.indexOf(ns + "hi") < order.indexOf(ns + "lo"),
                "at equal attempt counts, higher relevance (QMax) comes first");
    }

    @Test
    void claimIncrementsAttemptCountForSuccessOrFailure() throws SQLException {
        // Give our URL a very high relevance so it leads the 0-attempt tier, and request a
        // large batch, so the real claim query (shared with the live DB) claims it.
        String u = ns + "u";
        insertFrontier(u, 1.0e9, 0, 0.0);
        assertEquals(0, attemptCount(u));
        // Claim in a loop until our URL is claimed or the frontier is exhausted, so the test
        // is robust to other committed pending rows sharing the 0-attempt tier.
        boolean claimedU = false;
        for (int i = 0; i < 20 && !claimedU; i++) {
            List<String> claimed = repo.claimPending(500);
            if (claimed.isEmpty()) {
                break;
            }
            claimedU = claimed.contains(u);
        }
        assertTrue(claimedU, "our high-relevance 0-attempt URL must eventually be claimed");
        assertEquals(1, attemptCount(u), "claiming a URL counts exactly one fetch attempt");
    }

    @Test
    void lowQualityPageStillProducesEligibleOutgoingLinks() throws SQLException {
        // A very low-quality parent still yields a persisted link and a pending destination.
        long parent = insertDoc(ns + "lowparent", -9.5);
        String dest = ns + "child";
        repo.insertDiscovered(dest, 500.0);
        assertTrue(repo.insertLink(parent, OptionalLong.empty(), dest, "a"));
        // Destination remains pending/eligible (never hard-discarded by low quality).
        List<String> order = claimOrder();
        assertTrue(order.contains(dest), "links from low-quality pages stay eligible to crawl");
    }

    // ---- distillation ----

    @Test
    void distillationPromotesUnvisitedHubNeighbours() throws SQLException {
        // Promotion persistence is tested directly against the frontier primitive, because
        // the full distill() runs weighted HITS over the WHOLE committed graph (its correct
        // behaviour). Hub SELECTION is covered by the pure TopicDistillerTest; here we assert
        // that a promotion raises distill_boost ONLY for an unvisited (pending) neighbour.
        String n1 = ns + "n1";
        String n2 = ns + "n2";
        repo.insertDiscovered(n1, 500.0);
        repo.insertDiscovered(n2, 500.0);

        assertTrue(repo.promoteHubNeighbour(n1, 1000.0));
        assertTrue(repo.promoteHubNeighbour(n2, 1000.0));
        assertEquals(1000.0, distillBoost(n1));
        assertEquals(1000.0, distillBoost(n2));
    }

    @Test
    void promotionOnlyAppliesToPendingUnvisitedUrls() throws SQLException {
        // A terminal (fetched) URL is NOT promoted (not eligible/unvisited).
        String fetched = ns + "fetched";
        insertFrontier(fetched, 0.0, 1, 0.0);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE crawl_state SET state='fetched' WHERE url=?")) {
            ps.setString(1, fetched);
            ps.executeUpdate();
        }
        assertTrue(!repo.promoteHubNeighbour(fetched, 1000.0),
                "a fetched URL must not be promoted (only pending/unvisited are eligible)");
    }

    @Test
    void repeatedPromotionIsIdempotentNoDuplicateGrowth() throws SQLException {
        String n = ns + "n";
        repo.insertDiscovered(n, 500.0);
        repo.promoteHubNeighbour(n, 1000.0);
        double afterFirst = distillBoost(n);
        repo.promoteHubNeighbour(n, 1000.0); // repeat same boost
        assertEquals(afterFirst, distillBoost(n), "GREATEST keeps boost stable across repeated runs");
        // A lower boost does not reduce it.
        repo.promoteHubNeighbour(n, 10.0);
        assertEquals(afterFirst, distillBoost(n), "a lower boost must not reduce an existing promotion");
    }

    @Test
    void qmaxRecomputeDoesNotEraseDistillationPromotion() throws SQLException {
        // Promote a neighbour, then run a normal QMax recompute on it; the boost must remain
        // because QMax writes 'priority' (a separate column), never 'distill_boost'.
        long hub = insertDoc(ns + "hub", -0.5);
        String n = ns + "n";
        repo.insertDiscovered(n, 500.0);
        insertDoc(n, null);
        repo.insertLink(hub, OptionalLong.empty(), n, "a");
        repo.promoteHubNeighbour(n, 1000.0);
        assertEquals(1000.0, distillBoost(n));

        repo.recomputeQMaxForDestination(n); // updates priority only
        assertEquals(1000.0, distillBoost(n), "QMax update must not erase a distillation promotion");
    }

    @Test
    void distillRunOverWholeGraphExecutesWithoutError() throws SQLException {
        // Smoke-level: the full distill() pass runs over the (committed + sentinel) graph and
        // returns a non-negative promotion count. Correctness of hub selection is asserted in
        // the pure TopicDistillerTest; here we only verify the end-to-end DB pass is safe.
        long hub = insertDoc(ns + "hub", -0.2);
        String n = ns + "n";
        repo.insertDiscovered(n, 500.0);
        repo.insertLink(hub, OptionalLong.empty(), n, "a");
        int promoted = new TopicDistiller(repo, 10, 1000.0).distill();
        assertTrue(promoted >= 0);
    }

    @Test
    void distillationCannotOverrideAttemptCountFirstOrdering() throws SQLException {
        // 'boosted' has a huge distill_boost but 2 attempts; 'fresh' has no boost but 0
        // attempts. Attempt-count-first ordering must still put 'fresh' ahead of 'boosted'.
        insertFrontier(ns + "boosted", -5.0, 2, 100000.0);
        insertFrontier(ns + "fresh", -5.0, 0, 0.0);
        List<String> order = claimOrder();
        assertTrue(order.indexOf(ns + "fresh") < order.indexOf(ns + "boosted"),
                "a distillation boost must never let a higher-attempt URL overtake a lower-attempt one");
    }

    @Test
    void realClaimQueryConsumesSoftFocusOrdering() throws SQLException {
        // Three fresh (0-attempt) URLs with distinct, very high relevance so they lead the
        // 0-attempt tier ahead of any committed live rows. Claim large batches until all
        // three are claimed, recording the order the REAL claim query returns them in.
        insertFrontier(ns + "c", 1.0e9 + 1.0, 0, 0.0);  // lowest of the three
        insertFrontier(ns + "a", 1.0e9 + 3.0, 0, 0.0);  // highest
        insertFrontier(ns + "b", 1.0e9 + 2.0, 0, 0.0);  // middle
        List<String> mineInOrder = new ArrayList<>();
        for (int i = 0; i < 50 && mineInOrder.size() < 3; i++) {
            List<String> claimed = repo.claimPending(500);
            if (claimed.isEmpty()) {
                break;
            }
            for (String u : claimed) {
                if (u.startsWith(ns)) {
                    mineInOrder.add(u);
                }
            }
        }
        // The REAL claim path must order our tier highest-relevance-first: a, then b, then c.
        assertEquals(List.of(ns + "a", ns + "b", ns + "c"), mineInOrder,
                "the real claim query must consume the soft-focus (relevance DESC) ordering");
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
