param([string]$DevkitPro='C:\devkitPro')
$ErrorActionPreference='Stop'
$root=(Resolve-Path $PSScriptRoot).Path
$tools=Join-Path $DevkitPro 'msys2\usr\bin'
$env:DEVKITPRO=$DevkitPro.Replace('\','/')
$env:DEVKITARM=(Join-Path $DevkitPro 'devkitARM').Replace('\','/')
$env:PATH="$(Join-Path $DevkitPro 'devkitARM\bin');$(Join-Path $DevkitPro 'tools\bin');$tools;$env:PATH"
$make=Join-Path $tools 'make.exe'
if (-not (Test-Path $make)) {throw 'devkitPro make.exe missing'}
$staged=Join-Path $root 'dist'
New-Item -ItemType Directory -Path $staged -Force | Out-Null
$existing=Join-Path $staged 'Relay-3DSFamily-Horizon-ARM11-dev.3dsx'
if(Test-Path $existing){Remove-Item -LiteralPath $existing -Force}
# GNU make cannot use a CURDIR with spaces. Use existing short drive alias.
$drive='P:'
$mapping=(& subst | Where-Object {$_ -match '^P:\\'}) -join ''
$project=(Resolve-Path (Join-Path $root '..\..')).Path
if($mapping -and -not $mapping.ToLowerInvariant().Contains($project.ToLowerInvariant())) { throw 'P: occupied by unrelated path' }
if(-not $mapping){ & subst $drive $project; if($LASTEXITCODE){throw 'subst failed'} }
Push-Location 'P:\platforms\3ds'
try {
    & $make -j2
    if ($LASTEXITCODE) {throw "3DS build failed ($LASTEXITCODE)"}
} finally {Pop-Location}
$built=Join-Path $root 'relay_3ds.3dsx'
if(-not (Test-Path -LiteralPath $built)){throw '3DSX build artifact missing'}
Copy-Item -LiteralPath $built -Destination $existing
Write-Host "Built $existing SHA256 $((Get-FileHash -LiteralPath $existing -Algorithm SHA256).Hash)"
