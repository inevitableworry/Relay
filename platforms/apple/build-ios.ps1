# iOS/iPadOS ARM64 UI shell via the cloned WindowsIPABuilder CLI.
# Produces an ad-hoc signed IPA, not an Apple-signed App Store / stock device release.
param([string]$MinIOS='7.0')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$builder=Join-Path $root 'research\ios-build-tools\WindowsIPABuilder\app\src-tauri\target\release\ipabuilder-cli.exe'
$sdk=Join-Path $root 'research\ios-build-tools\WindowsIPABuilder\sdks\iPhoneOS16.5.sdk'
$ldid=Join-Path $root 'research\ios-build-tools\WindowsIPABuilder\tools\ldid.exe'
$clang=Join-Path "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358" 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
foreach($required in @($builder,$sdk,$ldid,$clang)) {
    if(-not (Test-Path $required)) {throw "Missing iOS build dependency: $required"}
}
& $builder build --source (Join-Path $PSScriptRoot 'RelayApp') --name Relay `
    --bundle-id 'us.orosapp.relay.ios' --min-ios $MinIOS --version '0.1.0' `
    --device iphone --device ipad --framework UIKit --framework Foundation `
    --framework QuartzCore --icon (Join-Path $PSScriptRoot 'RelayApp\relay_icon.png') `
    --clang $clang --sdk $sdk --ldid $ldid --output (Join-Path $PSScriptRoot 'dist')
if($LASTEXITCODE -ne 0) {throw 'iOS/iPadOS ARM64 IPA packaging failed'}
Write-Host "Built ad-hoc signed iOS/iPadOS UI-only IPA: $(Join-Path $PSScriptRoot 'dist\Relay.ipa')"
