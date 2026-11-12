# Group 9 — Distributed Web Search Engine

A distributed information retrieval system developed for the Information Retrieval project. The project combines **Hadoop MapReduce, HDFS, SPIMI-based inverted indexing, Okapi BM25 ranking, and a distributed coordinator–worker search architecture**.

This guide explains how to clone the repository and reproduce the indexing, deployment, and evaluation workflow using the BEIR TREC-COVID benchmark.

---

## 1. Clone the Repository

```bash
git clone https://github.com/group9-web-search-engine-team/group9-web-search-engine.git
cd group9-web-search-engine
```

## 2. Prerequisites

### Required software

- **Java JDK 17**
- **Apache Hadoop 3.5.0**, with HDFS and YARN configured and running.
- Maven (the repository includes a Maven wrapper).
- BEIR TREC-COVID dataset.

### Distributed deployment requirements

- One coordinator machine.
- Four physical worker machines.
- Java installed on all machines.
- Network connectivity between the coordinator and workers.
- AWS EC2 was used for the reported deployment.
- An S3 bucket containing the exported document collection, when using the S3 ingestion path.

The project uses Java 17, as specified in `pom.xml`:

```xml
<maven.compiler.release>17</maven.compiler.release>
```

PostgreSQL is used by the S3 export pipeline. It is not required for ordinary retrieval evaluation once the document export and index have already been prepared.

## 3. Build the Project

From the repository root:

```bash
./mvnw clean package -DskipTests
```

The build generates the project JAR artifacts.

The indexing pipeline uses:

```text
target/indexer.jar
```

The distributed worker deployment uses the application JAR generated under:

```text
target/group9-web-search-engine-*.jar
```

The coordinator and evaluator are deployed using:

```text
coordinator.jar
eval.jar
```

Use the artifacts generated for the corresponding project build.

## 4. Obtain the BEIR TREC-COVID Dataset

We use the official BEIR TREC-COVID benchmark.

Download and extract the dataset:

```bash
wget https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/trec-covid.zip
unzip trec-covid.zip
```

The benchmark contains:

| Dataset component | Description |
|---|---|
| `corpus.jsonl` | Document collection |
| `queries.jsonl` | 50 benchmark queries |
| `qrels/test.tsv` | Relevance judgments |

The full corpus contains **171,332 documents** and the evaluation set contains **50 queries**.

The retrieval system indexes the document title and text fields supplied by the BEIR corpus.

## 5. Export Documents from S3 to HDFS

The distributed indexing workflow uses documents exported from Amazon S3.

The Hadoop master/coordinator is responsible for obtaining the document export, staging it in HDFS, and executing the indexing pipeline.

### 5.1 Export the crawled documents from S3

On the Hadoop master (`10.0.15.125`), run:

```bash
yarn jar target/indexer.jar \
  com.group9.websearch.index.IndexerCli export \
  --s3-bucket web-search-engine-group9-crawler-output-20261110-709845 \
  --s3-prefix crawl/raw/trec-covid/ \
  --s3-region us-east-1 \
  --output week3-export/documents.jsonl \
  --pg-url jdbc:postgresql://localhost:5432/websearch \
  --pg-user websearch_crawler
```

This exports the crawled documents from S3 into:

```text
week3-export/documents.jsonl
```

The export command uses the PostgreSQL connection supplied above. Ensure that the required database access and AWS permissions are configured on the Hadoop master.

### 5.2 Upload the exported documents to HDFS

Create the HDFS staging directory and upload the document export:

```bash
hdfs dfs -mkdir -p /week3/exported-docs
hdfs dfs -put -f week3-export/documents.jsonl /week3/exported-docs/
```

Verify the uploaded file:

```bash
hdfs dfs -ls /week3/exported-docs
```

### Alternative: Use the BEIR corpus directly

If reproducing the BEIR experiment without using the crawler export, the `CorpusAdapter` can convert the official BEIR corpus into the document format used by the indexing pipeline.

Run:

```bash
yarn jar target/indexer.jar \
  com.group9.websearch.index.CorpusAdapter \
  data/beir/trec-covid/corpus.jsonl \
  week3-export/documents.jsonl \
  eval/beir-idmap.v1.tsv
```

Upload the converted documents to HDFS:

