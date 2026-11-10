# My Player

**Version:** 1.0.0  
**Application ID:** `com.mplayer`

Standalone Android / Android TV external video player. Opening My Player directly shows its Settings screen only. Other apps pass a media URI with Android `ACTION_VIEW`; My Player handles playback independently.

## Configurable settings and defaults

| Setting | Default | Options |
|---|---|---|
| Controls auto-hide | 2 seconds | 1, 2, 3 seconds |
| Video quality | Original | Original, 1080p, 720p |
| Seek interval | 10 seconds | 5, 10, 15 seconds |
| Preferred audio | Hindi | Hindi, English, Original |
| Aspect ratio | Fill | Fill, Fit, Zoom |

## Playback

- AndroidX Media3 ExoPlayer with HLS and DASH playback modules.
- Fullscreen landscape playback surface and bottom timeline.
- D-pad OK toggles play/pause; Left/Right seek by the configured interval.
- Auto-hiding controls, elapsed time, duration / LIVE label, buffering and error states.
- Quality caps are applied when the stream exposes compatible video variants; Original leaves adaptive selection unrestricted.
- Hindi audio is preferred when an appropriate audio track exists.
- Settings are persisted locally.
- External HTTP(S), content and file URIs are accepted by the playback activity.

## Build

The existing `.github/workflows/build.yml` is **unchanged**. It runs `./gradlew clean assembleDebug` and uploads `app/build/outputs/apk/debug/app-debug.apk`.

Requires JDK 17 and Android SDK 35:

```sh
chmod +x ./gradlew
./gradlew assembleDebug
```
