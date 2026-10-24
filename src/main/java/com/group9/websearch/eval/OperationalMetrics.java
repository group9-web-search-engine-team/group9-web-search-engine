package com.group9.websearch.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Operational (non-retrieval) measurements the report asks for, computed only from what the
 * existing system genuinely exposes. Anything that cannot be derived reliably is reported as
 * {@code unavailable} with a note explaining the missing instrumentation, never invented.
 *
 * <ul>
 *   <li><b>Query latency</b> -- measured here directly: each evaluated query is timed, and this
 *       class computes average and p50/p90/p95/p99 from the samples. Available.</li>
 *   <li><b>Index size on disk</b> -- summed byte size of the index snapshot directory.
 *       Available when the snapshot is a local path.</li>
 *   <li><b>Indexed document count</b> -- taken from the published stats artifact / corpus
 *       statistics (N). Available when stats are provided.</li>
 *   <li><b>Indexing throughput (docs or tokens / sec)</b> -- requires job start/end timestamps
 *       and processed counts. The MapReduce job records counters (documents, tokens) but the
 *       evaluation process does not have the job's wall-clock duration unless supplied, so
 *       throughput is reported as unavailable unless a duration is provided. Documented.</li>
 *   <li><b>Crawl throughput / harvest rate / coverage</b> -- these live in PostgreSQL
 *       crawl_state / documents and crawler logs, not in the evaluation inputs; reported as
 *       unavailable here with guidance, rather than guessed.</li>
 * </ul>
 */
public final class OperationalMetrics {

    private final List<Long> queryLatenciesNanos = new ArrayList<>();

    /** Peak used JVM heap observed across sampling points (bytes); -1 until first sample. */
    private long peakUsedHeapBytes = -1L;

    public void recordQueryLatencyNanos(long nanos) {
        queryLatenciesNanos.add(nanos);
    }

    /**
     * Sample the current used JVM heap (total - free) and keep the running peak. Call at key
     * points (e.g. after loading the index, and after the query sweep) for a cheap, accurate
     * high-water mark without extra dependencies.
     */
    public void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        peakUsedHeapBytes = Math.max(peakUsedHeapBytes, used);
    }

    /** Current used JVM heap in bytes (total - free). */
    public static long currentUsedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Peak used JVM heap observed via {@link #sampleMemory()}; -1 if never sampled. */
    public long peakUsedHeapBytes() {
        return peakUsedHeapBytes;
    }

    /** Max heap the JVM may grow to (-Xmx), in bytes. */
    public static long maxHeapBytes() {
        return Runtime.getRuntime().maxMemory();
    }

    public int latencySampleCount() {
        return queryLatenciesNanos.size();
    }

    public double averageLatencyMillis() {
        if (queryLatenciesNanos.isEmpty()) {
            return Double.NaN;
        }
        long sum = 0;
        for (long v : queryLatenciesNanos) {
            sum += v;
        }
        return (sum / (double) queryLatenciesNanos.size()) / 1_000_000.0;
    }

    /** Percentile (0..100) of query latency in milliseconds; NaN if no samples. */
    public double latencyPercentileMillis(double percentile) {
        if (queryLatenciesNanos.isEmpty()) {
            return Double.NaN;
        }
        List<Long> sorted = new ArrayList<>(queryLatenciesNanos);
        Collections.sort(sorted);
        double rank = (percentile / 100.0) * (sorted.size() - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        double interp = (lo == hi) ? sorted.get(lo)
                : sorted.get(lo) + (rank - lo) * (sorted.get(hi) - sorted.get(lo));
        return interp / 1_000_000.0;
    }

    /** Total byte size of a local directory tree (e.g. the index snapshot); -1 if unavailable. */
    public static long directorySizeBytes(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return -1L;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Human-readable byte size. */
    public static String humanBytes(long bytes) {
        if (bytes < 0) {
            return "unavailable";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(java.util.Locale.ROOT, "%.2f %s", v, units[u]);
    }
}
