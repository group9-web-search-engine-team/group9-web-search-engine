"""
Tests for the QT5-Small scorer and the local scoring service.

Two tiers:
  * Fast tests (always run): batch position/association, empty/blank handling,
    oversized-input safety, server request parsing and failure handling. These use a
    lightweight fake scorer and do NOT download the model.
  * Model-gated tests (auto-skip): load the real checkpoint and assert finite numeric
    output. They run only when torch + transformers are importable AND the model can
    be loaded; otherwise they skip with a clear reason (never a false pass).

Run:  py -m pytest quality_model -q
"""

from __future__ import annotations

import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import qt5_scorer  # noqa: E402


# --------------------------------------------------------------------------------------
# Fast tests: scorer-level logic that does not need the model (uses the empty sentinel).
# --------------------------------------------------------------------------------------

def test_empty_and_blank_text_use_sentinel_without_model():
    """Blank inputs are handled without loading the model (score_batch short-circuits
    empties). We exercise the empty path by monkeypatching _score_nonempty to ensure it
    is never called for blanks."""
    class _NoModelScorer(qt5_scorer.Qt5Scorer):
        def __init__(self):  # bypass heavy __init__
            self.batch_size = 4
        def _score_nonempty(self, batch):
            raise AssertionError("_score_nonempty must not be called for blank-only input")

    s = _NoModelScorer()
    out = s.score_batch(["", "   ", None])
    assert out == [qt5_scorer._EMPTY_SCORE] * 3


def test_batch_preserves_position_and_association():
    """Non-blank items are scored by position; blanks keep their slot. This guards the
    URL->score association that the Java side relies on (order in == order out)."""
    class _FakeScorer(qt5_scorer.Qt5Scorer):
        def __init__(self):
            self.batch_size = 2
        def _score_nonempty(self, batch):
            # deterministic fake: score = length of text as a negative log-prob-ish value
            return [-float(len(t)) for t in batch]

    s = _FakeScorer()
    texts = ["aaaa", "", "bb", "   ", "c"]
    out = s.score_batch(texts)
    assert out[0] == -4.0           # "aaaa"
    assert out[1] == qt5_scorer._EMPTY_SCORE  # blank keeps slot
    assert out[2] == -2.0           # "bb"
    assert out[3] == qt5_scorer._EMPTY_SCORE  # blank keeps slot
    assert out[4] == -1.0           # "c"
    assert len(out) == len(texts)


def test_inference_error_in_batch_yields_none_not_crash():
    """A failing inference call must degrade to None for that batch, not raise."""
    class _BoomScorer(qt5_scorer.Qt5Scorer):
        def __init__(self):
            self.batch_size = 10
        def _score_nonempty(self, batch):
            raise RuntimeError("simulated model failure")

    s = _BoomScorer()
    out = s.score_batch(["real text one", "real text two"])
    assert out == [None, None]


def test_oversized_text_is_accepted_and_scored_safely():
    """Very long text must not raise; the model truncates at max_len. With a fake scorer
    we just assert the pipeline accepts a large string and returns one score."""
    big = "word " * 200_000  # ~1 MB of text
    class _LenScorer(qt5_scorer.Qt5Scorer):
        def __init__(self):
            self.batch_size = 1
        def _score_nonempty(self, batch):
            return [-1.0 for _ in batch]
    s = _LenScorer()
    out = s.score_batch([big])
    assert out == [-1.0]


# --------------------------------------------------------------------------------------
# Fast tests: HTTP server request handling with a fake scorer (no model, no sockets).
# --------------------------------------------------------------------------------------

class _FakeScorer:
    """Stand-in scorer used to test the HTTP handler logic in isolation."""
    def score(self, text):
        return None if not text.strip() else -float(len(text))
    def score_batch(self, texts):
        return [self.score(t) for t in texts]


def _make_handler():
    import qt5_server
    # Build a handler instance without running BaseHTTPRequestHandler.__init__ (which
    # needs a live socket); we only test the request-dispatch helpers.
    handler = qt5_server._Handler.__new__(qt5_server._Handler)
    handler.scorer = _FakeScorer()
    handler.model_name = "fake"
    sent = {}
    def _capture(status, payload):
        sent["status"] = status
        sent["payload"] = payload
    handler._send_json = _capture  # type: ignore[assignment]
    return handler, sent


def test_server_scores_single_text():
    handler, sent = _make_handler()
    handler._dispatch_score(json.dumps({"text": "hello world"}).encode())
    assert sent["status"] == 200
    assert sent["payload"]["score"] == -11.0


def test_server_scores_batch_texts_in_order():
    handler, sent = _make_handler()
    handler._dispatch_score(json.dumps({"texts": ["ab", "", "abc"]}).encode())
    assert sent["status"] == 200
    assert sent["payload"]["scores"] == [-2.0, None, -3.0]


def test_server_rejects_missing_fields():
    handler, sent = _make_handler()
    handler._dispatch_score(json.dumps({"nope": 1}).encode())
    assert sent["status"] == 400


def test_server_rejects_invalid_json():
    handler, sent = _make_handler()
    handler._dispatch_score(b"{not json")
    assert sent["status"] == 400


# --------------------------------------------------------------------------------------
# Model-gated tests: load the real checkpoint if available; otherwise skip (no false pass)
# --------------------------------------------------------------------------------------

def _model_available():
    try:
        import torch  # noqa: F401
        import transformers  # noqa: F401
    except Exception:
        return False
    return True


@pytest.mark.skipif(not _model_available(),
                    reason="torch/transformers not installed; see quality_model/requirements.txt")
def test_real_model_scores_known_text_as_finite_number():
    import math
    try:
        scorer = qt5_scorer.Qt5Scorer()  # downloads/caches the checkpoint on first run
    except Exception as exc:  # network/checkpoint unavailable -> skip, do not fail
        pytest.skip(f"QT5 checkpoint unavailable in this environment: {exc!r}")
    score = scorer.score(
        "COVID-19 is caused by the SARS-CoV-2 virus. This article reviews the "
        "clinical features, transmission, and treatment of the disease."
    )
    assert score is not None
    assert isinstance(score, float)
    assert math.isfinite(score)
    # log-prob of the "true" token is <= 0.
    assert score <= 0.0


@pytest.mark.skipif(not _model_available(),
                    reason="torch/transformers not installed; see quality_model/requirements.txt")
def test_real_model_empty_text_is_sentinel():
    try:
        scorer = qt5_scorer.Qt5Scorer()
    except Exception as exc:
        pytest.skip(f"QT5 checkpoint unavailable in this environment: {exc!r}")
    assert scorer.score("") == qt5_scorer._EMPTY_SCORE
