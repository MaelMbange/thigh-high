//! Broadcasting and listening on the local network (thigh_high_cast), as
//! the terminal app shows them: one dialog opened with `c`.

use std::collections::VecDeque;
use std::io::Cursor;
use std::net::SocketAddr;
use std::num::NonZero;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender, SyncSender, TryRecvError};
use std::thread;
use std::time::Duration;

use lofty::prelude::*;
use ratatui::widgets::ListState;
use rodio::source::SeekError;
use rodio::{ChannelCount, SampleRate, Source};
use thigh_high_cast::client::{self, Closer, Connected, Error, Event, Pending};
use thigh_high_cast::discovery::{Announcement, Browser, Found};
use thigh_high_cast::lyrics::Lyrics;
use thigh_high_cast::{Server, TrackInfo};

use crate::app::AppEvent;
use crate::cover;
use crate::library::Track;
use crate::visualizer::Tap;

/// Audio buffered before a received broadcast starts playing.
const PREBUFFER_MS: u64 = 400;
/// Above this much buffered audio, the oldest is skipped to stay live.
const MAX_BUFFER_MS: u64 = 2_000;
/// Cover size sent to listeners.
const COVER_SIZE: u32 = 320;

/// Where this device's key and paired devices are kept.
pub fn data_dir() -> PathBuf {
    dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("thigh-high")
        .join("cast")
}

/// The name other devices see.
pub fn device_name() -> String {
    std::env::var("COMPUTERNAME")
        .ok()
        .or_else(|| std::fs::read_to_string("/etc/hostname").ok())
        .or_else(|| std::env::var("HOSTNAME").ok())
        .map(|name| name.trim().to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "thigh-high".into())
}

/// The track as sent to listeners: tags, a small cover and any lyrics.
pub fn track_info(track: &Track) -> TrackInfo {
    TrackInfo {
        title: track.title.clone(),
        artist: track.artist.clone().unwrap_or_default(),
        album: track.album.clone().unwrap_or_default(),
        duration_ms: track.duration.map_or(0, |d| d.as_millis() as u64),
        cover: cover::cover_art(&track.path).and_then(|art| small_cover(&art)),
        lyrics: embedded_lyrics(&track.path),
    }
}

fn small_cover(art: &[u8]) -> Option<Vec<u8>> {
    let image = image::load_from_memory(art).ok()?;
    let mut jpeg = Vec::new();
    image
        .thumbnail(COVER_SIZE, COVER_SIZE)
        .to_rgb8()
        .write_to(&mut Cursor::new(&mut jpeg), image::ImageFormat::Jpeg)
        .ok()?;
    Some(jpeg)
}

fn embedded_lyrics(path: &Path) -> Option<String> {
    let file = lofty::read_from_path(path).ok()?;
    let tag = file.primary_tag().or_else(|| file.first_tag())?;
    let lyrics = tag.get_string(ItemKey::Lyrics)?.trim();
    (!lyrics.is_empty()).then(|| lyrics.to_string())
}

/// What the dialog shows.
pub enum Cast {
    /// Choosing between broadcasting and listening.
    Menu {
        listen: bool,
    },
    Broadcasting(Broadcast),
    Browsing(Browse),
    Connecting {
        name: String,
        found: Found,
        closer: Option<Closer>,
        /// Shown again on the code prompt (a wrong code reconnects).
        error: Option<String>,
    },
    AskingCode(AskCode),
    Listening(Listen),
}

pub struct Broadcast {
    pub server: Arc<Server>,
    announcement: Option<Announcement>,
    pub name: String,
    /// Set when the network announcement failed (the port still works).
    pub warning: Option<String>,
}

impl Broadcast {
    pub fn start(name: &str, open: bool) -> std::io::Result<Self> {
        let server = Arc::new(Server::start(&data_dir(), name, open)?);
        let mut broadcast = Self {
            server,
            announcement: None,
            name: name.to_string(),
            warning: None,
        };
        broadcast.announce();
        Ok(broadcast)
    }

