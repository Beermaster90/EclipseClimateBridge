# Packages bridge-app into an installable, signed APK: Kotlin sources -> classes ->
# dex, plus the manifest -- assembled with a manual SDK command-line toolchain
# (no Gradle in this project by design).
#
# Pure Kotlin/JVM build -- the RC4 solution (Rc4CarClient etc.) needs no native code,
# so unlike the earlier phevcore/JNI attempt there is no native-build prerequisite step.

param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\mitsubishi-android-build\sdk",
    [string]$JavaHome = "$env:LOCALAPPDATA\mitsubishi-android-build\jdk17\jdk-17.0.20.1+1",
    [string]$KotlinHome = "$env:LOCALAPPDATA\mitsubishi-android-build\kotlinc-download\kotlinc"
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path $projectRoot 'build'
$kotlinClassesDir = Join-Path $buildRoot 'kotlin-classes'
$dexDir = Join-Path $buildRoot 'dex'
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$buildTools = Join-Path $SdkRoot 'build-tools\35.0.1'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$aapt = Join-Path $buildTools 'aapt.exe'
$d8 = Join-Path $buildTools 'd8.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$kotlinc = Join-Path $KotlinHome 'bin\kotlinc.bat'
$kotlinStdlib = Join-Path $KotlinHome 'lib\kotlin-stdlib.jar'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$classesJar = Join-Path $buildRoot 'classes.jar'
$manifest = Join-Path $projectRoot 'AndroidManifest.xml'
$kotlinSourceRoot = Join-Path $projectRoot 'src\main\kotlin'
$unsignedApk = Join-Path $buildRoot 'bridge-app-unsigned.apk'
$alignedApk = Join-Path $buildRoot 'bridge-app-aligned.apk'
$signedApk = Join-Path $buildRoot 'bridge-app.apk'
$keyStore = Join-Path $buildRoot 'debug.keystore'

foreach ($required in @($androidJar, $aapt2, $aapt, $d8, $zipalign, $apksigner, $kotlinc, $kotlinStdlib, $keytool)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required build input not found: $required"
    }
}

New-Item -ItemType Directory -Force -Path $kotlinClassesDir, $dexDir | Out-Null
Get-ChildItem -LiteralPath $kotlinClassesDir, $dexDir -Force -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

$kotlinSources = Get-ChildItem -LiteralPath (Join-Path $kotlinSourceRoot 'fi\mitsukanny\phevbridge') -Filter '*.kt' |
    Select-Object -ExpandProperty FullName
& $kotlinc -cp $androidJar -d $kotlinClassesDir $kotlinSources
if ($LASTEXITCODE -ne 0) { throw 'kotlinc failed' }

& $aapt2 link -o $unsignedApk --manifest $manifest -I $androidJar `
    --min-sdk-version 29 --target-sdk-version 35 --version-code 1 --version-name 0.1
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

& $jar cf $classesJar -C $kotlinClassesDir .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

& $d8 --lib $androidJar --min-api 29 --output $dexDir $classesJar $kotlinStdlib
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Push-Location $dexDir
try {
    & $aapt add $unsignedApk 'classes.dex'
    if ($LASTEXITCODE -ne 0) { throw 'aapt add classes.dex failed' }
} finally {
    Pop-Location
}

& $zipalign -f 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

if (-not (Test-Path -LiteralPath $keyStore)) {
    & $keytool -genkeypair -keystore $keyStore -storepass android -alias androiddebugkey -keypass android `
        -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

& $apksigner sign --ks $keyStore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android `
    --out $signedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }

& $apksigner verify --verbose $signedApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verification failed' }

Get-FileHash -Algorithm SHA256 -LiteralPath $signedApk
Write-Host "Built: $signedApk"
