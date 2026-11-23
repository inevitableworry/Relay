# Package Samsung Tizen UI prototype using an installed Tizen Studio certificate.
# This is a signed UI-only shell, not a functional PS Remote Play client.
param(
    [string]$CertificateProfile = 'QuietHome',
    [string]$Tizen = 'C:\tizen-studio\tools\ide\bin\tizen.bat'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Tizen)) { throw "Tizen CLI not found: $Tizen" }
$stage=Join-Path $PSScriptRoot '.wgt-input'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
# Do not accidentally nest the output package into the next signed package.
$oldPackage=Join-Path $stage 'Relay.wgt'
if(Test-Path $oldPackage) { Remove-Item -LiteralPath $oldPackage -Force }
foreach($file in @('config.xml','index.html','style.css','app.js','icon.png')) {
    $source=Join-Path $PSScriptRoot $file
    if(-not (Test-Path $source)) { throw "Missing Tizen asset: $file" }
    Copy-Item -LiteralPath $source -Destination (Join-Path $stage $file) -Force
}
& $Tizen package -t wgt -s $CertificateProfile -- $stage
if($LASTEXITCODE -ne 0) { throw "Tizen package failed; verify the certificate profile: $CertificateProfile" }
$package=Join-Path $stage 'Relay.wgt'
if(-not (Test-Path $package)) { throw 'Expected signed Relay.wgt was not produced' }
$output=Join-Path $PSScriptRoot 'Relay.wgt'
Copy-Item -LiteralPath $package -Destination $output -Force
Write-Host "Built signed Tizen UI-only preview: $output"
