package com.group9.websearch.search.cluster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;

/**
 * Explicit, validated wire formats for coordinator&lt;-&gt;worker communication. Plain JSON over
 * HTTP (no new dependency: JDK {@code HttpServer} + the project's existing Jackson). Keeping the
 * DTOs here lets both the {@link WorkerServer} and {@link HttpWorkerClient} share one schema so a
 * malformed/incompatible message is detected rather than silently mishandled.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /health} -&gt; {@link HealthResponse}: worker id, loaded partition ids, and
 *       the loaded index {@code partitionVersion} so the coordinator can verify every worker has
 *       the SAME authoritative index version before serving queries.</li>
 *   <li>{@code POST /lookup} with {@link LookupRequest} -&gt; {@link LookupResponse}: per-term
 *       df(t) and tf(t,d) (raw features, NOT local BM25 -- see {@link WorkerSearch}).</li>
 * </ul>
 */
public final class WorkerProtocol {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private WorkerProtocol() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LookupRequest {
        @JsonProperty("terms") public List<String> terms;
        /** Protocol version so an incompatible client/server is rejected, not mis-parsed. */
        @JsonProperty("protocol") public int protocol = PROTOCOL_VERSION;

        public LookupRequest() {
        }

        public LookupRequest(List<String> terms) {
            this.terms = terms;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TermResult {
        @JsonProperty("term") public String term;
        @JsonProperty("df") public int documentFrequency;
        /** docId (string key, JSON-safe) -> tf(t,d). */
        @JsonProperty("tf") public Map<String, Integer> termFreqByDoc;
        /**
         * docId (string key) -> sorted token positions of the term in that document, merged
         * across fields. Required for positional NEAR/n matching. Optional on the wire: an older
         * worker that omits it still answers tf/df correctly (NEAR simply has no positions to
         * match, which is the correct degraded behaviour, not a wrong answer).
         */
        @JsonProperty("pos") public Map<String, int[]> positionsByDoc;

        public TermResult() {
        }

        public TermResult(String term, int df, Map<String, Integer> tf,
                Map<String, int[]> positions) {
            this.term = term;
            this.documentFrequency = df;
            this.termFreqByDoc = tf;
            this.positionsByDoc = positions;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LookupResponse {
        @JsonProperty("protocol") public int protocol = PROTOCOL_VERSION;
        @JsonProperty("workerId") public String workerId;
        @JsonProperty("partitionVersion") public String partitionVersion;
        @JsonProperty("results") public List<TermResult> results;

        public LookupResponse() {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class HealthResponse {
        @JsonProperty("protocol") public int protocol = PROTOCOL_VERSION;
        @JsonProperty("workerId") public String workerId;
        @JsonProperty("partitionIds") public List<String> partitionIds;
        @JsonProperty("partitionVersion") public String partitionVersion;
        @JsonProperty("terms") public long termCount;
        @JsonProperty("ok") public boolean ok = true;

        public HealthResponse() {
        }
    }

    /** Current wire protocol version (bump on incompatible change). */
    public static final int PROTOCOL_VERSION = 1;
}
