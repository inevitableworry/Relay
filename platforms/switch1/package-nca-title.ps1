# Creates an actual NCA-based Nintendo Switch application NSP from Relay's
# independently ported native ELF, with local title-packaging tools only.
# No third-party Remote Play implementation is included.
param([switch]$SkipNativeBuild)
$ErrorActionPreference='Stop'
$root=(Resolve-Path $PSScriptRoot).Path
$project=(Resolve-Path (Join-Path $root '..\..')).Path
$builder='C:\Eden\RelayCargoCache\release\cargo-nx.exe'
$keyset='C:\Eden\prod.keys'
if(-not(Test-Path $keyset)){throw 'Cannot locate the existing local Eden keyset needed for NCA encryption'}
if(-not(Test-Path $builder)){throw 'Missing local cargo-nx title packager; build nx-tools cargo-nx first'}
if(-not $SkipNativeBuild){
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'build-switch1.ps1')
    if($LASTEXITCODE -ne 0){throw 'Native Relay NRO build failed'}
}
$elf=Join-Path $root 'relay-switch1.elf'
if(-not(Test-Path $elf)){throw 'Native Relay ELF is missing'}
$stage=Join-Path $root 'title-stage'
$out=Join-Path $root 'nca-title-output'
$fresh=Join-Path $out 'current-package'
New-Item -ItemType Directory -Force $out,$stage | Out-Null
if(Test-Path $fresh){Remove-Item -LiteralPath $fresh -Recurse -Force}
New-Item -ItemType Directory -Force "$stage\exefs","$stage\control","$stage\romfs",$fresh | Out-Null

# Generate normal application NPDM, not a sysmodule descriptor.
$spec=Join-Path $root 'nsp-title-npdm.json'
if(-not(Test-Path $spec)){throw 'Missing native application NPDM specification'}
# npdmtool reports omitted *optional* fields on stderr even when it succeeds.
# Run it with native stderr collected and judge its exit code/output file,
# rather than interpreting its harmless warnings as fatal PowerShell errors.
$savedPreference=$ErrorActionPreference
try {
    $ErrorActionPreference='Continue'
    $npdmOutput= & 'C:\devkitPro\tools\bin\npdmtool.exe' $spec (Join-Path $stage 'exefs\main.npdm') 2>&1
    $npdmExit=$LASTEXITCODE
} finally {$ErrorActionPreference=$savedPreference}
if($npdmExit -ne 0 -or -not(Test-Path (Join-Path $stage 'exefs\main.npdm'))){
    throw 'Unable to generate native application NPDM'
}
& 'C:\devkitPro\tools\bin\elf2nso.exe' $elf (Join-Path $stage 'exefs\main')
if($LASTEXITCODE -ne 0){throw 'Unable to convert native Relay ELF to NSO'}
Copy-Item -LiteralPath (Join-Path $root 'relay-switch1.nacp') -Destination (Join-Path $stage 'control\control.nacp') -Force
Copy-Item -LiteralPath (Join-Path $root 'icon.jpg') -Destination (Join-Path $stage 'control\icon_AmericanEnglish.dat') -Force
Copy-Item -Path (Join-Path $root 'romfs\*') -Destination (Join-Path $stage 'romfs') -Recurse -Force

# Only the user's existing local keyset is read by cargo-nx: do not copy or
# print it, embed it in the project, or place it into generated artifacts.
$previous=(Get-Location).Path
try {
    Set-Location $stage
    & $builder nx tool hacbrewpack --keyset $keyset --exefsdir exefs --romfsdir romfs --controldir control --nologo --nspdir $fresh --titleid 0100abcd23450000 --titlename Relay --titlepublisher 'Relay Project'
    if($LASTEXITCODE -ne 0){throw 'NCA title packer failed'}
} finally {Set-Location $previous}
$built=Join-Path $fresh '0100abcd23450000.nsp'
if(-not(Test-Path $built)){throw 'NCA title packer did not produce an NSP'}
& $builder nx tool hactool --keyset $keyset --verify $built
if($LASTEXITCODE -ne 0){throw 'NCA title signature/content checks failed'}
$destination=Join-Path $out '0100abcd23450000.nsp'
Copy-Item -LiteralPath $built -Destination $destination -Force
Remove-Item -LiteralPath $fresh -Recurse -Force
$f=Get-Item $destination
Write-Host "Native NCA Relay title NSP: $destination ($($f.Length) bytes)"
Write-Host "SHA256: $((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Host 'NSP packaging succeeded. Real Remote Play playback must still be validated separately.'
