//! Runs genre detection on a background thread. The model itself lives in
//! `thigh_high_core::genre`; this side decodes the excerpt with ffmpeg.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc::{self, Sender};
use std::thread;
use std::time::Duration;

use color_eyre::Result;
use color_eyre::eyre::eyre;
use thigh_high_core::genre::{Classifier, EXCERPT_SECONDS, SAMPLE_RATE, excerpt_start};

pub use thigh_high_core::genre::save_genre;

use crate::app::AppEvent;
use crate::tools;

/// A track waiting to be analysed.
pub struct Job {
    pub path: PathBuf,
    pub duration: Option<Duration>,
}

/// Starts the background thread that analyses tracks one at a time and
/// reports each result as an [`AppEvent::GenreDetected`]. The model is loaded
/// on the first job, so it costs nothing until a track needs it.
pub fn spawn_worker(ffmpeg: PathBuf, events: Sender<AppEvent>) -> Sender<Job> {
    let (jobs, queue) = mpsc::channel::<Job>();
    thread::spawn(move || {
        let mut classifier = None;
        for job in queue {
            let result = match &classifier {
                Some(c) => Ok(c),
                None => Classifier::new().map(|c| &*classifier.insert(c)),
            }
            .and_then(|c| detect(c, &ffmpeg, &job.path, job.duration))
            .map_err(|e| e.to_string());
            let event = AppEvent::GenreDetected {
                path: job.path,
                result,
            };
            if events.send(event).is_err() {
                break;
            }
        }
    });
    jobs
}

/// The most likely genre of the audio file, or None if no genre stands out.
fn detect(
    classifier: &Classifier,
    ffmpeg: &Path,
    file: &Path,
    duration: Option<Duration>,
) -> Result<Option<String>> {
    let audio = load_excerpt(ffmpeg, file, duration)?;
    classifier.detect(&audio)
}

/// Decodes up to 30 s from the middle of the track as 16 kHz mono samples.
fn load_excerpt(ffmpeg: &Path, file: &Path, duration: Option<Duration>) -> Result<Vec<f32>> {
    let start = excerpt_start(duration);
    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-nostdin", "-loglevel", "quiet", "-ss"])
        .arg(format!("{start:.3}"))
        .arg("-t")
        .arg(EXCERPT_SECONDS.to_string())
        .arg("-i")
        .arg(file)
        .args(["-vn", "-ac", "1", "-ar"])
        .arg(SAMPLE_RATE.to_string())
        .args(["-f", "f32le", "-"]);
    tools::quiet(&mut cmd);
    cmd.stdout(Stdio::piped());
    let mut child = cmd.spawn()?;
    let mut bytes = Vec::new();
    child
        .stdout
        .take()
        .ok_or_else(|| eyre!("no ffmpeg output"))?
        .read_to_end(&mut bytes)?;
    child.wait()?;
    Ok(bytes
        .chunks_exact(4)
        .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Runs the whole chain on an encoded file: ffmpeg excerpt, model, tag write.
    #[test]
    fn detects_and_saves_the_genre_of_a_file() {
        let (Ok(ffmpeg), true) = (
            which::which("ffmpeg"),
            Path::new("/tmp/claude-0/mcnn/fishin_16k.f32").exists(),
        ) else {
            return;
        };
        let file = std::env::temp_dir().join("thigh-high-genre-test.opus");
        let status = Command::new(&ffmpeg)
            .args([
                "-y",
                "-loglevel",
                "error",
                "-f",
                "f32le",
                "-ar",
                "16000",
                "-ac",
                "1",
            ])
            .args(["-i", "/tmp/claude-0/mcnn/fishin_16k.f32"])
            .arg(&file)
            .status()
            .unwrap();
        assert!(status.success());

        let classifier = Classifier::new().unwrap();
        let genre = detect(&classifier, &ffmpeg, &file, Some(Duration::from_secs(30))).unwrap();
        assert_eq!(genre.as_deref(), Some("Folk"));
        save_genre(&file, "Folk").unwrap();
        let track = crate::library::Track::read(file.clone());
        assert_eq!(track.genre.as_deref(), Some("Folk"));
        let _ = std::fs::remove_file(file);
    }
}
