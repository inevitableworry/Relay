param(
    [Parameter(Mandatory=$true)][string]$SupervisorConfig
)
$ErrorActionPreference = 'Stop'
$relayConfig = (Resolve-Path -LiteralPath $SupervisorConfig).Path
$relaySupervisor = Join-Path $PSScriptRoot 'start-relay-remote.ps1'
if (-not (Test-Path -LiteralPath $relaySupervisor)) { throw 'Relay supervisor script is missing' }
if ($relayConfig.Contains('"') -or $relaySupervisor.Contains('"')) { throw 'Invalid configuration path' }
$relayIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$relayTaskName = 'Relay Server and Tunnel'
$relayExisting = Get-ScheduledTask -TaskName $relayTaskName -ErrorAction SilentlyContinue
if ($relayExisting) {
    throw 'The Relay startup task already exists. Inspect it before changing its registration.'
}
$relayPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$relayArguments = '-NoProfile -NonInteractive -WindowStyle Hidden -File "{0}" -SupervisorConfig "{1}"' -f $relaySupervisor,$relayConfig
$relayAction = New-ScheduledTaskAction -Execute $relayPowerShell -Argument $relayArguments -WorkingDirectory (Split-Path -Parent $PSScriptRoot)
$relayTrigger = New-ScheduledTaskTrigger -AtLogOn -User $relayIdentity
$relayPrincipal = New-ScheduledTaskPrincipal -UserId $relayIdentity -LogonType Interactive -RunLevel Limited
$relaySettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $relayTaskName -Action $relayAction -Trigger $relayTrigger -Principal $relayPrincipal -Settings $relaySettings -Description 'Starts the authenticated Relay gateway and its named Cloudflare tunnel when Ben signs in.' | Out-Null
Start-ScheduledTask -TaskName $relayTaskName
Get-ScheduledTask -TaskName $relayTaskName | Select-Object TaskName,State
