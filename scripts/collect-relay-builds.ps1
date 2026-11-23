# Assemble a single read-only catalog of locally generated Relay packages.
# Copies packages into one folder by platform; never deletes originals.
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Destination = '',
    [switch]$UseHardlinks
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $Root).Path
if (-not $Destination) { $Destination = Join-Path $Root 'releases\organized' }
$Destination = [IO.Path]::GetFullPath($Destination)
if (-not $Destination.StartsWith(($Root.TrimEnd('\') + '\'),[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Catalog destination must remain inside Relay workspace.'
}
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
# Refresh only files registered by our own previous catalog. Hash mismatches
# indicate somebody modified a collected file: preserve those rather than
# deleting them. All source files remain untouched.
$index=Join-Path $Destination 'artifact-index.json'
if(Test-Path -LiteralPath $index){
    $prior=@(Get-Content -LiteralPath $index -Raw | ConvertFrom-Json)
    foreach($record in $prior){
        $oldPath=[string]$record.catalogPath
        if($oldPath.StartsWith(($Destination.TrimEnd('\')+'\'),[StringComparison]::OrdinalIgnoreCase) -and
           (Test-Path -LiteralPath $oldPath -PathType Leaf) -and
           (Get-FileHash -LiteralPath $oldPath -Algorithm SHA256).Hash -eq [string]$record.sha256){
            Remove-Item -LiteralPath $oldPath -Force
        }
    }
}
# Remove stale generic Gradle-named copies from this tool-owned staging area.
# Original Gradle APKs remain untouched in android/app/build/outputs/.
$androidStage=Join-Path $Destination 'Android'
if(Test-Path -LiteralPath $androidStage){
    Get-ChildItem -LiteralPath $androidStage -File -Filter 'app-*.apk' |
        Remove-Item -Force
}
# Retire filename-hash variants left by earlier catalog runs for the same
# experimental 3DS target. This directory is entirely tool-owned staging.
$threeDsStage=Join-Path $Destination 'Nintendo-3DS-Family'
if(Test-Path -LiteralPath $threeDsStage){
    Get-ChildItem -LiteralPath $threeDsStage -File |
        Where-Object { $_.Name -match '^Relay-3DSFamily-Horizon-ARM11-dev(?:-[a-fA-F0-9]{8})?\.(3dsx|cia|cci)$' } |
        Remove-Item -Force
}
$platforms = [ordered]@{
    'Android' = @('releases\local-development','android\app\build\outputs\apk')
    'PlayStation-Vita-PSTV' = @('platforms\vita\dist')
    'Nintendo-3DS-Family' = @('platforms\3ds\dist')
    'Nintendo-Switch' = @('platforms\switch1\nca-title-output','platforms\switch1')
    'Windows-ROG-Ally' = @('platforms\windows11\dist')
    'Xbox-Series' = @('platforms\xbox-series\dist')
    'Xbox-360' = @('platforms\xbox360\dist')
    'Apple-iOS-tvOS' = @('platforms\apple\dist')
    'Samsung-Tizen' = @('platforms\samsung-tizen')
    'Roku' = @('platforms\roku')
    'SteamOS' = @('platforms\steamdeck\dist')
}
$extensionAllow = @('.apk','.vpk','.3dsx','.cia','.cci','.3ds','.nro','.nsp','.exe','.msix','.appx','.xex','.ipa','.wgt','.zip','.appimage')
$rows = New-Object 'System.Collections.Generic.List[object]'
$seen = New-Object 'System.Collections.Generic.HashSet[string]'
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($platform in $platforms.Keys) {
    $stage = Join-Path $Destination $platform
    foreach ($rel in $platforms[$platform]) {
        $dir=Join-Path $Root $rel
        if (-not (Test-Path -LiteralPath $dir -PathType Container)) {continue}
        $files=Get-ChildItem -LiteralPath $dir -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $extensionAllow -contains $_.Extension.ToLowerInvariant() -and $_.Name -match '(?i)(relay|chiaki|app-)' }
        foreach ($file in $files) {
            # Keep repository source/archive ZIPs out of this build-only collection.
            if ($file.FullName -match '[\\/](third-party|source|build[\\/](obj|intermediates)|\.gradle|\.cxx)[\\/]') {continue}
            $incomingHash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
            if(-not $seen.Add($incomingHash)){continue}
            New-Item -ItemType Directory -Path $stage -Force | Out-Null
            $base=$file.Name
            if($platform -eq 'Android' -and $file.Name -match '^app(?:-([a-zA-Z0-9]+))?-(debug|release)\.apk$') {
                $flavor=if($Matches[1]){$Matches[1]}else{'Universal'}
                $type=$Matches[2]
                $zip=[System.IO.Compression.ZipFile]::OpenRead($file.FullName)
                try {
                    $abis=@($zip.Entries | Where-Object FullName -Match '^lib/[^/]+/' |
                        ForEach-Object {($_.FullName -split '/')[1]} | Sort-Object -Unique)
                } finally {$zip.Dispose()}
                $abi=if($abis.Count){$abis -join '_'}else{'no-native-lib-observed'}
                $base="Relay-$flavor-Android-$abi-$type.apk"
            }
            $target=Join-Path $stage $base
            if (Test-Path -LiteralPath $target) {
                $old=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
                $new=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
                if ($old -ne $new) {
                    $stem=[IO.Path]::GetFileNameWithoutExtension($base)
                    $target=Join-Path $stage ($stem + '-' + $new.Substring(0,8) + $file.Extension)
                }
            }
            if (-not (Test-Path -LiteralPath $target)) {
                if ($UseHardlinks) {
                    try { New-Item -ItemType HardLink -Path $target -Target $file.FullName -ErrorAction Stop | Out-Null }
                    catch { Copy-Item -LiteralPath $file.FullName -Destination $target -ErrorAction Stop }
                } else { Copy-Item -LiteralPath $file.FullName -Destination $target -ErrorAction Stop }
            }
            $hash=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
            $rows.Add([pscustomobject]@{
                platform=$platform;name=[IO.Path]::GetFileName($target);bytes=(Get-Item -LiteralPath $target).Length
                sha256=$hash;source=$file.FullName;catalogPath=$target;collectedAt=(Get-Date).ToString('o')
            })
        }
    }
}
$rows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $index -Encoding UTF8
Write-Host "Relay build catalog: $($rows.Count) source artifacts, $index"
$rows | Group-Object platform | Select-Object Name,Count | Format-Table -AutoSize
