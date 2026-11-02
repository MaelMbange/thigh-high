//! Picks the main color of a track's cover art.

use std::collections::HashMap;
use std::path::Path;

use lofty::prelude::*;

/// The embedded cover image of an audio file, if it has one.
pub fn cover_art(path: &Path) -> Option<Vec<u8>> {
    let file = lofty::read_from_path(path).ok()?;
    file.tags()
        .iter()
        .flat_map(|tag| tag.pictures())
        .next()
        .map(|picture| picture.data().to_vec())
}

pub type Rgb = (u8, u8, u8);

/// Three colors taken from a cover, from most to least prominent.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Palette {
    pub primary: Rgb,
    pub secondary: Rgb,
    pub tertiary: Rgb,
}

impl Default for Palette {
    /// Used until a track with cover art plays.
    fn default() -> Self {
        Self {
            primary: (200, 80, 200),
            secondary: (80, 180, 255),
            tertiary: (255, 180, 80),
        }
    }
}

/// Minimum hue difference, in degrees, for two palette colors to count as distinct.
const DISTINCT_HUE: f32 = 40.0;

/// The cover's most prominent vivid colors, brightened enough to stand out on
/// a dark terminal. When the cover has fewer than three distinct hues, the
/// missing ones are derived from the main color so the UI never goes flat.
/// Returns None when the image can't be decoded.
pub fn palette(image_bytes: &[u8]) -> Option<Palette> {
    let image = image::load_from_memory(image_bytes)
        .ok()?
        .thumbnail(64, 64)
        .to_rgb8();

    // Group similar colors, weighting vivid ones above greys, blacks and
    // whites so a dark background or letterbox bars don't win.
    let mut groups: HashMap<(u8, u8, u8), (f32, [f32; 3])> = HashMap::new();
    for pixel in image.pixels() {
        let [r, g, b] = pixel.0;
        let (_, saturation, value) = hsv(r, g, b);
        let weight = 0.05 + saturation * value;
        let group = groups.entry((r >> 5, g >> 5, b >> 5)).or_default();
        group.0 += weight;
        group.1[0] += r as f32 * weight;
        group.1[1] += g as f32 * weight;
        group.1[2] += b as f32 * weight;
    }
    let total: f32 = groups.values().map(|g| g.0).sum();
    let mut ranked: Vec<(f32, Rgb)> = groups
        .into_values()
        .map(|(weight, sum)| {
            let [r, g, b] = sum.map(|c| c / weight);
            (weight, vivid(r as u8, g as u8, b as u8))
        })
        .collect();
    ranked.sort_by(|a, b| b.0.total_cmp(&a.0));

    let primary = ranked.first()?.1;
    let grey = |c: Rgb| hsv(c.0, c.1, c.2).1 < 0.12;
    // Later colors must be vivid, visibly present and a different hue from those picked.
    let mut picked = vec![primary];
    for &(weight, color) in &ranked[1..] {
        if picked.len() == 3 {
            break;
        }
        let distinct = picked
            .iter()
            .all(|&p| hue_distance(p, color) >= DISTINCT_HUE);
        if weight >= total * 0.02 && !grey(color) && distinct {
            picked.push(color);
        }
    }

    let fallback = Palette::default();
    let derive = |degrees: f32, default: Rgb| {
        if grey(primary) {
            default
        } else {
            rotate(primary, degrees)
        }
    };
    Some(Palette {
        primary,
        secondary: picked
            .get(1)
            .copied()
            .unwrap_or_else(|| derive(150.0, fallback.secondary)),
        tertiary: picked
            .get(2)
            .copied()
            .unwrap_or_else(|| derive(-150.0, fallback.tertiary)),
    })
}

fn hue_distance(a: Rgb, b: Rgb) -> f32 {
    let d = (hsv(a.0, a.1, a.2).0 - hsv(b.0, b.1, b.2).0).abs();
    d.min(360.0 - d)
}

/// The same color with its hue turned by `degrees`.
fn rotate(color: Rgb, degrees: f32) -> Rgb {
    let (hue, saturation, value) = hsv(color.0, color.1, color.2);
    from_hsv((hue + degrees).rem_euclid(360.0), saturation, value)
}

