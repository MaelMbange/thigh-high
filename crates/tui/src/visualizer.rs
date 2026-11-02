//! Draws the animations from the analysis in `thigh_high_core::analysis`.

use ratatui::buffer::Buffer;
use ratatui::layout::Rect;
use ratatui::style::Color;
use ratatui::symbols::Marker;
use ratatui::widgets::Widget;
use ratatui::widgets::canvas::{Canvas, Line as CanvasLine};

pub use thigh_high_core::analysis::{FFT_SIZE, Spectrum, Tap};

use crate::cover::{Palette, Rgb};

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
