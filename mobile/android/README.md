# thigh-high for Android

The Android app: Kotlin, Jetpack Compose and Media3 (ExoPlayer). Android 10 or later.

## What it does today

- Lists the tracks in the phone's `Music/thigh-high` folder (the same folder
  name as the terminal app), sorted by title.
- A bottom bar switches between the library and the download screen.
- Tapping a track plays it, queues the rest of the list and opens the
  full-screen player: an oscilloscope of what you hear (top-right button
  switches to the cover art), loop (off / list / track), previous, play/pause,
  next, stop and a seek bar. The top-left button (or Back) shrinks it to a bar
  above the tabs without stopping the music; tap the bar to open it again.
- Colors come from the cover art, with the same algorithm as the terminal
  app (`crates/core/src/cover.rs`, ported in `ui/CoverPalette.kt`).
- Playback runs in a media service, so it keeps going in the background with
  the system's media notification and lock-screen controls.
- The download screen takes a link (pasted, or shared from the YouTube app
  with "Share › thigh-high") and runs [yt-dlp](https://github.com/yt-dlp/yt-dlp)
  on the phone through [youtubedl-android](https://github.com/JunkFood02/youtubedl-android),
  with the same options as the terminal app: best audio stream as-is, cover
  art and tags embedded. The file lands in `Music/thigh-high`. yt-dlp updates
  itself when the app starts its first download.

The APK only contains 64-bit ARM libraries (Python and ffmpeg for yt-dlp
weigh about 50 MB per CPU type), which covers every recent phone.

You can also copy audio files into `Music/thigh-high` on the phone (over USB, for
example). The list refreshes when the app comes back to the foreground, or with
the refresh button.

## Build

Open `mobile/android` in Android Studio, or from this folder:

```
./gradlew assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. It is signed with
the debug key for now, so it installs directly (allow installs from unknown
sources); a real signing key comes with the GitHub Releases milestone.

## Layout

- `library/`: reads the track list from Android's MediaStore.
- `playback/PlaybackService.kt`: the player (ExoPlayer in a `MediaSessionService`).
- `playback/AudioTap.kt`: copies the audio being played for the oscilloscope.
- `LibraryViewModel.kt`: connects the UI to the player through a `MediaController`.
- `download/Downloader.kt`: runs yt-dlp and files the result through MediaStore.
- `ui/`: Compose screens.

The Rust core (`crates/core`) is not linked yet; it comes in with genre
detection.
