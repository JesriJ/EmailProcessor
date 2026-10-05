# Benchmark harness

## Datasets

| File | Size | Use |
|------|------|-----|
| `datasets/emails-10k-v1.jsonl` | 10,000 | Scaling / load runs |
| `../samples/emails-100.jsonl` | 100 | Smoke gate |

Do not regenerate the 10k file between worker-count comparisons.

## Fairness rules

Hold constant across comparisons:

- dataset path and size
- model / provider / prompt version
- `WORKER_CONCURRENCY`
- host machine and datastore versions

## Running (Compose + Qwen/Ollama example)

```powershell
powershell -File scripts/run_10k_qwen.ps1 -WorkerCounts "1,2,4" -Emails 10000 -Concurrency 2
```

Or manually:

1. `powershell -File scripts/reset_data.ps1`
2. Produce with `DATASET_PATH=/data/benchmark/emails-10k-v1.jsonl`
3. Scale workers; wait until processed count reaches target
4. Persist JSON under `results/` (gitignored unless you choose to publish)

Helper scripts: `scripts/run_benchmark.py`, `scripts/compute_scaling.py`.

## Interpreting results

With a local LLM, wall-clock time is usually inference-bound. Use `LLM_PROVIDER=mock` when measuring queue/worker overhead alone.
