<#
 Relay external dependency bootstrap. Default: DRY RUN.
 Standalone third-party sources stay OUTSIDE the project Git tree and are
 referenced through ignored NTFS junctions at historical relative paths.
 Git-tracked submodules remain managed by git submodule update separately.
#>
param(
    [string]$CloneBase = $(if ($env:RELAY_LOCAL_CLONES) { $env:RELAY_LOCAL_CLONES } else { Join-Path $env:LOCALAPPDATA 'Relay\Source Clones' }),
    [switch]$Execute
)
$ErrorActionPreference = 'Stop'
$Root = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path).TrimEnd('\')
$Base = [IO.Path]::GetFullPath($CloneBase).TrimEnd('\')
if ($Base.StartsWith(($Root + '\'),[StringComparison]::OrdinalIgnoreCase)) {
    throw 'CloneBase must be outside the Relay Git worktree'
}
$Manifest = Join-Path $Root 'docs\RELAY_EXTERNAL_SOURCE_MANIFEST.json'
$Entries = (Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json).entries
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git for Windows is required' }
Write-Output ("Relay external clones: $($Entries.Count); mode=" + $(if ($Execute) {'EXECUTE'} else {'DRY RUN'}))
foreach ($entry in $Entries) {
    $relative = $entry.path.Replace('/','\')
    $Source = [IO.Path]::GetFullPath((Join-Path $Base $relative))
    $Link = [IO.Path]::GetFullPath((Join-Path $Root $relative))
    if (-not $Source.StartsWith(($Base + '\'),[StringComparison]::OrdinalIgnoreCase)) {throw 'Invalid dependency path'}
    if (-not $Link.StartsWith(($Root + '\'),[StringComparison]::OrdinalIgnoreCase)) {throw 'Invalid project path'}
    if ($entry.repository -notmatch '^https://github\.com/[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+(?:\.git)?$') {
        throw ('Unexpected public clone URL: ' + $entry.path)
    }
    if ($entry.commit -notmatch '^[0-9a-fA-F]{40}$') {throw ('Invalid commit pin: ' + $entry.path)}
    if (-not $Execute) {
        Write-Output ('WOULD PIN '+$entry.path+' @ '+$entry.commit.Substring(0,12))
        continue
    }
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $Source) -Force | Out-Null
        & git clone --no-checkout -- $entry.repository $Source
        if ($LASTEXITCODE -ne 0) { throw ('Git clone failed: '+$entry.path) }
        & git -C $Source checkout --detach $entry.commit
        if ($LASTEXITCODE -ne 0) { throw ('Pinned commit unavailable: '+$entry.path) }
    }
    if (Test-Path -LiteralPath (Join-Path $Source '.git')) {
        $actual = (& git -C $Source rev-parse HEAD).Trim()
        if ($actual -ne $entry.commit) {
            throw ('Dependency head differs from pinned manifest, refusing reset: '+$entry.path)
        }
    } elseif ($entry.source_kind -ne 'source-archive') {
        throw ('Existing local clone missing .git: '+$entry.path)
    }
    if (Test-Path -LiteralPath $Link) {
        $found = Get-Item -LiteralPath $Link -Force
        if (-not ($found.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw ('Local project path already contains files, refusing overwrite: '+$Link)
        }
        Write-Output ('Existing local junction preserved: '+$entry.path)
        continue
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Link) -Force | Out-Null
    New-Item -ItemType Junction -Path $Link -Target $Source -ErrorAction Stop | Out-Null
    Write-Output ('Created ignored junction: '+$entry.path)
}
Write-Output 'Git submodules, if required: git submodule update --init --recursive'
Write-Output 'Local modifications in existing clones are NEVER discarded by this script.'
