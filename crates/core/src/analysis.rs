//! Analyses the audio being played for the animations: the waveform for the
//! oscilloscope and frequency bars from an FFT.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use rustfft::num_complex::Complex;
use rustfft::{Fft, FftPlanner};

/// Samples per analysis window (~43 ms at 48 kHz): enough resolution for the bass.
pub const FFT_SIZE: usize = 2048;
const MIN_FREQ: f32 = 40.0;
const MAX_FREQ: f32 = 16_000.0;
/// Quietest level shown, in dB below full scale.
const FLOOR_DB: f32 = -70.0;
/// Without fresh audio for this long (paused, stopped), the bars fall to zero.
const STALE_AFTER: Duration = Duration::from_millis(150);

/// Shared between the audio thread, which pushes what it is about to play,
/// and the UI thread, which analyses it.
#[derive(Clone)]
pub struct Tap(Arc<Mutex<TapState>>);

struct TapState {
    mono: VecDeque<f32>,
    sample_rate: u32,
    last_push: Option<Instant>,
}

impl Tap {
    pub fn new(sample_rate: u32) -> Self {
        Self(Arc::new(Mutex::new(TapState {
            mono: VecDeque::with_capacity(FFT_SIZE * 2),
            sample_rate,
            last_push: None,
        })))
    }

    /// Records interleaved stereo samples. Never blocks: if the UI holds the
    /// lock, this chunk is skipped rather than delaying the audio.
    pub fn push(&self, stereo: &[f32]) {
        let Ok(mut state) = self.0.try_lock() else {
            return;
        };
        state
            .mono
            .extend(stereo.chunks_exact(2).map(|lr| (lr[0] + lr[1]) * 0.5));
        let excess = state.mono.len().saturating_sub(FFT_SIZE);
        state.mono.drain(..excess);
        state.last_push = Some(Instant::now());
    }

    /// The most recent `count` mono samples, or silence when nothing is playing.
    pub fn waveform(&self, count: usize) -> Vec<f32> {
        let Ok(state) = self.0.lock() else {
            return vec![0.0; count];
        };
        let fresh = state.last_push.is_some_and(|t| t.elapsed() < STALE_AFTER);
        if !fresh {
            return vec![0.0; count];
        }
        let skip = state.mono.len().saturating_sub(count);
        let mut samples: Vec<f32> = state.mono.iter().skip(skip).copied().collect();
        samples.resize(count, 0.0);
        samples
    }
}

pub struct Spectrum {
    fft: Arc<dyn Fft<f32>>,
    window: Vec<f32>,
    /// Smoothed bar heights, 0.0 to 1.0.
    bars: Vec<f32>,
    /// Phase of each sine wave in the Waves animation, advanced every frame.
    phases: [f64; 3],
    last_frame: Instant,
}

impl Spectrum {
    pub fn new() -> Self {
        let fft = FftPlanner::new().plan_fft_forward(FFT_SIZE);
        // Hann window: keeps a loud bass note from smearing into every other bar.
        let window = (0..FFT_SIZE)
            .map(|i| {
                let x = i as f32 / (FFT_SIZE - 1) as f32;
                0.5 - 0.5 * (std::f32::consts::TAU * x).cos()
            })
            .collect();
        Self {
            fft,
            window,
            bars: Vec::new(),
            phases: [0.0, 2.1, 4.2],
            last_frame: Instant::now(),
        }
    }

    /// Analyses the latest audio into `count` bars and returns their heights.
    pub fn update(&mut self, tap: &Tap, count: usize) -> &[f32] {
        self.bars.resize(count, 0.0);
        let targets = self.measure(tap, count);
        for (bar, target) in self.bars.iter_mut().zip(targets) {
            // Jump up quickly with the beat, fall back slowly.
            *bar = if target > *bar {
                *bar + (target - *bar) * 0.7
            } else {
                (*bar - 0.035).max(target)
            };
        }
        &self.bars
    }

    /// Average height of the bass, mid and treble thirds of the last update.
    pub fn bands(&self) -> [f32; 3] {
        let third = (self.bars.len() / 3).max(1);
        let mut bands = [0.0; 3];
        for (band, chunk) in bands.iter_mut().zip(self.bars.chunks(third)) {
            *band = chunk.iter().sum::<f32>() / chunk.len() as f32;
        }
        bands
    }

    /// Advances the sine waves: louder bands move faster.
    pub fn advance_waves(&mut self) -> [f64; 3] {
        let dt = self.last_frame.elapsed().as_secs_f64().min(0.1);
        self.last_frame = Instant::now();
        let bands = self.bands();
        for (i, phase) in self.phases.iter_mut().enumerate() {
            let speed = 1.5 + i as f64 * 1.2 + bands[i] as f64 * 6.0;
            *phase = (*phase + speed * dt) % std::f64::consts::TAU;
        }
        self.phases
    }

    fn measure(&self, tap: &Tap, count: usize) -> Vec<f32> {
        let silent = vec![0.0; count];
        let (samples, sample_rate) = {
            let Ok(state) = tap.0.lock() else {
                return silent;
            };
            let fresh = state.last_push.is_some_and(|t| t.elapsed() < STALE_AFTER);
            if !fresh || state.mono.len() < FFT_SIZE / 2 {
                return silent;
            }
            (
                state.mono.iter().copied().collect::<Vec<_>>(),
                state.sample_rate,
            )
        };

        let mut buffer: Vec<Complex<f32>> = samples
            .iter()
            .zip(&self.window)
            .map(|(s, w)| Complex::new(s * w, 0.0))
            .collect();
        buffer.resize(FFT_SIZE, Complex::default());
        self.fft.process(&mut buffer);

        // Amplitude of a full-scale sine comes out as 1.0 after this scaling.
        let scale = 2.0 / self.window.iter().sum::<f32>();
        let bin_hz = sample_rate as f32 / FFT_SIZE as f32;
        let max_freq = MAX_FREQ.min(sample_rate as f32 / 2.0);
        let max_bin = FFT_SIZE / 2 - 1;

        // Bars are spaced logarithmically, like musical notes.
        let edge = |i: usize| MIN_FREQ * (max_freq / MIN_FREQ).powf(i as f32 / count as f32);
        (0..count)
            .map(|i| {
                let lo = ((edge(i) / bin_hz) as usize).min(max_bin);
                let hi = ((edge(i + 1) / bin_hz) as usize).clamp(lo, max_bin);
                let peak = buffer[lo..=hi]
                    .iter()
                    .map(|c| c.norm() * scale)
                    .fold(0.0, f32::max);
                let db = 20.0 * peak.max(1e-9).log10();
                ((db - FLOOR_DB) / -FLOOR_DB).clamp(0.0, 1.0)
            })
            .collect()
    }
}

impl Default for Spectrum {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_bass_note_lights_up_the_low_bars() {
        let rate = 48_000;
        let tap = Tap::new(rate);
        let tone: Vec<f32> = (0..FFT_SIZE)
            .flat_map(|n| {
                let s = 0.5 * (std::f32::consts::TAU * 80.0 * n as f32 / rate as f32).sin();
                [s, s]
            })
            .collect();
        tap.push(&tone);

        let bars = Spectrum::new().measure(&tap, 24);
        let loudest = bars
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.total_cmp(b.1))
            .unwrap()
            .0;
        assert!(loudest < 6, "80 Hz peaked in bar {loudest}: {bars:?}");
        assert!(bars[loudest] > 0.8);
        assert!(bars[20] < 0.3, "high bars should stay low: {bars:?}");
    }
}
