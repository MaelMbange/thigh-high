//! Logic shared by the terminal app and the Android app: reading the music
//! library, filters, cover-art colors, audio analysis for the animations and
//! offline genre detection.
//!
//! Nothing here starts a process, touches a terminal or picks a folder: each
//! app decodes audio and chooses where music lives in its own way, then hands
//! paths and samples to this crate.

pub mod analysis;
pub mod cover;
pub mod filter;
pub mod genre;
pub mod library;
