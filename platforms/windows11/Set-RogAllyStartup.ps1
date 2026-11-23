param([switch]$Enable,[switch]$Disable,[switch]$OpenWindowsXboxModeSettings)
$ErrorActionPreference='Stop'
$root=(Resolve-Path $PSScriptRoot).Path
$exe=Join-Path $root 'dist\Relay-Windows11-x64\Relay.Windows11.exe'
$startup=[Environment]::GetFolderPath('Startup')
$link=Join-Path $startup 'Relay ROG Xbox Ally.lnk'
if($Enable -and $Disable){throw 'Select either -Enable or -Disable'}
if($Enable){
    if(-not(Test-Path $exe)){throw "Build Relay first: $exe"}
    $shell=New-Object -ComObject WScript.Shell
    $shortcut=$shell.CreateShortcut($link)
    $shortcut.TargetPath=$exe
    $shortcut.Arguments='--rog-ally'
    $shortcut.WorkingDirectory=Split-Path $exe
    $shortcut.IconLocation=$exe+',0'
    $shortcut.Save()
    Write-Host 'Opt-in Relay ROG Ally startup link created. Windows Explorer and Xbox app are unchanged.'
}
if($Disable){
    if(Test-Path $link){Remove-Item -LiteralPath $link -Force}
    Write-Host 'Relay ROG Ally startup link disabled; existing Xbox/Explorer settings unchanged.'
}
if($OpenWindowsXboxModeSettings){
    # Supported Microsoft setting; Windows decides which home-app identities
    # are eligible. Do not modify shell/explorer or hidden OEM registry values.
    Start-Process 'ms-settings:gaming'
    Write-Host 'In Settings > Gaming > Xbox mode, Windows lists supported home apps.'
}
