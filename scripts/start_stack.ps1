param(
  [int]$Workers = 1,
  [ValidateSet("mock", "ollama", "openai")]
  [string]$Llm = "ollama"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

# Ensure Docker CLI is on PATH (Docker Desktop user install)
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
try { docker info 1>$null 2>$null } catch {
  throw "Docker engine is not running. Open Docker Desktop and wait until it is ready, then retry."
}

switch ($Llm) {
  "mock" { $env:LLM_PROVIDER = "mock"; $env:USE_MOCK_LLM = "true" }
  "ollama" { $env:LLM_PROVIDER = "ollama"; $env:USE_MOCK_LLM = "false" }
  "openai" { $env:LLM_PROVIDER = "openai"; $env:USE_MOCK_LLM = "false" }
}

Write-Host "Starting EmailProcessor (workers=$Workers llm=$Llm)..."
docker compose up -d --build postgres redis ai-service gateway
docker compose up -d --scale "worker=$Workers" --build worker

Write-Host ""
Write-Host "Services:"
Write-Host "  Dashboard  http://127.0.0.1:8088/"
Write-Host "  AI health  http://127.0.0.1:8000/health"
Write-Host "  Admin API  http://127.0.0.1:8088/admin/stats"
Write-Host ""
Write-Host "Gmail (mock):  powershell -File scripts/run_gmail_ingest.ps1 -Mock"
Write-Host "Gmail (live):  python scripts/gmail_oauth.py"
Write-Host "               powershell -File scripts/run_gmail_ingest.ps1"
Write-Host "Synthetic:     docker compose --profile producer run --rm producer"
