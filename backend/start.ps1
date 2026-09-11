# start.ps1 — Load .env and start the Spring Boot backend
# Usage: .\start.ps1

$envFile = Join-Path $PSScriptRoot ".env"

if (-not (Test-Path $envFile)) {
    Write-Error ".env file not found at $envFile"
    exit 1
}

Write-Host "Loading environment from .env..." -ForegroundColor Cyan

Get-Content $envFile | Where-Object {
    $_ -notmatch '^\s*#' -and $_.Trim() -ne ''
} | ForEach-Object {
    $parts = $_ -split '=', 2
    $key   = $parts[0].Trim()
    $value = $parts[1].Trim()
    [System.Environment]::SetEnvironmentVariable($key, $value, 'Process')
    Write-Host "  $key set" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "Starting Spring Boot backend..." -ForegroundColor Green
mvn spring-boot:run
