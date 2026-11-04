//! Audio playback: ffmpeg decodes any format to raw samples, rodio plays them.

use std::io::{self, Read};
use std::num::NonZero;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use color_eyre::Result;
use rodio::source::SeekError;
use rodio::{ChannelCount, DeviceSinkBuilder, MixerDeviceSink, SampleRate, Source};

use crate::tools;
use crate::visualizer::Tap;

/// Where the played audio also goes while broadcasting.
pub type CastFeed = Arc<Mutex<Option<Arc<thigh_high_cast::Server>>>>;

const CHANNELS: ChannelCount = NonZero::new(2).unwrap();
/// Samples per chunk sent from the decoder thread (~45 ms of stereo audio).
const CHUNK: usize = 4096;
/// Chunks buffered ahead of playback (~1.5 s).
const AHEAD: usize = 32;
/// How long to wait for ffmpeg's first samples before starting anyway.
const STARTUP_WAIT: Duration = Duration::from_secs(3);
/// Chunks decoded before playback starts (~0.25 s), so the start never stutters.
const PREFILL: usize = 6;
/// Bass boost steps in dB, cycled with a key.
const BASS_LEVELS: [u8; 5] = [0, 3, 6, 9, 12];

pub struct Player {
    // Dropping the device sink stops all sound, so it lives as long as the player.
    _device: MixerDeviceSink,
    output: rodio::Player,
    sample_rate: SampleRate,
    /// Bass boost in dB, shared with the decoder so a restart picks it up.
    bass: Arc<AtomicU8>,
    tap: Tap,
    cast: CastFeed,
}

impl Player {
    pub fn new() -> Result<Self> {
        let mut device = DeviceSinkBuilder::open_default_sink()?;
        // rodio prints a message when the device closes, which would draw over the UI.
        device.log_on_drop(false);
        let output = rodio::Player::connect_new(device.mixer());
        output.set_volume(0.9);
        let sample_rate = device.config().sample_rate();
        Ok(Self {
            _device: device,
            output,
            sample_rate,
            bass: Arc::new(AtomicU8::new(0)),
            tap: Tap::new(sample_rate.get()),
            cast: Arc::default(),
        })
    }

    /// The output device's rate, which received broadcasts are converted to.
    pub fn sample_rate(&self) -> u32 {
        self.sample_rate.get()
    }

    /// Starts or stops sending the played audio to a broadcast.
    pub fn set_cast(&self, server: Option<Arc<thigh_high_cast::Server>>) {
        *self.cast.lock().unwrap() = server;
    }

    /// Plays a live source (a broadcast being listened to) instead of a file.
    pub fn play_source(&self, source: impl Source + Send + 'static) {
        self.output.clear();
        self.output.append(source);
        self.output.play();
    }

    pub fn play(&self, ffmpeg: &Path, file: &Path, duration: Option<Duration>) -> Result<()> {
        let source = FfmpegSource::new(
            ffmpeg,
            file,
            duration,
            self.sample_rate,
            self.bass.clone(),
            self.tap.clone(),
            self.cast.clone(),
        )?;
        // `clear` also pauses, so resume afterwards.
        self.output.clear();
        self.output.append(source);
        self.output.play();
        Ok(())
    }

    pub fn toggle_pause(&self) {
        if self.output.is_paused() {
            self.output.play();
        } else {
            self.output.pause();
        }
    }

    pub fn stop(&self) {
        self.output.clear();
    }

    pub fn is_paused(&self) -> bool {
        self.output.is_paused()
    }

    /// True once the current track has played to the end (or nothing was loaded).
    pub fn is_finished(&self) -> bool {
        self.output.empty()
    }

    pub fn position(&self) -> Duration {
        self.output.get_pos()
    }

    pub fn seek_by(&self, seconds: i64) {
        let now = self.position().as_secs_f64();
        let target = (now + seconds as f64).max(0.0);
        let _ = self.output.try_seek(Duration::from_secs_f64(target));
    }

    /// The audio as it is played, for the visualizer.
    pub fn tap(&self) -> &Tap {
        &self.tap
    }

    pub fn bass(&self) -> u8 {
        self.bass.load(Ordering::Relaxed)
    }

