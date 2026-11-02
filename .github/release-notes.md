## Downloads

| File | For |
| --- | --- |
| `thigh-high-{{VERSION}}-android-arm64.apk` | Android 10 or later, 64-bit ARM (every recent phone) |
| `thigh-high-{{VERSION}}-windows-x86_64.zip` | Terminal app for Windows |
| `thigh-high-{{VERSION}}-linux-x86_64.tar.gz` | Terminal app for Linux |
| `thigh-high-{{VERSION}}-macos-arm64.tar.gz` | Terminal app for macOS (Apple Silicon) |
| `SHA256SUMS.txt` | Checksums of the files above |

**Android:** allow installs from unknown sources, then open the APK (or
`adb install -r`). It updates the installed app in place.

**Terminal app:** unpack and run `thigh-high`. It finds or downloads yt-dlp on
first launch, and ffmpeg too on Windows; on Linux and macOS install ffmpeg with
your package manager. The macOS build is not notarized: allow it once in
System Settings > Privacy & Security.

## License

thigh-high's own code is under the MIT license. The Android APK bundles
[youtubedl-android](https://github.com/yausername/youtubedl-android), which is
under GPL-3.0, so the APK as a whole is distributed under the GPL-3.0; its
source is this repository at this tag. The MusiCNN model is under the ISC
license (`assets/LICENSE-musicnn.txt`).

