# QT5-Small page-quality scorer

Integrates the pretrained **`macavaney/qt5-small-msw`** checkpoint (the query-quality T5
from *Neural Prioritisation for Web Crawling*, Pezzuti et al., arXiv:2506.16146) into the
Group 9 crawler. The scorer runs as a small persistent local HTTP service; the Java
crawler calls it over loopback. No training or fine-tuning — the pretrained checkpoint is
used as-is.

## How the model is loaded and invoked

Replicates the official scoring from `terrierteam/pyterrier-quality` (`QualT5`):

- Architecture: T5 seq2seq (`AutoModelForSeq2SeqLM`) + its tokenizer (`spiece.model`).
- Prompt: `Document: {page text} Relevant:`.
- Decode one step from the decoder start token; read first-position logits over the
  target tokens `["true", "false"]`.
- **Quality = `log_softmax(logits)[true]`** — the log-probability of the `true` token.
  This is a **negative float (a log-prob), not a 0..1 probability**. Higher (closer to 0)
  means higher estimated quality.
- Input is truncated to 512 tokens; batch scoring supported; the model loads **once** per
  process; CPU by default (no GPU required).

Blank text returns a very-low sentinel (`-1000.0`); inference/invalid output is handled
safely (returns `null` rather than raising). Model-loading diagnostics go to **stderr**;
machine-readable scores are the **HTTP JSON body** only.

## Setup

Python 3.12 is available on this machine via the `py` launcher.

```powershell
# From the repo root:
py -m pip install -r quality_model/requirements.txt
```

This installs CPU `torch`, `transformers`, `sentencepiece`, `safetensors`, and `pytest`.

### Model download / cache behaviour

The checkpoint (~242 MB) is downloaded from the Hugging Face Hub on first use and cached
under the standard HF cache (`%USERPROFILE%\.cache\huggingface` or `HF_HOME` if set).
Weights are **never committed to Git** (see `.gitignore`). To pre-warm the cache or use an
offline cache, set `HF_HOME` to a local directory before starting the service.

## Run the scorer service

```powershell
# Loads the model once, then serves POST /score and GET /health on loopback.
py quality_model/qt5_server.py --host 127.0.0.1 --port 8077
```

Quick manual check (in another shell):

```powershell
curl.exe http://127.0.0.1:8077/health
curl.exe -X POST http://127.0.0.1:8077/score -H "Content-Type: application/json" `
  -d "{\"text\":\"COVID-19 clinical features and treatment.\"}"
# -> {"score": <negative float>}
```

## Enable scoring in the crawler

In `config/crawler-conf.yaml` (off by default):

```yaml
  qt5.enabled: true
  qt5.service.url: "http://127.0.0.1:8077"
  qt5.timeout.ms: 5000
  qt5.max.chars: 20000
```

With the service running and `qt5.enabled: true`, the crawler's S3 bolt extracts page
text, requests a score, and persists it to `documents.quality_score`. If the service is
down or slow, the crawl still stores the page (score left null) — scoring never blocks or
breaks the pipeline.

## Tests

Python (fast tier always runs; model-gated tests auto-skip if `torch`/`transformers` or the
checkpoint are unavailable — they never produce a false pass):

```powershell
py -m pytest quality_model -q
```

Java (includes the quality client fail-safe tests and DB persistence tests):

```powershell
.\mvnw.cmd -B test
```

## Notes / limitations

- The QT5 score is stored in the existing `documents.quality_score` column (no schema
  migration needed). It is kept **distinct** from `pagerank` and any future QMax signal.
- This task establishes inference → persisted quality. Using the score for frontier
  priority is a later step; low-scoring pages are **not** discarded (valuable pages can
  still be discovered via low-scoring parents).
- The model-gated Python tests and a live end-to-end score require installing `torch`
  (large) and downloading the checkpoint; those were not exercised in CI here.