    /// (Re)announces the broadcast, with whether a code is needed.
    fn announce(&mut self) {
        self.announcement = None;
        match Announcement::new(
            &self.name,
            self.server.port(),
            &self.server.short_id(),
            self.server.is_open(),
        ) {
            Ok(announcement) => {
                self.announcement = Some(announcement);
                self.warning = None;
            }
            Err(err) => self.warning = Some(format!("not visible on the network: {err}")),
        }
    }

    pub fn toggle_open(&mut self) {
        self.server.set_open(!self.server.is_open());
        self.announce();
    }
}

pub struct Browse {
    browser: Option<Browser>,
    pub found: Vec<Found>,
    pub state: ListState,
    pub error: Option<String>,
    own_id: Option<String>,
}

impl Browse {
    pub fn start(own_id: Option<String>) -> Self {
        let (browser, error) = match Browser::new() {
            Ok(browser) => (Some(browser), None),
            Err(err) => (None, Some(format!("Can't search the network: {err}"))),
        };
        Self {
            browser,
            found: Vec::new(),
            state: ListState::default(),
            error,
            own_id,
        }
    }

    /// Refreshes the list, keeping the selection on the same device.
    pub fn refresh(&mut self) {
        let Some(browser) = &mut self.browser else {
            return;
        };
        let selected = self
            .state
            .selected()
            .and_then(|i| self.found.get(i))
            .map(|f| f.id.clone());
        let own = self.own_id.clone();
        self.found = browser
            .poll()
            .into_iter()
            .filter(|f| Some(&f.id) != own.as_ref())
            .collect();
        let index = selected
            .and_then(|id| self.found.iter().position(|f| f.id == id))
            .or((!self.found.is_empty()).then_some(0));
        self.state.select(index);
    }

    pub fn selected(&self) -> Option<&Found> {
        self.state.selected().and_then(|i| self.found.get(i))
    }
}

pub struct AskCode {
    pub name: String,
    pub found: Found,
    pub input: String,
    pub error: Option<String>,
    pending: Option<Box<Pending>>,
}

impl AskCode {
    pub fn can_submit(&self) -> bool {
        self.input.len() == 6 && self.pending.is_some()
    }
}

pub struct Listen {
    pub name: String,
    pub track: Option<TrackInfo>,
    pub lyrics: Option<Lyrics>,
    pub playing: bool,
    /// Position in the track of the audio being heard.
    heard: Arc<AtomicU64>,
    closer: Closer,
}

impl Listen {
    pub fn heard_ms(&self) -> u64 {
        self.heard.load(Ordering::Relaxed)
    }

    pub fn stop(&self) {
        self.closer.close();
    }
}

/// Messages from the network threads to the app.
pub enum CastEvent {
    Connected(Box<Result<Connected, Error>>),
    Paired(Box<Result<client::Session, Error>>),
    Track(TrackInfo),
    State(bool),
    /// The broadcast ended (None) or the connection was lost.
    Ended(Option<String>),
}

/// Connects in the background (it can take seconds).
pub fn connect(found: &Found, events: Sender<AppEvent>, error: Option<String>) -> Cast {
    let addr: SocketAddr = found.addr;
    thread::spawn(move || {
        let result = client::connect(&data_dir(), addr, &device_name());
        let _ = events.send(AppEvent::Cast(CastEvent::Connected(Box::new(result))));
    });
    Cast::Connecting {
        name: found.name.clone(),
        found: found.clone(),
        closer: None,
        error,
    }
}

pub fn ask_code(name: String, found: Found, pending: Pending, error: Option<String>) -> Cast {
    Cast::AskingCode(AskCode {
        name,
        found,
        input: String::new(),
        error,
        pending: Some(Box::new(pending)),
    })
}

/// Sends the typed code in the background; the answer comes as `Paired`.
pub fn submit_code(ask: &mut AskCode, events: Sender<AppEvent>) -> Option<Closer> {
    let pending = ask.pending.take()?;
    let closer = pending.closer().ok();
    let code = ask.input.clone();
    thread::spawn(move || {
        let result = pending.pair(&code);
        let _ = events.send(AppEvent::Cast(CastEvent::Paired(Box::new(result))));
    });
    closer
}

