//! Runs `yt-dlp` in a background thread and reports progress as events.

use std::fs;
use std::io::{BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc::Sender;
use std::thread;

use crate::app::AppEvent;
use crate::library;
use crate::tools::{self, Tools};

// Prefixes on yt-dlp's output lines so we can tell them apart.
const PROGRESS: &str = "[p]";
const TITLE: &str = "[t]";
const FILE: &str = "[f]";
const DOWNLOADED: &str = "[d]";

pub fn start(id: usize, url: String, tools: &Tools, out_dir: &Path, tx: Sender<AppEvent>) {
    let mut cmd = Command::new(&tools.ytdlp);
    cmd.args([
        "--no-playlist",
        "--color",
        "never",
        "--encoding",
        "utf-8",
        // Only the audio is kept. Take the best audio-only stream as-is (often
        // Opus): re-encoding only loses quality. When a site serves the clip
        // only as one video+audio file, `--extract-audio` copies its audio
        // track out with ffmpeg and deletes the video.
        "-f",
        "bestaudio/best",
        "--extract-audio",
        "--embed-thumbnail",
        "--convert-thumbnails",
        "jpg",
        "--embed-metadata",
        // Many videos have no artist field: fall back to the channel name so
        // every download can be filtered by artist.
        "--parse-metadata",
        "%(artist,creator,uploader|)s:%(meta_artist)s",
        "--newline",
        "--progress",
        "--progress-template",
    ])
    .arg(format!("download:{PROGRESS}%(progress._percent_str)s"))
    .arg("--print")
    .arg(format!("before_dl:{TITLE}%(title)s"))
    .arg("--print")
    .arg(format!("post_process:{DOWNLOADED}%(filepath)s"))
    .arg("--print")
    .arg(format!("after_move:{FILE}%(filepath)s"))
    .arg("--ffmpeg-location")
    .arg(&tools.ffmpeg)
    .arg("--paths")
    .arg(out_dir)
    .args(["--output", "%(title)s.%(ext)s", "--", &url]);

    tools::quiet(&mut cmd);
    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());

    thread::spawn(move || {
        let result = run(id, &mut cmd, &tx);
        let event = match result {
            Ok(path) => AppEvent::DownloadDone { id, path },
            Err(error) => AppEvent::DownloadFailed { id, error },
        };
        let _ = tx.send(event);
    });
}

fn run(id: usize, cmd: &mut Command, tx: &Sender<AppEvent>) -> Result<PathBuf, String> {
    let mut child = cmd
        .spawn()
        .map_err(|e| format!("could not start yt-dlp: {e}"))?;

    // Drain stderr on its own thread so a chatty yt-dlp can't block on a full pipe.
    let mut stderr = child.stderr.take().expect("stderr is piped");
    let errors = thread::spawn(move || {
        let mut text = Vec::new();
        let _ = stderr.read_to_end(&mut text);
        String::from_utf8_lossy(&text).into_owned()
    });

    let stdout = child.stdout.take().expect("stdout is piped");
    let (mut file, mut downloaded) = (None, None);
    for line in BufReader::new(stdout).split(b'\n').map_while(Result::ok) {
        let line = String::from_utf8_lossy(&line);
        let line = line.trim();
        if let Some(pct) = line.strip_prefix(PROGRESS) {
            if let Ok(percent) = pct.trim().trim_end_matches('%').parse::<f64>() {
                let _ = tx.send(AppEvent::DownloadProgress { id, percent });
            }
        } else if let Some(title) = line.strip_prefix(TITLE) {
            let _ = tx.send(AppEvent::DownloadTitle {
                id,
                title: title.to_string(),
            });
        } else if let Some(path) = line.strip_prefix(DOWNLOADED) {
            downloaded = Some(PathBuf::from(path));
        } else if let Some(path) = line.strip_prefix(FILE) {
            file = Some(PathBuf::from(path));
        }
    }

    let status = child.wait().map_err(|e| e.to_string())?;
    let errors = errors.join().unwrap_or_default();
    match file {
        Some(path) if status.success() && library::is_audio(&path) => Ok(path),
        Some(path) if status.success() => {
            // Never leave a video in the music folder.
            let _ = fs::remove_file(&path);
            Err(format!("no audio file came out of this link ({})", ext(&path)))
        }
        _ => {
            // Extraction failed (typically a video with no sound): the video
            // yt-dlp fetched is still there, along with its thumbnail.
            if let Some(path) = downloaded.filter(|p| !library::is_audio(p)) {
                remove_leftovers(&path);
            }
            Err(error_message(&errors, status))
        }
    }
}

fn ext(path: &Path) -> String {
    path.extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_else(|| "no extension".into())
}

/// Deletes a downloaded video and the cover image yt-dlp saved beside it.
fn remove_leftovers(video: &Path) {
    let _ = fs::remove_file(video);
    for image in ["jpg", "webp", "png"] {
        let _ = fs::remove_file(video.with_extension(image));
    }
}

fn error_message(errors: &str, status: std::process::ExitStatus) -> String {
    let last = errors
        .lines()
        .rev()
        .find(|l| l.starts_with("ERROR:"))
        .map(|l| l.trim_start_matches("ERROR:").trim().to_string());
    match last {
        Some(e) if e.contains("audio codec") => "this video has no audio track".into(),
        Some(e) => e,
        None => format!("yt-dlp exited with {status}"),
    }
}
