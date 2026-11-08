package com.group9.websearch.search.cluster;

import com.group9.websearch.search.InvertedIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for a standalone search worker. Run one instance on each worker EC2;
 * it loads the partition index directory (or directories) that worker owns and serves lookups
 * over HTTP for the {@link DistributedCoordinator}.
 *
 * <p>Usage:
 * <pre>
 *   java -cp target/classes com.group9.websearch.search.cluster.WorkerServerCli \
 *        --worker-id P4 \
 *        --partition P4 --partition P5 \
 *        --index /data/index-worker4 \
 *        --bind 0.0.0.0 --port 9301 --threads 8
 * </pre>
 *
 * <p>Each worker loads ONE index directory containing all the {@code part-*} files it owns. This
 * is how Worker 4 serves BOTH P4 and P5: the deployment step (see the deploy script) places
 * {@code part-r-00003} (P4) and {@code part-r-00004} (P5) into that one directory, and because
 * term-range partitioning guarantees P4 and P5 share no terms, the merged dictionary is exact.
 * The {@code partitionVersion} is derived deterministically from the content of the loaded index
 * files (names + sizes + a SHA-256 digest), so the coordinator can verify every worker loaded
 * the SAME authoritative index build via {@code /health}. Mismatched versions indicate a bad
 * deploy.
 *
 * <p>The process runs until interrupted (Ctrl-C / SIGTERM); a shutdown hook stops the HTTP
 * server cleanly. To stop a worker started in the background, send it SIGTERM (see deployment
 * docs for the exact start/stop commands).
 */
public final class WorkerServerCli {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerServerCli.class);

    public static void main(String[] args) throws Exception {
        String workerId = null;
        String bind = "0.0.0.0";
        int port = 9301;
        int threads = 8;
        List<String> partitionIds = new ArrayList<>();
        Path indexDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--worker-id" -> workerId = requireArg(args, ++i, "--worker-id");
                case "--partition" -> partitionIds.add(requireArg(args, ++i, "--partition"));
                case "--index" -> indexDir = Path.of(requireArg(args, ++i, "--index"));
                case "--bind" -> bind = requireArg(args, ++i, "--bind");
                case "--port" -> port = Integer.parseInt(requireArg(args, ++i, "--port"));
                case "--threads" -> threads = Integer.parseInt(requireArg(args, ++i, "--threads"));
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }
        if (workerId == null || partitionIds.isEmpty() || indexDir == null) {
            System.err.println("missing required arguments");
            usage();
            System.exit(2);
        }

        InvertedIndex index = InvertedIndex.load(indexDir);
        String version = computePartitionVersion(List.of(indexDir));
        WorkerSearch search = new LocalWorkerSearch(workerId, index);
        WorkerServer server = new WorkerServer(search, partitionIds, version, index.termCount());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "worker-shutdown"));
        int bound = server.start(bind, port, threads);
        LOG.info("Worker {} serving {} on {}:{} (partitionVersion={})",
                workerId, partitionIds, bind, bound, version);
        // Block forever; the shutdown hook handles clean stop on SIGTERM/Ctrl-C.
        Thread.currentThread().join();
    }

    /**
     * Deterministic version string over the loaded index content: for each part file (sorted),
     * include its relative name and byte length, then SHA-256 the whole manifest. Two workers
     * that loaded byte-identical part files produce the SAME version; any difference changes it.
     */
    static String computePartitionVersion(List<Path> indexDirs) throws IOException {
        List<String> manifest = new ArrayList<>();
        for (Path dir : indexDirs) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(f -> f.getFileName().toString().startsWith("part-"))
                        .sorted().toList()) {
                    manifest.add(p.getFileName() + ":" + Files.size(p));
                }
            }
        }
        String joined = String.join("|", manifest);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8 && i < digest.length; i++) { // first 8 bytes is plenty
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String requireArg(String[] args, int i, String name) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + name);
        }
        return args[i];
    }

    private static void usage() {
        System.err.println("Usage: WorkerServerCli --worker-id <id> "
                + "--partition <Pn> [--partition <Pm> ...] "
                + "--index <dir> [--index <dir> ...] "
                + "[--bind 0.0.0.0] [--port 9301] [--threads 8]");
    }

    private WorkerServerCli() {
    }
}
