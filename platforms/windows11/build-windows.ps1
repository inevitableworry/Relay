param([switch]$SkipAssetRefresh,[switch]$FrameworkDependent)
$ErrorActionPreference='Stop'
$root=(Resolve-Path $PSScriptRoot).Path
$repo=(Resolve-Path (Join-Path $root '..\..')).Path
$xbox=(Resolve-Path (Join-Path $repo 'platforms\xbox-series')).Path
$www=Join-Path $root 'wwwroot'
if(-not $SkipAssetRefresh){
    & python (Join-Path $xbox 'scripts\prepare_assets.py')
    if($LASTEXITCODE -ne 0){throw 'Could not generate original Relay assets'}
}
New-Item -ItemType Directory -Force $www | Out-Null
foreach($folder in @('css','Assets')){
    $dest=Join-Path $www $folder
    if(Test-Path $dest){Remove-Item $dest -Recurse -Force}
    Copy-Item (Join-Path $xbox $folder) -Destination $dest -Recurse -Force
}
$js=Join-Path $www 'js'
New-Item -ItemType Directory -Force $js | Out-Null
foreach($file in @('particles.js','pbs-protocol.js')){
    Copy-Item (Join-Path $xbox ('js\'+$file)) (Join-Path $js $file) -Force
}
Copy-Item (Join-Path $root 'wwwroot\js\windows-bridge.js') (Join-Path $js 'windows-bridge.js') -Force -ErrorAction SilentlyContinue

# Keep the exact same Relay interface, while connecting its read-only PS5
# status check to Win32 WebView2 instead of the Xbox-only Windows Runtime API.
$page=[IO.File]::ReadAllText((Join-Path $xbox 'default.html'))
$page=$page.Replace('<script src="js/relay.js"></script>',
                     '<script src="js/windows-bridge.js"></script>' + "`n" + '  <script src="js/relay.js"></script>')
[IO.File]::WriteAllText((Join-Path $www 'default.html'),$page,[Text.UTF8Encoding]::new($false))
$script=[IO.File]::ReadAllText((Join-Path $xbox 'js\relay.js'))
$needle='if(!isXboxWinRT){'
$replace="if(window.RelayWindowsBridge){window.RelayWindowsBridge.check(value,setStatus);return;}" + "`n" + '        if(!isXboxWinRT){'
if(-not $script.Contains($needle)){throw 'Relay JS bridge injection point changed'}
$script=$script.Replace($needle,$replace)
[IO.File]::WriteAllText((Join-Path $js 'relay.js'),$script,[Text.UTF8Encoding]::new($false))
& node --check (Join-Path $js 'relay.js')
if($LASTEXITCODE -ne 0){throw 'Windows Relay JS failed validation'}
$publish=Join-Path $root 'dist\Relay-Windows11-x64'
$selfContained=(-not $FrameworkDependent)
& dotnet publish (Join-Path $root 'Relay.Windows11.csproj') -c Release -r win-x64 `
    --self-contained:$($selfContained.ToString().ToLowerInvariant()) -o $publish -p:PublishSingleFile=false
if($LASTEXITCODE -ne 0){throw 'Relay Windows 11 build failed'}
$exe=Join-Path $publish 'Relay.Windows11.exe'
if(-not(Test-Path $exe)){throw 'Compiled Relay Windows executable missing'}
Write-Host "WINDOWS11_BUILD=$exe BYTES=$((Get-Item $exe).Length)"
Write-Host 'ROG Xbox Ally mode: Relay.Windows11.exe --rog-ally'
Write-Host 'Optional startup registration: see Set-RogAllyStartup.ps1 (never alters Windows shell).'
