param(
  [switch]$AlsoRestartWorkers
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
  throw "docker not found. Start Docker Desktop and retry."
}

Write-Host "Truncating email_cache + processed_emails..."
docker compose exec -T postgres psql -U email -d emailprocessor -c "TRUNCATE processed_emails, email_cache RESTART IDENTITY CASCADE;"

Write-Host "Clearing Redis streams..."
docker compose exec -T redis redis-cli DEL emails:incoming emails:dlq | Out-Null

if ($AlsoRestartWorkers) {
  Write-Host "Restarting workers..."
  docker compose restart worker | Out-Null
}

Write-Host "Reset complete."
