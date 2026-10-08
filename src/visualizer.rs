//! Turns the audio being played into animated frequency bars.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use ratatui::buffer::Buffer;
use ratatui::layout::Rect;
use ratatui::style::Color;
use ratatui::symbols::Marker;
use ratatui::widgets::Widget;
use ratatui::widgets::canvas::{Canvas, Line as CanvasLine};
use rustfft::num_complex::Complex;
use rustfft::{Fft, FftPlanner};

use crate::cover::{Palette, Rgb};

/// Samples per analysis window (~43 ms at 48 kHz): enough resolution for the bass.
const FFT_SIZE: usize = 2048;
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

/// The animations, cycled with a key.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Mode {
    /// Every animation layered together.
    Fusion,
    Bars,
    Mirror,
    Waves,
    #[default]
    Scope,
}

impl Mode {
    pub fn next(self) -> Self {
        match self {
            Mode::Bars => Mode::Mirror,
            Mode::Mirror => Mode::Waves,
            Mode::Waves => Mode::Scope,
            Mode::Scope => Mode::Fusion,
            Mode::Fusion => Mode::Bars,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Mode::Bars => "Bars",
            Mode::Mirror => "Mirror",
            Mode::Waves => "Waves",
            Mode::Scope => "Oscilloscope",
            Mode::Fusion => "Fusion",
        }
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
    fn advance_waves(&mut self) -> [f64; 3] {
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

/// Draws bars that blend from the `base` color to the `tip` color as they rise.
pub struct Bars<'a> {
    pub heights: &'a [f32],
    pub base: Rgb,
    pub tip: Rgb,
    /// Overall brightness, 1.0 for full color.
    pub brightness: f32,
    pub bar_width: u16,
    pub gap: u16,
    /// Grow up and down from the middle instead of from the bottom.
    pub mirrored: bool,
}

impl Widget for Bars<'_> {
    fn render(self, area: Rect, buf: &mut Buffer) {
        const EIGHTHS: [&str; 8] = [" ", "▁", "▂", "▃", "▄", "▅", "▆", "▇"];
        if area.height == 0 {
            return;
        }
        let step = self.bar_width + self.gap;
        // Center the group of bars in the area.
        let used = (self.heights.len() as u16 * step).saturating_sub(self.gap);
        let left = area.x + area.width.saturating_sub(used) / 2;
        let mut paint = |x0: u16, y: u16, symbol: &str, color: Color| {
            for x in x0..(x0 + self.bar_width).min(area.right()) {
                buf[(x, y)].set_symbol(symbol).set_fg(color);
            }
        };

        for (i, &height) in self.heights.iter().enumerate() {
            let x0 = left + i as u16 * step;
            if self.mirrored {
                // Each half is drawn in half-cell steps from the center line.
                let half = area.height / 2;
                let halves = (height * half as f32 * 2.0).round() as u16;
                for row in 0..half {
                    let filled = halves.saturating_sub(row * 2).min(2);
                    if filled == 0 {
                        break;
                    }
                    let t = row as f32 / half.max(1) as f32;
                    let color = scale(mix(self.base, self.tip, t), self.brightness);
                    let up = area.y + half - 1 - row;
                    let down = area.y + area.height - half + row;
                    paint(x0, up, if filled == 2 { "█" } else { "▄" }, color);
                    paint(x0, down, if filled == 2 { "█" } else { "▀" }, color);
                }
            } else {
                let eighths = (height * area.height as f32 * 8.0).round() as u16;
                for row in 0..area.height {
                    let filled = eighths.saturating_sub(row * 8).min(8);
                    if filled == 0 {
                        break;
                    }
                    let symbol = if filled == 8 {
                        "█"
                    } else {
                        EIGHTHS[filled as usize]
                    };
                    let t = row as f32 / area.height.max(1) as f32;
                    let shade = (0.55 + 0.45 * t) * self.brightness;
                    paint(
                        x0,
                        area.bottom() - 1 - row,
                        symbol,
                        scale(mix(self.base, self.tip, t), shade),
                    );
                }
            }
        }
    }
}

/// Blends from `a` (t = 0) to `b` (t = 1).
fn mix(a: Rgb, b: Rgb, t: f32) -> Rgb {
    let t = t.clamp(0.0, 1.0);
    let f = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round() as u8;
    (f(a.0, b.0), f(a.1, b.1), f(a.2, b.2))
}

fn scale((r, g, b): (u8, u8, u8), factor: f32) -> Color {
    let f = |c: u8| (c as f32 * factor).round().clamp(0.0, 255.0) as u8;
    Color::Rgb(f(r), f(g), f(b))
}

/// Draws the chosen animation into `area`, using all three palette colors.
pub fn render(
    mode: Mode,
    spectrum: &mut Spectrum,
    tap: &Tap,
    palette: &Palette,
    area: Rect,
    buf: &mut Buffer,
) {
    const BAR_WIDTH: u16 = 2;
    const GAP: u16 = 1;
    let bar_count = ((area.width + GAP) / (BAR_WIDTH + GAP)) as usize;
    let bars = |heights: &[f32], brightness, mirrored, buf: &mut Buffer| {
        Bars {
            heights,
            base: palette.primary,
            tip: palette.secondary,
            brightness,
            bar_width: BAR_WIDTH,
            gap: GAP,
            mirrored,
        }
        .render(area, buf);
    };

    match mode {
        Mode::Bars | Mode::Mirror => {
            let heights = spectrum.update(tap, bar_count);
            bars(heights, 1.0, mode == Mode::Mirror, buf);
        }
        Mode::Waves => {
            spectrum.update(tap, 48);
            let bands = spectrum.bands();
            let phases = spectrum.advance_waves();
            waves(bands, phases, palette, area, buf);
        }
        Mode::Scope => {
            spectrum.update(tap, 48);
            let samples = tap.waveform(FFT_SIZE / 2);
            scope(&samples, palette, 1.0, area, buf);
        }
        Mode::Fusion => {
            // Back to front: dim mirrored bars, a faint oscilloscope, then the waves.
            let heights = spectrum.update(tap, bar_count).to_vec();
            bars(&heights, 0.4, true, buf);
            let samples = tap.waveform(FFT_SIZE / 2);
            scope(&samples, palette, 0.55, area, buf);
            let bands = spectrum.bands();
            let phases = spectrum.advance_waves();
            waves(bands, phases, palette, area, buf);
        }
    }
}

/// Three flowing sine waves for the bass, mids and treble: each one's height
/// follows its band's loudness, and its speed picks up with it.
fn waves(bands: [f32; 3], phases: [f64; 3], palette: &Palette, area: Rect, buf: &mut Buffer) {
    let width = area.width as f64;
    let steps = area.width as usize * 2;
    // Bass is a slow, wide wave in the main color; mids and treble are faster,
    // tighter waves in the secondary and tertiary colors.
    let layers = [
        (1.0, 0.45, palette.primary),
        (2.3, 0.3, palette.secondary),
        (4.7, 0.18, palette.tertiary),
    ];
    Canvas::default()
        .marker(Marker::Braille)
        .x_bounds([0.0, width])
        .y_bounds([-1.0, 1.0])
        .paint(|ctx| {
            for (i, &(cycles, base, color)) in layers.iter().enumerate().rev() {
                // A small idle swell keeps the waves alive in quiet passages.
                let amplitude = 0.05 + (base + 0.55 * bands[i] as f64) * bands[i] as f64 * 1.6;
                let point = |step: usize| {
                    let x = step as f64 / steps as f64 * width;
                    let angle = std::f64::consts::TAU * cycles * x / width + phases[i];
                    (x, (amplitude * angle.sin()).clamp(-1.0, 1.0))
                };
                for step in 0..steps {
                    let (x1, y1) = point(step);
                    let (x2, y2) = point(step + 1);
                    ctx.draw(&CanvasLine {
                        x1,
                        y1,
                        x2,
                        y2,
                        color: scale(color, 1.0),
                    });
                }
            }
        })
        .render(area, buf);
}

/// The actual waveform being played, like an oscilloscope.
/// The trace runs from the secondary color near silence to the tertiary at its peaks.
fn scope(samples: &[f32], palette: &Palette, shade: f32, area: Rect, buf: &mut Buffer) {
    let len = samples.len().max(2) as f64;
    // Scale up quiet tracks so the trace stays visible, without overflowing loud ones.
    let peak = samples.iter().fold(0.0f32, |m, s| m.max(s.abs())).max(0.25) as f64;
    Canvas::default()
        .marker(Marker::Braille)
        .x_bounds([0.0, len - 1.0])
        .y_bounds([-1.0, 1.0])
        .paint(|ctx| {
            for (i, pair) in samples.windows(2).enumerate() {
                let loudness = (pair[0].abs().max(pair[1].abs()) as f64 / peak) as f32;
                let color = mix(palette.secondary, palette.tertiary, loudness);
                ctx.draw(&CanvasLine {
                    x1: i as f64,
                    y1: pair[0] as f64 / peak * 0.9,
                    x2: i as f64 + 1.0,
                    y2: pair[1] as f64 / peak * 0.9,
                    color: scale(color, shade),
                });
            }
        })
        .render(area, buf);
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
