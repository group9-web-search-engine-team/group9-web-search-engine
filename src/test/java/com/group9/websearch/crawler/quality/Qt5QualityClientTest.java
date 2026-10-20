package com.group9.websearch.crawler.quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests the fail-safe behaviour of the QT5 quality client WITHOUT a running scorer.
 * These guarantee a crawl worker never blocks indefinitely or throws because of the
 * optional scorer.
 */
class Qt5QualityClientTest {

    @Test
    void disabledClientReturnsEmptyWithoutNetworkCall() {
        Qt5QualityClient client = new Qt5QualityClient(
                "http://127.0.0.1:9", Duration.ofMillis(200), 1000, false);
        assertFalse(client.isEnabled());
        assertTrue(client.score("some page text").isEmpty());
    }

    @Test
    void blankTextReturnsEmpty() {
        Qt5QualityClient client = new Qt5QualityClient(
                "http://127.0.0.1:9", Duration.ofMillis(200), 1000, true);
        assertTrue(client.score("").isEmpty());
        assertTrue(client.score("   ").isEmpty());
        assertTrue(client.score(null).isEmpty());
    }

    @Test
    void unavailableServiceFailsSafeToEmpty() {
        // Port 9 (discard) with a short timeout: connection fails fast -> empty, no throw.
        Qt5QualityClient client = new Qt5QualityClient(
                "http://127.0.0.1:9", Duration.ofMillis(300), 1000, true);
        Optional<Double> score = client.score("COVID-19 clinical features and treatment.");
        assertTrue(score.isEmpty(), "unreachable scorer must fail safe to empty");
    }

    @Test
    void timeoutFailsSafeToEmpty() {
        // Non-routable TEST-NET-1 address with a tiny timeout forces a timeout path.
        Qt5QualityClient client = new Qt5QualityClient(
                "http://192.0.2.1:8077", Duration.ofMillis(200), 1000, true);
        Optional<Double> score = client.score("some page text to score");
        assertTrue(score.isEmpty(), "timeout must fail safe to empty");
    }
}
