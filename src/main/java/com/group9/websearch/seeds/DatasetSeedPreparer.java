package com.group9.websearch.seeds;

import com.group9.websearch.crawler.DatasetUrlRewriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prepares the BEIR seed list for crawling:
 * <ol>
 *   <li>rewrites each URL with {@link DatasetUrlRewriter} (old PMC host, arXiv PDFs, ...),</li>
 *   <li>deduplicates the rewritten URLs (first occurrence wins), and</li>
 *   <li>interleaves them round-robin by host.</li>
 * </ol>
 *
 * <p>Why interleave: StormCrawler's FileSpout emits seeds in file order and the fetcher
 * keeps one politeness queue per host. The raw seed file has the first ~25,000 lines on a
 * single host, so only one queue was ever busy. Round-robin ordering keeps every host's
 * queue fed, so PMC (1 s crawl-delay), arXiv (15 s) and the doi.org redirect targets are
 * fetched in parallel instead of one after another.
 *
 * <p>Usage: {@code DatasetSeedPreparer <in seeds.txt> <out seeds.txt>}
 */
public final class DatasetSeedPreparer {

    private DatasetSeedPreparer() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: DatasetSeedPreparer <input-seeds.txt> <output-seeds.txt>");
            System.exit(2);
        }
        List<String> raw;
        try (BufferedReader r = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            raw = r.lines().toList();
        }
        List<String> rewritten = rewriteAndDedup(raw);
        List<String> ordered = interleaveByHost(rewritten);
        try (BufferedWriter w = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            for (String u : ordered) {
                w.write(u);
                w.write('\n');
            }
        }
        System.out.printf("read %d lines, wrote %d unique seeds to %s%n", raw.size(), ordered.size(), args[1]);
        countByHost(ordered).entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("%8d  %s%n", e.getValue(), e.getKey()));
    }

    /** Rewrite every non-blank, non-comment line and keep the first occurrence of each URL. */
    public static List<String> rewriteAndDedup(List<String> lines) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            out.add(DatasetUrlRewriter.rewrite(t));
        }
        return new ArrayList<>(out);
    }

    /**
     * Round-robin the URLs across hosts, preserving each host's relative order. Hosts are
     * visited in order of first appearance.
     */
    public static List<String> interleaveByHost(List<String> urls) {
        Map<String, Deque<String>> byHost = new LinkedHashMap<>();
        for (String u : urls) {
            byHost.computeIfAbsent(hostOf(u), h -> new ArrayDeque<>()).add(u);
        }
        List<String> out = new ArrayList<>(urls.size());
        while (!byHost.isEmpty()) {
            var it = byHost.values().iterator();
            while (it.hasNext()) {
                Deque<String> q = it.next();
                out.add(q.poll());
                if (q.isEmpty()) {
                    it.remove();
                }
            }
        }
        return out;
    }

    static Map<String, Integer> countByHost(List<String> urls) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String u : urls) {
            counts.merge(hostOf(u), 1, Integer::sum);
        }
        return counts;
    }

    static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
