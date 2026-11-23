# Builds the devkitPro ExeFS PFS0 NSP development container.
# WARNING: This is NOT an installable signed NCA/title NSP. It exists for
# debugging toolchain output and testing Eden's NSP format handling.
param([switch]$SkipNroBuild)
$ErrorActionPreference='Stop'
$project=(Resolve-Path $PSScriptRoot).Path
if(-not $SkipNroBuild) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $project 'build-switch1.ps1')
    if($LASTEXITCODE -ne 0) {throw 'Native Relay build failed'}
}
$tools='C:\devkitPro\tools\bin'
$elf=Join-Path $project 'relay-switch1.elf'
$stage=Join-Path $project 'exefs-dev'
$package=Join-Path $project 'relay-switch1-exefs-dev.nsp'
if(-not(Test-Path $elf)){throw 'Missing compiled Relay ELF'}
New-Item -ItemType Directory -Force $stage | Out-Null
& (Join-Path $tools 'elf2nso.exe') $elf (Join-Path $stage 'main')
if($LASTEXITCODE -ne 0){throw 'elf2nso failed'}
& (Join-Path $tools 'npdmtool.exe') (Join-Path $project 'relay-npdm.json') (Join-Path $stage 'main.npdm')
if($LASTEXITCODE -ne 0){throw 'npdmtool failed'}
& (Join-Path $tools 'build_pfs0.exe') $stage $package
if($LASTEXITCODE -ne 0){throw 'build_pfs0 failed'}
& python (Join-Path $project 'verify_nsp_dev.py')
if($LASTEXITCODE -ne 0){throw 'NSP development container verification failed'}
Write-Host "Development ExeFS NSP (NOT installable): $package"
