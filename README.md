# EclipseClimateBridge

An Android app (`bridge-app`) that joins a Mitsubishi Eclipse Cross PHEV's Wi-Fi and
starts/stops its climate control. 

General idea is to have android phone with this app in the car always. You just call to the phone in a car and it will start predefined operation (heating, cooling or heating windshield).

You can trigger it from a button in the app or by calling
the phone from an allowlisted number. It's pure Kotlin. There's no Gradle and no native code:
[bridge-app/build.ps1](bridge-app/build.ps1) runs the Android SDK command-line tools directly.

Other docs:

- [RC4Solution.md](docs/RC4Solution.md): the app's architecture, first-run setup on the phone, and current status.
- [MitsubishiEclipseCrossReverseEngineerWitifProtocol.md](docs/MitsubishiEclipseCrossReverseEngineerWitifProtocol.md): the car's protocol.
- [DEVELOPER-NOTES.md](docs/DEVELOPER-NOTES.md): test phone setup and debug/capture tooling.

## Prerequisites

- Windows with PowerShell 5.1 or later.
- About 1 GB of free disk space for the toolchain.
- An Android 10+ phone (`minSdk 29`) with USB debugging enabled.
- **The phone must already be paired with the car through the official Mitsubishi app**
  (`com.mitsubishi_motors.remote_ecp`). The car only accepts commands from a Wi-Fi MAC
  address that the official app has registered. This app has no pairing logic of its own.
  See [DEVELOPER-NOTES.md](docs/DEVELOPER-NOTES.md#mac-pairing-with-the-car-do-this-before-expecting-the-bridge-app-to-work).

## 1. Install the toolchain (one time)

The build needs these exact versions:

| Tool | Version | Why this version |
|---|---|---|
| JDK | 17 (Temurin) | Runs `kotlinc`, `d8` and `apksigner` |
| Android SDK platform | `android-35` | `build.ps1` compiles against it (`targetSdk 35`) |
| Android SDK build-tools | `35.0.1` | Provides `aapt2`, `d8`, `zipalign` and `apksigner` |
| Kotlin compiler | **2.0.x** (2.0.21) | `d8` from build-tools 35.0.1 can't read Kotlin 2.1+ metadata. Newer compilers still build, but `d8` prints thousands of warnings. |

`build.ps1` looks for everything under `%LOCALAPPDATA%\mitsubishi-android-build`. Don't put
the toolchain under `%TEMP%`, because Windows storage cleanup will eventually delete it.

Paste this into a PowerShell window. It downloads everything into the expected place:

```powershell
$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'
$root = "$env:LOCALAPPDATA\mitsubishi-android-build"
$dl = Join-Path $env:TEMP 'mitsubishi-android-build-dl'
New-Item -ItemType Directory -Force $root, $dl | Out-Null

# JDK 17 (latest Temurin 17 build)
Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile "$dl\jdk17.zip"
Expand-Archive "$dl\jdk17.zip" "$root\jdk17" -Force

# Kotlin compiler 2.0.21
Invoke-WebRequest 'https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip' -OutFile "$dl\kotlinc.zip"
Expand-Archive "$dl\kotlinc.zip" "$root\kotlinc-download" -Force

# Android command-line tools; sdkmanager requires the cmdline-tools\latest layout
Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' -OutFile "$dl\cmdline.zip"
Expand-Archive "$dl\cmdline.zip" "$dl\cmdline" -Force
New-Item -ItemType Directory -Force "$root\sdk\cmdline-tools" | Out-Null
Move-Item "$dl\cmdline\cmdline-tools" "$root\sdk\cmdline-tools\latest"

# SDK packages. Licenses are accepted through cmd redirection, because piping "y" from
# PowerShell doesn't reliably reach sdkmanager and the install then silently does nothing.
$env:JAVA_HOME = (Get-ChildItem "$root\jdk17" -Directory | Select-Object -First 1).FullName
$sm = "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat"
Set-Content "$dl\yes.txt" -Value (@('y') * 30) -Encoding ascii
cmd /c "`"$sm`" --sdk_root=`"$root\sdk`" --licenses < `"$dl\yes.txt`" > nul 2>&1"
cmd /c "`"$sm`" --sdk_root=`"$root\sdk`" `"platforms;android-35`" `"build-tools;35.0.1`" `"platform-tools`" < `"$dl\yes.txt`""

Remove-Item -Recurse -Force $dl
Test-Path "$root\sdk\platforms\android-35\android.jar", "$root\sdk\build-tools\35.0.1\d8.bat", "$root\kotlinc-download\kotlinc\bin\kotlinc.bat"
```

The last line should print `True` three times. sdkmanager prints a deprecation warning
("Use Android CLI instead"); you can ignore it.

**Check the JDK folder name.** `build.ps1` defaults to `jdk17\jdk-17.0.20.1+1`. If Adoptium
gave you a newer 17.x build, the folder name will be different. In that case, run
`build.ps1` with `-JavaHome` set to the new folder, or change the default in `build.ps1`.

The toolchain is somewhere else? Pass its locations to `build.ps1`:

```powershell
.\build.ps1 -SdkRoot <sdk> -JavaHome <jdk> -KotlinHome <kotlinc>
```

## 2. Build

```powershell
cd bridge-app
.\build.ps1
```

`build.ps1` runs these steps:

1. Compiles `src/main/kotlin` with `kotlinc`.
2. Links the manifest with `aapt2`.
3. Converts the classes to dex with `d8`.
4. Zip-aligns and signs the APK.

Output: `bridge-app\build\bridge-app.apk`. A successful build ends with `Built: ...`.

A few things to know:

- **Don't redirect the build's error output** in Windows PowerShell 5.1. With redirects like
  `2>&1` or `*>`, PowerShell treats `d8`'s warnings on stderr as a terminating error, and
  the build aborts. Run `.\build.ps1` without redirects.
- **Signing key.** The first build creates a debug keystore at
  `bridge-app\build\debug.keystore`. `build/` is gitignored, so each fresh clone or deleted
  `build/` folder gets a new key. Android won't install an update over an APK signed with a
  different key (see below). To keep the same key, back up `debug.keystore`.

## 3. Install and run

`adb` is in `%LOCALAPPDATA%\mitsubishi-android-build\sdk\platform-tools`. Add that folder to
`PATH`, or call `adb` by its full path.

```powershell
adb install -r bridge-app\build\bridge-app.apk
adb shell am start -n fi.mitsukanny.phevbridge/.Rc4TestActivity
```

`adb install -r` may fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. That means the phone
has a copy signed with a different debug key. Uninstall the old copy, then install again:

```powershell
adb uninstall fi.mitsukanny.phevbridge
```

Uninstalling deletes the app's saved settings, so you'll need to enter them again.

On some phones `adb install` fails silently. If that happens, push the APK to the phone and
install it there:

```powershell
adb push bridge-app\build\bridge-app.apk /data/local/tmp/
adb shell pm install -r /data/local/tmp/bridge-app.apk
```

To finish setting up the app on the phone (car Wi-Fi credentials, allowlisted callers,
permissions), follow
[RC4Solution.md → First-run setup](docs/RC4Solution.md#first-run-setup-on-the-phone).
