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

    // --- Distributed mode (evaluate through the live coordinator->worker path) ---
    /**
     * When true, queries are executed through the existing {@code DistributedCoordinator}
     * (HTTP fan-out to the five partition workers + global BM25), NOT a local index. No local
     * index is loaded in this mode; {@code statsArtifact} supplies the global corpus statistics
     * (required), and {@code clusterConfig} supplies the worker addresses.
     */
    public boolean distributed = false;
    /** Path to the cluster properties (worker.P1..P5 addresses + query.timeoutMillis). */
    public String clusterConfig = "config/cluster.properties";
    /**
     * Optional single coordinator/worker base host override (e.g. "44.222.139.216"). When set,
     * it is only used to annotate the report; worker routing always comes from the cluster
     * properties so no endpoint/protocol is invented here.
     */
    public String coordinatorAddress = null;
    /** Per-worker TCP connect timeout (ms) for the distributed-mode health gate. */
    public long connectTimeoutMillis = 2000;

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
