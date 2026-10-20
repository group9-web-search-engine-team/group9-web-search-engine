package com.group9.websearch.seeds;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic, conservative URL normalization for seed generation.
 *
 * <p>Design goals (aligned with the crawler-mvp requirements/design for URL handling):
 * <ul>
 *   <li>Accept only {@code http} and {@code https} schemes.</li>
 *   <li>Lowercase only the scheme and host; preserve the case of path and query.</li>
 *   <li>Remove the fragment component ({@code #...}).</li>
 *   <li>Remove the port when it is the scheme default (80 for http, 443 for https);
 *       retain any non-default port.</li>
 *   <li>Do NOT silently rewrite paths or query strings in ways that could change the
 *       identity of the resource (no query reordering, no trailing-slash edits,
 *       no percent-decoding).</li>
 *   <li>Normalization is idempotent: {@code normalize(normalize(u)) == normalize(u)}.</li>
 * </ul>
 *
 * <p>This class is a pure function with no I/O, so it is directly unit-testable.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
        // utility class
    }

    /**
     * Normalize a raw URL string to a canonical form, or return empty if the input is
     * not a supported, well-formed absolute HTTP/HTTPS URL.
     *
     * @param raw the raw URL string (may be null/blank)
     * @return the canonical URL, or {@link Optional#empty()} if the URL is unsupported or malformed
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        if (!uri.isAbsolute()) {
            return Optional.empty();
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return Optional.empty();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }

        // Host is required and must be non-empty. Reject opaque URIs (e.g. "http:foo")
        // and authority-less forms.
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.empty();
        }
        host = host.toLowerCase(Locale.ROOT);

        // Drop default ports; keep meaningful non-default ports.
        int port = uri.getPort();
        if (isDefaultPort(scheme, port)) {
            port = -1;
        }

        // Preserve raw path and query exactly (no decoding, no reordering, no edits).
        String rawPath = safeComponent(trimmed, uri.getRawPath());
        String rawQuery = uri.getRawQuery();

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(rawPath);
        if (rawQuery != null) {
            sb.append('?').append(rawQuery);
        }
        // Fragment intentionally dropped.

        return Optional.of(sb.toString());
    }

    private static boolean isDefaultPort(String scheme, int port) {
        if (port == -1) {
            return true;
        }
        return ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
    }

    /**
     * When the authority is present but the path is empty (e.g. {@code https://example.com}),
     * {@link URI#getRawPath()} returns an empty string. We keep it empty rather than forcing
     * a trailing slash, to avoid changing resource identity. The {@code original} argument is
     * reserved for potential future diagnostics and is currently unused beyond null-safety.
     */
    private static String safeComponent(String original, String rawPath) {
        return rawPath == null ? "" : rawPath;
    }
}
