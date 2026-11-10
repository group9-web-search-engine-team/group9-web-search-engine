#!/usr/bin/env bash
#
# worker-service.sh -- start / stop a single search worker (WorkerServerCli) on a worker EC2.
#
# Run this ON each worker host (or via ssh). It starts the JDK-HttpServer worker that the
# coordinator talks to over the private network, loading the local index directory placed by
# deploy/distribute-partitions.sh.
#
# CONFIG via environment (or edit the defaults):
#   WORKER_ID    logical id for logs            (e.g. P1 / P2 / P3 / P4)
#   PARTITIONS   space-separated owned parts     (e.g. "P1"  or  "P4 P5" for worker 4)
#   INDEX_DIR    local index dir to load         (default /data/search-index)
#   BIND         bind address                    (default 0.0.0.0)
#   PORT         listen port                     (default 9301)
#   THREADS      server threads                  (default 8)
#   APP_JAR      path to the built shaded/plain jar OR use CLASSPATH
#   CLASSPATH    alternative to APP_JAR (e.g. target/classes:<deps>)
#
# USAGE
#   WORKER_ID=P4 PARTITIONS="P4 P5" ./deploy/worker-service.sh start
#   ./deploy/worker-service.sh status
#   ./deploy/worker-service.sh stop
set -euo pipefail

CMD="${1:?usage: worker-service.sh {start|stop|status}}"

WORKER_ID="${WORKER_ID:-P1}"
PARTITIONS="${PARTITIONS:-P1}"
INDEX_DIR="${INDEX_DIR:-/data/search-index}"
BIND="${BIND:-0.0.0.0}"
PORT="${PORT:-9301}"
THREADS="${THREADS:-8}"
MAIN="com.group9.websearch.search.cluster.WorkerServerCli"
PID_FILE="/tmp/search-worker-${PORT}.pid"
LOG_FILE="/tmp/search-worker-${PORT}.log"

classpath_arg() {
  if [ -n "${APP_JAR:-}" ]; then echo "-cp $APP_JAR"
  elif [ -n "${CLASSPATH:-}" ]; then echo "-cp $CLASSPATH"
  else echo "-cp target/classes:$(cat target/classpath.txt 2>/dev/null || echo target/classes)"; fi
}

part_flags() { for p in $PARTITIONS; do printf -- "--partition %s " "$p"; done; }

start() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "worker already running (pid $(cat "$PID_FILE"))"; exit 0
  fi
  echo "Starting worker $WORKER_ID (partitions: $PARTITIONS) on ${BIND}:${PORT}"
  # shellcheck disable=SC2046
  nohup java $(classpath_arg) "$MAIN" \
      --worker-id "$WORKER_ID" $(part_flags) \
      --index "$INDEX_DIR" --bind "$BIND" --port "$PORT" --threads "$THREADS" \
      > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "started pid $(cat "$PID_FILE"); logs: $LOG_FILE"
}

stop() {
  if [ ! -f "$PID_FILE" ]; then echo "no pid file; worker not running?"; exit 0; fi
  PID="$(cat "$PID_FILE")"
  echo "Stopping worker pid $PID (SIGTERM -> clean HttpServer shutdown hook)"
  kill "$PID" 2>/dev/null || true
  for _ in $(seq 1 10); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
  kill -0 "$PID" 2>/dev/null && { echo "forcing"; kill -9 "$PID"; }
  rm -f "$PID_FILE"
  echo "stopped"
}

status() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "running (pid $(cat "$PID_FILE")) on ${BIND}:${PORT}"
    curl -fsS "http://127.0.0.1:${PORT}/health" || echo "(health endpoint not responding)"
  else
    echo "not running"
  fi
}

case "$CMD" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "usage: worker-service.sh {start|stop|status}"; exit 2 ;;
esac
