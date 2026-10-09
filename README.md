# My Player

Minimal native Android TV video player.

- **Application ID:** `com.myplayer`
- **Language:** Kotlin
- **Playback:** AndroidX Media3 / ExoPlayer
- **Build:** Gradle command line
- **Minimum Android:** API 23 (Android 6.0)

## Features

- Enter an HTTP or HTTPS video URL using the TV remote.
- Full-screen playback with Media3 controls.
- HLS playback for compatible `.m3u8` streams, including regular live HLS.
- Progressive media URLs such as MP4 and other containers/codecs supported by the device.
- Buffering and playback-error feedback.
- Android TV launcher entry point; no touchscreen required.

## Build without Android Studio

Install JDK 17 and Android SDK command-line tools. Set `ANDROID_HOME` to your SDK directory and install Android SDK Platform 35 and Build Tools using `sdkmanager`.

On Windows PowerShell, from the repository directory:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat --no-daemon assembleDebug
```

On Linux/macOS:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME="$HOME/Android/Sdk"
chmod +x gradlew
./gradlew --no-daemon assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

> The Gradle wrapper JAR and scripts are not committed in this initial source scaffold. Generate them once on a machine with Gradle installed using `gradle wrapper --gradle-version 8.13`, then commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/`.

## Playback notes

Media3 supports HLS MPEG-TS and fMP4/CMAF containers, but the codecs inside a stream must also be supported by the Android device. Some URLs require authorization, cookies, request headers, or DRM and will not play with the basic URL field. This initial version does not provide custom headers, DRM setup, M3U playlist importing, or local-file browsing.

Use only media URLs you are authorized to access. HTTPS is recommended; cleartext HTTP is disabled by default.

## GitHub Actions

The workflow builds the debug APK on pushes and pull requests. GitHub-hosted runners install JDK 17 and Android SDK components automatically.
