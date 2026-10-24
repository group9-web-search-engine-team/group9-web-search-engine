package com.group9.websearch.search;

/**
 * Configuration for the online search engine. Values are supplied by CLI flags; there are no
 * hardcoded machine-specific paths or credentials. Paths default to relative locations under
 * the working directory so the prototype runs anywhere the index + export are copied.
 */
public final class SearchConfig {

    /** Directory of the inverted-index {@code part-*} files (local copy of the HDFS index). */
    public String indexDir = "index-local";

    /** JSONL document export used for metadata (URL/title/body) and the NOT universe. */
    public String metadataJsonl = "week3-export/documents.jsonl";

    /**
     * Optional path to the published BM25 corpus-statistics artifact (JSON) produced by the
     * standalone stats calculator. When set and the file exists and is marked complete, BM25
     * uses these authoritative full-corpus numbers (N, dl(d), Lave). When absent, BM25 falls
     * back to deriving the statistics from the loaded index. It is never hardcoded.
     */
    public String statsArtifact = null;

    /** Max results to return. */
    public int topK = 10;

    /** Default proximity window (token span) for NEAR queries. */
    public int proximityWindow = 8;

    /** Snippet length (characters) and context size (tokens) around the first hit. */
    public int snippetMaxChars = 160;
    public int snippetContextTokens = 8;

    /** Print the per-term BM25 score breakdown for each result. */
    public boolean explain = false;

    /** Okapi BM25 parameters (k1 = 1.2, b = 0.75 by default). */
    public Bm25Params bm25 = Bm25Params.defaults();
}
