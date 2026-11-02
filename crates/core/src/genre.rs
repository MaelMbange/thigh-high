//! Detects a track's genre offline with MusiCNN, a small neural network
//! trained on the Million Song Dataset (Jordi Pons, MTG-UPF, ISC license).
//!
//! The model looks at 3-second log-mel spectrograms of 16 kHz mono audio
//! and scores 50 tags; we average a few patches and keep the best genre.

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use eyre::{Result, bail, eyre};
use rustfft::num_complex::Complex;
use rustfft::{Fft, FftPlanner};
use tract_onnx::prelude::*;

const MODEL: &[u8] = include_bytes!("../../../assets/musicnn-msd.onnx");
/// librosa's Slaney mel filterbank for 16 kHz, n_fft 512, 96 bands (96 × 257 f32).
const MEL_FILTERS: &[u8] = include_bytes!("../../../assets/mel_filters_96x257.f32");

/// Sample rate the model expects: callers decode to 16 kHz mono.
pub const SAMPLE_RATE: u32 = 16_000;
const N_FFT: usize = 512;
const HOP: usize = 256;
const N_MELS: usize = 96;
const N_BINS: usize = N_FFT / 2 + 1;
/// Frames per model input: 3 seconds.
const PATCH: usize = 187;
/// Seconds of audio analysed, taken from the middle of the track.
pub const EXCERPT_SECONDS: f64 = 30.0;

/// The model's 50 tags, in output order.
const TAGS: [&str; 50] = [
    "rock",
    "pop",
    "alternative",
    "indie",
    "electronic",
    "female vocalists",
    "dance",
    "00s",
    "alternative rock",
    "jazz",
    "beautiful",
    "metal",
    "chillout",
    "male vocalists",
    "classic rock",
    "soul",
    "indie rock",
    "Mellow",
    "electronica",
    "80s",
    "folk",
    "90s",
    "chill",
    "instrumental",
    "punk",
    "oldies",
    "blues",
    "hard rock",
    "ambient",
    "acoustic",
    "experimental",
    "female vocalist",
    "guitar",
    "Hip-Hop",
    "70s",
    "party",
    "country",
    "easy listening",
    "sexy",
    "catchy",
    "funk",
    "electro",
    "heavy metal",
    "Progressive rock",
    "60s",
    "rnb",
    "indie pop",
    "sad",
    "House",
    "happy",
];

/// Genres we report, each with the model tags that count towards it.
/// Tags about mood, era or voice ("sad", "80s", "female vocalists") are ignored.
const GENRES: [(&str, &[&str]); 17] = [
    (
        "Rock",
        &["rock", "classic rock", "hard rock", "Progressive rock"],
    ),
    ("Pop", &["pop"]),
    ("Alternative", &["alternative", "alternative rock"]),
    ("Indie", &["indie", "indie rock", "indie pop"]),
    ("Electronic", &["electronic", "electronica", "electro"]),
    ("Dance", &["dance"]),
    ("House", &["House"]),
    ("Jazz", &["jazz"]),
    ("Metal", &["metal", "heavy metal"]),
    ("Chill", &["chillout", "chill", "ambient"]),
    ("Soul / R&B", &["soul", "rnb"]),
    ("Folk", &["folk"]),
    ("Punk", &["punk"]),
    ("Blues", &["blues"]),
    ("Hip-Hop", &["Hip-Hop"]),
    ("Country", &["country"]),
    ("Funk", &["funk"]),
];

pub struct Classifier {
    model: Arc<TypedRunnableModel>,
    fft: Arc<dyn Fft<f32>>,
    window: Vec<f32>,
    mel_filters: Vec<f32>,
}

impl Classifier {
    pub fn new() -> Result<Self> {
        let model = load_model().map_err(|e| eyre!("could not load the genre model: {e:#}"))?;
        // Periodic Hann window, as librosa uses.
        let window = (0..N_FFT)
            .map(|i| 0.5 - 0.5 * (std::f32::consts::TAU * i as f32 / N_FFT as f32).cos())
            .collect();
        let mel_filters = MEL_FILTERS
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
            .collect();
        Ok(Self {
            model,
            fft: FftPlanner::new().plan_fft_forward(N_FFT),
            window,
            mel_filters,
        })
    }

    /// The most likely genre of an excerpt decoded as [`SAMPLE_RATE`] mono
    /// samples, or None if no genre stands out.
    pub fn detect(&self, audio: &[f32]) -> Result<Option<String>> {
        let scores = self.tag_scores(audio)?;
        Ok(best_genre(&scores))
    }

    /// Average score of each of the 50 tags over the excerpt's 3-second patches.
    fn tag_scores(&self, audio: &[f32]) -> Result<Vec<f32>> {
        let mel = self.log_mel(audio);
        let frames = mel.len() / N_MELS;
        if frames < PATCH {
            bail!("track too short to analyse");
        }
        let mut sums = vec![0.0f32; TAGS.len()];
        let patches = frames / PATCH;
        for p in 0..patches {
            let start = p * PATCH * N_MELS;
            let scores = self
                .run_patch(&mel[start..start + PATCH * N_MELS])
                .map_err(|e| eyre!("genre model failed: {e:#}"))?;
            for (sum, score) in sums.iter_mut().zip(scores) {
                *sum += score;
            }
        }
        Ok(sums.into_iter().map(|s| s / patches as f32).collect())
    }

