param(
    [Parameter(Mandatory=$true)][string]$SupervisorConfig
)
$ErrorActionPreference = 'Stop'
$relaySettingsPath = (Resolve-Path -LiteralPath $SupervisorConfig).Path
$relaySettings = Get-Content -LiteralPath $relaySettingsPath -Raw | ConvertFrom-Json
$relayNode = (Get-Command node.exe -ErrorAction Stop).Source
$relayCloudflared = (Get-Command cloudflared.exe -ErrorAction Stop).Source
$relayRoot = Split-Path -Parent $PSScriptRoot
$relayGateway = Join-Path $relayRoot 'tools\relay-gateway\cli.mjs'
$relayRuntime = Split-Path -Parent $relaySettingsPath
$relayGatewayConfig = (Resolve-Path -LiteralPath $relaySettings.gatewayConfig).Path
$relayTunnelConfig = (Resolve-Path -LiteralPath $relaySettings.tunnelConfig).Path
if ($relayGateway.Contains('"') -or $relayGatewayConfig.Contains('"') -or $relayTunnelConfig.Contains('"')) {
    throw 'Invalid configuration path'
}
$relayMutexCreated = $false
$relayMutex = New-Object System.Threading.Mutex($true, 'Local\RelayRemoteServices', [ref]$relayMutexCreated)
if (-not $relayMutexCreated) { $relayMutex.Dispose(); exit 0 }

function Find-RelayProcess([string]$ExecutableName, [string]$ConfigPath) {
    $relayCandidates = @(Get-CimInstance Win32_Process -Filter ("Name = '{0}'" -f $ExecutableName) |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($ConfigPath,[StringComparison]::OrdinalIgnoreCase) -ge 0 })
    if ($relayCandidates.Count -gt 1) { throw "More than one owned $ExecutableName process is running." }
    if ($relayCandidates.Count -eq 1) { return Get-Process -Id $relayCandidates[0].ProcessId -ErrorAction SilentlyContinue }
    return $null
}
$relayGatewayProcess = Find-RelayProcess 'node.exe' $relayGatewayConfig
$relayTunnelProcess = Find-RelayProcess 'cloudflared.exe' $relayTunnelConfig
try {
    while ($true) {
        if ($relayGatewayProcess) { $relayGatewayProcess.Refresh() }
        if (-not $relayGatewayProcess -or $relayGatewayProcess.HasExited) {
            $relayGatewayArgs = @{
                FilePath = $relayNode
                WindowStyle = 'Hidden'
                PassThru = $true
                WorkingDirectory = $relayRoot
                ArgumentList = ('"{0}" --config "{1}"' -f $relayGateway,$relayGatewayConfig)
                RedirectStandardOutput = (Join-Path $relayRuntime 'gateway.stdout.log')
                RedirectStandardError = (Join-Path $relayRuntime 'gateway.stderr.log')
            }
            $relayGatewayProcess = Start-Process @relayGatewayArgs
        }
        if ($relayTunnelProcess) { $relayTunnelProcess.Refresh() }
        if (-not $relayTunnelProcess -or $relayTunnelProcess.HasExited) {
            $relayTunnelArgs = @{
                FilePath = $relayCloudflared
                WindowStyle = 'Hidden'
                PassThru = $true
                WorkingDirectory = $relayRoot
                ArgumentList = ('tunnel --no-autoupdate --config "{0}" --loglevel warn --metrics 127.0.0.1:37940 run' -f $relayTunnelConfig)
                RedirectStandardOutput = (Join-Path $relayRuntime 'tunnel.stdout.log')
                RedirectStandardError = (Join-Path $relayRuntime 'tunnel.stderr.log')
            }
            $relayTunnelProcess = Start-Process @relayTunnelArgs
        }
        Start-Sleep -Seconds 5
    }
} finally {
    # These handles refer only to this configuration's owned processes.
    if ($relayGatewayProcess -and -not $relayGatewayProcess.HasExited) { Stop-Process -Id $relayGatewayProcess.Id -ErrorAction SilentlyContinue }
    if ($relayTunnelProcess -and -not $relayTunnelProcess.HasExited) { Stop-Process -Id $relayTunnelProcess.Id -ErrorAction SilentlyContinue }
    $relayMutex.ReleaseMutex()
    $relayMutex.Dispose()
}

