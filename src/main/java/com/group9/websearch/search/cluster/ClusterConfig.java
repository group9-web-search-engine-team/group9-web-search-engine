package com.group9.websearch.search.cluster;

import com.group9.websearch.index.TermPartitioner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Externalised cluster configuration for distributed query execution: the physical address of
 * each worker and the mapping from the five logical term partitions (P1..P5) to workers. NO IP
 * addresses are hardcoded in application logic -- they are loaded from a properties file (or
 * supplied programmatically in tests).
 *
 * <p>Properties file format (see {@code config/cluster.properties.example}):
 * <pre>
 *   worker.P1 = 10.0.0.1:9301
 *   worker.P2 = 10.0.0.2:9301
 *   worker.P3 = 10.0.0.3:9301
 *   worker.P4 = 10.0.0.4:9301
 *   worker.P5 = 10.0.0.4:9301     # P5 co-located on worker 4 by design
 *   query.timeoutMillis = 5000
 * </pre>
 * Exactly {@link TermPartitioner#NUM_PARTITIONS} partition entries are required; a missing or
 * blank partition address is a fatal configuration error (fail-fast, actionable message).
 */
public final class ClusterConfig {

    /** partitionIndex -> "host:port" (size == TermPartitioner.NUM_PARTITIONS). */
    private final List<String> partitionAddress;
    private final long queryTimeoutMillis;

    private ClusterConfig(List<String> partitionAddress, long queryTimeoutMillis) {
        this.partitionAddress = partitionAddress;
        this.queryTimeoutMillis = queryTimeoutMillis;
    }

    /** "host:port" owning partition index p (0..4). */
    public String addressForPartition(int p) {
        return partitionAddress.get(p);
    }

    /** "host:port" owning the partition of term t. */
    public String addressForTerm(String term) {
        return partitionAddress.get(TermPartitioner.partitionFor(term));
    }

    public long queryTimeoutMillis() {
        return queryTimeoutMillis;
    }

    public List<String> partitionAddresses() {
        return List.copyOf(partitionAddress);
    }

    /** Build directly (tests / programmatic use). The list must have exactly 5 entries. */
    public static ClusterConfig of(List<String> partitionAddresses, long queryTimeoutMillis) {
        if (partitionAddresses.size() != TermPartitioner.NUM_PARTITIONS) {
            throw new IllegalArgumentException("expected " + TermPartitioner.NUM_PARTITIONS
                    + " partition addresses (P1..P5), got " + partitionAddresses.size());
        }
        for (int i = 0; i < partitionAddresses.size(); i++) {
            String a = partitionAddresses.get(i);
            if (a == null || a.isBlank()) {
                throw new IllegalArgumentException("partition "
                        + TermPartitioner.PARTITION_IDS[i] + " has no address");
            }
        }
        return new ClusterConfig(List.copyOf(partitionAddresses), queryTimeoutMillis);
    }

    /** Load from a properties file. */
    public static ClusterConfig load(Path propertiesFile) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(propertiesFile)) {
            props.load(in);
        }
        List<String> addrs = new ArrayList<>(TermPartitioner.NUM_PARTITIONS);
        for (int i = 0; i < TermPartitioner.NUM_PARTITIONS; i++) {
            String key = "worker." + TermPartitioner.PARTITION_IDS[i];
            String val = props.getProperty(key);
            if (val == null || val.isBlank()) {
                throw new IOException("cluster config missing required key '" + key
                        + "' in " + propertiesFile);
            }
            addrs.add(val.trim());
        }
        long timeout = Long.parseLong(props.getProperty("query.timeoutMillis", "5000").trim());
        return of(addrs, timeout);
    }
}
