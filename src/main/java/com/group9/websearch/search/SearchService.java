package com.group9.websearch.search;

import com.group9.websearch.index.ExportedDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the online search flow, wiring the cleanly separated stages together:
 *
 * <pre>
 *   query string
 *     -> QueryParser          (normalise with the indexing tokenizer, pick retrieval mode)
 *     -> RetrievalEngine      (positional index -> candidate docIds + matched postings)
 *     -> Bm25Ranker           (Okapi BM25 score per candidate, with per-term breakdown)
 *     -> DocumentStore        (URL / title / snippet metadata)
 *     -> ranked SearchResults
 * </pre>
 *
 * <p>Ordinary queries use OR candidate generation then BM25 ranking. The ranking stage was
 * changed from the earlier linear/feature scorer to Okapi BM25; retrieval, query parsing,
 * Boolean operations, snippets, and the result format are unchanged. Blank queries, unknown
 * terms, and empty result sets are all handled without exceptions. Ties on score are broken by
 * ascending docId for deterministic output.
 */
public final class SearchService {

    private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);

    private final QueryParser parser;
    private final RetrievalEngine retrieval;
    private final Bm25Ranker ranker;
    private final DocumentStore documents;
    private final SnippetBuilder snippets;
    private final int topK;

    public SearchService(InvertedIndex index, DocumentStore documents, SearchConfig config) {
        this.documents = documents;
        this.parser = new QueryParser(config.proximityWindow);
        this.retrieval = new RetrievalEngine(index, documents);
        // BM25 corpus statistics: prefer the published, complete statistics artifact (the
        // authoritative full-corpus numbers). Only fall back to deriving from the loaded index
        // when no artifact is configured or it cannot be used. Never hardcoded.
        CorpusStatistics stats = loadStatistics(index, config);
        this.ranker = new Bm25Ranker(stats, config.bm25);
        this.snippets = new SnippetBuilder(config.snippetMaxChars, config.snippetContextTokens);
        this.topK = Math.max(1, config.topK);
    }

    /**
     * Resolve BM25 statistics. If {@code config.statsArtifact} points at a complete artifact,
     * use it (and cross-check its N against the loaded index, warning on a large mismatch which
     * indicates the artifact is stale relative to the index snapshot). Otherwise derive from the
     * index. An incomplete/partial artifact is rejected rather than silently consumed.
     */
    private static CorpusStatistics loadStatistics(InvertedIndex index, SearchConfig config) {
        if (config.statsArtifact != null && !config.statsArtifact.isBlank()) {
            java.nio.file.Path path = java.nio.file.Path.of(config.statsArtifact);
            if (java.nio.file.Files.isRegularFile(path)) {
                try {
                    CorpusStatsArtifact art = CorpusStatsArtifact.load(path);
                    CorpusStatistics stats = CorpusStatistics.fromArtifact(art);
                    LOG.info("BM25 using statistics artifact {} (N={}, avgdl={}, complete={})",
                            path, stats.numDocs(), stats.avgDocLength(), stats.isComplete());
                    return stats;
                } catch (Exception e) {
                    // Do not silently use incorrect defaults: report and fall back to the index.
                    LOG.warn("Could not use statistics artifact '{}' ({}); "
                            + "falling back to index-derived statistics.", path, e.toString());
                }
            } else {
                LOG.warn("Statistics artifact '{}' not found; falling back to index-derived "
                        + "statistics.", path);
            }
        }
        return CorpusStatistics.fromIndex(index);
    }

    /** The full outcome of a search, including diagnostics for the CLI to report. */
    public static final class Response {
        public final Query query;
        public final List<SearchResult> results;
        public final int candidateCount;
        public final List<String> unknownTerms;

        Response(Query query, List<SearchResult> results, int candidateCount,
                List<String> unknownTerms) {
            this.query = query;
            this.results = results;
            this.candidateCount = candidateCount;
            this.unknownTerms = unknownTerms;
        }
    }

    public Response search(String rawQuery) {
        Query query = parser.parse(rawQuery);
        if (query.isEmpty() && query.excludedTerms().isEmpty()) {
            // Blank query (or only operator markers) -> no results, no error.
            return new Response(query, List.of(), 0, List.of());
        }

        RetrievalEngine.Candidates candidates = retrieval.retrieve(query);
        if (candidates.docIds.isEmpty()) {
            return new Response(query, List.of(), 0, candidates.unknownTerms);
        }

        // Score every candidate with BM25.
        List<Ranked> ranked = new ArrayList<>(candidates.docIds.size());
        for (long docId : candidates.docIds) {
            Bm25Ranker.Scored scored = ranker.score(docId, query.terms(), candidates.termPostings);
            ranked.add(new Ranked(docId, scored));
        }
        // Deterministic order: score desc, then docId asc.
        ranked.sort(Comparator.<Ranked>comparingDouble(r -> r.scored.score).reversed()
                .thenComparingLong(r -> r.docId));

        List<SearchResult> out = new ArrayList<>();
        int limit = Math.min(topK, ranked.size());
        for (int i = 0; i < limit; i++) {
            Ranked r = ranked.get(i);
            out.add(toResult(i + 1, r, query.terms()));
        }
        return new Response(query, out, candidates.docIds.size(), candidates.unknownTerms);
    }

    private SearchResult toResult(int rank, Ranked r, List<String> queryTerms) {
        Optional<ExportedDocument> meta = documents.get(r.docId);
        String url = meta.map(ExportedDocument::url).orElse(null);
        String title = meta.map(ExportedDocument::title).orElse(null);
        String snippet = meta.map(d -> snippets.build(d.body(), queryTerms)).orElse("");
        return new SearchResult(rank, r.docId, r.scored.score, url, title, snippet, r.scored.breakdown);
    }

    private record Ranked(long docId, Bm25Ranker.Scored scored) {}
}
