use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use color_eyre::Result;
use lofty::prelude::*;

const AUDIO_EXTENSIONS: &[&str] = &["mp3", "m4a", "opus", "ogg", "flac", "wav"];

/// Genre values that say nothing about the music. YouTube files every song
/// under the "Music" category, and yt-dlp writes that into the genre tag.
const PLACEHOLDER_GENRES: &[&str] = &["music", "musique", "other", "unknown", "genre"];

#[derive(Debug, Clone)]
pub struct Track {
    pub title: String,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub genre: Option<String>,
    pub duration: Option<Duration>,
    pub path: PathBuf,
}

impl Track {
    /// Builds a track from the file's tags, falling back to the file name.
    pub fn read(path: PathBuf) -> Self {
        let fallback = path
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        let mut track = Track {
            title: fallback,
            artist: None,
            album: None,
            genre: None,
            duration: None,
            path,
        };
        if let Ok(file) = lofty::read_from_path(&track.path) {
            track.duration = Some(file.properties().duration()).filter(|d| !d.is_zero());
            if let Some(tag) = file.primary_tag().or_else(|| file.first_tag()) {
                if let Some(title) = tag.title() {
                    track.title = title.into_owned();
                }
                track.artist = tag.artist().map(|s| s.into_owned());
                track.album = tag.album().map(|s| s.into_owned());
                track.genre = tag
                    .genre()
                    .map(|s| s.trim().to_string())
                    .filter(|g| !g.is_empty() && !is_placeholder_genre(g));
            }
        }
        track
    }
}

/// Where downloaded music lives: `<Music folder>/thigh-high`, created if missing.
pub fn music_dir() -> Result<PathBuf> {
    let base = dirs::audio_dir()
        .or_else(dirs::home_dir)
        .unwrap_or_else(|| PathBuf::from("."));
    let dir = base.join("thigh-high");
    fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// Lists the audio files in `dir`, sorted by title.
pub fn scan(dir: &Path) -> Result<Vec<Track>> {
    let mut tracks: Vec<Track> = fs::read_dir(dir)?
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| is_audio(path))
        .map(Track::read)
        .collect();
    tracks.sort_by_key(|t| t.title.to_lowercase());
    Ok(tracks)
}

fn is_placeholder_genre(genre: &str) -> bool {
    PLACEHOLDER_GENRES
        .iter()
        .any(|p| p.eq_ignore_ascii_case(genre))
}

fn is_audio(path: &Path) -> bool {
    path.extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| AUDIO_EXTENSIONS.contains(&ext.to_lowercase().as_str()))
}
