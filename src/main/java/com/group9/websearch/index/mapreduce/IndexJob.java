package com.group9.websearch.index.mapreduce;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and submits the distributed SPIMI MapReduce job.
 *
 * <p>Data flow:
 * <ol>
 *   <li><b>Input:</b> JSONL documents (one {@link com.group9.websearch.index.ExportedDocument}
 *       per physical line) read via {@link TextInputFormat}. Its {@code LineRecordReader}
 *       splits input on HDFS block boundaries correctly and never hands a mapper a partial
 *       line, which is why single-line JSONL is the right on-disk format.</li>
 *   <li><b>Map:</b> {@link SpimiIndexMapper} builds in-memory SPIMI blocks and emits
 *       {@code (term, partial-postings)}.</li>
 *   <li><b>Shuffle:</b> partition by term (default hash partitioner over the Text key), so all
 *       partial postings for a term reach one reducer; keys arrive sorted.</li>
 *   <li><b>Reduce:</b> {@link SpimiIndexReducer} merges partial postings (summing tf) and
 *       writes one final index record per term.</li>
 *   <li><b>Output:</b> term-partitioned index under the output path, one {@code part-r-NNNNN}
 *       file per reducer, plus a {@code _SUCCESS} marker.</li>
 * </ol>
 *
 * <p>The number of reducers controls index partitioning and is configurable. Output must not
 * already exist unless {@code overwrite} is set (fail-fast, standard MapReduce safety).
 */
public final class IndexJob {

    private static final Logger LOG = LoggerFactory.getLogger(IndexJob.class);

    public static final String DEFAULT_JOB_NAME = "group9-spimi-index";

    private IndexJob() {
    }

    /** Parameters for configuring the index job. */
    public static final class Params {
        public String inputPath;
        public String outputPath;
        public int numReducers = 1;
        public long spimiMaxEntries = SpimiIndexMapper.DEFAULT_MAX_ENTRIES;
        public boolean overwrite = false;
        public String jobName = DEFAULT_JOB_NAME;
    }

    /**
     * Configure a {@link Job}. Validates inputs and (optionally) clears a pre-existing output
     * directory. Does not submit; the caller runs {@link Job#waitForCompletion(boolean)} so it
     * can control verbosity and exit codes.
     */
    public static Job configure(Configuration conf, Params p) throws Exception {
        if (p.inputPath == null || p.inputPath.isBlank()) {
            throw new IllegalArgumentException("input path is required");
        }
        if (p.outputPath == null || p.outputPath.isBlank()) {
            throw new IllegalArgumentException("output path is required");
        }
        if (p.numReducers < 1) {
            throw new IllegalArgumentException("numReducers must be >= 1");
        }

        conf.setLong(SpimiIndexMapper.SPIMI_MAX_ENTRIES, p.spimiMaxEntries);

        Path out = new Path(p.outputPath);
        FileSystem fs = out.getFileSystem(conf);
        if (fs.exists(out)) {
            if (!p.overwrite) {
                throw new IllegalStateException(
                        "output path already exists (use overwrite to replace): " + p.outputPath);
            }
            LOG.warn("Overwrite enabled: deleting existing output {}", p.outputPath);
            fs.delete(out, true);
        }

        Job job = Job.getInstance(conf, p.jobName);
        job.setJarByClass(IndexJob.class);

        job.setInputFormatClass(TextInputFormat.class);
        job.setOutputFormatClass(TextOutputFormat.class);

        job.setMapperClass(SpimiIndexMapper.class);
        job.setReducerClass(SpimiIndexReducer.class);

        // Map output: (term, partial postings)
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        // Final output: (NullWritable, encoded index record)
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        job.setNumReduceTasks(p.numReducers);

        org.apache.hadoop.mapreduce.lib.input.FileInputFormat.addInputPath(job, new Path(p.inputPath));
        org.apache.hadoop.mapreduce.lib.output.FileOutputFormat.setOutputPath(job, out);

        return job;
    }

    /** Configure and run the job, returning the JVM-style exit code (0 success, 1 failure). */
    public static int run(Configuration conf, Params p) throws Exception {
        Job job = configure(conf, p);
        LOG.info("Submitting SPIMI index job: input={} output={} reducers={} spimiMaxEntries={}",
                p.inputPath, p.outputPath, p.numReducers, p.spimiMaxEntries);
        boolean ok = job.waitForCompletion(true);
        return ok ? 0 : 1;
    }
}
