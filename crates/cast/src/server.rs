//! "Diffuser": shares what this device plays with the listeners that join.
//!
//! Each listener gets its own encrypted connection and a short queue. The
//! audio is sent as it is played, never as a file, and nothing a listener
//! sends can reach the library: after hello (and the code) it only receives.

use std::io::{self, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use rustls::{ServerConfig, ServerConnection, StreamOwned};

use crate::identity::{Identity, Paired, TrustedListeners, paired_id};
use crate::pairing;
use crate::wire::{self, LIVE, Message, PROTOCOL, Radio, Reject, TrackInfo};

/// Listeners at once, so a crowded network can't drain the broadcaster.
pub const MAX_LISTENERS: usize = 8;
/// Wrong codes in a row before a new code is drawn.
const WRONG_CODES: u32 = 3;
/// Messages queued per listener (~5 s of audio) before audio is dropped.
const QUEUE: usize = 128;
/// A listener that missed this many messages in a row is let go.
const MAX_DROPPED: u32 = 400;
/// Time a listener has to type the code.
const CODE_TIMEOUT: Duration = Duration::from_secs(180);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// With nothing to send (paused), a state message keeps the line alive.
const KEEPALIVE: Duration = Duration::from_secs(2);
/// Radios on offer at most (each one plays on its own when listened to).
pub const MAX_RADIOS: usize = 32;

type Frame = Arc<Vec<u8>>;

struct Listener {
    id: u64,
    name: String,
    /// Its entry among the paired listeners, if it entered the code once.
    paired: Option<String>,
    queue: SyncSender<Frame>,
    dropped: u32,
    /// The channel it hears, shared with its thread for the keepalive.
    channel: Arc<AtomicU32>,
}

/// What one channel plays: [`LIVE`] or a radio.
#[derive(Default)]
struct Channel {
    name: String,
    track: Option<Frame>,
    playing: bool,
    pos_ms: u64,
}

struct Shared {
    name: String,
    dir: PathBuf,
    tls: Arc<ServerConfig>,
    open: AtomicBool,
    code: Mutex<String>,
    wrong: AtomicU32,
    listeners: Mutex<Vec<Listener>>,
    next_id: AtomicU64,
    /// [`LIVE`] always, and the radios on offer.
    channels: Mutex<HashMap<u32, Channel>>,
    /// The radios in the order they are offered.
    radios: Mutex<Vec<Radio>>,
    stopped: AtomicBool,
}

pub struct Server {
    shared: Arc<Shared>,
    port: u16,
    fingerprint: [u8; 32],
    short_id: String,
}

impl Server {
    /// Starts listening on a free port chosen by the system.
    /// `dir` holds this device's key and the listeners it trusts.
    pub fn start(dir: &Path, name: &str, open: bool) -> io::Result<Self> {
        let identity = Identity::load_or_create(dir)?;
        let tls = crate::tls::server_config(&identity).map_err(io::Error::other)?;
        let socket = TcpListener::bind(("0.0.0.0", 0))?;
        socket.set_nonblocking(true)?;
        let port = socket.local_addr()?.port();
        let shared = Arc::new(Shared {
            name: name.to_string(),
            dir: dir.to_path_buf(),
            tls,
            open: AtomicBool::new(open),
            code: Mutex::new(pairing::new_code()),
            wrong: AtomicU32::new(0),
            listeners: Mutex::new(Vec::new()),
            next_id: AtomicU64::new(0),
            channels: Mutex::new(HashMap::from([(LIVE, Channel::default())])),
            radios: Mutex::new(Vec::new()),
            stopped: AtomicBool::new(false),
        });
        let accepting = shared.clone();
        thread::Builder::new()
            .name("cast-accept".into())
            .spawn(move || accept_loop(accepting, socket))?;
        Ok(Self {
            shared,
            port,
            short_id: identity.short_id(),
            fingerprint: identity.fingerprint,
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// Announced on the network so listeners can tell devices apart.
    pub fn short_id(&self) -> String {
        self.short_id.clone()
    }

    pub fn fingerprint(&self) -> [u8; 32] {
        self.fingerprint
    }

    /// The code new listeners must type (unless the broadcast is open).
    pub fn code(&self) -> String {
        self.shared.code.lock().unwrap().clone()
    }

    pub fn is_open(&self) -> bool {
        self.shared.open.load(Ordering::Relaxed)
    }

    /// Closing (open to code) lets go of the listeners that never typed
    /// the code; opening changes nothing for those already in.
    pub fn set_open(&self, open: bool) {
        self.shared.open.store(open, Ordering::Relaxed);
        if !open {
            let mut listeners = self.shared.listeners.lock().unwrap();
            listeners.retain(|l| l.paired.is_some());
        }
    }

    /// Lets every listener that ever paired type the code again; those
    /// listening now are let go, unless the broadcast is open.
    pub fn forget_listeners(&self) -> io::Result<()> {
        TrustedListeners::new(&self.shared.dir).forget_all()?;
        let open = self.is_open();
        let mut listeners = self.shared.listeners.lock().unwrap();
        listeners.retain_mut(|l| {
            if open {
                // Still welcome without a code, just no longer paired.
                l.paired = None;
                true
            } else {
                l.paired.is_none()
            }
        });
        Ok(())
    }

    /// Listeners that entered the code once and may come back without it.
    pub fn paired(&self) -> Vec<Paired> {
        TrustedListeners::new(&self.shared.dir).list()
    }

    /// Forgets one paired listener and lets it go if it is listening now.
    pub fn forget_paired(&self, id: &str) -> io::Result<()> {
        TrustedListeners::new(&self.shared.dir).forget(id)?;
        let mut listeners = self.shared.listeners.lock().unwrap();
        listeners.retain(|l| l.paired.as_deref() != Some(id));
        Ok(())
    }

    /// The devices listening now, each with its paired entry
    /// ([Paired::id]) or None when it came in without a code (open), and
    /// the channel it hears.
    pub fn listening(&self) -> Vec<(Option<String>, String, u32)> {
        let listeners = self.shared.listeners.lock().unwrap();
        listeners
            .iter()
            .map(|l| (l.paired.clone(), l.name.clone(), l.channel.load(Ordering::Relaxed)))
            .collect()
    }

    /// Offers these radios (ids other than [`LIVE`], at most [`MAX_RADIOS`])
    /// in place of the previous ones. Those listening to a radio taken off
    /// go back to live; every listener is told the new list.
    pub fn set_radios(&self, radios: &[Radio]) {
        let mut seen = Vec::new();
        let radios: Vec<Radio> = radios
            .iter()
            .filter(|r| r.id != LIVE && !seen.contains(&r.id) && {
                seen.push(r.id);
                true
            })
            .take(MAX_RADIOS)
            .map(|r| Radio {
                id: r.id,
                name: r.name.chars().filter(|c| !c.is_control()).take(64).collect(),
            })
            .collect();
        let mut channels = self.shared.channels.lock().unwrap();
        channels.retain(|id, _| *id == LIVE || radios.iter().any(|r| r.id == *id));
        for radio in &radios {
            channels.entry(radio.id).or_default().name = radio.name.clone();
        }
        *self.shared.radios.lock().unwrap() = radios.clone();
        let live = &channels[&LIVE];
        let back_to_live = [
            live.track.clone(),
            Some(Arc::new(channel_state(live).encode())),
        ];
        let mut listeners = self.shared.listeners.lock().unwrap();
        for listener in listeners.iter_mut() {
            let mut current = listener.channel.load(Ordering::Relaxed);
            let moved = !channels.contains_key(&current);
            if moved {
                current = LIVE;
                listener.channel.store(LIVE, Ordering::Relaxed);
            }
            let list = Message::Channels {
                current,
                radios: radios.clone(),
            };
            let _ = listener.queue.try_send(Arc::new(list.encode()));
            if moved {
                for frame in back_to_live.iter().flatten() {
                    let _ = listener.queue.try_send(frame.clone());
                }
            }
        }
    }

    /// The radios someone listens to now: only those need to play.
    pub fn radios_listened(&self) -> Vec<u32> {
        let listeners = self.shared.listeners.lock().unwrap();
        let mut ids: Vec<u32> = listeners
            .iter()
            .map(|l| l.channel.load(Ordering::Relaxed))
            .filter(|&c| c != LIVE)
            .collect();
        ids.sort_unstable();
        ids.dedup();
        ids
    }

    /// Names of the devices listening now.
    pub fn listeners(&self) -> Vec<String> {
        let listeners = self.shared.listeners.lock().unwrap();
        listeners.iter().map(|l| l.name.clone()).collect()
    }

    /// The track now playing; sent at once and to every listener that joins.
    pub fn set_track(&self, track: TrackInfo) {
        self.set_track_on(LIVE, track);
    }

    /// Where playback is, called often by the app (the audio is stamped with it).
    pub fn set_position(&self, pos_ms: u64, playing: bool) {
        self.set_position_on(LIVE, pos_ms, playing);
    }

    /// Audio as it is played, interleaved with `channels` channels.
    /// Never blocks: a listener that falls behind loses audio, not the player.
    pub fn push_f32(&self, samples: &[f32], channels: usize, rate: u32) {
        self.push_f32_on(LIVE, samples, channels, rate);
    }

    pub fn push_i16(&self, samples: &[i16], channels: usize, rate: u32) {
        self.push_i16_on(LIVE, samples, channels, rate);
    }

    /// [`Self::set_track`] for one channel; ignored for a radio not on offer.
    pub fn set_track_on(&self, channel: u32, track: TrackInfo) {
        let frame = Arc::new(Message::Track(track).encode());
        {
            let mut channels = self.shared.channels.lock().unwrap();
            let Some(state) = channels.get_mut(&channel) else {
                return;
            };
            state.track = Some(frame.clone());
        }
        self.shared.send(channel, frame);
    }

    pub fn set_position_on(&self, channel: u32, pos_ms: u64, playing: bool) {
        let changed = {
            let mut channels = self.shared.channels.lock().unwrap();
            let Some(state) = channels.get_mut(&channel) else {
                return;
            };
            state.pos_ms = pos_ms;
            std::mem::replace(&mut state.playing, playing) != playing
        };
        if changed {
            self.shared
                .send(channel, Arc::new(Message::State { playing, pos_ms }.encode()));
        }
    }

    pub fn push_f32_on(&self, channel: u32, samples: &[f32], channels: usize, rate: u32) {
        let to_i16 = |s: f32| (s.clamp(-1.0, 1.0) * 32767.0) as i16;
        self.push(channel, stereo(samples, channels, to_i16), rate);
    }

    pub fn push_i16_on(&self, channel: u32, samples: &[i16], channels: usize, rate: u32) {
        self.push(channel, stereo(samples, channels, |s| s), rate);
    }

    fn push(&self, channel: u32, samples: Vec<i16>, rate: u32) {
        if samples.is_empty() || !self.shared.heard(channel) {
            return;
        }
        let Some(pos_ms) = self.shared.channels.lock().unwrap().get(&channel).map(|c| c.pos_ms) else {
            return;
        };
        let frame = Message::Audio {
            pos_ms,
            rate,
            samples,
        };
        self.shared.send(channel, Arc::new(frame.encode()));
    }

    /// Ends the broadcast: listeners are told and disconnected.
    pub fn stop(&self) {
        self.shared.stopped.store(true, Ordering::Relaxed);
        // Dropping the queues ends each listener's thread, which says goodbye.
        self.shared.listeners.lock().unwrap().clear();
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Converts interleaved audio of any channel count to interleaved stereo.
fn stereo<T: Copy>(samples: &[T], channels: usize, convert: impl Fn(T) -> i16) -> Vec<i16> {
    match channels {
        0 => Vec::new(),
        1 => samples
            .iter()
            .flat_map(|&s| {
                let s = convert(s);
                [s, s]
            })
            .collect(),
        2 => samples.iter().map(|&s| convert(s)).collect(),
        n => samples
            .chunks_exact(n)
            .flat_map(|frame| [convert(frame[0]), convert(frame[1])])
            .collect(),
    }
}

fn channel_state(channel: &Channel) -> Message {
    Message::State {
        playing: channel.playing,
        pos_ms: channel.pos_ms,
    }
}

impl Shared {
    /// Whether someone listens to that channel.
    fn heard(&self, channel: u32) -> bool {
        let listeners = self.listeners.lock().unwrap();
        listeners
            .iter()
            .any(|l| l.channel.load(Ordering::Relaxed) == channel)
    }

    /// Queues a frame for those listening to `channel`.
    fn send(&self, channel: u32, frame: Frame) {
        let mut listeners = self.listeners.lock().unwrap();
        listeners.retain_mut(|listener| {
            if listener.channel.load(Ordering::Relaxed) != channel {
                return true;
            }
            match listener.queue.try_send(frame.clone()) {
            Ok(()) => {
                listener.dropped = 0;
                true
            }
            Err(TrySendError::Full(_)) => {
                listener.dropped += 1;
                listener.dropped < MAX_DROPPED
            }
            Err(TrySendError::Disconnected(_)) => false,
            }
        });
    }

    fn new_code(&self) {
        *self.code.lock().unwrap() = pairing::new_code();
        self.wrong.store(0, Ordering::Relaxed);
    }

    /// A wrong or abandoned code: counts towards drawing a new one.
    fn wrong_code(&self) -> Reject {
        let wrong = self.wrong.fetch_add(1, Ordering::Relaxed) + 1;
        if wrong >= WRONG_CODES {
            self.new_code();
            Reject::CodeChanged
        } else {
            Reject::WrongCode
        }
    }
}

fn accept_loop(shared: Arc<Shared>, socket: TcpListener) {
    while !shared.stopped.load(Ordering::Relaxed) {
        match socket.accept() {
            Ok((stream, _)) => {
                let shared = shared.clone();
                let _ = thread::Builder::new()
                    .name("cast-listener".into())
                    .spawn(move || {
                        let _ = serve(&shared, stream);
                    });
            }
            Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(100));
            }
            Err(_) => thread::sleep(Duration::from_millis(100)),
        }
    }
}

type Tls = StreamOwned<ServerConnection, TcpStream>;

fn serve(shared: &Shared, stream: TcpStream) -> io::Result<()> {
    // Accepted sockets may inherit non-blocking mode on some systems.
    stream.set_nonblocking(false)?;
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
    stream.set_write_timeout(Some(HANDSHAKE_TIMEOUT))?;
    let conn = ServerConnection::new(shared.tls.clone()).map_err(io::Error::other)?;
    let mut tls = StreamOwned::new(conn, stream);
    while tls.conn.is_handshaking() {
        tls.conn.complete_io(&mut tls.sock)?;
    }

    let Message::Hello {
        protocol,
        name,
        token,
        channel,
    } = wire::read(&mut tls)?
    else {
        return reject(&mut tls, Reject::Protocol);
    };
    let name: String = name.chars().filter(|c| !c.is_control()).take(64).collect();
    if protocol != PROTOCOL {
        return reject(&mut tls, Reject::Protocol);
    }
    if shared.listeners.lock().unwrap().len() >= MAX_LISTENERS {
        return reject(&mut tls, Reject::Full);
    }

    let trusted_store = TrustedListeners::new(&shared.dir);
    let trusted = token.is_some_and(|t| trusted_store.is_trusted(&t));
    let mut paired = token.filter(|_| trusted).map(|t| paired_id(&t));
    let mut new_token = None;
    if !trusted && !shared.open.load(Ordering::Relaxed) {
        match pair(shared, &mut tls)? {
            true => {
                let token = pairing::random::<32>();
                trusted_store.add(&token, &name)?;
                paired = Some(paired_id(&token));
                new_token = Some(token);
            }
            false => return Ok(()),
        }
    }

    wire::write(
        &mut tls,
        &Message::Accepted {
            name: shared.name.clone(),
            token: new_token,
        },
    )?;
    if shared.stopped.load(Ordering::Relaxed) {
        return wire::write(&mut tls, &Message::Bye);
    }

    let (queue, frames) = mpsc::sync_channel(QUEUE);
    let id = shared.next_id.fetch_add(1, Ordering::Relaxed);
    // Registered before the current track is read: a track change in
    // between is then queued after it, so the newest one always wins.
    // The channels are locked meanwhile, so a radio can't go in between.
    let first = {
        let channels = shared.channels.lock().unwrap();
        let channel = if channels.contains_key(&channel) { channel } else { LIVE };
        let tuned = Arc::new(AtomicU32::new(channel));
        shared.listeners.lock().unwrap().push(Listener {
            id,
            name,
            paired,
            queue,
            dropped: 0,
            channel: tuned.clone(),
        });
        let state = &channels[&channel];
        let list = Message::Channels {
            current: channel,
            radios: shared.radios.lock().unwrap().clone(),
        };
        (tuned, [Some(Arc::new(list.encode())), state.track.clone(), Some(Arc::new(channel_state(state).encode()))])
    };
    let result = stream_to(shared, &mut tls, frames, first);
    shared.listeners.lock().unwrap().retain(|l| l.id != id);
    let _ = wire::write(&mut tls, &Message::Bye);
    tls.conn.send_close_notify();
    let _ = tls.flush();
    result
}

/// Runs the code exchange. Ok(false) when the code was wrong.
fn pair(shared: &Shared, tls: &mut Tls) -> io::Result<bool> {
    wire::write(tls, &Message::NeedCode)?;
    tls.sock.set_read_timeout(Some(CODE_TIMEOUT))?;
    let Ok(Message::Pair(theirs)) = wire::read(tls) else {
        // Left without typing a code: not an attempt.
        return Ok(false);
    };
    tls.sock.set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
    let code = shared.code.lock().unwrap().clone();
    let (exchange, ours) = pairing::start(&code);
    let Some(key) = exchange.finish(&theirs) else {
        reject(tls, Reject::Protocol)?;
        return Ok(false);
    };
    let exported = exported(&tls.conn)?;
    let confirm = pairing::confirm(&key, &exported, pairing::BROADCASTER);
    wire::write(
        tls,
        &Message::PairReply {
            spake: ours,
            confirm,
        },
    )?;
    let expected = pairing::confirm(&key, &exported, pairing::LISTENER);
    let proven = matches!(wire::read(tls), Ok(Message::Confirm(theirs)) if pairing::same(&theirs, &expected));
    if proven {
        shared.wrong.store(0, Ordering::Relaxed);
        Ok(true)
    } else {
        // Leaving after seeing our proof counts too: it was a guess.
        let why = shared.wrong_code();
        // Slows down anyone trying codes one after the other.
        thread::sleep(Duration::from_secs(1));
        let _ = reject(tls, why);
        Ok(false)
    }
}

fn exported(conn: &ServerConnection) -> io::Result<[u8; 32]> {
    conn.export_keying_material([0u8; 32], pairing::EXPORTER_LABEL, None)
        .map_err(io::Error::other)
}

fn reject(tls: &mut Tls, why: Reject) -> io::Result<()> {
    wire::write(tls, &Message::Rejected(why))?;
    tls.conn.send_close_notify();
    tls.flush()
}

fn stream_to(
    shared: &Shared,
    tls: &mut Tls,
    frames: Receiver<Frame>,
    (channel, first): (Arc<AtomicU32>, [Option<Frame>; 3]),
) -> io::Result<()> {
    for frame in first.iter().flatten() {
        tls.write_all(frame)?;
    }
    tls.flush()?;
    let state = |shared: &Shared| {
        let channels = shared.channels.lock().unwrap();
        let current = channels
            .get(&channel.load(Ordering::Relaxed))
            .or_else(|| channels.get(&LIVE))
            .expect("live is always there");
        channel_state(current)
    };
    loop {
        match frames.recv_timeout(KEEPALIVE) {
            Ok(frame) => {
                tls.write_all(&frame)?;
                // Send what's queued together, then flush once.
                while let Ok(frame) = frames.try_recv() {
                    tls.write_all(&frame)?;
                }
                tls.flush()?;
            }
            Err(RecvTimeoutError::Timeout) => wire::write(tls, &state(shared))?,
            // Dropped by `stop` or because it fell too far behind.
            Err(RecvTimeoutError::Disconnected) => return Ok(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_to_stereo() {
        assert_eq!(stereo(&[1i16, 2], 1, |s| s), vec![1, 1, 2, 2]);
        assert_eq!(stereo(&[1i16, 2, 3, 4, 5, 6], 3, |s| s), vec![1, 2, 4, 5]);
        assert_eq!(
            stereo(&[1.0f32, -1.0], 2, |s| (s * 10.0) as i16),
            vec![10, -10]
        );
    }
}
