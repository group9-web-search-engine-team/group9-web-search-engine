package com.group9.websearch.search;

import com.group9.websearch.index.IndexRecordCodec;
import com.group9.websearch.index.PostingList;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory read-only view of the positional inverted index produced by the Week 3 MapReduce
 * job. It loads the term -> {@link PostingList} dictionary from the on-disk {@code part-*}
 * files (the exact {@link IndexRecordCodec} format), without modifying or rebuilding anything.
 *
 * <p>Input is a directory of index part files on the local filesystem. The Hadoop job writes
 * the index to HDFS; to search it offline, copy it down once (e.g.
 * {@code hdfs dfs -get /week3/index ./index-local}) and point the search CLI at that directory.
 * This keeps the online engine free of a hard Hadoop runtime dependency.
 *
 * <p>Robustness: a malformed index line is counted and skipped (never silently mis-parsed and
 * never fatal), so a single corrupt record cannot take down querying. The index is read exactly
 * as written; because partitioning is by term, each term appears in exactly one part file, so
 * the loaded dictionary needs no cross-file term merge (but if duplicates were present they are
 * merged defensively).
 */
public final class InvertedIndex {

    private static final Logger LOG = LoggerFactory.getLogger(InvertedIndex.class);

    private final Map<String, PostingList> dictionary;
    private final long malformedLines;

    private InvertedIndex(Map<String, PostingList> dictionary, long malformedLines) {
        this.dictionary = dictionary;
        this.malformedLines = malformedLines;
    }

    /** Terms known to the index. */
    public Set<String> terms() {
        return dictionary.keySet();
    }

    public int termCount() {
        return dictionary.size();
    }

    public long malformedLines() {
        return malformedLines;
    }

    /** Postings for a term, or {@code null} if the term is unknown. */
    public PostingList postings(String term) {
        return dictionary.get(term);
    }

    public boolean contains(String term) {
        return dictionary.containsKey(term);
    }

    /**
     * Load every {@code part-*} file under {@code indexDir}. Non-part files (e.g. {@code _SUCCESS})
     * are ignored. Lines that cannot be decoded are skipped and counted.
     */
    public static InvertedIndex load(Path indexDir) throws IOException {
        if (!Files.isDirectory(indexDir)) {
            throw new IOException("index directory not found or not a directory: " + indexDir);
        }
        Map<String, PostingList> dict = new HashMap<>();
        long[] malformed = {0L};
        List<Path> parts;
        try (Stream<Path> s = Files.list(indexDir)) {
            parts = s.filter(p -> p.getFileName().toString().startsWith("part-"))
                    .sorted()
                    .toList();
        }
        if (parts.isEmpty()) {
            LOG.warn("No part-* files found in index directory {}", indexDir);
        }
        for (Path part : parts) {
            try (BufferedReader br = Files.newBufferedReader(part, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    final IndexRecordCodec.DecodedRecord rec;
                    try {
                        rec = IndexRecordCodec.decode(line);
                    } catch (RuntimeException e) {
                        malformed[0]++;
                        continue;
                    }
                    dict.merge(rec.term(), rec.postings(), (existing, incoming) -> {
                        existing.merge(incoming);
                        return existing;
                    });
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed reading index part " + part, e);
            }
        }
        LOG.info("Loaded index: {} terms from {} part file(s), {} malformed line(s) skipped",
                dict.size(), parts.size(), malformed[0]);
        return new InvertedIndex(dict, malformed[0]);
    }
}
