# My Player

Version: 1.0.0  
Application ID: `com.mplayer`

A standalone external video player for Android / Android TV.

## Product behavior

- Opening My Player directly shows **Settings only**. It does not show a media library or channel catalogue.
- Other apps (including My India TV) pass a media URL using an Android VIEW intent. My Player handles the URL and plays it. The calling app only redirects/launches My Player; playback remains entirely in My Player.
- Full-screen, black video surface with an on-screen timeline similar to the supplied reference.
- With the timeline focused, D-pad OK toggles play/pause; Left and Right seek backward/forward by the configured interval.
- Playback controls auto-hide after the configured delay.

## Default preferences

| Setting | Default | Options |
|---|---|---|
| Controls auto-hide | 2 seconds | 1, 2, 3 seconds |
| Preferred quality | Original | Original, 1080p, 720p |
| Seek interval | 10 seconds | 5, 10, 15 seconds |
| Preferred audio language | Hindi | Language selection, Hindi default |
| Aspect ratio | Fill | Fill and player-supported alternatives |

Quality selection should select a matching variant when the stream exposes selectable variants; Original means leave the stream at its original/automatic quality. Unsupported quality or audio choices must not be falsely presented as applied.

## Technical direction

- Kotlin Android application, application ID `com.mplayer`, versionName `1.0.0`.
- AndroidX Media3 ExoPlayer for HLS, DASH, and progressive streams as supported by the stream and device.
- Handle `ACTION_VIEW` / `ACTION_SEND` incoming media URIs, validate supported URI schemes, and show actionable errors for invalid or unsupported links.
- Persist settings using DataStore.
- Fullscreen landscape playback with D-pad / remote support and touch controls.
- Release build and CI validation before publishing.

## Playback UX

- Timeline shows current position and duration when available.
- Buffering indicator, playback error state, retry action, and audio-track selection.
- Controls fade after the configured delay and reappear on user input.
- Seek is clamped to valid media bounds; live streams without a seekable window do not expose unsupported seeking.
- Back exits playback and returns to the launching app when appropriate.
