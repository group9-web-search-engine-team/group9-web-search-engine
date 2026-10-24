package com.group9.websearch.index;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Exports eligible crawled documents to a JSON Lines file for Hadoop indexing.
 *
 * <p>Pipeline per document: read metadata from PostgreSQL (streamed via a server-side cursor),
 * download the raw HTML object from S3 by its stored {@code s3_key} (bounded size), extract
 * title + body text with Jsoup, and write one {@link ExportedDocument} JSONL record.
 *
 * <p>Robustness: a single inaccessible/malformed document is logged (with its doc id and a
 * sanitised reason) and counted as a failure; it never aborts the whole export. Credentials
 * and raw HTML are never logged. Output is written to a temp file and atomically moved into
 * place, so reruns replace the file rather than appending duplicates (idempotent).
 */
public final class DocumentExporter {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentExporter.class);

    // Eligible = has a non-empty s3_key; optional OK-status filter applied in Java so the
    // rule is explicit and documented. ORDER BY doc_id for deterministic export order.
    private static final String SELECT_ELIGIBLE =
            "SELECT doc_id, url, status, s3_key FROM documents "
            + "WHERE s3_key IS NOT NULL AND s3_key <> '' ORDER BY doc_id";

    private final ExporterConfig cfg;

    public DocumentExporter(ExporterConfig cfg) {
        this.cfg = cfg;
    }

    /** Aggregate counts for honest reporting. */
    public static final class Stats {
        public long discovered;      // rows read from PostgreSQL
        public long eligible;        // passed the eligibility rule
        public long s3Success;       // S3 downloads that returned bytes
        public long s3Missing;       // NoSuchKey
        public long s3Denied;        // access denied
        public long s3OtherError;    // other S3 errors
        public long parseFailures;   // jsoup/encoding failures
        public long emptyText;       // extracted body was empty
        public long exported;        // records written

        @Override
        public String toString() {
            return "discovered=" + discovered + " eligible=" + eligible
                    + " s3Success=" + s3Success + " s3Missing=" + s3Missing
                    + " s3Denied=" + s3Denied + " s3OtherError=" + s3OtherError
                    + " parseFailures=" + parseFailures + " emptyText=" + emptyText
                    + " exported=" + exported;
        }
    }

    /**
     * Run the export. Returns the stats. Throws only for non-recoverable setup failures
     * (DB unreachable, missing password, output IO) so the CLI can map them to exit codes.
     */
    public Stats run() throws SQLException, IOException {
        String password = cfg.resolvePassword();
        if (password == null) {
            throw new IllegalStateException(
                    "PostgreSQL password env var '" + cfg.passwordEnv + "' is not set.");
        }

        Path out = Paths.get(cfg.outputPath);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = parent == null ? Paths.get(cfg.outputPath + ".tmp")
                : parent.resolve(out.getFileName() + ".tmp");

        Stats stats = new Stats();
        S3Client s3 = buildS3();
        try (Connection conn = DriverManager.getConnection(cfg.jdbcUrl, cfg.dbUser, password)) {
            conn.setAutoCommit(false); // required for a streaming server-side cursor
            try (BufferedWriter writer = cfg.dryRun ? null
                    : Files.newBufferedWriter(tmp, StandardCharsets.UTF_8);
                 PreparedStatement ps = conn.prepareStatement(SELECT_ELIGIBLE)) {
                ps.setFetchSize(cfg.fetchBatchSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stats.discovered++;
                        processRow(rs, s3, writer, stats);
                    }
                }
                if (writer != null) {
                    writer.flush();
                }
            }
            conn.rollback(); // read-only; release the cursor transaction
        } finally {
            s3.close();
        }

        if (!cfg.dryRun) {
            // Atomic publish: replace any previous export (idempotent, no duplicate append).
            try {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        LOG.info("Export complete: {} -> {}", stats, cfg.dryRun ? "(dry-run, no file)" : out);
        return stats;
    }

    private void processRow(ResultSet rs, S3Client s3, BufferedWriter writer, Stats stats)
            throws SQLException, IOException {
        long docId = rs.getLong("doc_id");
        String url = rs.getString("url");
        int status = rs.getInt("status");
        boolean statusNull = rs.wasNull();
        String s3Key = rs.getString("s3_key");

        // Eligibility (documented): optionally require 2xx or unknown status.
        if (cfg.requireOkStatus && !statusNull && (status < 200 || status >= 300)) {
            return;
        }
        stats.eligible++;

        byte[] html = downloadBounded(s3, s3Key, docId, stats);
        if (html == null) {
            return; // failure already counted/logged
        }
        stats.s3Success++;

        String title;
        String body;
        try {
            Document doc = Jsoup.parse(new String(html, StandardCharsets.UTF_8), url == null ? "" : url);
            title = doc.title();
            body = doc.body() != null ? doc.body().text() : doc.text();
        } catch (Exception e) {
            stats.parseFailures++;
            LOG.warn("HTML parse failed for doc_id={} ({}): {}", docId, safeHost(url), e.toString());
            return;
        }
        if (body == null || body.isBlank()) {
            stats.emptyText++;
            // Still export (with empty body) so document identity is preserved; the index
            // simply contributes no body terms. This avoids silently dropping documents.
            body = body == null ? "" : body;
        }
        if (body.length() > cfg.maxBodyChars) {
            body = body.substring(0, cfg.maxBodyChars);
        }

        ExportedDocument ed = new ExportedDocument(docId, url, title, body, s3Key);
        if (writer != null) {
            writer.write(ed.toJsonLine());
            writer.write('\n');
        }
        stats.exported++;
    }

    private byte[] downloadBounded(S3Client s3, String key, long docId, Stats stats) {
        try {
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(cfg.s3Bucket)
                    .key(key)
                    .build();
            ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(req);
            byte[] bytes = resp.asByteArray();
            if (bytes.length > cfg.maxObjectBytes) {
                byte[] truncated = new byte[(int) cfg.maxObjectBytes];
                System.arraycopy(bytes, 0, truncated, 0, truncated.length);
                return truncated;
            }
            return bytes;
        } catch (NoSuchKeyException e) {
            stats.s3Missing++;
            LOG.warn("S3 object missing for doc_id={} (key hidden)", docId);
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 403) {
                stats.s3Denied++;
                LOG.warn("S3 access denied for doc_id={} (key hidden)", docId);
            } else {
                stats.s3OtherError++;
                LOG.warn("S3 error for doc_id={}: HTTP {}", docId, e.statusCode());
            }
            return null;
        } catch (Exception e) {
            stats.s3OtherError++;
            LOG.warn("S3 download failed for doc_id={}: {}", docId, e.getClass().getSimpleName());
            return null;
        }
    }

    private S3Client buildS3() {
        return S3Client.builder().region(Region.of(cfg.s3Region)).build();
    }

    /** Return only the host of a URL for logs, never the full URL/path (sanitised). */
    private static String safeHost(String url) {
        if (url == null) {
            return "<null>";
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "<unparseable>";
        }
    }
}
