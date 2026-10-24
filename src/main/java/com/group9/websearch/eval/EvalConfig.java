package com.group9.websearch.eval;

import java.util.List;

/**
 * Configuration for the evaluation run. All paths are supplied via CLI flags; no machine-specific
 * paths or credentials are hardcoded.
 */
public final class EvalConfig {

    // --- Benchmark inputs ---
    /** BEIR queries file (queries.jsonl or a queryId<TAB>text TSV). */
    public String queriesPath;
    /** BEIR qrels TSV (query-id, corpus-id, score). */
    public String qrelsPath;
    /** Optional benchmark-docId -> ourDocId|url mapping TSV. */
    public String idMapPath = null;

    // --- Index snapshot (read-only) ---
    /** Directory of the FIXED index snapshot to evaluate against (never the live index). */
    public String indexDir = "index-local";
    /** Document metadata JSONL (for URL-based id mapping and result rendering). */
    public String metadataJsonl = "week3-export/documents.jsonl";
    /** Optional published BM25 stats artifact (for N / Lave in the report). */
    public String statsArtifact = null;

    // --- Metric configuration ---
    /** Cutoffs to evaluate (must include 10 per the report). */
    public List<Integer> cutoffs = List.of(10);
    /** Binary-relevance threshold: grade >= threshold is "relevant". */
    public int relevanceThreshold = 1;
    /** How many results to request from the search service per query (>= max cutoff). */
    public int retrievalDepth = 100;

    // --- Output ---
    /** JSON report output path (machine-readable). */
    public String jsonOut = "eval/report.json";
    /** CSV per-query output path. */
    public String csvOut = "eval/per-query.csv";

    public int maxCutoff() {
        int m = 1;
        for (int k : cutoffs) {
            m = Math.max(m, k);
        }
        return m;
    }
}
