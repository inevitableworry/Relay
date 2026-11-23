# Reproducible Windows build for the Portal-style Chiaki Android client.
# The native OpenSSL makefiles cannot tolerate the space in "Samsung Android".
# P: points to the SAME source tree; no project files are copied.
param(
    [string]$AliasDrive = "P:",
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$HostPython = "$env:LOCALAPPDATA\Programs\Python\Python311\python.exe",
    [string]$Protoc = "",
    [string]$InstallSerial = "",
    [switch]$Tv,
    [switch]$Shield,
    [switch]$GoogleTv,
    [switch]$Bravia8II,
    [switch]$Retroid,
    [switch]$RazerEdge,
    [switch]$AynThor,
    [switch]$DualScreen,
    [switch]$MetaQuest,
    [switch]$All,
    [switch]$RunTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path $PSScriptRoot).Path
$driveName = $AliasDrive.TrimEnd(':')
$existingDrive = Get-PSDrive -Name $driveName -PSProvider FileSystem -ErrorAction SilentlyContinue
if ($existingDrive) {
    $mapping = @(& subst) | Where-Object { $_.StartsWith($AliasDrive + '\:') }
    $mappedTarget = if ($mapping) { ($mapping -split '=>',2)[1].Trim() } else { '' }
    if (-not $mappedTarget.Equals($projectRoot,[StringComparison]::OrdinalIgnoreCase)) {
        throw "${AliasDrive} is already occupied by another drive. Choose another -AliasDrive."
    }
}
if (-not $existingDrive) {
    & subst ${AliasDrive} $projectRoot
    if ($LASTEXITCODE -ne 0) { throw "Cannot map ${AliasDrive} to $projectRoot" }
}

$ndk = Get-Item (Join-Path $SdkRoot 'ndk\28.2.13676358') -ErrorAction SilentlyContinue
if (-not $ndk) { throw "Android NDK 28.2.13676358 was not found under $SdkRoot" }
if (-not (Test-Path $HostPython)) { throw "Python 3.11 is missing: $HostPython" }

if (-not $Protoc) {
    $found = Get-Command protoc.exe -ErrorAction SilentlyContinue
    if ($found) { $Protoc = $found.Source }
}
if (-not $Protoc) {
    $fallback = 'C:\Program Files\AI Ebook to Audio Converter\_internal\torch\bin\protoc.exe'
    if (Test-Path $fallback) { $Protoc = $fallback }
}
if (-not (Test-Path $Protoc)) {
    throw 'The host protoc compiler is missing. Supply -Protoc with an installed executable.'
}

$pythonDeps = "${AliasDrive}\.tools\protobuf_py"
if (-not (Test-Path (Join-Path $pythonDeps 'google\protobuf\__init__.py'))) {
    New-Item -ItemType Directory -Path $pythonDeps -Force | Out-Null
    & $HostPython -m pip install --disable-pip-version-check --target $pythonDeps protobuf==3.20.3
    if ($LASTEXITCODE -ne 0) { throw 'Could not install isolated protobuf 3.20.3' }
}

$toolchainBin = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$env:PATH = "$toolchainBin;$(Split-Path $Protoc);$(Split-Path $HostPython);$env:PATH"
$env:PYTHONPATH = $pythonDeps
$env:CHIAKI_HOST_PYTHON = $HostPython.Replace('\','/')
$source = "${AliasDrive}\android"
Push-Location $source
try {
    if ((@($Tv,$Shield,$GoogleTv,$Bravia8II,$Retroid,$RazerEdge,$AynThor,$DualScreen,$MetaQuest,$All) | Where-Object {$_}).Count -gt 1) { throw 'Choose exactly one platform switch or -All.' }
    $variant = if ($MetaQuest) { 'MetaQuest' } elseif ($DualScreen) { 'DualScreen' } elseif ($AynThor) { 'AynThor' } elseif ($RazerEdge) { 'RazerEdge' } elseif ($Retroid) { 'Retroid' } elseif ($Bravia8II) { 'Bravia8ii' } elseif ($GoogleTv) { 'GoogleTv' } elseif ($Shield) { 'Shield' } elseif ($Tv) { 'Tv' } else { 'Mobile' }
    $variants = if ($All) { @('Mobile','Tv','Shield','GoogleTv','Bravia8ii','Retroid','RazerEdge','AynThor','DualScreen','MetaQuest') } else { @($variant) }
    # Delete only generated artifacts for selected variants before rebuilding.
    # Source, keystores, caches and unrelated target outputs are never touched.
    $artifactRoot = Join-Path $projectRoot 'releases\local-development'
    New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
    foreach ($selected in $variants) {
        $variantOutput = Join-Path $source ('app\build\outputs\apk\' + $selected.ToLowerInvariant())
        if (Test-Path -LiteralPath $variantOutput) { Remove-Item -LiteralPath $variantOutput -Recurse -Force }
        Get-ChildItem -LiteralPath $artifactRoot -Filter ('Relay-' + $selected + '-*.apk') -File -ErrorAction SilentlyContinue | Remove-Item -Force
    }
    $gradleTasks = @($variants | ForEach-Object { ':app:assemble' + $_ + 'Debug' })
    if ($RunTests) { $gradleTasks = @($variants | ForEach-Object { ':app:test' + $_ + 'DebugUnitTest' }) + $gradleTasks }
    & .\gradlew.bat @gradleTasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android debug build failed' }
    if ($All -and $InstallSerial) { throw 'Specify one build variant when installing with -InstallSerial.' }
    foreach ($selected in $variants) {
        $apk = Join-Path $source ('app\build\outputs\apk\' + $selected.ToLowerInvariant() + '\debug\app-' + $selected.ToLowerInvariant() + '-debug.apk')
        if (-not (Test-Path $apk)) { throw "APK missing: $apk" }
        Write-Host "Built $apk"
        $abis = if ($selected -in @('Shield','GoogleTv','Bravia8ii','Retroid','DualScreen')) { 'armeabi-v7a_arm64-v8a' } else { 'arm64-v8a' }
        $model = if ($selected -eq 'Bravia8ii') { 'Sony-BRAVIA-8-II-AndroidTV12' } else { $selected }
        $named = Join-Path $artifactRoot ("Relay-$model-Android-$abis-debug.apk")
        Copy-Item -LiteralPath $apk -Destination $named -Force
        Write-Host "Named artifact $named SHA256 $((Get-FileHash -LiteralPath $named -Algorithm SHA256).Hash)"
        if ($InstallSerial) {
            & adb -s $InstallSerial install -r $apk
            if ($LASTEXITCODE -ne 0) { throw "ADB install failed on $InstallSerial" }
            Write-Host "Installed on $InstallSerial"
        }
    }
}
finally { Pop-Location }
