# Builds Relay's native Switch UI/audio NRO without replacing the original
# Android UI/sound/animation sources. This does not build a PS Remote Play backend.
param([switch]$SkipAssetRefresh)
$ErrorActionPreference='Stop'
$sourceRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$projectRoot=(Resolve-Path $PSScriptRoot).Path
$python=(Get-Command python -ErrorAction Stop).Source
if(-not $SkipAssetRefresh -and -not (Test-Path (Join-Path $PSScriptRoot 'tools\node_modules\@resvg\resvg-js'))) {
    & npm ci --no-audit --no-fund --prefix (Join-Path $PSScriptRoot 'tools')
    if($LASTEXITCODE -ne 0) {throw 'Unable to install local vector rasterizer dependency'}
}
if(-not $SkipAssetRefresh) {
    & $python (Join-Path $PSScriptRoot 'tools\build_assets.py')
    if($LASTEXITCODE -ne 0){throw 'Unable to prepare Relay assets'}
}
& $python (Join-Path $PSScriptRoot 'verify_ui_parity.py')
if($LASTEXITCODE -ne 0){throw 'Original Relay UI/audio/animation sources changed'}
# RomFS contents and the original-derived NACP icon are inputs to elf2nro;
# force re-packaging even when the compiled C++ objects are unchanged.
$oldNro=Join-Path $PSScriptRoot 'relay-switch1.nro'
if(Test-Path $oldNro){Remove-Item -LiteralPath $oldNro -Force}
$devkit='C:\devkitPro'
foreach($prereq in @("$devkit\libnx\switch_rules","$devkit\devkitA64\bin\aarch64-none-elf-g++.exe","$devkit\msys2\usr\bin\make.exe")) {
    if(-not (Test-Path $prereq)){throw "Missing devkitPro prerequisite: $prereq"}
}
$aliases=(& subst | Out-String)
$letter=@('V:','W:','X:') | Where-Object {$aliases -notmatch ([regex]::Escape($_)+'\\')} | Select-Object -First 1
if(!$letter){throw 'No free short drive for paths containing spaces'}
& subst $letter $projectRoot
try {
    Push-Location ($letter+'\')
    try {
        $env:DEVKITPRO='C:/devkitPro'
        $env:DEVKITA64='C:/devkitPro/devkitA64'
        $env:PATH="$devkit\devkitA64\bin;$devkit\tools\bin;$devkit\msys2\usr\bin;"+$env:PATH
        & "$devkit\msys2\usr\bin\make.exe" -j2
        if($LASTEXITCODE -ne 0){throw 'Switch native NRO build failed'}
    } finally {Pop-Location}
} finally {& subst $letter /D}
& $python (Join-Path $PSScriptRoot 'verify_nro.py')
if($LASTEXITCODE -ne 0){throw 'Switch NRO or assets failed validation'}
Write-Host "NRO: $(Join-Path $PSScriptRoot 'relay-switch1.nro')"
