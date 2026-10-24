package com.group9.websearch.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationalMetricsTest {

    @Test
    void averageAndPercentiles() {
        OperationalMetrics m = new OperationalMetrics();
        // latencies in ms: 10, 20, 30, 40, 50 (stored as nanos)
        for (int ms : new int[] {10, 20, 30, 40, 50}) {
            m.recordQueryLatencyNanos(ms * 1_000_000L);
        }
        assertEquals(5, m.latencySampleCount());
        assertEquals(30.0, m.averageLatencyMillis(), 1e-9);
        // p50 of [10..50] via linear interpolation on 0..4 index => index 2 => 30
        assertEquals(30.0, m.latencyPercentileMillis(50), 1e-9);
        // p100 => max => 50
        assertEquals(50.0, m.latencyPercentileMillis(100), 1e-9);
    }

    @Test
    void emptyLatencyIsNaN() {
        OperationalMetrics m = new OperationalMetrics();
        assertTrue(Double.isNaN(m.averageLatencyMillis()));
        assertTrue(Double.isNaN(m.latencyPercentileMillis(90)));
    }

    @Test
    void humanBytesFormatsAndMarksUnavailable() {
        assertEquals("unavailable", OperationalMetrics.humanBytes(-1));
        assertTrue(OperationalMetrics.humanBytes(1024).startsWith("1.00 KB"));
        assertTrue(OperationalMetrics.humanBytes(0).startsWith("0.00 B"));
    }
}
