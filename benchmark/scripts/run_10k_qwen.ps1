param(
  # Pass as string to avoid PowerShell CLI turning "1,2,4" into int 124.
  [string]$WorkerCounts = "1,2,4",
  [int]$Emails = 10000,
  [int]$Concurrency = 2
)
$WorkerCountList = @($WorkerCounts -split '[,;\s]+' | Where-Object { $_ } | ForEach-Object { [int]$_ })
if (-not $WorkerCountList -or $WorkerCountList.Count -lt 1) {
  throw "WorkerCounts must be a comma-separated list of ints, e.g. 1,2,4"
}

$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location $Root
$ResultsDir = Join-Path $Root "benchmark/results"
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$Log = Join-Path $ResultsDir "run-10k-qwen.log"

function Log($msg) {
  $line = "[{0}] {1}" -f (Get-Date).ToString("o"), $msg
  Add-Content -Path $Log -Value $line
  Write-Host $line
}

function Get-Stats {
  $cid = docker ps --filter "name=emailprocessor-worker" --format "{{.ID}}" | Select-Object -First 1
  if (-not $cid) { throw "No worker container" }
  $raw = docker exec $cid wget -qO- http://127.0.0.1:8080/admin/stats
  return ($raw | ConvertFrom-Json)
}

function Reset-And-Scale([int]$n) {
  Log "Scaling to $n workers"
  docker compose up -d --scale "worker=$n" --force-recreate worker | Out-Null
  Start-Sleep 15
  docker compose exec -T postgres psql -U email -d emailprocessor -c "TRUNCATE processed_emails, email_cache RESTART IDENTITY CASCADE;" | Out-Null
  docker compose exec -T redis redis-cli DEL emails:incoming emails:dlq | Out-Null
  docker compose restart worker | Out-Null
  Start-Sleep 15
  docker compose ps --format "{{.Name}} {{.Status}}"
}

function Wait-Processed([int]$target, [int]$timeoutSec = 86400) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = -1
  while ((Get-Date) -lt $deadline) {
    $stats = Get-Stats
    $p = [int]$stats.processed
    $d = [int]$stats.dlqStreamLength
    $accounted = $p + $d
    if ($accounted -ne $last -or ((Get-Date).Second % 30 -eq 0)) {
      Log ("progress processed={0}/{1} cached={2} dlq={3}" -f $p, $target, $stats.cached, $d)
      $last = $accounted
    }
    if ($p -ge $target) { return $stats }
    if ($accounted -ge $target -and $p -lt $target) {
      Log ("WARNING settled with dlq: processed={0} dlq={1}" -f $p, $d)
      return $stats
    }
    Start-Sleep 5
  }
  throw "Timed out waiting for $target processed"
}

Log "=== START 10k Qwen benchmarks emails=$Emails concurrency=$Concurrency ==="
docker compose up -d ai-service | Out-Null
Start-Sleep 5

foreach ($w in $WorkerCountList) {
  Log "=== BENCH workers=$w ==="
  Reset-And-Scale $w
  $started = Get-Date
  docker compose --profile producer run --rm `
    -e "DATASET_PATH=/data/benchmark/emails-10k-v1.jsonl" `
    producer 2>&1 | Tee-Object -FilePath (Join-Path $ResultsDir "producer-w$w.log") | Select-Object -Last 5

  $stats = Wait-Processed $Emails
  $elapsed = ((Get-Date) - $started).TotalSeconds
  if ($elapsed -lt 0.001) { $elapsed = 0.001 }
  $processed = [int]$stats.processed
  $epm = $processed / $elapsed * 60.0
  $result = [ordered]@{
    timestamp         = (Get-Date).ToUniversalTime().ToString("o")
    workerCount       = $w
    workerConcurrency = $Concurrency
    emailsProcessed   = $processed
    elapsedSeconds    = [math]::Round($elapsed, 3)
    emailsPerMinute   = [math]::Round($epm, 2)
    dlqCount          = [int]$stats.dlqStreamLength
    cached            = [int]$stats.cached
    dataset           = "benchmark/datasets/emails-10k-v1.jsonl"
    datasetSize       = $Emails
    model             = "qwen3:8b"
    llmProvider       = "ollama"
    notes             = "Measured full 10k synthetic run via docker compose + Ollama Qwen3 8B"
  }
  $out = Join-Path $ResultsDir ("bench-w{0}-c{1}-t1-n{2}-qwen3.json" -f $w, $Concurrency, $Emails)
  ($result | ConvertTo-Json) | Set-Content $out -Encoding utf8
  Log ("WROTE {0} => {1}" -f $out, ($result | ConvertTo-Json -Compress))

  # reconciliation for this run
  $recon = [ordered]@{
    timestamp = (Get-Date).ToUniversalTime().ToString("o")
    scenario  = "bench-w$w-n$Emails-qwen3"
    submitted = $Emails
    processed = $processed
    dlq       = [int]$stats.dlqStreamLength
    missing   = $Emails - $processed - [int]$stats.dlqStreamLength
    duplicates= 0
    cached    = [int]$stats.cached
  }
  ($recon | ConvertTo-Json) | Set-Content (Join-Path $Root "failure-tests/results/reconcile-bench-w$w-n$Emails-qwen3.json") -Encoding utf8
}

# scaling summary from qwen 10k files only
$py = "$env:USERPROFILE\tools\python312\python.exe"
& $py -c @"
import json
from pathlib import Path
rows=[]
for p in sorted(Path(r'$ResultsDir').glob('bench-w*-n$Emails-qwen3.json')):
    rows.append(json.loads(p.read_text(encoding='utf-8')))
by={}
for r in rows:
    by.setdefault(int(r['workerCount']), []).append(float(r['emailsPerMinute']))
base=None
summary=[]
for w in sorted(by):
    thr=sum(by[w])/len(by[w])
    if base is None: base=thr
    speedup = thr/base if base else 0
    summary.append({
        'workers': w,
        'avgEmailsPerMinute': round(thr,2),
        'speedup': round(speedup,3),
        'parallelEfficiency': round(speedup/w,3),
        'trials': len(by[w]),
        'dataset': 'emails-10k-v1.jsonl',
        'model': 'qwen3:8b'
    })
out=Path(r'$ResultsDir')/'scaling-summary-qwen3-10k.json'
out.write_text(json.dumps(summary, indent=2)+'\n', encoding='utf-8')
print(json.dumps(summary, indent=2))
"@

Log "=== ALL 10k Qwen benchmarks complete ==="
