package com.group9.websearch.search.cluster;

import com.group9.websearch.index.TermPartitioner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator-side check that every worker has loaded the SAME authoritative index version
 * before the cluster serves queries. Distributed BM25 is only correct when all five logical
 * partitions come from one consistent index build; serving queries across a mix of versions
 * would silently corrupt df/N/avgdl consistency. This verifier makes a version skew an explicit,
 * reported failure rather than a silent scoring bug.
 *
 * <p>It queries each worker's {@code /health} (via {@link HttpWorkerClient#health()}), collects
 * the reported {@code partitionVersion}, and reports:
 * <ul>
 *   <li>whether all reachable workers agree on a single version,</li>
 *   <li>the set of distinct versions seen (should be exactly one),</li>
 *   <li>which partitions were unreachable (so the operator sees partial coverage).</li>
 * </ul>
 */
public final class PartitionVersionVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionVersionVerifier.class);

    /** Outcome of a cluster version verification. */
    public static final class Result {
        public final boolean consistent;
        public final Set<String> distinctVersions;
        public final Map<String, String> versionByPartition; // partitionId -> version (or "UNREACHABLE")
        public final List<String> unreachablePartitions;

        Result(boolean consistent, Set<String> distinctVersions,
                Map<String, String> versionByPartition, List<String> unreachablePartitions) {
            this.consistent = consistent;
            this.distinctVersions = distinctVersions;
            this.versionByPartition = versionByPartition;
            this.unreachablePartitions = unreachablePartitions;
        }

        /** Single authoritative version, or null if not consistent / none reachable. */
        public String authoritativeVersion() {
            return (consistent && distinctVersions.size() == 1)
                    ? distinctVersions.iterator().next() : null;
        }
    }

    private final HttpWorkerClient[] clientsByPartition;

    /** @param clientsByPartition one client per logical partition (size == NUM_PARTITIONS). */
    public PartitionVersionVerifier(HttpWorkerClient[] clientsByPartition) {
        if (clientsByPartition.length != TermPartitioner.NUM_PARTITIONS) {
            throw new IllegalArgumentException("need exactly " + TermPartitioner.NUM_PARTITIONS
                    + " partition clients");
        }
        this.clientsByPartition = clientsByPartition.clone();
    }

    /** Query all workers and determine version consistency. Never throws on worker failure. */
    public Result verify() {
        Set<String> versions = new LinkedHashSet<>();
        Map<String, String> byPartition = new LinkedHashMap<>();
        List<String> unreachable = new ArrayList<>();
        for (int p = 0; p < clientsByPartition.length; p++) {
            String pid = TermPartitioner.PARTITION_IDS[p];
            try {
                WorkerProtocol.HealthResponse h = clientsByPartition[p].health();
                String v = h.partitionVersion == null ? "UNKNOWN" : h.partitionVersion;
                versions.add(v);
                byPartition.put(pid, v);
            } catch (RuntimeException ex) {
                unreachable.add(pid);
                byPartition.put(pid, "UNREACHABLE");
                LOG.warn("partition {} health check failed: {}", pid, ex.toString());
            }
        }
        boolean consistent = unreachable.isEmpty() && versions.size() == 1;
        if (!consistent) {
            LOG.warn("partition version verification FAILED: versions={}, unreachable={}",
                    versions, unreachable);
        } else {
            LOG.info("partition versions consistent: {}", versions.iterator().next());
        }
        return new Result(consistent, versions, byPartition, unreachable);
    }
}
