# thigh-high

A terminal music app built with Rust and ratatui.

## Run

```
cargo run
```

Downloaded music is stored in your Music folder under `thigh-high`.

Btw, I didn't know what to name this repo, so I picked a random name.

## Now playing

An animation fills the top of the Now playing panel: by default an
oscilloscope of the waveform. `v` switches to a fusion of every animation,
frequency bars, mirrored bars, or sine waves following the bass/mids/treble, drawn in the main color of
the track's cover art (the whole UI picks up that color too). Use a terminal
with true color, such as Windows Terminal.

## Downloads

Paste a link into the URL bar and press Enter. Downloads run in the background
with [yt-dlp](https://github.com/yt-dlp/yt-dlp) and keep the site's best audio
stream as-is (often Opus), with its cover art and tags embedded. Playback decodes
through ffmpeg, so any format in the music folder plays.

On first launch the app looks for `yt-dlp` and `ffmpeg` on your PATH. If they're
missing it downloads them from their official GitHub releases into its own
data folder (`%LOCALAPPDATA%\thigh-high\bin` on Windows) and keeps that copy of
yt-dlp up to date. ffmpeg is the LGPL build from
[BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds). On Linux and macOS,
install ffmpeg with your package manager.

## Genre detection

Tracks without a genre tag get one detected offline by
[MusiCNN](https://github.com/jordipons/musicnn), a small neural network (3 MB)
trained on the Million Song Dataset, run with [tract](https://github.com/sonos/tract).
New downloads are analysed automatically; `g` analyses every existing track
that has no genre. The model listens to 30 seconds from the middle of the track
and the genre is written into the file's tags. It knows popular-music genres
(rock, pop, electronic, hip-hop, jazz, metal, folk…) but not classical, and
leaves the genre empty when nothing stands out. YouTube's catch-all "Music"
category, which yt-dlp writes as the genre, counts as no genre. MusiCNN is © 2019 Music
Technology Group, Universitat Pompeu Fabra, under the ISC license
(`assets/LICENSE-musicnn.txt`).

## Keys

| Key               | Action                                                       |
| ----------------- | ------------------------------------------------------------ |
| `/` or `i`        | Focus the URL bar                                            |
| `Enter` (URL bar) | Download the pasted URL                                      |
| `Esc`             | Leave the URL bar                                            |
| `↑` `↓` / `j` `k` | Move in the library                                          |
| `Enter` (library) | Play the selected track                                      |
| `Space`           | Pause / resume                                               |
| `n` / `p`         | Next / previous track                                        |
| `←` `→` / `h` `l` | Seek 5 seconds                                               |
| `+` / `-`         | Volume                                                       |
| `b`               | Bass boost (off, +3, +6, +9, +12 dB)                         |
| `v`               | Switch animation (oscilloscope, fusion, bars, mirror, waves) |
| `s`               | Stop                                                         |
| `f`               | Filter by artist, genre or album                             |
| `x`               | Clear the filter                                             |
| `g`               | Detect the genre of tracks that have none                    |
| `r`               | Rescan the music folder                                      |
| `q` / `Ctrl+C`    | Quit                                                         |
