package com.group9.websearch.search.cluster;

import com.group9.websearch.index.ExportedDocument;
import com.group9.websearch.index.TermPartitioner;
import com.group9.websearch.index.Tokenizer;
import com.group9.websearch.search.Bm25Params;
import com.group9.websearch.search.CorpusStatistics;
import com.group9.websearch.search.CorpusStatsArtifact;
import com.group9.websearch.search.DocumentStore;
import com.group9.websearch.search.SnippetBuilder;
import java.util.Optional;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Production entry point that runs the DISTRIBUTED search engine on the coordinator host. It
 * wires together the already-implemented, already-tested distributed components -- it adds no
 * new retrieval, scoring, tokenisation, or indexing logic of its own:
 * <ul>
 *   <li>{@link ClusterConfig#load(Path)} -- reads {@code config/cluster.properties} for the five
 *       partition worker addresses (P1..P5) and the per-query timeout.</li>
 *   <li>{@link CorpusStatsArtifact}/{@link CorpusStatistics#fromArtifact} -- loads the published
 *       global stats ({@code stats/corpus-stats.json}) so BM25 uses the full-corpus N/avgdl/dl.</li>
 *   <li>{@link HttpWorkerClient} -- one per partition, pointed at the configured worker.</li>
 *   <li>{@link DistributedCoordinator} -- the tested fan-out + global BM25 + dedup logic.</li>
 * </ul>
 *
 * <p><b>Query semantics:</b> the raw line is normalised with the SAME indexing
 * {@link Tokenizer#tokenize(String)} the index was built with (no second tokeniser), and the
 * distinct tokens are passed to {@link DistributedCoordinator#search(List, int)}. The coordinator
 * implements OR-union retrieval with global BM25 ranking ONLY. There is deliberately NO AND / NOT
 * / NEAR support here, because the distributed coordinator does not implement those operators;
 * this CLI does not pretend otherwise.
 *
 * <p><b>Startup health gate (version semantics):</b> each worker's {@code partitionVersion}
 * (see {@link WorkerServerCli#computePartitionVersion}) is a hash of ITS OWN part files, so by
 * design every partition reports a DIFFERENT hash -- they hold different content. Therefore
 * {@link PartitionVersionVerifier}'s "all identical" rule does NOT apply to this per-partition
 * deployment and must NOT be used to reject startup. Instead the gate only requires that every
 * worker is reachable and reports {@code ok=true} on {@code /health}; the per-partition versions
 * are printed for operator visibility, never used to fail a healthy cluster.
 */
public final class CoordinatorCli {

    private CoordinatorCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String clusterConfig = "config/cluster.properties";
        String statsPath = "stats/corpus-stats.json";
        String metaPath = null;
        int topK = 10;
        int snippetMaxChars = 160;
        int snippetContextTokens = 8;
        long connectMillis = 2000;
        String inlineQuery = null;
        boolean requireAllWorkers = false;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--config" -> clusterConfig = req(args, ++i, "--config");
                    case "--stats" -> statsPath = req(args, ++i, "--stats");
                    case "--meta" -> metaPath = req(args, ++i, "--meta");
                    case "--topk" -> topK = Integer.parseInt(req(args, ++i, "--topk"));
                    case "--snippet-chars" ->
                            snippetMaxChars = Integer.parseInt(req(args, ++i, "--snippet-chars"));
                    case "--connect-timeout" ->
                            connectMillis = Long.parseLong(req(args, ++i, "--connect-timeout"));
                    case "--require-all-workers" -> requireAllWorkers = true;
                    case "--query" -> inlineQuery = req(args, ++i, "--query");
                    case "-h", "--help" -> {
                        usage(out);
                        return 0;
                    }
                    default -> {
                        usage(err);
                        return 2;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            usage(err);
            return 2;
        }

        // 1) Cluster config (five worker addresses + query timeout).
        final ClusterConfig cluster;
        try {
            cluster = ClusterConfig.load(Path.of(clusterConfig));
        } catch (IOException e) {
            err.println("error: could not load cluster config '" + clusterConfig + "': "
                    + e.getMessage());
            return 1;
        }

        // 2) Global corpus statistics artifact.
        final CorpusStatistics stats;
        try {
            CorpusStatsArtifact art = CorpusStatsArtifact.load(Path.of(statsPath));
            stats = CorpusStatistics.fromArtifact(art);
        } catch (IOException e) {
            err.println("error: could not load corpus stats '" + statsPath + "': "
                    + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            err.println("error: corpus stats unusable: " + e.getMessage());
            return 1;
        }
        out.println("Loaded global corpus stats: N=" + stats.numDocs()
                + " avgdl=" + String.format(java.util.Locale.ROOT, "%.3f", stats.avgDocLength())
                + " (source=" + stats.source() + ")");

        // Optional document metadata (title/url/snippet). Reuses the SAME DocumentStore the
        // single-node SearchCli uses; the export is the ExportedDocument JSONL the index was
        // built from, so docIds line up exactly. If absent/unreadable we fall back gracefully to
        // docId + score without breaking distributed retrieval.
        DocumentStore documents = DocumentStore.empty();
        if (metaPath != null) {
            try {
                documents = DocumentStore.loadJsonl(Path.of(metaPath));
                out.println("Loaded document metadata: " + documents.size()
                        + " doc(s) from " + metaPath);
            } catch (IOException e) {
                err.println("warning: could not load metadata '" + metaPath + "' ("
                        + e.getMessage() + "); results will show docId + score only.");
            }
        } else {
            out.println("No --meta supplied; results will show docId + score only.");
        }
        SnippetBuilder snippets = new SnippetBuilder(snippetMaxChars, snippetContextTokens);

        // 3) One HTTP worker client per partition, from the configured addresses.
        long queryTimeout = cluster.queryTimeoutMillis();
        HttpWorkerClient[] clients = new HttpWorkerClient[TermPartitioner.NUM_PARTITIONS];
        WorkerSearch[] workers = new WorkerSearch[TermPartitioner.NUM_PARTITIONS];
        for (int p = 0; p < TermPartitioner.NUM_PARTITIONS; p++) {
            String addr = cluster.addressForPartition(p);
            // Shorter connect timeout for the /health phase; query (read) timeout from config.
            clients[p] = new HttpWorkerClient(
                    TermPartitioner.PARTITION_IDS[p], "http://" + addr, connectMillis, queryTimeout);
            workers[p] = clients[p];
        }

        // 4) Health gate: every worker reachable and ok. Versions differ per partition BY
        //    DESIGN, so we only report them -- we do NOT require a single shared hash.
        int reachable = 0;
        List<String> unreachable = new ArrayList<>();
        for (int p = 0; p < clients.length; p++) {
            String pid = TermPartitioner.PARTITION_IDS[p];
            try {
                WorkerProtocol.HealthResponse h = clients[p].health();
                out.println("  " + pid + " @ " + cluster.addressForPartition(p)
                        + "  ok=" + h.ok + "  partitions=" + h.partitionIds
                        + "  version=" + h.partitionVersion + "  terms=" + h.termCount);
                if (h.ok) {
                    reachable++;
                } else {
                    unreachable.add(pid + " (ok=false)");
                }
            } catch (RuntimeException ex) {
                unreachable.add(pid + " (" + ex.getMessage() + ")");
                out.println("  " + pid + " @ " + cluster.addressForPartition(p)
                        + "  UNREACHABLE: " + ex.getMessage());
            }
        }
        if (!unreachable.isEmpty()) {
            err.println("warning: " + unreachable.size() + " partition(s) not healthy: "
                    + unreachable);
            if (requireAllWorkers) {
                err.println("error: --require-all-workers set and not all workers are healthy; "
                        + "aborting startup.");
                return 1;
            }
            err.println("continuing; queries touching an unhealthy partition will report it as a "
                    + "failed partition (partial results).");
        }
        out.println(reachable + "/" + TermPartitioner.NUM_PARTITIONS + " partitions healthy.");

        // 5) The tested distributed coordinator (global BM25).
        DistributedCoordinator coordinator =
                new DistributedCoordinator(workers, stats, Bm25Params.defaults(), queryTimeout);

        if (inlineQuery != null) {
            runOneQuery(coordinator, inlineQuery, topK, documents, snippets, out);
            return 0;
        }
        return repl(coordinator, topK, documents, snippets, out);
    }

    private static int repl(DistributedCoordinator coordinator, int topK,
            DocumentStore documents, SnippetBuilder snippets, PrintStream out) {
        out.println("\nGroup 9 DISTRIBUTED search (OR-union retrieval + global Okapi BM25).");
        out.println("Type a query, or 'exit'. (AND/NOT/NEAR are NOT supported in distributed mode.)");
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            out.print("\nquery> ");
            String line;
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }
                if (!line.isEmpty()) {
                    runOneQuery(coordinator, line, topK, documents, snippets, out);
                }
                out.print("\nquery> ");
            }
        } catch (IOException e) {
            out.println("input error: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private static void runOneQuery(DistributedCoordinator coordinator, String raw, int topK,
            DocumentStore documents, SnippetBuilder snippets, PrintStream out) {
        long t0 = System.currentTimeMillis();
        final DistributedCoordinator.Response resp;
        final String diag;
        if (BooleanQuery.isBooleanExpression(raw)) {
            // Boolean / positional-proximity query: AND/OR/NOT/NEAR/n with precedence
            // NOT > AND > OR > NEAR/n. Plain multi-term queries fall through to the OR path below.
            BooleanQuery bq = BooleanQuery.parse(raw);
            if (bq.terms().isEmpty()) {
                out.println("(no searchable terms after tokenisation)");
                return;
            }
            resp = coordinator.searchBoolean(bq, topK);
            diag = "boolean=" + raw.trim() + "  terms=" + bq.termList();
        } else {
            // Reuse the indexing tokenizer -- identical normalisation to how the index was built.
            List<String> terms = Tokenizer.tokenize(raw);
            if (terms.isEmpty()) {
                out.println("(no searchable terms after tokenisation)");
                return;
            }
            resp = coordinator.search(terms, topK);
            diag = "terms=" + terms;
        }
        long ms = System.currentTimeMillis() - t0;
        // Snippet terms: the plain tokens of the raw query (operators tokenise to terms too, but
        // the snippet builder only highlights tokens that actually occur in the body).
        List<String> terms = Tokenizer.tokenize(raw);

        out.println(diag + "  candidates=" + resp.candidateCount
                + "  failedPartitions=" + resp.failedPartitions + "  (" + ms + " ms)");
        if (resp.hits.isEmpty()) {
            out.println("No results.");
            return;
        }
        // Render each ranked hit with metadata (title/url/snippet) when available, exactly like
        // the single-node SearchCli. Metadata is resolved by docId via the shared DocumentStore;
        // a missing entry gracefully degrades to docId + score only.
        int rank = 1;
        for (DistributedCoordinator.Hit h : resp.hits) {
            out.printf(java.util.Locale.ROOT, "%2d. [doc %d] score=%.6f%n",
                    rank++, h.docId, h.score);
            Optional<ExportedDocument> meta = documents.get(h.docId);
            if (meta.isPresent()) {
                ExportedDocument d = meta.get();
                if (d.title() != null && !d.title().isBlank()) {
                    out.println("    title: " + d.title());
                }
                if (d.url() != null && !d.url().isBlank()) {
                    out.println("    url:   " + d.url());
                }
                String snippet = snippets.build(d.body(), terms);
                if (snippet != null && !snippet.isBlank()) {
                    out.println("    …" + snippet);
                }
            }
        }
        if (!resp.failedPartitions.isEmpty()) {
            out.println("NOTE: results are PARTIAL; these partitions did not respond: "
                    + resp.failedPartitions);
        }
    }

    private static String req(String[] a, int i, String flag) {
        if (i >= a.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return a[i];
    }

    private static void usage(PrintStream s) {
        s.println("Usage: CoordinatorCli [--config config/cluster.properties] "
                + "[--stats stats/corpus-stats.json] [--meta documents.jsonl] [--topk N] "
                + "[--snippet-chars N] [--connect-timeout MS] [--require-all-workers] "
                + "[--query \"terms\"]");
        s.println();
        s.println("  --config                cluster properties with worker.P1..P5 addresses");
        s.println("  --stats                 published global corpus-stats JSON artifact");
        s.println("  --meta JSONL            document metadata export (ExportedDocument JSONL) for");
        s.println("                          title/url/snippet; omit to show docId + score only");
        s.println("  --topk N                number of ranked results (default 10)");
        s.println("  --snippet-chars N       max snippet length (default 160)");
        s.println("  --connect-timeout MS    per-worker TCP connect timeout for /health (default 2000)");
        s.println("  --require-all-workers   abort startup if any worker is unhealthy");
        s.println("  --query \"terms\"         run one query and exit; otherwise interactive prompt");
        s.println();
        s.println("  Distributed retrieval is OR-union + global BM25 only (no AND/NOT/NEAR).");
    }
}
