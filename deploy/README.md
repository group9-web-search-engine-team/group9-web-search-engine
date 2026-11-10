# Distributed search deployment (coordinator + 4 workers)

End-to-end deployment of the five-way term-range distributed query engine on the Group 9 AWS
cluster. No IPs or credentials are hardcoded in application code — all addresses live in
`config/cluster.properties` (coordinator) and environment variables (workers).

## Topology

| Role        | Host (private IP) | Logical partition(s) | Index part file(s)              |
|-------------|-------------------|----------------------|---------------------------------|
| Coordinator | 10.0.15.125       | —                    | reads `config/cluster.properties` + `stats/corpus-stats.json` |
| Worker 1    | 10.0.15.33        | P1 (a–h)             | `part-r-00000`                  |
| Worker 2    | 10.0.15.37        | P2 (i–m)             | `part-r-00001`                  |
| Worker 3    | 10.0.2.162        | P3 (n–s)             | `part-r-00002`                  |
| Worker 4    | 10.0.13.149       | P4 (t–z) + P5 (special) | `part-r-00003` + `part-r-00004` |

Why P5 is co-located on Worker 4: there are five logical partitions but four workers. P5 holds
only "special" terms (digits/symbols/non-a–z), which is small, so it rides with P4.

## 1. Build (once, on any host with the repo)

```
./mvnw.cmd -q -DskipTests package      # Windows
./mvnw     -q -DskipTests package      # Linux worker/coordinator hosts
```

## 2. Index with five-way range partitioning (on the Hadoop master, 10.0.15.125)

`--range-partition` forces exactly five reducers and the shared `TermRangePartitioner`, so the
on-disk partitions match the coordinator's query routing. Omit it for the default behavior.

```
yarn jar target/group9-web-search-engine-*.jar \
    com.group9.websearch.index.IndexerCli index \
    --input  hdfs:///week3/exported-docs \
    --output hdfs:///week3/index-rangepart \
    --range-partition \
    --overwrite
```

Produces: `part-r-00000`=P1, `00001`=P2, `00002`=P3, `00003`=P4, `00004`=P5, plus `_SUCCESS`.
A non-5 explicit `--reducers` together with `--range-partition` is rejected with an actionable
error (one reducer per logical partition is mandatory).

## 3. Compute the authoritative global corpus statistics (after indexing)

BM25 needs N, avgdl, and per-doc dl(d) from the COMPLETE merged index. `StatsCalculator`
publishes `corpus-stats.json` (consumed by both coordinator and, implicitly, the global scorer)
and a `df.tsv` sidecar. It refuses to mark the artifact `complete` unless `_SUCCESS` exists and
every record decoded.

```
yarn jar target/group9-web-search-engine-*.jar \
    com.group9.websearch.index.IndexerCli stats \
    --index hdfs:///week3/index-rangepart \
    --stats-out stats/corpus-stats.json \
    --df-out    stats/df.tsv \
    --require-complete
```

(The coordinator loads `stats/corpus-stats.json` via `CorpusStatistics.fromArtifact`; the same
file must be present on the coordinator host.)

## 4. Physical partition placement (HDFS → workers)

Hadoop's partitioner decides the reducer, NOT the EC2 host. This step copies each part file to
the right worker from the single authoritative HDFS copy, stamping one version on every worker.

```
export SSH_USER=ubuntu
./deploy/distribute-partitions.sh \
    hdfs:///week3/index-rangepart \
    /data/search-index \
    10.0.15.33 10.0.15.37 10.0.2.162 10.0.13.149
```

Each worker ends up with `/data/search-index/` containing its own part file(s), `_SUCCESS`, and
a `VERSION` stamp. The copy is swapped in atomically so a worker never loads a half-copied dir.

## 5. Start the worker services (on each worker, or via ssh)

```
# Worker 1 (10.0.15.33)
WORKER_ID=P1 PARTITIONS="P1"    INDEX_DIR=/data/search-index PORT=9301 \
  APP_JAR=target/group9-web-search-engine-*.jar ./deploy/worker-service.sh start
# Worker 2 (10.0.15.37)
WORKER_ID=P2 PARTITIONS="P2"    INDEX_DIR=/data/search-index PORT=9301 ... start
# Worker 3 (10.0.2.162)
WORKER_ID=P3 PARTITIONS="P3"    INDEX_DIR=/data/search-index PORT=9301 ... start
# Worker 4 (10.0.13.149) — serves BOTH P4 and P5 from one merged index dir
WORKER_ID=P4 PARTITIONS="P4 P5" INDEX_DIR=/data/search-index PORT=9301 ... start
```

Stop / status:

```
PORT=9301 ./deploy/worker-service.sh stop
PORT=9301 ./deploy/worker-service.sh status      # hits /health
```

## 6. Configure and verify the coordinator (10.0.15.125)

```
cp config/cluster.properties.example config/cluster.properties
# edit to:
#   worker.P1 = 10.0.15.33:9301
#   worker.P2 = 10.0.15.37:9301
#   worker.P3 = 10.0.2.162:9301
#   worker.P4 = 10.0.13.149:9301
#   worker.P5 = 10.0.13.149:9301     # co-located with P4
#   query.timeoutMillis = 5000
```

Health / version check (confirm every worker reports the SAME partition version):

```
for hp in 10.0.15.33 10.0.15.37 10.0.2.162 10.0.13.149; do
  echo -n "$hp -> "; curl -fsS "http://$hp:9301/health"; echo
done
```

Programmatically, the coordinator uses `PartitionVersionVerifier` over five `HttpWorkerClient`s;
`verify().consistent == true` and a single `authoritativeVersion()` are required before serving.
A mismatch means a bad/partial deploy — re-run step 4 so all workers share one HDFS snapshot.

## Fault behavior (by design)

- A worker that is down, slow (> `query.timeoutMillis`), returns non-200, a protocol-version
  mismatch, or malformed JSON causes `HttpWorkerClient` to throw `WorkerUnavailableException`.
- The coordinator records that partition in `Response.failedPartitions` and returns a PARTIAL,
  clearly-flagged result rather than silently presenting it as complete or fabricating postings.

## Transport summary

- Protocol: JSON over HTTP/1.1, version `1` (`WorkerProtocol.PROTOCOL_VERSION`).
- `GET  /health` → worker id, owned partition ids, loaded `partitionVersion`, term count.
- `POST /lookup` → per-term df(t) and tf(t,d) (RAW features; global BM25 is computed by the
  coordinator using the global corpus stats, so scores are globally consistent).
- No new dependencies: JDK `HttpServer`/`HttpClient` + the project's existing Jackson.
