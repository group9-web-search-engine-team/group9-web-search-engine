package com.group9.websearch.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts a BEIR {@code corpus.jsonl} ({@code {"_id","title","text",...}} per line) into the
 * indexer's {@link ExportedDocument} JSONL format, plus an exact id-map, WITHOUT the crawler,
 * the exporter, PostgreSQL, or S3.
 *
 * <p>Per BEIR record it assigns a sequential numeric {@code docId} (the index key is a long) and
 * writes:
 * <ul>
 *   <li><b>documents.jsonl</b> -- one {@link ExportedDocument} per line:
 *       {@code {docId:N, url:"beir:<_id>", title, body:<text>, s3Key:""}}. The BEIR id is kept
 *       verbatim in {@code url} as {@code beir:<_id>} for traceability; {@code s3Key} is empty
 *       because no raw HTML is stored.</li>
 *   <li><b>idmap.tsv</b> -- {@code <beirId><TAB><docId>}, the exact mapping the evaluator's
 *       DocIdResolver consumes so qrels match the index with ~100% coverage (no title-join).</li>
 * </ul>
 *
 * <p>Usage: {@code CorpusAdapter <corpus.jsonl> <documents.jsonl> <idmap.tsv> [maxDocs]}.
 * {@code maxDocs} is optional (0 or omitted = all). Records missing {@code _id} are skipped and
 * counted; nothing is fabricated.
 */
public final class CorpusAdapter {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private CorpusAdapter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                "Usage: CorpusAdapter <corpus.jsonl> <documents.jsonl> <idmap.tsv> [maxDocs]");
            System.exit(2);
            return;
        }
        Path corpus = Path.of(args[0]);
        Path docsOut = Path.of(args[1]);
        Path idMapOut = Path.of(args[2]);
        long maxDocs = (args.length >= 4) ? Long.parseLong(args[3]) : 0L;

        if (!Files.isRegularFile(corpus)) {
            System.err.println("corpus not found: " + corpus);
            System.exit(1);
            return;
        }
        if (docsOut.toAbsolutePath().getParent() != null) {
            Files.createDirectories(docsOut.toAbsolutePath().getParent());
        }
        if (idMapOut.toAbsolutePath().getParent() != null) {
            Files.createDirectories(idMapOut.toAbsolutePath().getParent());
        }

        long written = 0;
        long skipped = 0;
        long docId = 1; // sequential numeric index id (1-based)

        try (BufferedReader br = Files.newBufferedReader(corpus, StandardCharsets.UTF_8);
             BufferedWriter docs = Files.newBufferedWriter(docsOut, StandardCharsets.UTF_8);
             BufferedWriter ids = Files.newBufferedWriter(idMapOut, StandardCharsets.UTF_8)) {

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonNode n;
                try {
                    n = MAPPER.readTree(line);
                } catch (IOException e) {
                    skipped++;
                    continue;
                }
                JsonNode idNode = n.get("_id");
                if (idNode == null || idNode.isNull()) {
                    skipped++;
                    continue;
                }
                String beirId = idNode.asText();
                String title = text(n, "title");
                String body = text(n, "text");

                ExportedDocument ed = new ExportedDocument(
                        docId, "beir:" + beirId, title, body, "");
                docs.write(ed.toJsonLine());
                docs.write('\n');

                ids.write(beirId);
                ids.write('\t');
                ids.write(Long.toString(docId));
                ids.write('\n');

                docId++;
                written++;
                if (maxDocs > 0 && written >= maxDocs) {
                    break;
                }
            }
        }

        System.out.printf(
            "CorpusAdapter done: wrote %d document(s) -> %s%n  id-map -> %s%n  skipped %d record(s)%n",
            written, docsOut, idMapOut, skipped);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }
}
