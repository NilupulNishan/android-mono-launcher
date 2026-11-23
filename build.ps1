<#
  Builds mono.apk with the plain Android SDK tools - no Gradle, no Android Studio.

    .\build.ps1             build  -> build\mono.apk
    .\build.ps1 -Install    build, then install on the USB-connected phone (adb)

  Needs: Android SDK (platforms + build-tools + platform-tools) and a JDK 17+.
  The signing key is created on first build in keystore\ (git-ignored). Keep a backup:
  Android only installs updates signed with the same key.
#>
param([switch]$Install)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Run a command-line tool with an explicit argument list; stop on a non-zero exit code.
function Invoke-Tool([string]$Exe, [string[]]$ToolArgs) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # tools print warnings to stderr; judge by exit code
    & $Exe @ToolArgs
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    if ($code -ne 0) { throw "$(Split-Path $Exe -Leaf) failed (exit $code)" }
}

# ---------------------------------------------------------------- find tools
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $sdk)) { throw "Android SDK not found at $sdk (set ANDROID_HOME)" }

$platform = Join-Path $sdk 'platforms\android-36'
if (-not (Test-Path "$platform\android.jar")) {
    $platform = (Get-ChildItem "$sdk\platforms" -Directory |
        Where-Object { Test-Path "$($_.FullName)\android.jar" } |
        Sort-Object { [double](($_.Name -replace '^android-', '') -replace '[^0-9.]', '') } |
        Select-Object -Last 1).FullName
}
$androidJar = Join-Path $platform 'android.jar'

$bt = (Get-ChildItem "$sdk\build-tools" -Directory |
    Where-Object { Test-Path "$($_.FullName)\d8.bat" } |
    Sort-Object { [version]$_.Name } | Select-Object -Last 1).FullName
$aapt2 = Join-Path $bt 'aapt2.exe'
$d8 = Join-Path $bt 'd8.bat'
$zipalign = Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'

$jdk = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $jdk = $env:JAVA_HOME }
if (-not $jdk) {
    $jdk = (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path "$($_.FullName)\bin\javac.exe" } |
        Sort-Object Name | Select-Object -Last 1).FullName
}
if (-not $jdk) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { $jdk = Split-Path (Split-Path $javac.Source) }
}
if (-not $jdk) { throw 'No JDK found (set JAVA_HOME)' }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
$jar = Join-Path $jdk 'bin\jar.exe'

Write-Host "sdk      $sdk"
Write-Host "platform $(Split-Path $platform -Leaf)   build-tools $(Split-Path $bt -Leaf)"
Write-Host "jdk      $jdk"

# --------------------------------------------------------------------- build
$out = Join-Path $PSScriptRoot 'build'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes", "$out\gen" | Out-Null

Write-Host '[1/6] resources'
Invoke-Tool $aapt2 @('compile', '--dir', 'res', '-o', "$out\res.zip")
Invoke-Tool $aapt2 @('link', '-I', $androidJar, '--manifest', 'AndroidManifest.xml', '-A', 'assets',
    '--java', "$out\gen", '-o', "$out\unsigned.apk", "$out\res.zip")

Write-Host '[2/6] java'
$sources = @(Get-ChildItem src, "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Invoke-Tool "$jdk\bin\javac.exe" (@('--release', '11', '-encoding', 'UTF-8', '-Xlint:-options',
    '-classpath', $androidJar, '-d', "$out\classes") + $sources)

Write-Host '[3/6] dex'
Invoke-Tool $jar @('--create', '--file', "$out\classes.jar", '-C', "$out\classes", '.')
Invoke-Tool $d8 @('--release', '--min-api', '29', '--lib', $androidJar, '--output', $out, "$out\classes.jar")

Write-Host '[4/6] package'
Invoke-Tool $jar @('--update', '--no-manifest', '--file', "$out\unsigned.apk", '-C', $out, 'classes.dex')
Invoke-Tool $zipalign @('-p', '-f', '4', "$out\unsigned.apk", "$out\aligned.apk")

Write-Host '[5/6] sign'
$ksDir = Join-Path $PSScriptRoot 'keystore'
$ks = Join-Path $ksDir 'mono.keystore'
$pwFile = Join-Path $ksDir 'password.txt'
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force $ksDir | Out-Null
    # 24 characters from a cryptographically secure random source.
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pw = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
    Set-Content -Path $pwFile -Value $pw -NoNewline -Encoding ascii
    # Passwords are read from the file, never put on the command line (visible to other processes).
    Invoke-Tool "$jdk\bin\keytool.exe" @('-genkeypair', '-keystore', $ks, '-alias', 'mono', '-keyalg', 'RSA',
        '-keysize', '3072', '-validity', '10000', '-storepass:file', $pwFile, '-keypass:file', $pwFile,
        '-dname', 'CN=mono')
    Write-Host "      new signing key: $ks  (back it up)"
}
# One password protects both the keystore and the key (PKCS12); apksigner reuses it for the key.
Invoke-Tool $apksigner @('sign', '--ks', $ks, '--ks-key-alias', 'mono', '--ks-pass', "file:$pwFile",
    '--out', "$out\mono.apk", "$out\aligned.apk")
Invoke-Tool $apksigner @('verify', "$out\mono.apk")

$size = [math]::Round((Get-Item "$out\mono.apk").Length / 1KB)
Write-Host "[6/6] done: $out\mono.apk ($size KB)"

if ($Install) {
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    Write-Host 'installing on phone...'
    Invoke-Tool $adb @('install', '-r', "$out\mono.apk")
}