/// Starts receiving a joined broadcast, converted to `output_rate`.
pub fn listen(
    session: client::Session,
    output_rate: u32,
    tap: Tap,
    events: Sender<AppEvent>,
) -> std::io::Result<(Listen, LiveSource)> {
    let closer = session.closer()?;
    let name = session.name().to_string();
    let heard = Arc::new(AtomicU64::new(0));
    let (audio, chunks) = mpsc::sync_channel(64);
    thread::spawn(move || receive(session, output_rate, audio, events));
    let source = LiveSource {
        chunks,
        queue: VecDeque::new(),
        chunk: Vec::new(),
        index: 0,
        buffering: true,
        rate: output_rate,
        heard: heard.clone(),
        tap,
    };
    Ok((
        Listen {
            name,
            track: None,
            lyrics: None,
            playing: true,
            heard,
            closer,
        },
        source,
    ))
}

fn receive(
    mut session: client::Session,
    output_rate: u32,
    audio: SyncSender<(u64, Vec<f32>)>,
    events: Sender<AppEvent>,
) {
    let mut resampler = Resampler::new(output_rate);
    let send = |event| {
        let _ = events.send(AppEvent::Cast(event));
    };
    loop {
        match session.receive() {
            Ok(Event::Audio {
                pos_ms,
                rate,
                samples,
            }) => {
                let samples: Vec<f32> = samples.iter().map(|&s| s as f32 / 32768.0).collect();
                let samples = resampler.process(&samples, rate);
                // Full only when playback stalls (paused): drop rather than lag.
                let _ = audio.try_send((pos_ms, samples));
            }
            Ok(Event::Track(track)) => send(CastEvent::Track(track)),
            Ok(Event::State { playing, .. }) => send(CastEvent::State(playing)),
            Ok(Event::Ended) => {
                send(CastEvent::Ended(None));
                return;
            }
            Err(err) => {
                send(CastEvent::Ended(Some(err.to_string())));
                return;
            }
        }
    }
}

/// Converts interleaved stereo between sample rates (linear interpolation).
struct Resampler {
    to: u32,
    from: u32,
    /// Position in input frames, relative to `prev`.
    pos: f64,
    prev: [f32; 2],
}

impl Resampler {
    fn new(to: u32) -> Self {
        Self {
            to,
            from: to,
            pos: 0.0,
            prev: [0.0; 2],
        }
    }

    fn process(&mut self, input: &[f32], from: u32) -> Vec<f32> {
        if from == 0 || input.len() < 2 {
            return Vec::new();
        }
        if from != self.from {
            self.from = from;
            self.pos = 0.0;
        }
        if from == self.to {
            return input.to_vec();
        }
        let frames = input.len() / 2;
        let step = from as f64 / self.to as f64;
        let frame = |i: usize| -> [f32; 2] {
            if i == 0 {
                self.prev
            } else {
                [input[(i - 1) * 2], input[(i - 1) * 2 + 1]]
            }
        };
        let mut out = Vec::with_capacity((frames as f64 / step) as usize * 2 + 4);
        while self.pos < frames as f64 {
            let i = self.pos as usize;
            let t = (self.pos - i as f64) as f32;
            let (a, b) = (frame(i), frame(i + 1));
            out.push(a[0] + (b[0] - a[0]) * t);
            out.push(a[1] + (b[1] - a[1]) * t);
            self.pos += step;
        }
        self.pos -= frames as f64;
        self.prev = [input[(frames - 1) * 2], input[(frames - 1) * 2 + 1]];
        out
    }
}

/// A rodio source playing what the network thread receives.
pub struct LiveSource {
    chunks: Receiver<(u64, Vec<f32>)>,
    queue: VecDeque<(u64, Vec<f32>)>,
    chunk: Vec<f32>,
    index: usize,
    buffering: bool,
    rate: u32,
    heard: Arc<AtomicU64>,
    tap: Tap,
}

impl LiveSource {
    fn queued_ms(&self) -> u64 {
        let samples: usize = self.queue.iter().map(|(_, c)| c.len()).sum();
        samples as u64 * 1000 / (self.rate as u64 * 2)
    }
}

impl Iterator for LiveSource {
    type Item = f32;

