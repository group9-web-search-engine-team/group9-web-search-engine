"""
Persistent local QT5-Small scoring service.

Loads the model ONCE at startup (via qt5_scorer.Qt5Scorer) and serves scoring
requests over a tiny HTTP API built on the Python standard library only (no Flask
/ FastAPI dependency). The Java crawler calls this instead of spawning a Python
process per URL, so the model stays warm.

Endpoints:
  GET  /health                      -> {"status":"ok","model":"..."}
  POST /score   {"text": "..."}     -> {"score": <float|null>}
  POST /score   {"texts": ["..."]}  -> {"scores": [<float|null>, ...]}

Design notes:
  * Binds to 127.0.0.1 only (local-loopback) by default -- not exposed externally.
  * Input size is bounded (max request bytes) and text is truncated by the model's
    max_len; oversized requests are rejected with HTTP 413.
  * Errors return a JSON body and a non-2xx status; the service never crashes on a
    single bad request.
  * Diagnostics go to stderr; the HTTP body is the only machine-readable output.

Run:
  py quality_model/qt5_server.py --host 127.0.0.1 --port 8077
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Ensure this module's directory is importable whatever the CWD, so `qt5_scorer`
# resolves whether launched as `py quality_model/qt5_server.py` or `py qt5_server.py`.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from qt5_scorer import DEFAULT_MODEL, Qt5Scorer  # noqa: E402

# Reject request bodies larger than this (bytes) to bound memory; text is further
# truncated by the model's max_len at inference time.
MAX_REQUEST_BYTES = 2 * 1024 * 1024  # 2 MiB


def _log(msg: str) -> None:
    print(f"[qt5_server] {msg}", file=sys.stderr, flush=True)


class _Handler(BaseHTTPRequestHandler):
    # Set by the server factory below.
    scorer: Qt5Scorer = None  # type: ignore[assignment]
    model_name: str = DEFAULT_MODEL

    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # noqa: D401, N802 - silence default stdout access log
        _log("%s - %s" % (self.address_string(), fmt % args))

    def do_GET(self):  # noqa: N802
        if self.path == "/health":
            self._send_json(200, {"status": "ok", "model": self.model_name})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        if self.path != "/score":
            self._send_json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send_json(400, {"error": "invalid Content-Length"})
            return
        if length <= 0:
            self._send_json(400, {"error": "empty body"})
            return
        if length > MAX_REQUEST_BYTES:
            self._send_json(413, {"error": "request too large"})
            return
        raw = self.rfile.read(length)
        self._dispatch_score(raw)

    def _dispatch_score(self, raw: bytes) -> None:
        """Parse a /score request body and send the JSON response. Extracted from
        do_POST so it is unit-testable without opening a socket."""
        try:
            req = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            self._send_json(400, {"error": f"invalid JSON: {exc}"})
            return
        try:
            if "texts" in req:
                texts = req["texts"]
                if not isinstance(texts, list):
                    self._send_json(400, {"error": "'texts' must be a list"})
                    return
                scores = self.scorer.score_batch([_as_text(t) for t in texts])
                self._send_json(200, {"scores": scores})
            elif "text" in req:
                score = self.scorer.score(_as_text(req["text"]))
                self._send_json(200, {"score": score})
            else:
                self._send_json(400, {"error": "provide 'text' or 'texts'"})
        except Exception as exc:  # noqa: BLE001 - never crash the service
            _log(f"Scoring failure: {exc!r}")
            self._send_json(500, {"error": "scoring failed"})


def _as_text(value) -> str:
    return "" if value is None else str(value)


def main() -> int:
    parser = argparse.ArgumentParser(description="QT5-Small scoring service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8077)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--max-len", type=int, default=512)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--device", default="cpu")
    args = parser.parse_args()

    _log(f"Starting QT5 scoring service; loading model '{args.model}' (device={args.device}) ...")
    scorer = Qt5Scorer(
        model_name=args.model,
        max_len=args.max_len,
        batch_size=args.batch_size,
        device=args.device,
    )
    _Handler.scorer = scorer
    _Handler.model_name = args.model

    server = ThreadingHTTPServer((args.host, args.port), _Handler)
    _log(f"Listening on http://{args.host}:{args.port} (POST /score, GET /health)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        _log("Shutting down on interrupt.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
