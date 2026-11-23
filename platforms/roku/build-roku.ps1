# Produces a Roku SceneGraph development-channel ZIP, not a PS5 streaming app.
$ErrorActionPreference='Stop'
$required=@('manifest','source','components','images')
foreach($part in $required) {
    if(-not (Test-Path (Join-Path $PSScriptRoot $part))) {throw "Missing Roku package asset: $part"}
}
$zip=Join-Path $PSScriptRoot 'Relay-Roku-UI-preview.zip'
if(Test-Path $zip) {Remove-Item -LiteralPath $zip -Force}
$entries=$required | ForEach-Object {Join-Path $PSScriptRoot $_}
Compress-Archive -Path $entries -DestinationPath $zip
Write-Host "Built Roku UI-only development ZIP: $zip"
