package com.group9.websearch.search.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone search-worker HTTP service. Runs on each worker EC2 instance, loads one or more
 * term-range index partitions (as a single {@link WorkerSearch}, e.g. a {@link LocalWorkerSearch}
 * over the merged partition files it owns), and answers coordinator lookups over the private
 * network.
 *
 * <p>Uses only the JDK {@link HttpServer} (no new dependency). Request/response bodies are the
 * validated JSON DTOs in {@link WorkerProtocol}. A malformed body or wrong protocol version is
 * rejected with HTTP 400 (never mis-parsed into a wrong answer). The worker publishes its loaded
 * {@code partitionVersion} on {@code /health} and echoes it on every {@code /lookup} response so
 * the coordinator can verify all workers run the SAME authoritative index version.
 */
public final class WorkerServer {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerServer.class);

    private final WorkerSearch search;
    private final List<String> partitionIds;
    private final String partitionVersion;
    private final long termCount;
    private HttpServer http;

    /**
     * @param search           the local search over this worker's loaded partition(s).
     * @param partitionIds     logical partitions this worker serves (e.g. ["P4","P5"]).
     * @param partitionVersion the authoritative index version this worker loaded (e.g. a
     *                         _SUCCESS timestamp or checksum); echoed for version verification.
     * @param termCount        number of terms loaded (for /health diagnostics).
     */
    public WorkerServer(WorkerSearch search, List<String> partitionIds,
            String partitionVersion, long termCount) {
        this.search = search;
        this.partitionIds = List.copyOf(partitionIds);
        this.partitionVersion = partitionVersion;
        this.termCount = termCount;
    }

    /** Start the HTTP server on the given bind address/port. Returns the actual bound port. */
    public int start(String bindHost, int port, int threads) throws IOException {
        http = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        http.setExecutor(Executors.newFixedThreadPool(Math.max(1, threads)));
        http.createContext("/health", this::handleHealth);
        http.createContext("/lookup", this::handleLookup);
        http.start();
        int bound = http.getAddress().getPort();
        LOG.info("WorkerServer up on {}:{} serving {} (version={}, {} terms)",
                bindHost, bound, partitionIds, partitionVersion, termCount);
        return bound;
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            LOG.info("WorkerServer stopped ({})", partitionIds);
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"GET only\"}");
            return;
        }
        WorkerProtocol.HealthResponse h = new WorkerProtocol.HealthResponse();
        h.workerId = search.id();
        h.partitionIds = partitionIds;
        h.partitionVersion = partitionVersion;
        h.termCount = termCount;
        h.ok = true;
        send(ex, 200, WorkerProtocol.MAPPER.writeValueAsString(h));
    }

    private void handleLookup(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"POST only\"}");
            return;
        }
        final WorkerProtocol.LookupRequest req;
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            req = WorkerProtocol.MAPPER.readValue(body, WorkerProtocol.LookupRequest.class);
        } catch (Exception bad) {
            send(ex, 400, "{\"error\":\"malformed lookup request\"}");
            return;
        }
        if (req.protocol != WorkerProtocol.PROTOCOL_VERSION) {
            send(ex, 400, "{\"error\":\"protocol mismatch: expected "
                    + WorkerProtocol.PROTOCOL_VERSION + ", got " + req.protocol + "\"}");
            return;
        }
        if (req.terms == null) {
            send(ex, 400, "{\"error\":\"missing 'terms'\"}");
            return;
        }
        List<WorkerSearch.TermPostingsResult> results = search.lookup(req.terms);
        WorkerProtocol.LookupResponse resp = new WorkerProtocol.LookupResponse();
        resp.workerId = search.id();
        resp.partitionVersion = partitionVersion;
        resp.results = new ArrayList<>(results.size());
        for (WorkerSearch.TermPostingsResult r : results) {
            Map<String, Integer> tf = new HashMap<>();
            for (Map.Entry<Long, Integer> e : r.termFreqByDoc.entrySet()) {
                tf.put(Long.toString(e.getKey()), e.getValue());
            }
            Map<String, int[]> pos = new HashMap<>();
            for (Map.Entry<Long, int[]> e : r.positionsByDoc.entrySet()) {
                pos.put(Long.toString(e.getKey()), e.getValue());
            }
            resp.results.add(new WorkerProtocol.TermResult(r.term, r.documentFrequency, tf, pos));
        }
        send(ex, 200, WorkerProtocol.MAPPER.writeValueAsString(resp));
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
