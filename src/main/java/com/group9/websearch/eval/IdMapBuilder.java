package com.group9.websearch.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the benchmark-docId -&gt; our-URL id-map used by {@link DocIdResolver}, by joining a
 * BEIR {@code corpus.jsonl} to our crawl's {@code documents.jsonl}.
 *
 * <p>Two join keys are attempted, best first:
 * <ol>
 *   <li><b>PMC id</b> -- if a BEIR corpus record exposes a PMC id (in {@code metadata.pmcid} /
 *       {@code metadata.pubmed_id} / a {@code metadata.url}) AND our document URL contains a
 *       {@code PMC\d+} id, join on that id. This is the strongest key.</li>
 *   <li><b>Normalized title</b> -- fallback when no PMC id is available on the BEIR side (the
 *       plain BEIR TREC-COVID corpus often has only {@code _id}, {@code title}, {@code text}).
 *       Titles are lower-cased and stripped to letters/digits/spaces before matching.</li>
 * </ol>
 *
 * <p>Output: TSV {@code benchmarkDocId<TAB>url}. It only emits rows that genuinely matched; it
 * never fabricates a mapping. The console prints how many mapped via each key and how many BEIR
 * docs and crawl docs went unmatched, so coverage is honest.
 *
 * <p>Usage: {@code IdMapBuilder <beirCorpus.jsonl> <documents.jsonl> <out.tsv>}
 */
public final class IdMapBuilder {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern PMC = Pattern.compile("PMC(\\d+)", Pattern.CASE_INSENSITIVE);

    private IdMapBuilder() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: IdMapBuilder <beirCorpus.jsonl> <documents.jsonl> <out.tsv>");
            System.exit(2);
            return;
        }
        Path corpus = Path.of(args[0]);
        Path docs = Path.of(args[1]);
        Path out = Path.of(args[2]);

        // --- Index our crawl docs by PMC id and by normalized title ---
        Map<String, String> urlByPmc = new HashMap<>();
        Map<String, String> urlByTitle = new HashMap<>();
        long ourDocs = 0;
        try (BufferedReader br = Files.newBufferedReader(docs, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = MAPPER.readTree(line);
                String url = text(n, "url");
                String title = text(n, "title");
                if (url == null || url.isBlank()) {
                    continue;
                }
                ourDocs++;
                String pmc = pmcId(url);
                if (pmc != null) {
                    urlByPmc.putIfAbsent(pmc, url);
                }
                String nt = normTitle(title);
                if (!nt.isEmpty()) {
                    urlByTitle.putIfAbsent(nt, url);
                }
            }
        }

        // --- Walk BEIR corpus, join, write matches ---
        long beirDocs = 0;
        long viaPmc = 0;
        long viaTitle = 0;
        long unmatched = 0;
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedReader br = Files.newBufferedReader(corpus, StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = MAPPER.readTree(line);
                String benchId = text(n, "_id");
                if (benchId == null) {
                    continue;
                }
                beirDocs++;

                String matchedUrl = null;
                // 1) PMC id from metadata/url on the BEIR side
                String beirPmc = beirPmcId(n);
                if (beirPmc != null) {
                    matchedUrl = urlByPmc.get(beirPmc);
                    if (matchedUrl != null) {
                        viaPmc++;
                    }
                }
                // 2) fallback: normalized title
                if (matchedUrl == null) {
                    String nt = normTitle(text(n, "title"));
                    if (!nt.isEmpty()) {
                        matchedUrl = urlByTitle.get(nt);
                        if (matchedUrl != null) {
                            viaTitle++;
                        }
                    }
                }

                if (matchedUrl != null) {
                    w.write(benchId);
                    w.write('\t');
                    w.write(matchedUrl);
                    w.write('\n');
                } else {
                    unmatched++;
                }
            }
        }

        System.out.printf(
                "id-map built: %s%n  BEIR docs=%d  crawl docs=%d%n"
                + "  matched via PMC=%d  via title=%d  unmatched BEIR=%d%n",
                out, beirDocs, ourDocs, viaPmc, viaTitle, unmatched);
        if (viaPmc + viaTitle == 0) {
            System.out.println("  WARNING: zero matches. The crawl export does not appear to "
                    + "contain the TREC-COVID documents (check you exported the PMC crawl, not "
                    + "the smoke crawl). Coverage will be 0 and MAP/NDCG will not be valid.");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** Extract a PMC numeric id (e.g. "35282") from a URL, or null. */
    private static String pmcId(String url) {
        Matcher m = PMC.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Try to find a PMC id anywhere in the BEIR record's metadata/url, or null. */
    private static String beirPmcId(JsonNode n) {
        JsonNode meta = n.get("metadata");
        if (meta != null && !meta.isNull()) {
            for (String f : new String[] {"pmcid", "pmc", "pubmed_id", "url", "doi"}) {
                JsonNode v = meta.get(f);
                if (v != null && !v.isNull()) {
                    Matcher m = PMC.matcher(v.asText());
                    if (m.find()) {
                        return m.group(1);
                    }
                }
            }
        }
        return null;
    }

    /** Lower-case, keep letters/digits/space, collapse whitespace. */
    private static String normTitle(String title) {
        if (title == null) {
            return "";
        }
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd} ]", " ");
        return s.replaceAll("\\s+", " ").trim();
    }
}
