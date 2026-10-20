package com.group9.websearch.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.apache.storm.spout.ISpoutOutputCollector;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BoundedFileSpout.CountingCollector}, the authoritative global
 * page limiter. Verifies that at most {@code cap} emissions pass through regardless of
 * how many the underlying FileSpout attempts (i.e. the 53k BEIR seeds cannot cause
 * unbounded fetching).
 */
class BoundedFileSpoutTest {

    /** Recording delegate that counts how many emissions actually reach the topology. */
    static final class RecordingCollector implements ISpoutOutputCollector {
        final List<List<Object>> emitted = new ArrayList<>();

        @Override
        public List<Integer> emit(String streamId, List<Object> tuple, Object messageId) {
            emitted.add(tuple);
            return List.of(0);
        }

        @Override
        public void emitDirect(int taskId, String streamId, List<Object> tuple, Object messageId) {
            emitted.add(tuple);
        }

        @Override public void flush() { }
        @Override public void reportError(Throwable error) { }
        @Override public long getPendingCount() { return 0L; }
    }

    @Test
    void stopsEmittingAfterCap() {
        RecordingCollector rec = new RecordingCollector();
        BoundedFileSpout.CountingCollector capped = new BoundedFileSpout.CountingCollector(rec, 5);

        // Attempt to emit far more than the cap (simulating the 53k-line seed file).
        for (int i = 0; i < 1000; i++) {
            capped.emit("default", List.of("https://example.com/" + i), "msg-" + i);
        }

        assertEquals(5, capped.emitted(), "at most 5 emissions should pass the cap");
        assertEquals(5, rec.emitted.size(), "delegate must receive only 5 emissions");
        assertTrue(capped.reachedCap());
    }

    @Test
    void capAppliesToEmitDirectToo() {
        RecordingCollector rec = new RecordingCollector();
        BoundedFileSpout.CountingCollector capped = new BoundedFileSpout.CountingCollector(rec, 3);
        for (int i = 0; i < 50; i++) {
            capped.emitDirect(1, "default", List.of("https://example.com/" + i), "m" + i);
        }
        assertEquals(3, capped.emitted());
        assertEquals(3, rec.emitted.size());
    }

    @Test
    void nonPositiveCapMeansUnlimited() {
        RecordingCollector rec = new RecordingCollector();
        BoundedFileSpout.CountingCollector unlimited =
                new BoundedFileSpout.CountingCollector(rec, BoundedFileSpout.DEFAULT_MAX_SEEDS);
        for (int i = 0; i < 500; i++) {
            unlimited.emit("default", List.of("https://example.com/" + i), "m" + i);
        }
        assertEquals(500, rec.emitted.size(), "a non-positive cap must not limit emissions");
        assertFalse(unlimited.reachedCap());
    }

    @Test
    void reachedCapIsFalseBeforeCap() {
        RecordingCollector rec = new RecordingCollector();
        BoundedFileSpout.CountingCollector capped = new BoundedFileSpout.CountingCollector(rec, 2);
        assertFalse(capped.reachedCap());
        capped.emit("default", List.of("https://example.com/a"), "a");
        assertFalse(capped.reachedCap());
        capped.emit("default", List.of("https://example.com/b"), "b");
        assertTrue(capped.reachedCap());
    }
}
