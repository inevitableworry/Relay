# Register local social website startup at current-user logon.
# Public Cloudflare social ingress stays disabled.
$ErrorActionPreference = 'Stop'
$name = 'Relay Social Local Website'
$existing = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
if ($existing) {
    Write-Output "$name already exists; refusing to modify existing task."
    exit 0
}
$script = (Resolve-Path (Join-Path $PSScriptRoot 'start-relay-social.ps1')).Path
$powershell = (Get-Command powershell.exe).Source
$argument = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script + '"'
$action = New-ScheduledTaskAction -Execute $powershell -Argument $argument
$trigger = New-ScheduledTaskTrigger -AtLogOn -User ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name)
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Description 'Starts localhost-only Relay Social; no public network listener' | Out-Null
Write-Output "$name installed (AtLogOn). Public ingress intentionally disabled."
