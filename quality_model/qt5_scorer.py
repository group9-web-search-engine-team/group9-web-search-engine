"""
QT5-Small page-quality scorer.

Loads the pretrained ``macavaney/qt5-small-msw`` checkpoint and scores page text
exactly the way the official implementation does (terrierteam/pyterrier-quality,
``QualT5``), which is the scorer used by the "Neural Prioritisation for Web Crawling"
paper (Pezzuti et al., arXiv:2506.16146).

Scoring method (verified against pyterrier_quality/qualt5.py):
  * architecture: T5 seq2seq (``AutoModelForSeq2SeqLM``) + matching tokenizer.
  * prompt: ``"Document: {text} Relevant:"``.
  * decode a single step from the decoder start token and read the first-position
    logits over the target tokens ``["true", "false"]``.
  * quality = ``log_softmax(logits)[true]`` -- the log-probability of the "true"
    token. This is a NEGATIVE float (a log-prob), NOT a 0..1 probability. Higher
    (closer to 0) means higher estimated quality.

This module loads the model ONCE per process, runs on CPU by default (no GPU
required), supports batch scoring, and fails safely on empty/oversized/invalid
input. It prints nothing to stdout on import or load; diagnostics go to stderr.
"""

from __future__ import annotations

import math
import sys
from typing import List, Optional

# Default pretrained checkpoint (page-quality QT5-small, MS-MARCO web).
DEFAULT_MODEL = "macavaney/qt5-small-msw"
DEFAULT_MAX_LEN = 512
DEFAULT_BATCH_SIZE = 16
# Official prompt and targets (pyterrier_quality QualT5 defaults).
PROMPT = "Document: {} Relevant:"
TARGETS = ["true", "false"]


def _log(msg: str) -> None:
    """Diagnostics go to stderr only, never to the score (stdout) stream."""
    print(f"[qt5_scorer] {msg}", file=sys.stderr, flush=True)


class Qt5Scorer:
    """Loads QT5-Small once and scores page text. Thread-unsafe; use one per process."""

    def __init__(
        self,
        model_name: str = DEFAULT_MODEL,
        max_len: int = DEFAULT_MAX_LEN,
        batch_size: int = DEFAULT_BATCH_SIZE,
        device: Optional[str] = None,
    ) -> None:
        # Import heavy deps lazily so importing this module (e.g. for --help) is cheap
        # and import errors surface with a clear message.
        import torch  # noqa: WPS433 (local import by design)
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer  # noqa: WPS433

        self._torch = torch
        self._F = torch.nn.functional
        self.max_len = int(max_len)
        self.batch_size = max(1, int(batch_size))
        self.prompt = PROMPT

        # CPU by default; only use CUDA if explicitly requested AND available.
        if device is None:
            device = "cpu"
        if device == "cuda" and not torch.cuda.is_available():
            _log("CUDA requested but not available; falling back to CPU.")
            device = "cpu"
        self.device = torch.device(device)

        _log(f"Loading tokenizer and model '{model_name}' on {self.device} ...")
        self.tokenizer = AutoTokenizer.from_pretrained(model_name, use_fast=True)
        self.model = AutoModelForSeq2SeqLM.from_pretrained(model_name)
        self.model.to(self.device)
        self.model.eval()

        # Targets come from model config if present, else the official default.
        targets = getattr(self.model.config, "targets", None) or TARGETS
        true_id = self.tokenizer.encode(targets[0])[0]
        false_id = self.tokenizer.encode(targets[1])[0]
        # Trim lm_head to just the true/false rows, exactly as the official scorer does,
        # so logits[..., 0] is "true" and logits[..., 1] is "false".
        self.model.lm_head.weight = torch.nn.Parameter(
            self.model.lm_head.weight[[true_id, false_id]]
        )
        self._decoder_start = self.model.config.decoder_start_token_id
        _log("Model ready.")

    def score_batch(self, texts: List[str]) -> List[Optional[float]]:
        """
        Score a list of page texts. Returns one float per input (the log-prob of the
        "true" target). Returns ``None`` for an individual item only if it cannot be
        represented (never raises for a single bad item); empty/whitespace text scores
        as a very low quality sentinel rather than raising.
        """
        if not texts:
            return []

        results: List[Optional[float]] = [None] * len(texts)

        # Map blank inputs to a sentinel (very low quality) and only run the model on
        # non-blank inputs, preserving positions.
        work_indices: List[int] = []
        work_texts: List[str] = []
        for i, t in enumerate(texts):
            if t is None or not str(t).strip():
                results[i] = _EMPTY_SCORE
            else:
                work_indices.append(i)
                work_texts.append(str(t))

        for start in range(0, len(work_texts), self.batch_size):
            batch = work_texts[start : start + self.batch_size]
            try:
                batch_scores = self._score_nonempty(batch)
            except Exception as exc:  # noqa: BLE001 - must not crash the whole batch
                _log(f"Inference error on batch starting at {start}: {exc!r}")
                batch_scores = [None] * len(batch)
            for j, s in enumerate(batch_scores):
                results[work_indices[start + j]] = s
        return results

    def score(self, text: str) -> Optional[float]:
        """Score a single page text."""
        return self.score_batch([text])[0]

    def _score_nonempty(self, batch: List[str]) -> List[Optional[float]]:
        torch = self._torch
        enc = self.tokenizer.batch_encode_plus(
            [self.prompt.format(d) for d in batch],
            return_tensors="pt",
            padding="longest",
            max_length=self.max_len,
            truncation=True,
        )
        enc = {k: v.to(self.device) for k, v in enc.items()}
        dec_ids = torch.full(
            (len(batch), 1),
            self._decoder_start,
            dtype=torch.long,
            device=self.device,
        )
        enc["decoder_input_ids"] = dec_ids
        with torch.no_grad():
            logits = self.model(**enc).logits  # [batch, 1, 2]
            first = logits[:, 0]  # [batch, 2] -> columns are [true, false]
            log_probs = self._F.log_softmax(first, dim=1)[:, 0]  # log P(true)
        out: List[Optional[float]] = []
        for v in log_probs.cpu().detach().tolist():
            if v is None or math.isnan(v) or math.isinf(v):
                out.append(None)
            else:
                out.append(float(v))
        return out


# Sentinel for empty/blank text: a very low log-probability (well below any real
# log-prob the model produces), so blank pages sort to the bottom without raising.
_EMPTY_SCORE = -1000.0
