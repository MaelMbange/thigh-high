# thigh-high

A small personal music player that comes in two flavours sharing one Rust core:

- a **terminal app** for Windows, Linux and macOS, built with Rust and
  [ratatui](https://ratatui.rs);
- an **Android app** (Kotlin, Jetpack Compose, Media3) for Android 10 and later.

Both read the same `thigh-high` folder inside your Music folder, download audio
from a pasted link with [yt-dlp](https://github.com/yt-dlp/yt-dlp), detect
missing genres offline with a small neural network, and color the whole
interface after the cover art of the track that is playing.

> [!WARNING]
> **For personal use only.** thigh-high is a hobby project. Downloading from
> YouTube or other sites may break their terms of service, and most music is
> protected by copyright. Only download content you have the right to download
> (your own work, free or Creative Commons music, content whose owner allows
> it), keep it for your own private use, and respect the laws of your country
> and the terms of the sites you use. The authors take no responsibility for
> how the app is used.

## Features

### Terminal app

- Library of the tracks in `<Music>/thigh-high`, with tags and cover art read
  by [lofty](https://github.com/Serial-ATA/lofty-rs).
- Paste a link to download it in the background: yt-dlp keeps the site's best
  audio stream as-is (often Opus) with its cover and tags embedded.
- Playback of any format ffmpeg decodes: pause, seek, volume, next/previous,
  loop (list or track), bass boost up to +12 dB.
- Animations at the top of the Now playing panel: oscilloscope, frequency bars,
  mirrored bars, waves following bass/mids/treble, or a fusion of them all.
- The interface takes the three main colors of the playing track's cover.
- Filters by artist, genre or album, and track deletion (with confirmation).
- Offline genre detection with MusiCNN, written into the file's tags.

### Android app

- Library of the phone's `Music/thigh-high` folder with search, filters
  (artist, genre, album), shuffle and swipe-to-delete.
- Full-screen player with swipe gestures, a seek bar that dances with the
  music, and a mini player; playback keeps going in the background with the
  system media notification and lock-screen controls.
- Playlists, "play next" and an editable queue.
- Downloads by pasting one or more links, or by sharing from the YouTube app.
  yt-dlp runs on the phone and keeps itself up to date. Songs downloaded twice
  are spotted, and the app offers to keep the new version.
- Synced lyrics from [LRCLIB](https://lrclib.net), cached on the phone.
- Same cover-colored interface and same genre model as the terminal app
  (the Rust core is called through JNI).

See [`mobile/android/README.md`](mobile/android/README.md) for details on the
Android app.

## Install and run

Ready-made builds are on the
[Releases page](https://github.com/MaelMbange/thigh-high/releases): the Android
APK, and the terminal app for Windows, Linux and macOS (Apple Silicon). The
sections below build from source instead.

### Terminal app

You need a recent stable [Rust toolchain](https://rustup.rs) (edition 2024,
Rust 1.85 or later).

```
git clone https://github.com/MaelMbange/thigh-high.git
cd thigh-high
cargo install --path crates/tui
thigh-high
```

Or run it from the repository without installing: `cargo run --release`.

On first launch the app looks for `yt-dlp` and `ffmpeg` on your PATH. If
yt-dlp is missing, it downloads it from its official GitHub releases into its
own data folder (`%LOCALAPPDATA%\thigh-high\bin` on Windows) and keeps that
copy up to date. On Windows it fetches ffmpeg the same way (the LGPL build from
[BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds)); on Linux and
macOS, install ffmpeg with your package manager. Use a terminal with
true color, such as Windows Terminal, for the colors and animations.

### Android app

The build compiles the Rust core for the phone. Once:

```
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

Android Studio installs the right NDK by itself (the version is set in
`mobile/android/app/build.gradle.kts`). Then open `mobile/android` in Android
Studio, or from that folder:

```
./gradlew assembleRelease
```

The APK is `mobile/android/app/build/outputs/apk/release/app-release.apk`. It
only contains 64-bit ARM libraries, which covers every recent phone, and is
signed with your local debug key: allow installs from unknown sources, or use
`adb install -r`.

To sign with another key, set `THIGH_HIGH_KEYSTORE` (path to the keystore),
`THIGH_HIGH_KEYSTORE_PASSWORD`, `THIGH_HIGH_KEY_ALIAS` and
`THIGH_HIGH_KEY_PASSWORD` before building. An APK signed with a different key
than the installed one cannot update it: uninstall the app first.

### Publishing a release

The [Release workflow](.github/workflows/release.yml) builds the signed APK
and the terminal app for each platform and publishes them as a GitHub release
when a version tag is pushed. Set the same version in `versionName` (and bump
`versionCode`) in `mobile/android/app/build.gradle.kts` and in the workspace
`Cargo.toml`, commit, then:

```
git tag v0.8.1
git push origin v0.8.1
```

The signing key comes from the repository secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.

## Terminal keys

| Key | Action |
| --- | --- |
| `/` or `i` | Focus the URL bar |
| `Enter` (URL bar) | Download the pasted URL |
| `Esc` | Leave the URL bar |
| `↑` `↓` / `j` `k` | Move in the library |
| `Enter` (library) | Play the selected track |
| `Space` | Pause / resume |
| `n` / `p` | Next / previous track |
| `←` `→` / `h` `l` | Seek 5 seconds |
| `+` / `-` | Volume |
| `b` | Bass boost (off, +3, +6, +9, +12 dB) |
| `v` | Switch animation (oscilloscope, fusion, bars, mirror, waves) |
| `s` | Stop |
| `f` | Filter by artist, genre or album |
| `x` | Clear the filter |
| `g` | Detect the genre of tracks that have none |
| `o` | Loop: off, whole list, current track |
| `d` / `Delete` | Delete the selected track (asks first, No is preselected) |
| `r` | Rescan the music folder |
| `q` / `Ctrl+C` | Quit |

## Genre detection

Tracks without a genre tag get one detected offline by
[MusiCNN](https://github.com/jordipons/musicnn), a small neural network (3 MB)
trained on the Million Song Dataset, run with
[tract](https://github.com/sonos/tract). The model listens to 30 seconds from
the middle of the track and the genre is written into the file's tags. It
knows popular-music genres (rock, pop, electronic, hip-hop, jazz, metal,
folk…) but not classical, and leaves the genre empty when nothing stands out.
YouTube's catch-all "Music" category, which yt-dlp writes as the genre, counts
as no genre.

## Project layout

The repository is a Cargo workspace:

- `crates/core`: logic shared by both apps (library and tags, filters,
  cover-art colors, audio analysis for the animations, genre model). It never
  starts a process or picks a folder.
- `crates/tui`: the terminal app (ratatui UI, playback and downloads through
  ffmpeg and yt-dlp).
- `crates/android`: the JNI bridge the Android app uses to run the core's
  genre model.
- `mobile/android`: the Android app.
- `assets`: the genre model (ONNX) and its mel filterbank.

## Credits

- [MusiCNN](https://github.com/jordipons/musicnn) by Jordi Pons, © 2019 Music
  Technology Group, Universitat Pompeu Fabra, under the ISC license
  ([`assets/LICENSE-musicnn.txt`](assets/LICENSE-musicnn.txt)). The model in
  `assets/musicnn-msd.onnx` is its MSD model converted to ONNX.
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense) for downloads, and
  [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)
  (GPL-3.0) to run it on Android.
- [FFmpeg](https://ffmpeg.org) for decoding and conversion.
- [LRCLIB](https://lrclib.net) for synced lyrics.
- [ratatui](https://ratatui.rs), [rodio](https://github.com/RustAudio/rodio),
  [lofty](https://github.com/Serial-ATA/lofty-rs),
  [tract](https://github.com/sonos/tract) and
  [AndroidX Media3](https://developer.android.com/media/media3).

thigh-high is not affiliated with YouTube, Google, or any of the projects
above.

## License

thigh-high's own code is released under the [MIT license](LICENSE).
Third-party parts keep their own licenses: the MusiCNN model is under the ISC
license ([`assets/LICENSE-musicnn.txt`](assets/LICENSE-musicnn.txt)), and the
Android APK bundles youtubedl-android, which is under GPL-3.0, so an APK you
build and share is covered by the GPL-3.0 as a whole.
