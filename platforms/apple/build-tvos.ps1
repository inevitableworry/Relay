# ARM64 tvOS experimental UIKit shell build for Apple TV HD A1625 and 4K.
# Not a functional streaming client. Requires the locally extracted AppleTVOS SDK.
param(
    [string]$Sdk = "",
    [string]$NdkRoot = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358",
    [string]$Ldid = ""
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $Sdk) { $Sdk = Join-Path $projectRoot 'research\ios-build-tools\WindowsIPABuilder\sdks\AppleTVOS12.4.sdk' }
if (-not $Ldid) { $Ldid = Join-Path $projectRoot 'research\ios-build-tools\WindowsIPABuilder\tools\ldid.exe' }
$bin = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
foreach ($required in @($Sdk,$Ldid,(Join-Path $bin 'clang.exe'),(Join-Path $bin 'ld.lld.exe'))) {
    if (-not (Test-Path $required)) { throw "Missing prerequisite $required" }
}
$build = Join-Path $PSScriptRoot 'build'
$dist = Join-Path $PSScriptRoot 'dist'
$app = Join-Path $build 'tvos\Payload\Relay.app'
New-Item -ItemType Directory -Force $app,$dist | Out-Null
$object = Join-Path $build 'RelayApp-tvos-arm64.o'
$executable = Join-Path $app 'Relay'
& (Join-Path $bin 'clang.exe') -target arm64-apple-tvos9.0 -isysroot $Sdk -fobjc-arc -c (Join-Path $PSScriptRoot 'RelayApp\RelayApp.m') -o $object
if ($LASTEXITCODE -ne 0) { throw 'ARM64 tvOS compile failed' }
& (Join-Path $bin 'ld.lld.exe') -flavor darwin -arch arm64 -platform_version tvos 9.0 12.4 -syslibroot $Sdk -o $executable $object -lSystem -lobjc -framework UIKit -framework Foundation -framework QuartzCore
if ($LASTEXITCODE -ne 0) { throw 'ARM64 tvOS link failed' }
Copy-Item (Join-Path $PSScriptRoot 'RelayApp\Info-tvOS.plist') (Join-Path $app 'Info.plist') -Force
Copy-Item (Join-Path $PSScriptRoot 'RelayApp\relay_icon.png') (Join-Path $app 'relay_icon.png') -Force
& $Ldid -S $executable
if ($LASTEXITCODE -ne 0) { throw 'Ad-hoc signing failed' }
$zip = Join-Path $dist 'Relay-tvOS-UI-preview.zip'
$ipa = Join-Path $dist 'Relay-tvOS-UI-preview.ipa'
if (Test-Path $zip) { Remove-Item -LiteralPath $zip -Force }
if (Test-Path $ipa) { Remove-Item -LiteralPath $ipa -Force }
Compress-Archive -Path (Join-Path $build 'tvos\Payload') -DestinationPath $zip
Move-Item -LiteralPath $zip -Destination $ipa
Write-Host "Built (ad-hoc signed UI-only preview): $ipa"