    fn run_patch(&self, patch: &[f32]) -> TractResult<Vec<f32>> {
        let input = tract_ndarray::Array3::from_shape_vec((1, PATCH, N_MELS), patch.to_vec())?;
        let output = self.model.run(tvec!(Tensor::from(input).into()))?;
        Ok(output[0]
            .to_plain_array_view::<f32>()?
            .iter()
            .copied()
            .collect())
    }

    /// librosa-compatible log-mel spectrogram, frames × 96, row-major.
    fn log_mel(&self, audio: &[f32]) -> Vec<f32> {
        // Centered frames: pad half a window of zeros on both sides.
        let mut padded = vec![0.0; N_FFT / 2];
        padded.extend_from_slice(audio);
        padded.extend(std::iter::repeat_n(0.0, N_FFT / 2));
        let frames = 1 + (padded.len() - N_FFT) / HOP;

        let mut mel = Vec::with_capacity(frames * N_MELS);
        let mut buffer = vec![Complex::default(); N_FFT];
        let mut power = vec![0.0f32; N_BINS];
        for f in 0..frames {
            let frame = &padded[f * HOP..f * HOP + N_FFT];
            for ((b, s), w) in buffer.iter_mut().zip(frame).zip(&self.window) {
                *b = Complex::new(s * w, 0.0);
            }
            self.fft.process(&mut buffer);
            for (p, c) in power.iter_mut().zip(&buffer) {
                *p = c.norm_sqr();
            }
            for band in self.mel_filters.chunks_exact(N_BINS) {
                let energy: f32 = band.iter().zip(&power).map(|(w, p)| w * p).sum();
                mel.push((10_000.0 * energy + 1.0).log10());
            }
        }
        mel
    }
}

fn load_model() -> TractResult<Arc<TypedRunnableModel>> {
    tract_onnx::onnx()
        .model_for_read(&mut std::io::Cursor::new(MODEL))?
        .with_input_fact(0, f32::fact([1, PATCH, N_MELS]).into())?
        .into_optimized()?
        .into_runnable()
}

/// Where to start the [`EXCERPT_SECONDS`] excerpt: the middle of the track.
pub fn excerpt_start(duration: Option<Duration>) -> f64 {
    duration
        .map(|d| (d.as_secs_f64() / 2.0 - EXCERPT_SECONDS / 2.0).max(0.0))
        .unwrap_or(0.0)
}

/// Picks the genre whose strongest tag scores highest, if it's convincing enough.
fn best_genre(scores: &[f32]) -> Option<String> {
    let score_of = |tag: &str| {
        TAGS.iter()
            .position(|t| *t == tag)
            .and_then(|i| scores.get(i))
            .copied()
            .unwrap_or(0.0)
    };
    let (genre, score) = GENRES
        .iter()
        .map(|(genre, tags)| {
            let score = tags.iter().map(|t| score_of(t)).fold(0.0, f32::max);
            (*genre, score)
        })
        .max_by(|a, b| a.1.total_cmp(&b.1))?;
    // Below this the model is essentially guessing.
    (score >= 0.08).then(|| genre.to_string())
}

/// Writes `genre` into the file's tags, creating a tag if the file has none.
pub fn save_genre(file: &Path, genre: &str) -> Result<()> {
    use lofty::config::WriteOptions;
    use lofty::prelude::*;
    use lofty::tag::Tag;

    let mut tagged = lofty::read_from_path(file)?;
    if tagged.primary_tag().is_none() {
        let tag_type = tagged.primary_tag_type();
        tagged.insert_tag(Tag::new(tag_type));
    }
    let tag = tagged
        .primary_tag_mut()
        .ok_or_else(|| eyre!("could not create a tag"))?;
    tag.set_genre(genre.to_string());
    tag.save_to_path(file, WriteOptions::default())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read_f32(path: &str) -> Option<Vec<f32>> {
        let bytes = std::fs::read(path).ok()?;
        Some(
            bytes
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                .collect(),
        )
    }

    #[test]
    fn genre_mapping_picks_the_strongest_group() {
        let mut scores = vec![0.0; TAGS.len()];
        scores[TAGS.iter().position(|t| *t == "heavy metal").unwrap()] = 0.4;
        scores[TAGS.iter().position(|t| *t == "rock").unwrap()] = 0.3;
        scores[TAGS.iter().position(|t| *t == "80s").unwrap()] = 0.9; // ignored: an era
        assert_eq!(best_genre(&scores).as_deref(), Some("Metal"));
        assert_eq!(best_genre(&vec![0.01; TAGS.len()]), None);
    }

    /// Compares against reference data exported from librosa + TensorFlow
    /// (skipped when the reference files aren't present).
    #[test]
    fn matches_the_reference_implementation() {
        let (Some(audio), Some(reference_mel)) = (
            read_f32("/tmp/claude-0/mcnn/fishin_16k.f32"),
            read_f32("/tmp/claude-0/mcnn/fishin_mel.f32"),
        ) else {
            return;
        };
        let classifier = Classifier::new().unwrap();
        let mel = classifier.log_mel(&audio);
        assert_eq!(mel.len(), reference_mel.len());
        let worst = mel
            .iter()
            .zip(&reference_mel)
            .map(|(a, b)| (a - b).abs())
            .fold(0.0, f32::max);
        assert!(worst < 1e-3, "mel differs by {worst}");

        let scores = classifier.tag_scores(&audio).unwrap();
        let folk = scores[TAGS.iter().position(|t| *t == "folk").unwrap()];
        assert!((folk - 0.30).abs() < 0.02, "folk scored {folk}");
        assert_eq!(best_genre(&scores).as_deref(), Some("Folk"));
    }
}
