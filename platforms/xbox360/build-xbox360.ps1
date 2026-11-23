# Compile native Relay Xbox 360 homebrew on the locally configured WSL2 Ubuntu.
# A valid native .xex is not proof of Xenia rendering or physical Xbox 360 use.
param([switch]$SkipParticleRefresh)
$ErrorActionPreference='Stop'
$root=(Resolve-Path $PSScriptRoot).Path
if(-not $SkipParticleRefresh){
    & python (Join-Path $root 'scripts\prepare_particles.py')
    if($LASTEXITCODE -ne 0){throw 'Cannot generate original Relay particle data'}
}
$script='/mnt/h/Samsung Android/PSPortalAndroid/platforms/xbox360/scripts/build-xex.sh'
& wsl -d Ubuntu -u root --exec bash $script
if($LASTEXITCODE -ne 0){throw 'Native Xbox 360 Relay XEX compilation failed'}
$file=Join-Path $root 'dist\relay360.xex'
if(-not(Test-Path $file)){throw 'Xbox 360 native build returned without XEX'}
Write-Host "RELAY_XBOX360_XEX=$file SIZE=$((Get-Item $file).Length)"
Write-Host 'Run on an RGH/JTAG Xbox 360 or test in Xenia Canary; no retail compatibility claim.'
