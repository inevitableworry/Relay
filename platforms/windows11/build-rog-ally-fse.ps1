# Optional MSIX identity for users who explicitly choose Relay as the Xbox mode
# Home app in Settings. This does not modify Explorer, registry or Xbox itself.
param([switch]$SkipWindowsBuild)
$ErrorActionPreference='Stop'
$root=(Resolve-Path $PSScriptRoot).Path
$publish=Join-Path $root 'dist\Relay-Windows11-x64'
$sdk='C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64'
$makeappx=Join-Path $sdk 'MakeAppx.exe'
$signtool=Join-Path $sdk 'SignTool.exe'
if(-not $SkipWindowsBuild){
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'build-windows.ps1')
    if($LASTEXITCODE -ne 0){throw 'Windows Relay application build failed'}
}
if(-not(Test-Path (Join-Path $publish 'Relay.Windows11.exe'))){throw 'Publish Relay first'}
$stage=Join-Path $root 'build\rog-ally-fse-stage'
$out=Join-Path $root 'dist\Relay-ROG-Xbox-Ally-FSE-Dev_0.1.0.0_x64.msix'
if(Test-Path $stage){Remove-Item -LiteralPath $stage -Recurse -Force}
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item -Path (Join-Path $publish '*') -Destination $stage -Recurse -Force
Copy-Item -LiteralPath (Join-Path $root 'Package.RogAllyFSE.appxmanifest') -Destination (Join-Path $stage 'AppxManifest.xml') -Force
Copy-Item -LiteralPath (Join-Path $root 'CustomCapability.SCCD') -Destination (Join-Path $stage 'CustomCapability.SCCD') -Force
'The MSI package marks this installed edition as full-screen ROG Ally mode.'|Set-Content -LiteralPath (Join-Path $stage 'rog-ally-fse.marker') -Encoding ASCII
if(Test-Path (Join-Path $stage 'Assets')){Remove-Item -LiteralPath (Join-Path $stage 'Assets') -Recurse -Force}
Copy-Item -LiteralPath (Join-Path $publish 'wwwroot\Assets') -Destination (Join-Path $stage 'Assets') -Recurse -Force
if(Test-Path $out){Remove-Item -LiteralPath $out -Force}
& $makeappx pack /d $stage /p $out /o
if($LASTEXITCODE -ne 0){throw 'Xbox Mode home app MSIX packaging failed'}
$cert=Get-ChildItem Cert:\CurrentUser\My | Where-Object {
    $_.Subject -eq 'CN=Relay Windows Development' -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date).AddMonths(1)
} | Sort-Object NotAfter -Descending | Select-Object -First 1
if(-not $cert){
    $cert=New-SelfSignedCertificate -Type Custom -Subject 'CN=Relay Windows Development' `
        -CertStoreLocation Cert:\CurrentUser\My -KeyExportPolicy Exportable -KeyAlgorithm RSA -KeyLength 2048 `
        -HashAlgorithm SHA256 -KeyUsage DigitalSignature `
        -TextExtension '2.5.29.37={text}1.3.6.1.5.5.7.3.3' -NotAfter (Get-Date).AddYears(2)
}
& $signtool sign /fd SHA256 /sha1 $cert.Thumbprint /s My $out
if($LASTEXITCODE -ne 0){throw 'Windows Ally MSIX signing failed'}
Export-Certificate -Cert $cert -FilePath (Join-Path $root 'dist\Relay-Windows-Dev-Public.cer') -Force | Out-Null
Write-Host "OPTIONAL_ROG_ALLY_HOME_APP=$out"
Write-Host 'This package uses a gamingApp extension/custom capability. Windows may reject unapproved capabilities.'
Write-Host 'Do not install or select it without the user choosing it in Windows Xbox mode Settings.'
Write-Host 'If rejected, use the Windows executable with --rog-ally and the reversible startup shortcut instead.'
