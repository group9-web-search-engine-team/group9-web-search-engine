package com.group9.websearch.eval;

import com.group9.websearch.index.ExportedDocument;
import com.group9.websearch.search.DocumentStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps benchmark (BEIR) document IDs to our index's numeric docIds, and back.
 *
 * <p><b>Why this is needed:</b> BEIR TREC-COVID uses opaque <em>string</em> document IDs (e.g.
 * {@code ug7v899j}); our crawler/index assigns its own sequential <em>numeric</em> docIds.
 * These are NOT the same namespace, so benchmark qrels cannot be applied to our ranked results
 * without an explicit, reliable mapping. This resolver never assumes the two id spaces coincide.
 *
 * <p><b>Mapping strategies, in priority order:</b>
 * <ol>
 *   <li><b>Explicit mapping file</b> ({@code --idmap}) -- a TSV with
 *       {@code benchmarkDocId<TAB>ourDocId} (numeric) or {@code benchmarkDocId<TAB>url}. This is
 *       the authoritative source when the crawl recorded the BEIR identity.</li>
 *   <li><b>URL match</b> -- if a {@link DocumentStore} is available, a benchmark id that is (or
 *       maps via the file to) a URL is matched to the our-docId whose {@link ExportedDocument}
 *       url equals it exactly.</li>
 * </ol>
 *
 * <p>If a benchmark id resolves by neither strategy it is reported as <em>unmapped</em>. The
 * evaluator uses the mapped fraction to report benchmark-document <b>coverage</b> and will
 * refuse to present a benchmark score when coverage is effectively zero, rather than fabricate
 * one. No heuristic/fuzzy matching is performed (exact match only), so a score is never inflated
 * by guesswork.
 */
public final class DocIdResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DocIdResolver.class);

    /** benchmarkDocId -> ourDocId (only entries that resolved). */
    private final Map<String, Long> benchToOurs;
    /** ourDocId -> benchmarkDocId (inverse, for translating ranked results). */
    private final Map<Long, String> oursToBench;
    private final int explicitMappings;
    private final int urlMappings;

    private DocIdResolver(Map<String, Long> benchToOurs, Map<Long, String> oursToBench,
            int explicitMappings, int urlMappings) {
        this.benchToOurs = benchToOurs;
        this.oursToBench = oursToBench;
        this.explicitMappings = explicitMappings;
        this.urlMappings = urlMappings;
    }

    public Optional<Long> toOurDocId(String benchmarkDocId) {
        return Optional.ofNullable(benchToOurs.get(benchmarkDocId));
    }

    public Optional<String> toBenchmarkDocId(long ourDocId) {
        return Optional.ofNullable(oursToBench.get(ourDocId));
    }

    public int mappedCount() {
        return benchToOurs.size();
    }

    public int explicitMappings() {
        return explicitMappings;
    }

    public int urlMappings() {
        return urlMappings;
    }

    /**
     * Coverage over a set of benchmark docIds: fraction that resolve to an our-docId. Returns 0
     * when the input set is empty.
     */
    public double coverage(Set<String> benchmarkDocIds) {
        if (benchmarkDocIds.isEmpty()) {
            return 0.0;
        }
        int mapped = 0;
        for (String id : benchmarkDocIds) {
            if (benchToOurs.containsKey(id)) {
                mapped++;
            }
        }
        return mapped / (double) benchmarkDocIds.size();
    }

    /**
     * Build a resolver.
     *
     * @param idMapFile optional TSV ({@code benchDocId<TAB>ourDocId|url}); may be null.
     * @param store     optional document store for URL-based resolution; may be null.
     */
    public static DocIdResolver build(Path idMapFile, DocumentStore store) throws IOException {
        Map<String, Long> benchToOurs = new HashMap<>();
        Map<Long, String> oursToBench = new HashMap<>();
        int explicit = 0;
        int viaUrl = 0;

        // Build a url -> ourDocId index from the store once (for URL resolution).
        Map<String, Long> urlToOur = new HashMap<>();
        if (store != null) {
            for (long ourId : store.universe()) {
                Optional<ExportedDocument> d = store.get(ourId);
                d.ifPresent(doc -> {
                    if (doc.url() != null && !doc.url().isBlank()) {
                        urlToOur.put(doc.url(), doc.docId());
                    }
                });
            }
        }

        if (idMapFile != null) {
            if (!Files.isRegularFile(idMapFile)) {
                throw new IOException("id map file not found: " + idMapFile);
            }
            try (BufferedReader br = Files.newBufferedReader(idMapFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    int tab = line.indexOf('\t');
                    if (tab <= 0 || tab == line.length() - 1) {
                        continue; // skip malformed mapping line (reported by caller counts)
                    }
                    String bench = line.substring(0, tab).trim();
                    String rhs = line.substring(tab + 1).trim();
                    Long ourId = null;
                    // RHS is either a numeric our-docId or a URL to look up in the store.
                    try {
                        ourId = Long.parseLong(rhs);
                    } catch (NumberFormatException notNumeric) {
                        ourId = urlToOur.get(rhs);
                        if (ourId != null) {
                            viaUrl++;
                        }
                    }
                    if (ourId != null) {
                        benchToOurs.put(bench, ourId);
                        oursToBench.put(ourId, bench);
                        explicit++;
                    }
                }
            }
        }

        LOG.info("DocIdResolver built: {} total mappings ({} explicit-numeric/url, {} via url lookup)",
                benchToOurs.size(), explicit, viaUrl);
        return new DocIdResolver(benchToOurs, oursToBench, explicit, viaUrl);
    }

    /** An empty resolver (no mappings): everything is unmapped, coverage 0. */
    public static DocIdResolver empty() {
        return new DocIdResolver(new HashMap<>(), new HashMap<>(), 0, 0);
    }
}
