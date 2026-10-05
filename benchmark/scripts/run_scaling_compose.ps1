param(
  [int]$Emails = 1000,
  [int]$Concurrency = 4
)

$ErrorActionPreference = "Continue"
$WorkerCounts = @(1, 2, 4)
$Root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location $Root

function Reset-State {
  docker compose exec -T postgres psql -U email -d emailprocessor -c "TRUNCATE processed_emails, email_cache RESTART IDENTITY CASCADE;" | Out-Null
  docker compose exec -T redis redis-cli FLUSHALL | Out-Null
  Start-Sleep 2
}

function Wait-Processed([int]$target, [int]$timeoutSec = 3600) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $stats = Invoke-RestMethod http://127.0.0.1:8080/admin/stats
    Write-Host ("  progress processed={0}/{1} dlq={2}" -f $stats.processed, $target, $stats.dlqStreamLength)
    if ([int]$stats.processed -ge $target) { return $stats }
    Start-Sleep 3
  }
  throw "Timed out waiting for $target processed"
}

function Set-Workers([int]$n) {
  Write-Host "Scaling workers to $n"
  docker compose up -d --scale "worker=$n" worker
  Start-Sleep 10
  docker compose ps worker
}

New-Item -ItemType Directory -Force -Path "$Root/benchmark/results" | Out-Null

# Prepare fixed prefix dataset once
$tmpName = "_run-$Emails.jsonl"
$tmp = Join-Path $Root "benchmark/datasets/$tmpName"
Get-Content "$Root/benchmark/datasets/emails-10k-v1.jsonl" -TotalCount $Emails | Set-Content $tmp -Encoding utf8

foreach ($w in $WorkerCounts) {
  Write-Host "=== Scaling run workers=$w emails=$Emails ==="
  Set-Workers $w
  Reset-State

  $started = Get-Date
  docker compose --profile producer run --rm `
    -e "DATASET_PATH=/data/benchmark/$tmpName" `
    producer 2>&1 | Select-Object -Last 8

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
    dataset           = "benchmark/datasets/emails-10k-v1.jsonl (first $Emails)"
    notes             = "Measured via docker compose --scale worker. Mock LLM unless OPENAI configured."
  }
  $out = Join-Path $Root "benchmark/results/bench-w$w-c$Concurrency-t1-n$Emails.json"
  ($result | ConvertTo-Json) | Set-Content $out -Encoding utf8
  Write-Host "Wrote $out"
  Write-Host ($result | ConvertTo-Json -Compress)
}

& "$env:USERPROFILE\tools\python312\python.exe" "$Root/benchmark/scripts/compute_scaling.py"
Write-Host "SCALING_DONE"
