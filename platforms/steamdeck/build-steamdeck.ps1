# SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
# Reproducible SteamOS launcher-adapter source package, not a native PS5 decoder.
param([string]$Version = '0.1.0-dev')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path $PSScriptRoot).Path
$project = (Resolve-Path (Join-Path $root '..\..')).Path
$sourceWeb = Join-Path $project 'platforms\windows11\wwwroot'
$dist = Join-Path $root 'dist'
$folderName = "Relay-SteamDeck-$Version"
$stage = Join-Path $dist $folderName
if (-not (Test-Path (Join-Path $sourceWeb 'default.html'))) {
    throw "Canonical Portal-style Windows web assets unavailable: $sourceWeb"
}
New-Item -ItemType Directory -Force -Path $dist | Out-Null
if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'wwwroot') | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceWeb 'default.html') -Destination (Join-Path $stage 'wwwroot\default.html') -Force
Copy-Item -LiteralPath (Join-Path $sourceWeb 'css') -Destination (Join-Path $stage 'wwwroot\css') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $sourceWeb 'js') -Destination (Join-Path $stage 'wwwroot\js') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $sourceWeb 'Assets') -Destination (Join-Path $stage 'wwwroot\Assets') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $root 'js\steamdeck-bridge.js') -Destination (Join-Path $stage 'wwwroot\js\steamdeck-bridge.js') -Force
foreach ($name in @('relay_server.py','run-relay.sh','relay-steamdeck.desktop','README.md')) {
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $stage $name) -Force
}
$htmlPath = Join-Path $stage 'wwwroot\default.html'
$html = [System.IO.File]::ReadAllText($htmlPath)
$html = [regex]::Replace($html, '(?s)<title>.*?</title>', '<title>Relay - Steam Deck</title>')
$html = $html.Replace('</body>', '  <script src="js/steamdeck-bridge.js"></script>' + [Environment]::NewLine + '</body>')
[System.IO.File]::WriteAllText($htmlPath, $html, (New-Object System.Text.UTF8Encoding($false)))
$py = Get-Command python.exe -ErrorAction Stop
& $py.Source (Join-Path $stage 'relay_server.py') --check
if ($LASTEXITCODE -ne 0) { throw 'Steam Deck package validation failed' }
$archive = Join-Path $dist "$folderName.zip"
if (Test-Path $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -LiteralPath $stage -DestinationPath $archive -CompressionLevel Optimal -Force
$hash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "Built: $archive"
Write-Output "SHA256: $hash"
Write-Output 'This package is a SteamOS Portal UI + native client launcher, NOT an embedded PS5 streaming implementation.'