    /// Steps the bass boost up, wrapping back to off, and applies it right away.
    pub fn cycle_bass(&self) {
        let current = self.bass();
        let next = BASS_LEVELS
            .iter()
            .copied()
            .find(|&level| level > current)
            .unwrap_or(0);
        self.bass.store(next, Ordering::Relaxed);
        // Restarting the decoder where we are applies the new filter.
        if !self.is_finished() {
            let _ = self.output.try_seek(self.position());
        }
    }

    pub fn volume(&self) -> f32 {
        self.output.volume()
    }

    pub fn change_volume(&self, delta: f32) {
        self.output
            // Above 100% the samples would exceed full scale and clip (audible crackle).
            .set_volume((self.output.volume() + delta).clamp(0.0, 1.0));
    }
}

/// A rodio source fed by an ffmpeg process running on a background thread,
/// so a slow disk or a slow ffmpeg start never blocks the audio thread.
struct FfmpegSource {
    ffmpeg: PathBuf,
    file: PathBuf,
    duration: Option<Duration>,
    sample_rate: SampleRate,
    bass: Arc<AtomicU8>,
    tap: Tap,
    cast: CastFeed,
    chunks: Receiver<Vec<f32>>,
    chunk: Vec<f32>,
    index: usize,
    /// Chunks decoded ahead of time at startup or after a seek.
    prefilled: std::collections::VecDeque<Vec<f32>>,
}

impl FfmpegSource {
    fn new(
        ffmpeg: &Path,
        file: &Path,
        duration: Option<Duration>,
        sample_rate: SampleRate,
        bass: Arc<AtomicU8>,
        tap: Tap,
        cast: CastFeed,
    ) -> io::Result<Self> {
        let chunks = decode(
            ffmpeg,
            file,
            Duration::ZERO,
            sample_rate,
            bass.load(Ordering::Relaxed),
        )?;
        let mut source = Self {
            ffmpeg: ffmpeg.to_path_buf(),
            file: file.to_path_buf(),
            duration,
            sample_rate,
            bass,
            tap,
            cast,
            chunks,
            chunk: Vec::new(),
            index: 0,
            prefilled: Default::default(),
        };
        source.prefill();
        Ok(source)
    }

    /// Waits until ffmpeg has decoded a little audio, so playback starts smoothly.
    fn prefill(&mut self) {
        self.chunk.clear();
        self.index = 0;
        self.prefilled.clear();
        let deadline = std::time::Instant::now() + STARTUP_WAIT;
        while self.prefilled.len() < PREFILL {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            match self.chunks.recv_timeout(left) {
                Ok(chunk) => self.prefilled.push_back(chunk),
                Err(_) => break, // Short file or slow start: play what we have.
            }
        }
    }
}

impl Iterator for FfmpegSource {
    type Item = f32;

    fn next(&mut self) -> Option<f32> {
        if self.index >= self.chunk.len() {
            let next = match self.prefilled.pop_front() {
                Some(chunk) => Ok(chunk),
                None => self.chunks.try_recv(),
            };
            match next {
                Ok(chunk) => {
                    // Hand each chunk to the visualizer as it starts playing.
                    self.tap.push(&chunk);
                    // And to the listeners; skipped rather than waited for.
                    if let Ok(cast) = self.cast.try_lock()
                        && let Some(server) = cast.as_ref()
                    {
                        server.push_f32(&chunk, CHANNELS.get() as usize, self.sample_rate.get());
                    }
                    self.chunk = chunk;
                    self.index = 0;
                }
                // The decoder is behind: play silence rather than stalling the device.
                Err(TryRecvError::Empty) => return Some(0.0),
                Err(TryRecvError::Disconnected) => return None,
            }
        }
        let sample = self.chunk.get(self.index).copied();
        self.index += 1;
        sample
    }
}

impl Source for FfmpegSource {
    fn current_span_len(&self) -> Option<usize> {
        None
    }

    fn channels(&self) -> ChannelCount {
        CHANNELS
    }

    fn sample_rate(&self) -> SampleRate {
        self.sample_rate
    }

    fn total_duration(&self) -> Option<Duration> {
        self.duration
    }

