package com.group9.websearch.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Command-line interface for the online sparse-retrieval + Okapi BM25 search engine.
 *
 * <p>It loads the existing positional inverted index and the JSONL document metadata once, then
 * answers one query (passed on the command line) or runs an interactive prompt. It never crawls
 * and never rebuilds the index.
 *
 * <p>Usage:
 * <pre>
 *   SearchCli [--index DIR] [--meta JSONL] [--topk N] [--window N] [--explain] [query terms...]
 * </pre>
 * With no trailing query it starts an interactive REPL (one query per line, blank line / "exit"
 * to quit). Operator prefixes are supported by {@link QueryParser} (AND:, OR:, NOT:, NEAR/N:).
 *
 * <p>Exit codes: 0 ok, 2 usage error, 1 runtime error (e.g. index/metadata not found).
 */
public final class SearchCli {

    private SearchCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        SearchConfig cfg = new SearchConfig();
        StringBuilder inlineQuery = new StringBuilder();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--index" -> cfg.indexDir = req(args, ++i, "--index");
                    case "--meta" -> cfg.metadataJsonl = req(args, ++i, "--meta");
                    case "--stats" -> cfg.statsArtifact = req(args, ++i, "--stats");
                    case "--topk" -> cfg.topK = Integer.parseInt(req(args, ++i, "--topk"));
                    case "--window" -> cfg.proximityWindow = Integer.parseInt(req(args, ++i, "--window"));
                    case "--snippet-chars" -> cfg.snippetMaxChars = Integer.parseInt(req(args, ++i, "--snippet-chars"));
                    case "--explain" -> cfg.explain = true;
                    case "-h", "--help" -> {
                        usage(null);
                        return 0;
                    }
                    default -> {
                        if (args[i].startsWith("--")) {
                            usage("unknown flag: " + args[i]);
                            return 2;
                        }
                        // Remaining tokens form an inline query.
                        if (inlineQuery.length() > 0) {
                            inlineQuery.append(' ');
                        }
                        inlineQuery.append(args[i]);
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            usage(e.getMessage());
            return 2;
        }

        final InvertedIndex index;
        final DocumentStore documents;
        try {
            index = InvertedIndex.load(Path.of(cfg.indexDir));
        } catch (IOException e) {
            System.err.println("error: could not load index from '" + cfg.indexDir + "': " + e.getMessage());
            System.err.println("hint: copy the HDFS index down first, e.g. "
                    + "`hdfs dfs -get /week3/index " + cfg.indexDir + "`");
            return 1;
        }
        documents = loadMetadata(cfg);

        SearchService service = new SearchService(index, documents, cfg);

        if (inlineQuery.length() > 0) {
            printResponse(service.search(inlineQuery.toString()), cfg);
            return 0;
        }
        return repl(service, cfg);
    }

    private static DocumentStore loadMetadata(SearchConfig cfg) {
        try {
            return DocumentStore.loadJsonl(Path.of(cfg.metadataJsonl));
        } catch (IOException e) {
            System.err.println("warning: metadata not loaded from '" + cfg.metadataJsonl
                    + "' (" + e.getMessage() + "). Results will show docId + score only, "
                    + "and NOT queries have no document universe.");
            return DocumentStore.empty();
        }
    }

    private static int repl(SearchService service, SearchConfig cfg) {
        System.out.println("Group 9 search (sparse retrieval + Okapi BM25 ranking). "
                + "Type a query, or 'exit'. Operators: AND:/OR:/NOT:/NEAR/N:");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            System.out.print("\nquery> ");
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }
                if (!line.isEmpty()) {
                    printResponse(service.search(line), cfg);
                }
                System.out.print("\nquery> ");
            }
        } catch (IOException e) {
            System.err.println("input error: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private static void printResponse(SearchService.Response resp, SearchConfig cfg) {
        if (!resp.unknownTerms.isEmpty()) {
            System.out.println("(terms not in index, ignored: " + resp.unknownTerms + ")");
        }
        if (resp.query.isEmpty() && resp.query.excludedTerms().isEmpty()) {
            System.out.println("(empty query)");
            return;
        }
        if (resp.results.isEmpty()) {
            System.out.println("No results. " + resp.query);
            return;
        }
        System.out.println(resp.query + "  (" + resp.candidateCount + " candidate(s))");
        for (SearchResult r : resp.results) {
            System.out.printf("%2d. [doc %d] score=%.4f%n", r.rank(), r.docId(), r.score());
            if (r.title() != null && !r.title().isBlank()) {
                System.out.println("    title: " + r.title());
            }
            if (r.url() != null && !r.url().isBlank()) {
                System.out.println("    url:   " + r.url());
            }
            if (r.snippet() != null && !r.snippet().isBlank()) {
                System.out.println("    …" + r.snippet());
            }
            if (cfg.explain) {
                printBreakdown(r.breakdown());
            }
        }
    }

    private static void printBreakdown(Map<String, Double> breakdown) {
        System.out.println("    BM25 per-term contributions:");
        for (Map.Entry<String, Double> e : breakdown.entrySet()) {
            System.out.printf("      %-20s %8.4f%n", e.getKey(), e.getValue());
        }
    }

    private static String req(String[] a, int i, String flag) {
        if (i >= a.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return a[i];
    }

    private static void usage(String err) {
        if (err != null) {
            System.err.println("error: " + err);
        }
        System.err.println(
            "Usage: SearchCli [--index DIR] [--meta JSONL] [--stats JSON] [--topk N] [--window N] "
            + "[--snippet-chars N] [--explain] [query...]\n"
            + "\n"
            + "  --index DIR        directory of inverted-index part-* files (default: index-local)\n"
            + "  --meta JSONL       JSONL document export for URL/title/snippet (default: week3-export/documents.jsonl)\n"
            + "  --stats JSON       published BM25 corpus-statistics artifact (optional; else derived from index)\n"
            + "  --topk N           number of results (default 10)\n"
            + "  --window N         default proximity window for NEAR queries (default 8)\n"
            + "  --snippet-chars N  max snippet length (default 160)\n"
            + "  --explain          print per-feature score breakdown\n"
            + "\n"
            + "  With a trailing query, runs once; otherwise starts an interactive prompt.\n"
            + "  Query operators: 'AND: a b', 'OR: a b', 'NOT: a -b', 'NEAR/5: a b'.");
    }
}
