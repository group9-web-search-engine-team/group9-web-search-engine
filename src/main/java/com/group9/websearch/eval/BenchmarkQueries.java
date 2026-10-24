package com.group9.websearch.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Benchmark queries: an ordered {@code queryId -> queryText} map.
 *
 * <p>Supports the two common BEIR layouts:
 * <ul>
 *   <li>{@code queries.jsonl} -- one JSON object per line with {@code _id} and {@code text}
 *       (BEIR's native format).</li>
 *   <li>a simple TSV {@code queryId<TAB>queryText} (one query per line).</li>
 * </ul>
 * The format is chosen by file extension ({@code .jsonl}/{@code .json} -> JSONL, else TSV).
 * Malformed lines are recorded as problems and skipped; query text is never invented.
 */
public final class BenchmarkQueries {

    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkQueries.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final LinkedHashMap<String, String> byId;
    private final List<String> problems;

    private BenchmarkQueries(LinkedHashMap<String, String> byId, List<String> problems) {
        this.byId = byId;
        this.problems = problems;
    }

    public Map<String, String> all() {
        return Collections.unmodifiableMap(byId);
    }

    public int size() {
        return byId.size();
    }

    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }

    public static BenchmarkQueries load(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return (name.endsWith(".jsonl") || name.endsWith(".json")) ? loadJsonl(path) : loadTsv(path);
    }

    /** BEIR queries.jsonl: {@code {"_id": "...", "text": "..."}} per line. */
    public static BenchmarkQueries loadJsonl(Path path) throws IOException {
        requireFile(path);
        LinkedHashMap<String, String> byId = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode n = MAPPER.readTree(line);
                    JsonNode id = n.get("_id");
                    JsonNode text = n.get("text");
                    if (id == null || text == null) {
                        problems.add("queries:" + lineNo + " missing _id or text");
                        continue;
                    }
                    byId.put(id.asText(), text.asText());
                } catch (IOException e) {
                    problems.add("queries:" + lineNo + " malformed JSON");
                }
            }
        }
        LOG.info("Loaded {} queries (jsonl), {} problem line(s) from {}",
                byId.size(), problems.size(), path);
        return new BenchmarkQueries(byId, problems);
    }

    /** Simple TSV: {@code queryId<TAB>queryText}. */
    public static BenchmarkQueries loadTsv(Path path) throws IOException {
        requireFile(path);
        LinkedHashMap<String, String> byId = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    problems.add("queries:" + lineNo + " expected 'id<TAB>text'");
                    continue;
                }
                byId.put(line.substring(0, tab).trim(), line.substring(tab + 1).trim());
            }
        }
        LOG.info("Loaded {} queries (tsv), {} problem line(s) from {}",
                byId.size(), problems.size(), path);
        return new BenchmarkQueries(byId, problems);
    }

    private static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("queries file not found: " + path);
        }
    }
}
