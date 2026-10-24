#!/usr/bin/env bash
cd /tmp
echo "=== indexer.jar size ==="
stat -c%s indexer.jar
echo "=== StaticLoggerBinder anywhere ==="
jar tf indexer.jar | grep -i 'StaticLoggerBinder' || echo "(none)"
echo "=== org/apache/log4j/Logger or Category (log4j-over-slf4j bridge) ==="
jar tf indexer.jar | grep -E 'org/apache/log4j/(Logger|Category|LogManager)\.class' || echo "(none)"
echo "=== all org/apache/log4j entries (first 10) ==="
jar tf indexer.jar | grep -E 'org/apache/log4j/' | head -n 10 || echo "(none)"
echo "=== does the job see a different jar? check staging on hdfs ==="
export HADOOP_HOME="$HOME/hadoop-3.5.0"; export PATH="$HADOOP_HOME/bin:$PATH"
