package com.group9.websearch.crawler.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin, fail-safe client for the persistent QT5-Small scoring service
 * ({@code quality_model/qt5_server.py}).
 *
 * <p>Design (matches the integration requirements):
 * <ul>
 *   <li>Talks to a local HTTP service that loads the model ONCE — no per-URL Python
 *       process spawn.</li>
 *   <li>Applies a configurable connect/request timeout so crawl workers never block
 *       indefinitely.</li>
 *   <li>Bounds the text sent (truncates to a max character budget) so a huge page cannot
 *       blow up the request.</li>
 *   <li>Fails SAFE: if the service is unavailable, times out, or returns anything
 *       unexpected, {@link #score(String)} returns {@link Optional#empty()} and the crawl
 *       proceeds unaffected (no exception propagates to the topology).</li>
 *   <li>Never logs page text or credentials — only lengths and status.</li>
 * </ul>
 *
 * <p>The returned score is the model's RAW QT5 log-probability of the "true" target
 * (a value &le; 0). It is intentionally NOT normalised here and is kept distinct from
 * pagerank / QMax.
 */
public final class Qt5QualityClient {

    private static final Logger LOG = LoggerFactory.getLogger(Qt5QualityClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final URI scoreUri;
    private final Duration timeout;
    private final int maxChars;
    private final boolean enabled;

    public Qt5QualityClient(String baseUrl, Duration timeout, int maxChars, boolean enabled) {
        this.scoreUri = URI.create(trimTrailingSlash(baseUrl) + "/score");
        this.timeout = timeout;
        this.maxChars = Math.max(1, maxChars);
        this.enabled = enabled;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    /**
     * Score one page's extracted text. Returns the raw QT5 score, or empty if scoring is
     * disabled, the text is blank, or the service is unavailable / slow / erroring.
     */
    public Optional<Double> score(String text) {
        if (!enabled) {
            return Optional.empty();
        }
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String bounded = text.length() > maxChars ? text.substring(0, maxChars) : text;
        try {
            String body = MAPPER.writeValueAsString(java.util.Map.of("text", bounded));
            HttpRequest req = HttpRequest.newBuilder(scoreUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warn("QT5 scorer returned HTTP {} (text length {}); skipping score.",
                        resp.statusCode(), bounded.length());
                return Optional.empty();
            }
            JsonNode node = MAPPER.readTree(resp.body());
            JsonNode score = node.get("score");
            if (score == null || score.isNull() || !score.isNumber()) {
                return Optional.empty();
            }
            double v = score.asDouble();
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return Optional.empty();
            }
            return Optional.of(v);
        } catch (Exception e) {
            // Fail safe: never let a scorer problem break the crawl pipeline.
            LOG.warn("QT5 scoring unavailable ({}); continuing without a quality score.", e.toString());
            return Optional.empty();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null || s.isBlank()) {
            return "http://127.0.0.1:8077";
        }
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }
}
