$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath 'H:\Samsung Android\PSPortalAndroid').Path.TrimEnd('\')
$outside = 'H:\Relay Source Clones'
if ($outside.StartsWith($root + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'External directory must remain outside the Git repository' }
$repos = @(
  '.tools\pbs-upstream',
  'external\InstantCaption',
  'platforms\ps5-social-link\vendor\qrcodegen-upstream',
  'platforms\switch1\tools\packaging\hacbrewpack-src',
  'platforms\switch1\tools\packaging\nx-tools',
  'platforms\windows11\research\Playnite-FSE-Launcher',
  'platforms\xbox360\toolchain\xsdk',
  'research\design-skills\anti-slop-design',
  'research\design-skills\no-slop-ui',
  'research\design-skills\unslop-ui-skill',
  'research\design-skills\ux-ui-skills',
  'research\ios-build-tools\TheosWin',
  'research\ios-build-tools\WindowsIPABuilder',
  'research\playtranslate-upstream',
  'third-party\InstantCaption',
  'third-party\jxrlib'
)
$manifest = @()
foreach ($rel in $repos) {
  $source = [IO.Path]::GetFullPath((Join-Path $root $rel))
  $target = [IO.Path]::GetFullPath((Join-Path $outside $rel))
  if (-not $source.StartsWith($root + '\',[StringComparison]::OrdinalIgnoreCase)) { throw ('Source escaped: '+$source) }
  if (-not $target.StartsWith($outside + '\',[StringComparison]::OrdinalIgnoreCase)) { throw ('Destination escaped: '+$target) }
  if (-not (Test-Path -LiteralPath $source -PathType Container)) { Write-Output ('SKIP missing '+$rel);continue }
  $item = Get-Item -LiteralPath $source -Force
  $isJunction = [bool]($item.Attributes -band [IO.FileAttributes]::ReparsePoint)
  $hasGit = Test-Path -LiteralPath (Join-Path $source '.git')
  if (-not $hasGit -and $rel -ne 'third-party\jxrlib') { throw ('Clone metadata missing: '+$rel) }
  $gitHead = $null
  $dirty = 0
  $remote = $null
  if ($hasGit) {
    $gitHead = (git -C $source rev-parse HEAD 2>$null | Select-Object -First 1).Trim()
    $dirty = @((git -C $source status --porcelain --untracked-files=no 2>$null)).Count
    $remoteFull = (git -C $source remote get-url origin 2>$null | Select-Object -First 1)
    if ($remoteFull -match '^https?://') {
      try { $remoteUri = [uri]$remoteFull; $remote = $remoteUri.Scheme + '://' + $remoteUri.Host + $remoteUri.AbsolutePath }
      catch { $remote = '[remote URL omitted: unparseable]' }
    } elseif ($remoteFull -match '^git@') {
      $remote = $remoteFull
    } else { $remote = '[local or unsupported remote URL omitted]' }
  }
  if ($isJunction) {
    if (-not (Test-Path -LiteralPath $target -PathType Container)) { throw ('Orphaned clone junction: '+$rel) }
    $state = 'already-external'
  } else {
    if (Test-Path -LiteralPath $target) { throw ('Target already exists, refusing overwrite: '+$target) }
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $target -ErrorAction Stop
    try {
      New-Item -ItemType Junction -Path $source -Target $target -ErrorAction Stop | Out-Null
    } catch {
      Move-Item -LiteralPath $target -Destination $source -ErrorAction Stop
      throw
    }
    $state = 'moved-to-external'
  }
  $link = Get-Item -LiteralPath $source -Force
  if (-not ($link.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw ('Missing junction: '+$rel) }
  if ($hasGit) {
    $headNow = (git -C $source rev-parse HEAD 2>$null | Select-Object -First 1).Trim()
    if ($headNow -ne $gitHead) { throw ('Git commit changed during relocation: '+$rel) }
  }
  $record = [ordered]@{repo_relative_path=($rel -replace '\\','/');outside_path=$target;old_path_junction=$source;clone_head=$gitHead;uncommitted_file_count=$dirty;remote=$remote;state=$state;live_social=$false}
  $manifest += $record
  Write-Output ('OK '+$state+' '+$rel+' dirty='+$dirty)
}
$manifestPath = 'H:\Samsung Android\PSPortalAndroid\docs\RELAY_LOCAL_CLONES_MANIFEST.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Output ('MANIFEST '+$manifestPath+' records='+$manifest.Count)
Write-Output ('Live Relay Social not accessed by this migration')
