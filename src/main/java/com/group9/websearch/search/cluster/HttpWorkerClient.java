package com.group9.websearch.search.cluster;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link WorkerSearch} that talks to a remote {@link WorkerServer} over HTTP/JSON on
 * the private network. One instance targets one worker address ({@code host:port}).
 *
 * <p>It is a drop-in replacement for {@link LocalWorkerSearch} in the
 * {@link DistributedCoordinator}'s {@code WorkerSearch[]} array, so ALL the tested coordinator
 * logic (routing, dedup, deterministic order, global BM25, failed-partition handling) is reused
 * unchanged. The only difference is that {@link #lookup(List)} goes over the wire.
 *
 * <p><b>Fault handling (critical):</b> any transport problem -- connect failure, read/response
 * timeout, non-200 status, protocol-version mismatch, or malformed JSON -- is surfaced as an
 * unchecked {@link WorkerUnavailableException}. The coordinator already catches a thrown lookup
 * and records that partition as FAILED, so an unavailable worker yields a clearly-flagged
 * partial result instead of silently-missing postings or fabricated zeros. This client NEVER
 * returns an empty-but-successful result to mask a failure.
 */
public final class HttpWorkerClient implements WorkerSearch {

    private static final Logger LOG = LoggerFactory.getLogger(HttpWorkerClient.class);

    /** Thrown when a worker cannot be reached or returns an invalid/unexpected response. */
    public static final class WorkerUnavailableException extends RuntimeException {
        public WorkerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public WorkerUnavailableException(String message) {
            super(message);
        }
    }

    private final String id;
    private final URI lookupUri;
    private final URI healthUri;
    private final HttpClient http;
    private final Duration requestTimeout;

    /**
     * @param id             stable identifier for logs (e.g. the partition id "P4").
     * @param baseUrl        worker base URL, e.g. {@code http://10.0.15.149:9301}.
     * @param connectMillis  TCP connect timeout.
     * @param requestMillis  per-request response timeout.
     */
    public HttpWorkerClient(String id, String baseUrl, long connectMillis, long requestMillis) {
        this.id = id;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.lookupUri = URI.create(base + "/lookup");
        this.healthUri = URI.create(base + "/health");
        this.requestTimeout = Duration.ofMillis(requestMillis);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMillis))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Convenience factory from a "host:port" address and a single timeout for both phases. */
    public static HttpWorkerClient fromAddress(String id, String hostPort, long timeoutMillis) {
        return new HttpWorkerClient(id, "http://" + hostPort, timeoutMillis, timeoutMillis);
    }

    @Override
    public List<TermPostingsResult> lookup(List<String> terms) {
        WorkerProtocol.LookupRequest reqBody = new WorkerProtocol.LookupRequest(terms);
        final byte[] payload;
        try {
            payload = WorkerProtocol.MAPPER.writeValueAsBytes(reqBody);
        } catch (IOException e) {
            throw new WorkerUnavailableException("failed to serialise lookup request", e);
        }
        HttpRequest request = HttpRequest.newBuilder(lookupUri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        final HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new WorkerUnavailableException("worker " + id + " lookup I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException("worker " + id + " lookup interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new WorkerUnavailableException(
                    "worker " + id + " returned HTTP " + response.statusCode());
        }
        final WorkerProtocol.LookupResponse resp;
        try {
            resp = WorkerProtocol.MAPPER.readValue(response.body(),
                    WorkerProtocol.LookupResponse.class);
        } catch (IOException e) {
            throw new WorkerUnavailableException("worker " + id + " malformed lookup response", e);
        }
        if (resp.protocol != WorkerProtocol.PROTOCOL_VERSION) {
            throw new WorkerUnavailableException("worker " + id + " protocol mismatch: expected "
                    + WorkerProtocol.PROTOCOL_VERSION + ", got " + resp.protocol);
        }
        if (resp.results == null) {
            throw new WorkerUnavailableException("worker " + id + " returned no results field");
        }
        List<TermPostingsResult> out = new ArrayList<>(resp.results.size());
        for (WorkerProtocol.TermResult tr : resp.results) {
            Map<Long, Integer> tf = new HashMap<>();
            if (tr.termFreqByDoc != null) {
                for (Map.Entry<String, Integer> e : tr.termFreqByDoc.entrySet()) {
                    tf.put(Long.parseLong(e.getKey()), e.getValue());
                }
            }
            Map<Long, int[]> pos = new HashMap<>();
            if (tr.positionsByDoc != null) {
                for (Map.Entry<String, int[]> e : tr.positionsByDoc.entrySet()) {
                    pos.put(Long.parseLong(e.getKey()), e.getValue());
                }
            }
            out.add(new TermPostingsResult(tr.term, tr.documentFrequency, tf, pos));
        }
        return out;
    }

    /**
     * Query this worker's {@code /health}. Used by the coordinator to verify partition version
     * before serving queries. Throws {@link WorkerUnavailableException} on any failure.
     */
    public WorkerProtocol.HealthResponse health() {
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(requestTimeout)
                .GET()
                .build();
        final HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new WorkerUnavailableException("worker " + id + " health I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException("worker " + id + " health interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new WorkerUnavailableException(
                    "worker " + id + " health HTTP " + response.statusCode());
        }
        try {
            WorkerProtocol.HealthResponse h = WorkerProtocol.MAPPER.readValue(
                    response.body(), WorkerProtocol.HealthResponse.class);
            if (h.protocol != WorkerProtocol.PROTOCOL_VERSION) {
                throw new WorkerUnavailableException("worker " + id + " health protocol mismatch");
            }
            return h;
        } catch (IOException e) {
            throw new WorkerUnavailableException("worker " + id + " malformed health response", e);
        }
    }

    @Override
    public String id() {
        return id;
    }
}
