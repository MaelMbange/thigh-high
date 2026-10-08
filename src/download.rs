//! Runs `yt-dlp` in a background thread and reports progress as events.

use std::io::{BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc::Sender;
use std::thread;

use crate::app::AppEvent;
use crate::tools::{self, Tools};

// Prefixes on yt-dlp's output lines so we can tell them apart.
const PROGRESS: &str = "[p]";
const TITLE: &str = "[t]";
const FILE: &str = "[f]";

pub fn start(id: usize, url: String, tools: &Tools, out_dir: &Path, tx: Sender<AppEvent>) {
    let mut cmd = Command::new(&tools.ytdlp);
    cmd.args([
        "--no-playlist",
        "--color",
        "never",
        "--encoding",
        "utf-8",
        // Keep the best audio stream as-is (often Opus): re-encoding only loses quality.
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
    let mut file = None;
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
        } else if let Some(path) = line.strip_prefix(FILE) {
            file = Some(PathBuf::from(path));
        }
    }

    let status = child.wait().map_err(|e| e.to_string())?;
    let errors = errors.join().unwrap_or_default();
    match file {
        Some(path) if status.success() => Ok(path),
        _ => Err(errors
            .lines()
            .rev()
            .find(|l| l.starts_with("ERROR:"))
            .map(|l| l.trim_start_matches("ERROR:").trim().to_string())
            .unwrap_or_else(|| format!("yt-dlp exited with {status}"))),
    }
}
