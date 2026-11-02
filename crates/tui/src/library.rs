use std::fs;
use std::path::PathBuf;

use color_eyre::Result;

pub use thigh_high_core::library::*;

/// Where downloaded music lives: `<Music folder>/thigh-high`, created if missing.
pub fn music_dir() -> Result<PathBuf> {
    let base = dirs::audio_dir()
        .or_else(dirs::home_dir)
        .unwrap_or_else(|| PathBuf::from("."));
    let dir = base.join("thigh-high");
    fs::create_dir_all(&dir)?;
    Ok(dir)
}
