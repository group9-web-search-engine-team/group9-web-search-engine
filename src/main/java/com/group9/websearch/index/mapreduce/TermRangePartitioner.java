package com.group9.websearch.index.mapreduce;

import com.group9.websearch.index.TermPartitioner;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Partitioner;

/**
 * Hadoop {@link Partitioner} that routes each term to one of the five logical term-range
 * partitions using the SHARED {@link TermPartitioner} (a-h, i-m, n-s, t-z, special=P5), instead
 * of Hadoop's default hash partitioner.
 *
 * <p>This guarantees the on-disk reducer partition for a term is identical to the partition the
 * query coordinator will route that term to (both call {@link TermPartitioner}). The job MUST
 * set {@code numReduceTasks == TermPartitioner.NUM_PARTITIONS} (5); the driver enforces this.
 *
 * <p>If a reducer count other than 5 is configured, we still clamp into range with a modulo so
 * the job cannot crash, but the driver rejects a mismatch up front so partitions stay 1:1 with
 * the logical P1..P5.
 */
public final class TermRangePartitioner extends Partitioner<Text, Text> {

    @Override
    public int getPartition(Text term, Text value, int numReduceTasks) {
        int p = TermPartitioner.partitionFor(term.toString());
        if (numReduceTasks <= 0) {
            return 0;
        }
        return (p < numReduceTasks) ? p : (p % numReduceTasks);
    }
}
