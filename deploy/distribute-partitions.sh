#!/usr/bin/env bash
#
# distribute-partitions.sh -- Physical partition placement for the distributed search cluster.
#
# PROBLEM THIS SOLVES
#   Hadoop's TermRangePartitioner controls which REDUCER writes which term range, producing
#   exactly five part files from a --range-partition index job:
#       part-r-00000 = P1 (a-h)
#       part-r-00001 = P2 (i-m)
#       part-r-00002 = P3 (n-s)
#       part-r-00003 = P4 (t-z)
#       part-r-00004 = P5 (special: digits, symbols, non-a-z)
#   The reducer NUMBER does NOT decide which EC2 host serves that partition. This script is the
#   explicit placement step: it pulls the authoritative index from HDFS and copies each part
#   file to the correct worker's local index directory.
#
# AUTHORITATIVE SINGLE COPY + VERSIONING
#   HDFS holds the single authoritative index (written once, immutable). We stamp a VERSION from
#   the HDFS index (the _SUCCESS mtime) and lay down every worker from that same snapshot, so all
#   workers load ONE consistent version. The coordinator later verifies this via /health.
#
# LAYOUT (matches config/cluster.properties and the AWS topology)
#   Worker1 -> P1            part-r-00000
#   Worker2 -> P2            part-r-00001
#   Worker3 -> P3            part-r-00002
#   Worker4 -> P4 AND P5     part-r-00003 + part-r-00004   (co-located by design)
#
# USAGE
#   ./deploy/distribute-partitions.sh \
#       hdfs:///week3/index-rangepart \
#       /data/search-index \
#       10.0.15.33 10.0.15.37 10.0.2.162 10.0.13.149
#
#   arg1 = HDFS index path produced by `index --range-partition` (must contain _SUCCESS + 5 parts)
#   arg2 = remote local dir each worker loads with WorkerServerCli --index
#   arg3..6 = Worker1 Worker2 Worker3 Worker4 private IPs
#
# REQUIREMENTS: run from a host with `hdfs` CLI and passwordless SSH/scp to the workers.
set -euo pipefail

HDFS_INDEX="${1:?HDFS index path required}"
REMOTE_DIR="${2:?remote worker index dir required}"
W1="${3:?Worker1 IP required}"; W2="${4:?Worker2 IP required}"
W3="${5:?Worker3 IP required}"; W4="${6:?Worker4 IP required}"
SSH_USER="${SSH_USER:-ubuntu}"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "==> Verifying authoritative index in HDFS: $HDFS_INDEX"
if ! hdfs dfs -test -e "$HDFS_INDEX/_SUCCESS"; then
  echo "FATAL: $HDFS_INDEX/_SUCCESS missing -- index is incomplete or path is wrong." >&2
  exit 1
fi
for n in 00000 00001 00002 00003 00004; do
  if ! hdfs dfs -test -e "$HDFS_INDEX/part-r-$n"; then
    echo "FATAL: $HDFS_INDEX/part-r-$n missing -- was the job run with --range-partition (5 reducers)?" >&2
    exit 1
  fi
done

# Single authoritative version stamp from the HDFS index.
VERSION="$(hdfs dfs -stat '%Y' "$HDFS_INDEX/_SUCCESS")"
echo "==> Authoritative index version (HDFS _SUCCESS mtime): $VERSION"

echo "==> Staging index from HDFS to $STAGE"
hdfs dfs -get "$HDFS_INDEX/_SUCCESS"      "$STAGE/_SUCCESS"
hdfs dfs -get "$HDFS_INDEX/part-r-00000"  "$STAGE/part-r-00000"  # P1
hdfs dfs -get "$HDFS_INDEX/part-r-00001"  "$STAGE/part-r-00001"  # P2
hdfs dfs -get "$HDFS_INDEX/part-r-00002"  "$STAGE/part-r-00002"  # P3
hdfs dfs -get "$HDFS_INDEX/part-r-00003"  "$STAGE/part-r-00003"  # P4
hdfs dfs -get "$HDFS_INDEX/part-r-00004"  "$STAGE/part-r-00004"  # P5

# place <worker-ip> <label> <part-file...>  -- ships _SUCCESS + VERSION + the given parts.
place() {
  local ip="$1"; local label="$2"; shift 2
  echo "==> $label ($ip): placing $* into $REMOTE_DIR"
  ssh "${SSH_USER}@${ip}" "rm -rf '${REMOTE_DIR}.incoming' && mkdir -p '${REMOTE_DIR}.incoming'"
  scp "$STAGE/_SUCCESS" "${SSH_USER}@${ip}:${REMOTE_DIR}.incoming/_SUCCESS"
  for part in "$@"; do
    scp "$STAGE/$part" "${SSH_USER}@${ip}:${REMOTE_DIR}.incoming/$part"
  done
  echo "$VERSION" | ssh "${SSH_USER}@${ip}" "cat > '${REMOTE_DIR}.incoming/VERSION'"
  # Atomic swap so the worker never loads a half-copied dir.
  ssh "${SSH_USER}@${ip}" "rm -rf '${REMOTE_DIR}.old' 2>/dev/null; \
      if [ -d '${REMOTE_DIR}' ]; then mv '${REMOTE_DIR}' '${REMOTE_DIR}.old'; fi; \
      mv '${REMOTE_DIR}.incoming' '${REMOTE_DIR}'"
}

place "$W1" "Worker1/P1"    part-r-00000
place "$W2" "Worker2/P2"    part-r-00001
place "$W3" "Worker3/P3"    part-r-00002
place "$W4" "Worker4/P4+P5" part-r-00003 part-r-00004

echo "==> Done. All workers loaded version $VERSION."
echo "    Start workers with deploy/worker-service.sh start, then run the coordinator"
echo "    version check (PartitionVersionVerifier) to confirm all report: $VERSION"