    fn try_seek(&mut self, pos: Duration) -> Result<(), SeekError> {
        // Restart ffmpeg at the new position; dropping the old receiver stops the old decoder.
        let bass = self.bass.load(Ordering::Relaxed);
        let chunks = decode(&self.ffmpeg, &self.file, pos, self.sample_rate, bass)
            .map_err(|e| SeekError::Other(Arc::new(e)))?;
        self.chunks = chunks;
        self.prefill();
        Ok(())
    }
}

/// Starts ffmpeg decoding `file` from `start` and returns a channel of sample chunks.
fn decode(
    ffmpeg: &Path,
    file: &Path,
    start: Duration,
    sample_rate: SampleRate,
    bass_db: u8,
) -> io::Result<Receiver<Vec<f32>>> {
    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-nostdin", "-loglevel", "quiet", "-ss"])
        .arg(format!("{:.3}", start.as_secs_f64()))
        .arg("-i")
        .arg(file)
        .arg("-vn");
    if bass_db > 0 {
        // Lower everything first, then boost around 100 Hz back up: the lows gain
        // relative weight but nothing can exceed full scale and clip. A limiter
        // would avoid the volume drop but distorts bass-heavy music.
        cmd.arg("-af")
            .arg(format!("volume=-{bass_db}dB,bass=g={bass_db}:f=100:w=0.6"));
    }
    cmd.args(["-f", "f32le", "-ac"])
        .arg(CHANNELS.to_string())
        .arg("-ar")
        .arg(sample_rate.to_string())
        .arg("-");
    tools::quiet(&mut cmd);
    cmd.stdout(Stdio::piped());
    let mut child = cmd.spawn()?;
    let mut stdout = child.stdout.take().expect("stdout is piped");

    let (tx, rx) = mpsc::sync_channel(AHEAD);
    thread::spawn(move || {
        let mut bytes = vec![0u8; CHUNK * 4];
        loop {
            let filled = read_full(&mut stdout, &mut bytes);
            // Keep whole samples only; a partial one can only appear at the very end.
            let usable = filled - filled % 4;
            if usable > 0 {
                let chunk = bytes[..usable]
                    .chunks_exact(4)
                    .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                    .collect();
                if tx.send(chunk).is_err() {
                    break; // The track was skipped or seeked away from.
                }
            }
            if filled < bytes.len() {
                break; // End of file.
            }
        }
        let _ = child.kill();
        let _ = child.wait();
    });
    Ok(rx)
}

/// Reads until `buf` is full or the stream ends, returning the number of bytes read.
fn read_full(reader: &mut impl Read, buf: &mut [u8]) -> usize {
    let mut filled = 0;
    while filled < buf.len() {
        match reader.read(&mut buf[filled..]) {
            Ok(0) | Err(_) => break,
            Ok(n) => filled += n,
        }
    }
    filled
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Decodes a generated 2 s tone through ffmpeg (skipped when ffmpeg isn't installed).
    #[test]
    fn decodes_and_seeks() {
        let Ok(ffmpeg) = which::which("ffmpeg") else {
            return;
        };
        let file = std::env::temp_dir().join("thigh-high-test-tone.opus");
        let status = Command::new(&ffmpeg)
            .args([
                "-y",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=2",
            ])
            .arg(&file)
            .status()
            .unwrap();
        assert!(status.success());

        // Read the decoder's chunks directly: iterating the source would pad
        // with silence whenever the test outruns ffmpeg.
        let rate = NonZero::new(48_000).unwrap();
        let seconds_from = |start: u64| {
            let samples: Vec<f32> = decode(&ffmpeg, &file, Duration::from_secs(start), rate, 6)
                .unwrap()
                .into_iter()
                .flatten()
                .collect();
            assert!(samples.iter().any(|s| s.abs() > 0.01), "decoded silence");
            samples.len() as f64 / (48_000.0 * 2.0)
        };
        let full = seconds_from(0);
        assert!((full - 2.0).abs() < 0.1, "decoded {full} s");
        let rest = seconds_from(1);
        assert!((rest - 1.0).abs() < 0.1, "after seek {rest} s left");
        let _ = std::fs::remove_file(file);
    }
}
