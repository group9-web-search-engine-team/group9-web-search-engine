package com.group9.websearch.index;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * Test-only probe for whether Hadoop's local filesystem works in the current environment.
 *
 * <p>On Windows, {@code RawLocalFileSystem} calls {@code NativeIO$Windows.access0}, which
 * requires {@code winutils.exe} + {@code hadoop.dll} (a {@code HADOOP_HOME} install). When
 * that native layer is absent the call throws {@link UnsatisfiedLinkError}. These tests are
 * meant to run where a real Hadoop runtime exists (the WSL2 cluster, Linux CI); on a bare
 * Windows dev box they are skipped via {@code Assumptions.assumeTrue(hadoopLocalFsUsable())}
 * rather than failing the whole build. This mirrors the repo's gated-integration-test
 * convention (skip cleanly when the external prerequisite is unavailable).
 */
public final class HadoopLocalFsSupport {

    private HadoopLocalFsSupport() {
    }

    private static final boolean USABLE = probe();

    public static boolean hadoopLocalFsUsable() {
        return USABLE;
    }

    private static boolean probe() {
        try {
            Configuration conf = new Configuration();
            conf.set("fs.defaultFS", "file:///");
            FileSystem fs = FileSystem.getLocal(conf);
            // listStatus on a directory invokes NativeIO$Windows.access0 on Windows, which is
            // exactly the call that throws UnsatisfiedLinkError without winutils/hadoop.dll.
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("hadoopfsprobe");
            java.nio.file.Files.write(tmp.resolve("f"), new byte[] {1});
            try {
                fs.listStatus(new Path(tmp.toUri()));
                fs.getFileStatus(new Path(tmp.resolve("f").toUri()));
            } finally {
                java.nio.file.Files.deleteIfExists(tmp.resolve("f"));
                java.nio.file.Files.deleteIfExists(tmp);
            }
            return true;
        } catch (UnsatisfiedLinkError | Exception e) {
            return false;
        }
    }
}