    fn next(&mut self) -> Option<f32> {
        if self.index >= self.chunk.len() {
            loop {
                match self.chunks.try_recv() {
                    Ok(chunk) => self.queue.push_back(chunk),
                    Err(TryRecvError::Empty) => break,
                    Err(TryRecvError::Disconnected) if self.queue.is_empty() => return None,
                    Err(TryRecvError::Disconnected) => break,
                }
            }
            if self.buffering && self.queued_ms() < PREBUFFER_MS {
                return Some(0.0);
            }
            self.buffering = false;
            while self.queued_ms() > MAX_BUFFER_MS {
                self.queue.pop_front();
            }
            let Some((pos_ms, chunk)) = self.queue.pop_front() else {
                // Ran dry (network hiccup or pause): wait for a new cushion.
                self.buffering = true;
                return Some(0.0);
            };
            self.heard.store(pos_ms, Ordering::Relaxed);
            self.tap.push(&chunk);
            self.chunk = chunk;
            self.index = 0;
        }
        let sample = self.chunk.get(self.index).copied();
        self.index += 1;
        sample
    }
}

impl Source for LiveSource {
    fn current_span_len(&self) -> Option<usize> {
        None
    }

    fn channels(&self) -> ChannelCount {
        NonZero::new(2).unwrap()
    }

    fn sample_rate(&self) -> SampleRate {
        NonZero::new(self.rate).unwrap_or(NonZero::new(48_000).unwrap())
    }

    fn total_duration(&self) -> Option<Duration> {
        None
    }

    fn try_seek(&mut self, _pos: Duration) -> Result<(), SeekError> {
        Err(SeekError::NotSupported {
            underlying_source: "a live broadcast",
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resamples_keeping_length() {
        let mut r = Resampler::new(48_000);
        let input = vec![0.5f32; 44_100 * 2];
        let mut total = 0;
        for chunk in input.chunks(882) {
            total += r.process(chunk, 44_100).len();
        }
        let frames = total / 2;
        assert!((frames as i64 - 48_000).abs() <= 2, "{frames} frames");
    }

    /// A broadcast reaches a listener's speakers, resampled, with its track.
    #[test]
    fn listens_to_a_broadcast() {
        let dir = std::env::temp_dir().join(format!("thigh-high-tui-cast-{}", std::process::id()));
        let server = Server::start(&dir.join("pc"), "PC", true).unwrap();
        server.set_track(TrackInfo {
            title: "Song".into(),
            ..Default::default()
        });
        server.set_position(5_000, true);
        let addr = SocketAddr::from(([127, 0, 0, 1], server.port()));
        let Ok(Connected::Ready(session)) = client::connect(&dir.join("me"), addr, "Me") else {
            panic!("could not join");
        };
        let (events, app_events) = mpsc::channel();
        let (listen, mut source) = listen(session, 48_000, Tap::new(48_000), events).unwrap();
        assert_eq!(listen.name, "PC");
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while server.listeners().is_empty() {
            assert!(std::time::Instant::now() < deadline);
            thread::sleep(Duration::from_millis(10));
        }
        // 1 s of a constant at 44.1 kHz, in 20 ms chunks.
        for _ in 0..50 {
            server.push_i16(&[8_192i16; 882 * 2], 2, 44_100);
        }
        let mut heard = 0;
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while heard < 48_000 {
            assert!(
                std::time::Instant::now() < deadline,
                "heard {heard} samples"
            );
            match source.next() {
                Some(s) if (s - 0.25).abs() < 0.01 => heard += 1,
                Some(_) => thread::sleep(Duration::from_micros(50)),
                None => panic!("stream ended"),
            }
        }
        assert_eq!(listen.heard_ms(), 5_000);
        let titles: Vec<String> = app_events
            .try_iter()
            .filter_map(|e| match e {
                AppEvent::Cast(CastEvent::Track(t)) => Some(t.title),
                _ => None,
            })
            .collect();
        assert_eq!(titles, ["Song"]);
        listen.stop();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn same_rate_is_untouched() {
        let mut r = Resampler::new(48_000);
        assert_eq!(
            r.process(&[0.1, 0.2, 0.3, 0.4], 48_000),
            vec![0.1, 0.2, 0.3, 0.4]
        );
    }
}
