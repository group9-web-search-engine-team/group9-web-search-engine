#!/usr/bin/env bash
# Week 3: stage the indexer jar from the Windows path, then run the REAL SPIMI MapReduce job
# on the WSL2 Hadoop/YARN cluster. Self-contained (stages jar + JSONL itself) to avoid stale
# OneDrive placeholder copies. Safe: never reformats the NameNode; only writes under /week3/.
set -euo pipefail

export HADOOP_HOME="$HOME/hadoop-3.5.0"
export PATH="$HADOOP_HOME/bin:$HADOOP_HOME/sbin:$PATH"

PROJ="/mnt/c/Users/vaidy/OneDrive/Desktop/SOP/search engine IR"
# `cat` forces a full read so OneDrive materializes the file (Copy-Item/placeholder-safe).
cat "$PROJ/target/indexer.jar"            > /tmp/indexer.jar
cat "$PROJ/week3-export/sample-docs.jsonl" > /tmp/sample-docs.jsonl
JAR=/tmp/indexer.jar
JSONL=/tmp/sample-docs.jsonl
echo "jar=$(stat -c%s "$JAR") bytes (md5 $(md5sum "$JAR" | cut -d' ' -f1)), jsonl=$(stat -c%s "$JSONL") bytes"

# Stage JSONL into HDFS.
hdfs dfs -mkdir -p /week3/staging
hdfs dfs -put -f "$JSONL" /week3/staging/sample-docs.jsonl
echo "=== HDFS staging ==="
hdfs dfs -ls /week3/staging

# Run the REAL job on YARN (mapreduce.framework.name=yarn comes from the cluster mapred-site).
echo "=== Submitting YARN job ==="
yarn jar "$JAR" index \
  --input hdfs:///week3/staging/sample-docs.jsonl \
  --output hdfs:///week3/index \
  --reducers 2 \
  --overwrite

echo "=== Index output listing ==="
hdfs dfs -ls /week3/index
echo "=== _SUCCESS present? ==="
hdfs dfs -test -e /week3/index/_SUCCESS && echo "SUCCESS marker present"
echo "=== Index records (all parts, sorted) ==="
hdfs dfs -cat '/week3/index/part-r-*' | sort

echo "=== Validate via IndexerCli ==="
yarn jar "$JAR" validate --index hdfs:///week3/index
