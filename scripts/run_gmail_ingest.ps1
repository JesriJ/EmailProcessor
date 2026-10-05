param(
  [switch]$Mock,
  [int]$WaitSeconds = 120,
  [int]$MaxMessages = 0
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

$dockerBins = @(
  "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin",
  "$env:ProgramFiles\Docker\Docker\resources\bin"
)
foreach ($bin in $dockerBins) {
  if (Test-Path (Join-Path $bin "docker.exe")) {
    $env:PATH = "$bin;$env:PATH"
    break
  }
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw "docker not found. Start Docker Desktop, then open a new terminal."
}

function Get-Stats {
  $cid = docker ps --filter "name=emailprocessor-worker" --format "{{.ID}}" | Select-Object -First 1
  if (-not $cid) { throw "No worker container running. Start the stack first." }
  $raw = docker exec $cid wget -qO- http://127.0.0.1:8080/admin/stats
  return ($raw | ConvertFrom-Json)
}

if ($Mock) {
  $env:USE_MOCK_GMAIL = "true"
  $env:MAIL_MODE = "gmail"
  Write-Host "Using MOCK Gmail client"
} else {
  $env:USE_MOCK_GMAIL = "false"
  $env:MAIL_MODE = "gmail"
  foreach ($k in @("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "GOOGLE_REFRESH_TOKEN")) {
    # Load from .env if not already in process env
    if (-not [Environment]::GetEnvironmentVariable($k)) {
      $line = Get-Content .env | Where-Object { $_ -match "^$k=" } | Select-Object -First 1
      if ($line) {
        $val = $line.Substring($k.Length + 1).Trim()
        [Environment]::SetEnvironmentVariable($k, $val, "Process")
      }
    }
    if (-not [Environment]::GetEnvironmentVariable($k)) {
      throw "$k is required for live Gmail. Run: python scripts/gmail_oauth.py"
    }
  }
  Write-Host "Using LIVE Gmail API"
}

if ($MaxMessages -gt 0) {
  $env:GMAIL_SYNC_MAX_MESSAGES = "$MaxMessages"
}

Write-Host "Ensuring stack is up..."
docker compose up -d postgres redis ai-service worker | Out-Null
Start-Sleep 5

Write-Host "Running ingest..."
docker compose --profile ingest run --rm --build ingest

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$before = Get-Stats
Write-Host ("After ingest: cached={0} processed={1} dlq={2}" -f $before.cached, $before.processed, $before.dlqStreamLength)
Write-Host "Waiting up to ${WaitSeconds}s for workers to process..."
while ((Get-Date) -lt $deadline) {
  $s = Get-Stats
  Write-Host ("  processed={0} cached={1} dlq={2}" -f $s.processed, $s.cached, $s.dlqStreamLength)
  if ([int]$s.processed -ge [int]$s.cached) { break }
  Start-Sleep 5
}
$final = Get-Stats
Write-Host ("DONE cached={0} processed={1} dlq={2}" -f $final.cached, $final.processed, $final.dlqStreamLength)
Write-Host "Dashboard: http://127.0.0.1:8088/"
