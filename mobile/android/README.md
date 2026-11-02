# thigh-high for Android

The Android app: Kotlin, Jetpack Compose and Media3 (ExoPlayer). Android 10 or later.

## What it does today (milestone 1)

- Lists the tracks in the phone's `Music/thigh-high` folder (the same folder
  name as the terminal app), sorted by title.
- Tapping a track plays it and queues the rest of the list. A bar at the
  bottom shows the current track with play/pause.
- Playback runs in a media service, so it keeps going in the background with
  the system's media notification and lock-screen controls.

To try it, copy audio files into `Music/thigh-high` on the phone (over USB, for
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
- `LibraryViewModel.kt`: connects the UI to the player through a `MediaController`.
- `ui/`: Compose screens.

The Rust core (`crates/core`) is not used yet; it comes in with the
oscilloscope and genre detection.