```bash
hdfs dfs -mkdir -p /week3/exported-docs
hdfs dfs -put -f week3-export/documents.jsonl /week3/exported-docs/
```

This path also generates the benchmark document ID mapping required for evaluation.

**Choose the appropriate ingestion path for your experiment:** the S3 export path uses the crawled document collection, while the BEIR adapter path uses the official benchmark corpus directly.

## 6. Build the Five-Partition Inverted Index Using Hadoop

The indexing pipeline uses **SPIMI (Single-Pass In-Memory Indexing)** with Hadoop MapReduce.

For the full-corpus distributed experiment, the indexing job uses range partitioning to generate exactly five logical term partitions.

### 6.1 Execute the Hadoop indexing job

Run on the Hadoop master:

```bash
yarn jar target/indexer.jar \
  com.group9.websearch.index.IndexerCli index \
  --input hdfs:///week3/exported-docs \
  --output hdfs:///week3/index-rangepart \
  --range-partition \
  --overwrite
```

The `--range-partition` option forces exactly **five reducers** and uses the shared `TermRangePartitioner`.

The resulting index is divided into five logical partitions:

| Partition | Term range | Hadoop output file |
|---|---|---|
| P1 | a–h | `part-r-00000` |
| P2 | i–m | `part-r-00001` |
| P3 | n–s | `part-r-00002` |
| P4 | t–z | `part-r-00003` |
| P5 | special | `part-r-00004` |

The five reducer output files form the five logical partitions used by the distributed search engine.

### 6.2 Validate the generated index

After the Hadoop indexing job completes, validate the output:

```bash
yarn jar target/indexer.jar \
  com.group9.websearch.index.IndexerCli validate \
  --index hdfs:///week3/index-rangepart
```

Inspect the generated files:

```bash
hdfs dfs -ls hdfs:///week3/index-rangepart
```

### 6.3 Compute corpus statistics

After indexing, compute the corpus statistics required by the coordinator and BM25 ranking:

```bash
yarn jar target/indexer.jar \
  com.group9.websearch.index.IndexerCli stats \
  --index hdfs:///week3/index-rangepart \
  --stats-out stats/corpus-stats.json \
  --df-out stats/df.tsv \
  --require-complete
```

This generates:

| File | Purpose |
|---|---|
| `stats/corpus-stats.json` | Global corpus statistics |
| `stats/df.tsv` | Document-frequency information |

The `--require-complete` option ensures that the statistics computation requires a complete index.

> **Hadoop execution note:** HDFS and YARN provide the distributed processing infrastructure for index construction. Hadoop schedules mapper and reducer tasks across the available cluster resources, and some of the same physical machines used later as search workers can also participate in MapReduce execution. This Hadoop mapper/reducer distribution is separate from the five logical term partitions produced by the `TermRangePartitioner`.

## 7. Distribute the Five Index Partitions to Search Workers

Once indexing and statistics generation are complete, the five partition files are transferred from HDFS on the Hadoop master to the local storage of the search workers.

The deployment uses four physical worker machines hosting five logical partitions:

| Physical machine | Private IP | Logical partition | Term range |
|---|---|---|---|
| Worker 1 | `10.0.15.33` | P1 | a–h |
| Worker 2 | `10.0.15.37` | P2 | i–m |
| Worker 3 | `10.0.2.162` | P3 | n–s |
| Worker 4 | `10.0.13.149` | P4 and P5 | t–z, special |

### 7.1 Use the distribution script

Configure the SSH user and private key:

```bash
export SSH_USER=ubuntu
export KEY=~/.ssh/websearch-cluster-key.pem
```

Run the partition distribution script from the repository root:

```bash
./deploy/distribute-partitions.sh \
  hdfs:///week3/index-rangepart \
  /data/search-index \
  10.0.15.33 10.0.15.37 10.0.2.162 10.0.13.149
```

The script transfers the appropriate partition files from HDFS to the corresponding worker machines.

### 7.2 Manual distribution (alternative)

The same operation can be performed manually if required.

Set the SSH key:

```bash
export KEY=~/.ssh/websearch-cluster-key.pem
```

**Worker 1 — P1 (a–h)**