fn hsv(r: u8, g: u8, b: u8) -> (f32, f32, f32) {
    let (r, g, b) = (r as f32 / 255.0, g as f32 / 255.0, b as f32 / 255.0);
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let delta = max - min;
    let hue = if delta == 0.0 {
        0.0
    } else if max == r {
        60.0 * ((g - b) / delta).rem_euclid(6.0)
    } else if max == g {
        60.0 * ((b - r) / delta + 2.0)
    } else {
        60.0 * ((r - g) / delta + 4.0)
    };
    let saturation = if max == 0.0 { 0.0 } else { delta / max };
    (hue, saturation, max)
}

fn from_hsv(hue: f32, saturation: f32, value: f32) -> (u8, u8, u8) {
    let c = value * saturation;
    let x = c * (1.0 - ((hue / 60.0).rem_euclid(2.0) - 1.0).abs());
    let m = value - c;
    let (r, g, b) = match hue as u32 {
        0..60 => (c, x, 0.0),
        60..120 => (x, c, 0.0),
        120..180 => (0.0, c, x),
        180..240 => (0.0, x, c),
        240..300 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let byte = |v: f32| ((v + m) * 255.0).round() as u8;
    (byte(r), byte(g), byte(b))
}

/// Keeps the hue but makes the color bright, and saturated unless it was grey.
fn vivid(r: u8, g: u8, b: u8) -> (u8, u8, u8) {
    let (hue, saturation, value) = hsv(r, g, b);
    let saturation = if saturation < 0.12 {
        saturation
    } else {
        saturation.max(0.55)
    };
    from_hsv(hue, saturation, value.max(0.85))
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{ImageFormat, Rgb, RgbImage};
    use std::io::Cursor;

    fn jpeg(image: &RgbImage) -> Vec<u8> {
        let mut bytes = Vec::new();
        image
            .write_to(&mut Cursor::new(&mut bytes), ImageFormat::Jpeg)
            .unwrap();
        bytes
    }

    #[test]
    fn vivid_color_beats_a_larger_dark_background() {
        // Mostly near-black with a teal square in the middle, like many covers.
        let image = RgbImage::from_fn(200, 200, |x, y| {
            if (60..140).contains(&x) && (60..140).contains(&y) {
                Rgb([20, 160, 150])
            } else {
                Rgb([12, 12, 14])
            }
        });
        let (r, g, b) = palette(&jpeg(&image)).unwrap().primary;
        assert!(g > 150 && b > 140 && r < 90, "got ({r}, {g}, {b})");
    }

    #[test]
    fn grey_cover_stays_grey() {
        let image = RgbImage::from_pixel(50, 50, Rgb([120, 120, 120]));
        let (r, g, b) = palette(&jpeg(&image)).unwrap().primary;
        assert!(
            r.abs_diff(g) < 12 && g.abs_diff(b) < 12,
            "got ({r}, {g}, {b})"
        );
    }

    #[test]
    fn picks_the_cover_s_other_colors() {
        // Mostly red, with a clear blue band and a smaller yellow one.
        let image = RgbImage::from_fn(200, 200, |_, y| match y {
            0..120 => Rgb([210, 30, 40]),
            120..170 => Rgb([30, 60, 220]),
            _ => Rgb([230, 210, 30]),
        });
        let palette = palette(&jpeg(&image)).unwrap();
        let hue = |c: super::Rgb| hsv(c.0, c.1, c.2).0;
        assert!(
            hue(palette.primary) < 15.0 || hue(palette.primary) > 345.0,
            "{palette:?}"
        );
        assert!(
            (200.0..250.0).contains(&hue(palette.secondary)),
            "{palette:?}"
        );
        assert!((45.0..70.0).contains(&hue(palette.tertiary)), "{palette:?}");
    }

    #[test]
    fn single_color_cover_still_gets_three_colors() {
        let image = RgbImage::from_pixel(50, 50, Rgb([20, 160, 60]));
        let palette = palette(&jpeg(&image)).unwrap();
        assert!(
            hue_distance(palette.primary, palette.secondary) > 100.0,
            "{palette:?}"
        );
        assert!(
            hue_distance(palette.primary, palette.tertiary) > 100.0,
            "{palette:?}"
        );
    }
}
