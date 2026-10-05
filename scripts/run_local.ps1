# Local stack without Docker Desktop (Windows).
# Prerequisites: JDK21/Maven in ~/tools, Redis for Windows, user Postgres on 5433.

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "$env:USERPROFILE\tools\jdk-21.0.12.1+1"
$env:PATH = "$env:JAVA_HOME\bin;$env:USERPROFILE\tools\apache-maven-3.9.9\bin;$env:PATH"
$py = "$env:USERPROFILE\tools\python312\python.exe"

# Redis
if (-not (Get-Process redis-server -ErrorAction SilentlyContinue)) {
  Start-Process "$env:USERPROFILE\tools\redis\redis-server.exe" `
    -ArgumentList "$env:USERPROFILE\tools\redis\redis.windows.conf" -WindowStyle Hidden
}

# Postgres on 5433
$pgBin = "C:\Program Files\PostgreSQL\18\bin"
$pgData = "$env:USERPROFILE\tools\pgdata-email"
& "$pgBin\pg_ctl.exe" -D $pgData status 2>$null
if ($LASTEXITCODE -ne 0) {
  & "$pgBin\pg_ctl.exe" -D $pgData -l "$env:USERPROFILE\tools\pg-email.log" start
}

$env:POSTGRES_HOST = "127.0.0.1"
$env:POSTGRES_PORT = "5433"
$env:POSTGRES_DB = "emailprocessor"
$env:POSTGRES_USER = "email"
$env:POSTGRES_PASSWORD = "email"
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = "6379"
$env:AI_SERVICE_URL = "http://127.0.0.1:8000"
$env:WORKER_ID = "worker-1"
$env:WORKER_CONCURRENCY = "4"
$env:USE_MOCK_LLM = "true"

Write-Host "Starting AI service..."
Start-Process $py -ArgumentList "-m","uvicorn","app.main:app","--host","127.0.0.1","--port","8000" `
  -WorkingDirectory "$Root\ai-service" -WindowStyle Hidden

Write-Host "Starting worker..."
$jar = Get-ChildItem "$Root\worker-service\target\worker-service-*.jar" | Select-Object -First 1
if (-not $jar) {
  Push-Location "$Root\worker-service"; mvn -q -DskipTests package; Pop-Location
  $jar = Get-ChildItem "$Root\worker-service\target\worker-service-*.jar" | Select-Object -First 1
}
Start-Process java -ArgumentList "-jar", $jar.FullName -WindowStyle Hidden
Write-Host "Local stack starting. Health: http://127.0.0.1:8000/health  Admin: http://127.0.0.1:8080/admin/stats"
