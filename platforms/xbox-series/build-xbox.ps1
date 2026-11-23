# Create a neutral, JavaScript/HTML UWP APPX for Xbox Series S/X Developer Mode.
# Requires existing Windows 10/11 SDK tools on BEN-PC and a user-local signing
# certificate. Does not use a console GDK, retail signing, or secret platform keys.
param([switch]$SkipSigning,[switch]$SkipAssets)
$ErrorActionPreference='Stop'
$root=(Resolve-Path $PSScriptRoot).Path
$project=(Resolve-Path (Join-Path $root '..\..')).Path
$sdk='C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64'
$makeappx=Join-Path $sdk 'makeappx.exe'
$signtool=Join-Path $sdk 'signtool.exe'
$outDir=Join-Path $root 'dist'
$staging=Join-Path $root 'build\stage'
$package=Join-Path $outDir 'Relay-Xbox-Series-S-X-Dev_0.1.0.0_neutral.appx'
if(-not(Test-Path $makeappx)){throw "Windows SDK MakeAppx missing: $makeappx"}
if(-not(Test-Path $signtool)){throw "Windows SDK SignTool missing: $signtool"}
if(-not $SkipAssets){
    & python (Join-Path $root 'scripts\prepare_assets.py')
    if($LASTEXITCODE -ne 0){throw 'Relay Android assets could not be converted for Xbox'}
}
if(-not(Test-Path (Join-Path $root 'js\particles.js'))){throw 'Generated original Relay particle distribution missing'}
& node --check (Join-Path $root 'js\relay.js')
if($LASTEXITCODE -ne 0){throw 'Xbox UI JavaScript failed syntax validation'}
& node --check (Join-Path $root 'js\pbs-protocol.js')
if($LASTEXITCODE -ne 0){throw 'Relay PBS JavaScript failed syntax validation'}
& node --test (Join-Path $root 'tests\pbs-protocol.test.cjs')
if($LASTEXITCODE -ne 0){throw 'PBS protocol unit tests failed'}

New-Item -ItemType Directory -Force $outDir | Out-Null
if(Test-Path $staging){Remove-Item -LiteralPath $staging -Force -Recurse}
New-Item -ItemType Directory -Force $staging | Out-Null
foreach($item in @('default.html','css','js','Assets')){
    Copy-Item -LiteralPath (Join-Path $root $item) -Destination (Join-Path $staging $item) -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $root 'Package.appxmanifest') -Destination (Join-Path $staging 'AppxManifest.xml') -Force
if(Test-Path $package){Remove-Item -LiteralPath $package -Force}
& $makeappx pack /d $staging /p $package /o
if($LASTEXITCODE -ne 0 -or -not(Test-Path $package)){throw 'Xbox UWP APPX packaging failed'}

if(-not $SkipSigning){
    $subject='CN=Relay Xbox Development'
    $cert=Get-ChildItem Cert:\CurrentUser\My | Where-Object {
        $_.Subject -eq $subject -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date).AddMonths(1)
    } | Sort-Object NotAfter -Descending | Select-Object -First 1
    if(-not $cert){
        $cert=New-SelfSignedCertificate -Type Custom -Subject $subject -CertStoreLocation 'Cert:\CurrentUser\My' `
            -KeyExportPolicy Exportable -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256 `
            -KeyUsage DigitalSignature -TextExtension '2.5.29.37={text}1.3.6.1.5.5.7.3.3' -NotAfter (Get-Date).AddYears(2)
    }
    if(-not $cert){throw 'Local UWP development code-signing certificate unavailable'}
    & $signtool sign /fd SHA256 /sha1 $cert.Thumbprint /s My $package
    if($LASTEXITCODE -ne 0){throw 'Xbox APPX signature failed'}
    & $signtool verify /pa /v $package
    if($LASTEXITCODE -ne 0){
        Write-Host 'Self-signed APPX: signature present, but trust chain is not yet established on this PC or Xbox.'
        Write-Host 'Deploy through Xbox Developer Mode using a certificate trusted by that console.'
    }
    $publicCert=Join-Path $outDir 'Relay-Xbox-Dev-Public.cer'
    Export-Certificate -Cert $cert -FilePath $publicCert -Force | Out-Null
    Write-Host "Public development certificate: $publicCert"
    Write-Host 'Private certificate key is retained in the BEN-PC user certificate store, not exported.'
}
$hash=(Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash
Write-Host "BUILT XBOX SERIES S/X UWP: $package"
Write-Host "SIZE=$((Get-Item $package).Length) SHA256=$hash"
Write-Host 'Device testing still requires a paired Xbox in Developer Mode. No PS5 live-streaming capability is claimed.'
