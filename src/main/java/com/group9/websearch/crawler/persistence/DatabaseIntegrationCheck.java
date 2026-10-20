package com.group9.websearch.crawler.persistence;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicitly-invoked database integration check. It is NOT a JUnit test and does not run
 * during {@code mvn test}; it is a manual, safe verification tool run against the live
 * {@code websearch} database when the operator chooses to.
 *
 * <p>Safety guarantees:
 * <ul>
 *   <li>Credentials are read only from environment variables; the password is never
 *       printed.</li>
 *   <li>It performs NO DDL and NO schema migration.</li>
 *   <li>All data-modifying operations run inside a single transaction that is ALWAYS
 *       rolled back, so the database is left unchanged (reversible, no truncation, no
 *       deletes of existing data).</li>
 *   <li>It discourages superuser use by honoring a dedicated, non-superuser account via
 *       env vars.</li>
 * </ul>
 *
 * <p>Environment variables:
 * <pre>
 *   WEBSEARCH_PG_URL       (default jdbc:postgresql://localhost:5432/websearch)
 *   WEBSEARCH_PG_USER      (default websearch_crawler -- NOT the postgres superuser)
 *   WEBSEARCH_PG_PASSWORD  (required; the password value, never logged)
 * </pre>
 *
 * <p>Run (after building classes):
 * <pre>
 *   java -cp target/classes;&lt;jdbc-driver-jars&gt; \
 *        com.group9.websearch.crawler.persistence.DatabaseIntegrationCheck
 * </pre>
 */
public final class DatabaseIntegrationCheck {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseIntegrationCheck.class);

    private static final String URL_ENV = "WEBSEARCH_PG_URL";
    private static final String USER_ENV = "WEBSEARCH_PG_USER";
    private static final String PASSWORD_ENV = "WEBSEARCH_PG_PASSWORD";

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/websearch";
    // Default to a dedicated, non-superuser role; do not default to 'postgres'.
    private static final String DEFAULT_USER = "websearch_crawler";

    private DatabaseIntegrationCheck() {
    }

    public static void main(String[] args) {
        String url = envOrDefault(URL_ENV, DEFAULT_URL);
        String user = envOrDefault(USER_ENV, DEFAULT_USER);
        String password = System.getenv(PASSWORD_ENV);

        if (password == null || password.isEmpty()) {
            System.err.println("ERROR: environment variable " + PASSWORD_ENV + " is not set.");
            System.err.println("Set it to the password of a dedicated, non-superuser crawler role.");
            System.exit(2);
            return;
        }
        if ("postgres".equalsIgnoreCase(user)) {
            System.err.println("WARNING: using the 'postgres' superuser is discouraged for routine checks. "
                    + "Create and use a dedicated non-superuser role (e.g. websearch_crawler).");
        }

        System.out.println("Database integration check against: " + url + " as user: " + user);

        List<String> results = new ArrayList<>();
        boolean ok = true;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            ok &= verifyColumns(conn, "crawl_state",
                    new String[]{"url", "priority", "state", "last_attempt", "next_attempt", "retry_count"},
                    results);
            ok &= verifyColumns(conn, "documents",
                    new String[]{"doc_id", "url", "crawl_time", "status", "content_length", "s3_key",
                            "quality_score", "pagerank"},
                    results);
            ok &= verifyColumns(conn, "links",
                    new String[]{"source_doc_id", "destination_doc_id", "destination_url", "anchor_text"},
                    results);

            ok &= exerciseReversibleRoundTrip(conn, results);
        } catch (SQLException e) {
            ok = false;
            results.add("FAIL  connection/query error: " + e.getMessage());
        }

        results.forEach(System.out::println);
        if (ok) {
            System.out.println("RESULT: schema verified and reversible round-trip succeeded (rolled back).");
            System.exit(0);
        } else {
            System.out.println("RESULT: integration check FAILED (see lines above).");
            System.exit(1);
        }
    }

    private static boolean verifyColumns(Connection conn, String table, String[] expected, List<String> out)
            throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        List<String> found = new ArrayList<>();
        try (ResultSet rs = md.getColumns(conn.getCatalog(), null, table, null)) {
            while (rs.next()) {
                found.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        if (found.isEmpty()) {
            out.add("FAIL  table '" + table + "' not found or has no columns visible to this role");
            return false;
        }
        boolean allPresent = true;
        for (String col : expected) {
            if (!found.contains(col.toLowerCase())) {
                out.add("FAIL  table '" + table + "' missing expected column '" + col + "'");
                allPresent = false;
            }
        }
        if (allPresent) {
            out.add("OK    table '" + table + "' has all expected columns " + java.util.Arrays.toString(expected));
        }
        return allPresent;
    }

    /**
     * Exercise insert/upsert/link against the real schema inside a transaction that is
     * always rolled back, so no row survives the check. Uses a throwaway sentinel URL.
     */
    private static boolean exerciseReversibleRoundTrip(Connection conn, List<String> out) {
        boolean priorAutoCommit = true;
        Savepoint savepoint = null;
        try {
            priorAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            savepoint = conn.setSavepoint("integration_check");

            FrontierRepository repo = new FrontierRepository(conn);
            String sentinelSrc = "https://integration-check.invalid/src/" + Instant.now().toEpochMilli();
            String sentinelDst = "https://integration-check.invalid/dst/" + Instant.now().toEpochMilli();

            boolean inserted = repo.insertDiscovered(sentinelSrc, 500.0);
            out.add((inserted ? "OK  " : "FAIL") + "  crawl_state insert (pending) for sentinel URL");

            long docId = repo.upsertDocument(new DocumentRecord(sentinelSrc, 200, 123L, null, Instant.now()));
            out.add("OK    documents upsert returned doc_id=" + docId + " (sentinel)");

            // Read the sentinel document back in the SAME transaction and verify the row.
            boolean readbackOk = verifySentinelDocument(conn, sentinelSrc, docId, 200, 123L, out);

            // Second upsert for the SAME url with all-null status/content_length/s3_key.
            // The COALESCE-based upsert must preserve the existing non-null values.
            long docId2 = repo.upsertDocument(new DocumentRecord(sentinelSrc, null, null, null, Instant.now()));
            boolean preserveOk = (docId2 == docId)
                    && verifySentinelDocument(conn, sentinelSrc, docId, 200, 123L, out);
            out.add((preserveOk ? "OK  " : "FAIL")
                    + "  null-field re-upsert preserved existing status=200 and content_length=123");

            repo.markTerminal(sentinelSrc, FrontierRepository.STATE_FETCHED);
            out.add("OK    crawl_state mark fetched (sentinel)");

            boolean linkInserted = repo.insertLink(docId, OptionalLong.empty(), sentinelDst, "sentinel anchor");
            out.add((linkInserted ? "OK  " : "FAIL") + "  links insert (unfetched destination) for sentinel edge");

            return inserted && readbackOk && preserveOk && linkInserted;
        } catch (SQLException e) {
            out.add("FAIL  reversible round-trip error: " + e.getMessage());
            return false;
        } finally {
            // Always undo everything this check did.
            try {
                if (savepoint != null) {
                    conn.rollback(savepoint);
                }
                conn.rollback();
            } catch (SQLException e) {
                out.add("WARN  rollback issue (no data should persist): " + e.getMessage());
            }
            try {
                conn.setAutoCommit(priorAutoCommit);
            } catch (SQLException ignored) {
                // leaving autocommit as-is is harmless; connection is about to close
            }
        }
    }

    /**
     * Query the sentinel document within the current transaction and verify that the URL
     * exists and that its doc_id, status and content_length match the expected values.
     * Returns true on a full match; appends an OK/FAIL line to {@code out} either way.
     */
    private static boolean verifySentinelDocument(Connection conn, String url, long expectedDocId,
            int expectedStatus, long expectedContentLength, List<String> out) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT doc_id, status, content_length FROM documents WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    out.add("FAIL  sentinel document not found for url=" + url);
                    return false;
                }
                long docId = rs.getLong("doc_id");
                int status = rs.getInt("status");
                long contentLength = rs.getLong("content_length");
                boolean ok = docId == expectedDocId
                        && status == expectedStatus
                        && contentLength == expectedContentLength;
                out.add((ok ? "OK  " : "FAIL")
                        + "  sentinel readback doc_id=" + docId + " (expected " + expectedDocId + "), status="
                        + status + " (expected " + expectedStatus + "), content_length=" + contentLength
                        + " (expected " + expectedContentLength + ")");
                return ok;
            }
        }
    }

    private static String envOrDefault(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