```bash
mkdir -p /tmp/p1

hdfs dfs -get /week3/index-rangepart/part-r-00000 /tmp/p1/
hdfs dfs -get /week3/index-rangepart/_SUCCESS /tmp/p1/

rsync -avz -e "ssh -i $KEY" \
  /tmp/p1/ ubuntu@10.0.15.33:/data/search-index/
```

**Worker 2 — P2 (i–m)**

```bash
mkdir -p /tmp/p2

hdfs dfs -get /week3/index-rangepart/part-r-00001 /tmp/p2/

rsync -avz -e "ssh -i $KEY" \
  /tmp/p2/ ubuntu@10.0.15.37:/data/search-index/
```

**Worker 3 — P3 (n–s)**

```bash
mkdir -p /tmp/p3

hdfs dfs -get /week3/index-rangepart/part-r-00002 /tmp/p3/

rsync -avz -e "ssh -i $KEY" \
  /tmp/p3/ ubuntu@10.0.2.162:/data/search-index/
```

**Worker 4 — P4 (t–z) and P5 (special)**

Both partition files are placed in the same local directory:

```bash
mkdir -p /tmp/p4

hdfs dfs -get /week3/index-rangepart/part-r-00003 /tmp/p4/
hdfs dfs -get /week3/index-rangepart/part-r-00004 /tmp/p4/
hdfs dfs -get /week3/index-rangepart/_SUCCESS /tmp/p4/

rsync -avz -e "ssh -i $KEY" \
  /tmp/p4/ ubuntu@10.0.13.149:/data/search-index/
```

After distribution, each search worker has its assigned index partition files under:

```text
/data/search-index
```

The workers load these local index files when their search processes start. They do not rebuild the index independently.

## 8. Start the Distributed Search Workers

The worker processes are started using the deployment script:

```text
deploy/worker-service.sh
```

Set the SSH key:

```bash
export KEY=~/.ssh/websearch-cluster-key.pem
```

### Worker 1 — P1

```bash
ssh -i $KEY ubuntu@10.0.15.33 \
  "WORKER_ID=P1 PARTITIONS='P1' INDEX_DIR=/data/search-index PORT=9301 \
   APP_JAR=~/web-search-engine/target/group9-web-search-engine-*.jar \
   ~/web-search-engine/deploy/worker-service.sh start"
```

### Worker 2 — P2

```bash
ssh -i $KEY ubuntu@10.0.15.37 \
  "WORKER_ID=P2 PARTITIONS='P2' INDEX_DIR=/data/search-index PORT=9301 \
   APP_JAR=~/web-search-engine/target/group9-web-search-engine-*.jar \
   ~/web-search-engine/deploy/worker-service.sh start"
```

### Worker 3 — P3

```bash
ssh -i $KEY ubuntu@10.0.2.162 \
  "WORKER_ID=P3 PARTITIONS='P3' INDEX_DIR=/data/search-index PORT=9301 \
   APP_JAR=~/web-search-engine/target/group9-web-search-engine-*.jar \
   ~/web-search-engine/deploy/worker-service.sh start"
```

### Worker 4 — P4 and P5

Worker 4 serves both logical partitions:

```bash
ssh -i $KEY ubuntu@10.0.13.149 \
  "WORKER_ID=P4 PARTITIONS='P4 P5' INDEX_DIR=/data/search-index PORT=9301 \
   APP_JAR=~/web-search-engine/target/group9-web-search-engine-*.jar \
   ~/web-search-engine/deploy/worker-service.sh start"
```

## 9. Verify Worker Health

From the coordinator machine, verify that all four physical worker endpoints respond:

```bash
for ip in 10.0.15.33 10.0.15.37 10.0.2.162 10.0.13.149; do
  echo -n "$ip -> "
  curl -fsS "http://$ip:9301/health" \
    | python3 -m json.tool --no-indent 2>/dev/null || echo "UNREACHABLE"
done
```

All four physical endpoints should be reachable, representing all five logical partitions.

## 10. Start the Distributed Coordinator

On the coordinator machine (`10.0.15.125`):

```bash
cd ~/web-search-engine

java -jar coordinator.jar \
  --config config/cluster.properties \
  --stats stats/corpus-stats.json \
  --meta week3-export/documents.jsonl
```

The coordinator reads the cluster configuration, global corpus statistics, and document metadata. It communicates with the workers and combines their results to provide distributed search.

The system supports Boolean and proximity queries, including:

```text
coronavirus AND infection
coronavirus OR infection
coronavirus NOT animal
coronavirus AND NOT animal
coronavirus NEAR/5 infection
coronavirus AND infection NEAR/3 treatment
```

## 11. Run the Distributed Evaluation

The evaluation uses the BEIR TREC-COVID queries and relevance judgments.

Run the evaluator from the coordinator machine:

```bash
java -jar eval.jar \
  --distributed \
  --coordinator 44.201.25.56 \
  --cluster-config config/cluster.properties \
  --queries data/beir/trec-covid/queries.jsonl \
  --qrels data/beir/trec-covid/qrels/test.tsv \
  --idmap eval/beir-idmap.v1.tsv \
  --stats stats/corpus-stats.json \
  --meta week3-export/documents.jsonl \
  --k 10 --k 100 \
  --depth 171332 \
  --json-out eval/report-distributed.json \
  --csv-out eval/per-query-distributed.csv
```

Replace the coordinator IP with the current reachable address when using a different deployment.

### Evaluation configuration

| Parameter | Value |
|---|---|
| Benchmark | BEIR TREC-COVID |
| Retrieval model | Okapi BM25 |
| Architecture | Distributed coordinator–worker |
| Indexed documents | 171,332 |
| Evaluation queries | 50 |
| Evaluation cutoffs | 10 and 100 |
| Retrieval depth | 171,332 |
| Relevance threshold | Grade ≥ 1 |
| Logical partitions | 5 |

The retrieval depth can be changed to `100` for the depth-100 experiment.

## 12. Expected Results

The following results were obtained using the full BEIR TREC-COVID corpus and 50 evaluation queries.

### 12.1 Retrieval effectiveness

| Metric | Depth 100 | Depth 171,332 |
|---|---:|---:|
| Precision@10 | 0.6860 | 0.6860 |
| Recall@10 | 0.0170 | 0.0170 |
| F1@10 | 0.0329 | 0.0329 |
| MAP | 0.0723 | 0.5390 |
| NDCG@10 (linear gain) | 0.6235 | 0.6235 |
| NDCG@10 (exponential gain) | — | 0.5995 |

**NDCG note:** Linear and exponential gain use different relevance-gain formulations. Their scores should not be compared directly. The exponential-gain result is reported for the full-depth evaluation.

### 12.2 Distributed execution and reliability

A successful full-corpus evaluation reports:

| Validation | Expected result |
|---|---|
| Queries evaluated | 50/50 |
| Queries skipped | 0 |
| Healthy logical partitions | 5/5 |
| Queries with failed partitions | 0 |
| Judged-document mapping coverage | 100% |
| Judged documents mapped | 35,480 / 35,480 |

### 12.3 Query performance

The reported full-depth baseline had:

| Performance metric | Result |
|---|---:|
| Average query latency | 2,455.62 ms |
| Median latency (P50) | 2,457.36 ms |
| P90 latency | 3,310.78 ms |
| P95 latency | 3,359.95 ms |
| P99 latency | 4,131.52 ms |
| Total evaluation time | 157.25 seconds |

Actual latency can vary depending on machine specifications, network conditions, and system load.

### 12.4 Evaluation output files

The evaluator generates:

- `eval/report-distributed.json` — aggregate evaluation metrics and execution information.
- `eval/per-query-distributed.csv` — per-query retrieval metrics and latency.

These files can be used to inspect individual query performance and compare evaluation runs.

## 13. Reproducibility Notes

For meaningful comparisons between runs:

- Use the same BEIR TREC-COVID corpus and relevance judgments.
- Keep BM25 parameters consistent.
- Keep the evaluation cutoff consistent when comparing metrics.
- Keep the relevance threshold at grade 1 or higher.
- Use the same retrieval depth when comparing MAP.
- Record whether NDCG uses linear or exponential gain.
- Ensure all five logical partitions are available before running distributed evaluation.
- Verify that the document ID mapping and corpus statistics correspond to the indexed corpus.
- Ensure that the coordinator and workers use compatible versions of the search-engine JAR.
- Do not commit AWS credentials, private SSH keys, database passwords, or other deployment secrets.

The reported distributed results use the full 171,332-document benchmark corpus, 50 queries, and five logical search partitions.

---

**Group 9 — Distributed Web Search Engine**