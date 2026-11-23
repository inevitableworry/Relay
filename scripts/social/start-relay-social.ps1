# First-party Relay Social local-only startup on BEN-PC.
# Does NOT expose social to Cloudflare until production hardening.
param([int]$Port = 48175)
$ErrorActionPreference = 'Stop'
# Production source and all user uploads live together on the largest external
# NTFS disk. The previous development folder is retained solely for rollback.
$root = 'H:\Relay Social'
if (-not (Test-Path -LiteralPath (Join-Path $root 'data\social.sqlite'))) {
    throw 'Relay Social persistent database missing from external disk; refusing to start an empty site.'
}
if (-not (Test-Path -LiteralPath (Join-Path $root 'data\.master-key'))) {
    throw 'Relay Social encryption key missing; refusing to start without existing account secrets.'
}
$logs = Join-Path $env:LOCALAPPDATA 'Relay\logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$uri = "http://127.0.0.1:$Port/api/health"
try {
    $health = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 3
    if ($health.StatusCode -eq 200 -and $health.Content -match 'relay-social') {
        Write-Output 'Relay Social local-only service is already running.'
        exit 0
    }
    throw "Port $Port responds but not as Relay Social; refusing to modify another process."
} catch {
    if ($_.Exception.Message -match 'Port .* responds but not') { throw }
}
if (-not (Test-Path (Join-Path $root 'server.mjs'))) { throw 'Relay Social server missing' }
$node = (Get-Command node.exe -ErrorAction Stop).Source
$process = Start-Process -FilePath $node -ArgumentList @('server.mjs') -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'relay-social.log') -RedirectStandardError (Join-Path $logs 'relay-social-error.log') -PassThru
Start-Sleep -Seconds 2
if ($process.HasExited) { throw "Relay Social exited with code $($process.ExitCode)" }
$check = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 5
if ($check.StatusCode -ne 200 -or $check.Content -notmatch 'relay-social') {
    throw 'Relay Social local-origin health check failed'
}
Write-Output "Relay Social local-only ready at http://127.0.0.1:$Port (pid $($process.Id))"
