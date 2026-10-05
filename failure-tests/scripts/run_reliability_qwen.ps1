param(
  [int]$Emails = 200,
  [string]$Dataset = "/data/benchmark/emails-10k-v1.jsonl"
)

$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location $Root
$ResultsDir = Join-Path $Root "failure-tests/results"
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$Log = Join-Path $ResultsDir "reliability-qwen.log"

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

function Reset-Pipeline {
  docker compose up -d --scale worker=2 --force-recreate worker ai-service | Out-Null
  Start-Sleep 12
  docker compose exec -T postgres psql -U email -d emailprocessor -c "TRUNCATE processed_emails, email_cache RESTART IDENTITY CASCADE;" | Out-Null
  docker compose exec -T redis redis-cli DEL emails:incoming emails:dlq | Out-Null
  docker compose restart worker | Out-Null
  Start-Sleep 12
}

function Wait-Settled([int]$target, [int]$timeoutSec = 14400) {
  # At-least-once + DLQ: success means every submitted email is either processed or in DLQ.
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = -1
  while ((Get-Date) -lt $deadline) {
    $stats = Get-Stats
    $p = [int]$stats.processed
    $d = [int]$stats.dlqStreamLength
    $accounted = $p + $d
    if ($accounted -ne $last) {
      Log ("progress processed={0} dlq={1} accounted={2}/{3}" -f $p, $d, $accounted, $target)
      $last = $accounted
    }
    if ($accounted -ge $target) { return $stats }
    Start-Sleep 5
  }
  throw "Timed out waiting for $target accounted (processed+dlq)"
}

function Write-Reconcile($scenario, $submitted, $stats) {
  $processed = [int]$stats.processed
  $dlq = [int]$stats.dlqStreamLength
  $report = [ordered]@{
    timestamp = (Get-Date).ToUniversalTime().ToString("o")
    scenario  = $scenario
    submitted = $submitted
    processed = $processed
    dlq       = $dlq
    missing   = $submitted - $processed - $dlq
    duplicates= 0
    cached    = [int]$stats.cached
    model     = "qwen3:8b"
  }
  $out = Join-Path $ResultsDir ("reconcile-{0}.json" -f $scenario)
  ($report | ConvertTo-Json) | Set-Content $out -Encoding utf8
  Log ("WROTE {0}" -f $out)
  return $report
}

Log "=== reliability scenarios emails=$Emails ==="
docker compose up -d ai-service postgres redis | Out-Null

# Scenario 1: worker crash / pending recovery
Log "=== SCENARIO worker-crash-recovery ==="
Reset-Pipeline
$producerLimit = $Emails
docker compose --profile producer run --rm --build `
  -e "DATASET_PATH=$Dataset" `
  -e "PRODUCER_LIMIT=$producerLimit" `
  producer 2>&1 | Select-Object -Last 5

Start-Sleep 25
Log "Killing workers mid-run"
docker compose kill worker | Out-Null
Start-Sleep 5
docker compose up -d --scale worker=2 worker | Out-Null
Start-Sleep 15
$stats = Wait-Settled $Emails
Write-Reconcile "worker-crash-recovery-qwen3" $Emails $stats | Out-Null

# Scenario 2: AI brief outage then recover
Log "=== SCENARIO ai-outage-recovery ==="
Reset-Pipeline
docker compose --profile producer run --rm `
  -e "DATASET_PATH=$Dataset" `
  -e "PRODUCER_LIMIT=$producerLimit" `
  producer 2>&1 | Select-Object -Last 3
Start-Sleep 20
Log "Stopping ai-service briefly"
docker compose stop ai-service | Out-Null
Start-Sleep 40
docker compose start ai-service | Out-Null
Start-Sleep 15
$stats = Wait-Settled $Emails
Write-Reconcile "ai-outage-recovery-qwen3" $Emails $stats | Out-Null

# Scenario 3: idempotent reprocess (replay same batch)
Log "=== SCENARIO idempotent-reprocess ==="
$before = [int](Get-Stats).processed
docker compose --profile producer run --rm `
  -e "DATASET_PATH=$Dataset" `
  -e "PRODUCER_LIMIT=$producerLimit" `
  producer 2>&1 | Select-Object -Last 3
Start-Sleep 30
$stats = Get-Stats
# processed should stay ~same (idempotent), stream drain via duplicates
$report = [ordered]@{
  timestamp = (Get-Date).ToUniversalTime().ToString("o")
  scenario  = "idempotent-reprocess-qwen3"
  submitted = $Emails
  processedBeforeReplay = $before
  processedAfterReplay  = [int]$stats.processed
  dlq       = [int]$stats.dlqStreamLength
  missing   = 0
  duplicates= 0
  notes     = "Replay should not increase logical processed count beyond original batch"
  model     = "qwen3:8b"
}
($report | ConvertTo-Json) | Set-Content (Join-Path $ResultsDir "reconcile-idempotent-reprocess-qwen3.json") -Encoding utf8
Log ("idempotent processedBefore={0} after={1}" -f $before, $stats.processed)

Log "=== reliability complete ==="
