//! JNI functions for broadcasting (`io.github.maelmbange.thighhigh.cast.CastNative`).
//!
//! Handles are boxed Rust objects passed to Kotlin as `long`. Kotlin frees
//! each one once, after the last call that uses it (see CastController.kt).

use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JShortArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jintArray, jlong, jstring};
use thigh_high_cast::client::{self, Closer, Connected, Event, Pending, Session};
use thigh_high_cast::{Radio, Server, TrackInfo};

fn throw(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/RuntimeException", message.as_ref());
}

fn string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    Ok(env.get_string(value).map_err(|e| e.to_string())?.into())
}

fn optional_string(env: &mut JNIEnv, value: &JString) -> Result<Option<String>, String> {
    if value.is_null() {
        Ok(None)
    } else {
        string(env, value).map(Some)
    }
}

fn new_string(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn new_bytes(env: &mut JNIEnv, value: Option<&[u8]>) -> jbyteArray {
    match value {
        Some(bytes) => env
            .byte_array_from_slice(bytes)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// # Safety
/// `handle` must come from `serverStart` and not be freed yet.
unsafe fn server<'a>(handle: jlong) -> &'a Server {
    unsafe { &*(handle as *const Server) }
}

// ---------------------------------------------------------------- Diffuser

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverStart<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    dir: JString<'l>,
    name: JString<'l>,
    open: jboolean,
) -> jlong {
    let result = (|| -> Result<Server, String> {
        let dir = PathBuf::from(string(&mut env, &dir)?);
        let name = string(&mut env, &name)?;
        Server::start(&dir, &name, open == JNI_TRUE).map_err(|e| e.to_string())
    })();
    match result {
        Ok(server) => Box::into_raw(Box::new(server)) as jlong,
        Err(message) => {
            throw(&mut env, message);
            0
        }
    }
}

/// Ends the broadcast and frees the handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // Safety: Kotlin stops each server once, after its last use.
        drop(unsafe { Box::from_raw(handle as *mut Server) });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverPort(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    unsafe { server(handle) }.port() as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverId<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let id = unsafe { server(handle) }.short_id();
    new_string(&mut env, &id)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverCode<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let code = unsafe { server(handle) }.code();
    new_string(&mut env, &code)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverSetOpen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    open: jboolean,
) {
    unsafe { server(handle) }.set_open(open == JNI_TRUE);
}

/// The devices listening, one per line: paired id (empty if none), a tab,
/// the channel heard (0 for live), a tab, the name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverListeners<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let lines: Vec<String> = unsafe { server(handle) }
        .listening()
        .into_iter()
        .map(|(id, name, channel)| {
            format!(
                "{}\t{channel}\t{}",
                id.unwrap_or_default(),
                name.replace(['\t', '\n'], " ")
            )
        })
        .collect();
    new_string(&mut env, &lines.join("\n"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverForgetListeners<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Err(err) = unsafe { server(handle) }.forget_listeners() {
        throw(&mut env, err.to_string());
    }
}

/// Paired listeners, one per line: id, a tab, then the name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverPaired<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let lines: Vec<String> = unsafe { server(handle) }
        .paired()
        .into_iter()
        .map(|p| format!("{}\t{}", p.id, p.name.replace(['\t', '\n'], " ")))
        .collect();
    new_string(&mut env, &lines.join("\n"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverForgetPaired<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    id: JString<'l>,
) {
    let result = string(&mut env, &id).and_then(|id| {
        unsafe { server(handle) }
            .forget_paired(&id)
            .map_err(|e| e.to_string())
    });
    if let Err(err) = result {
        throw(&mut env, err);
    }
}

/// The radios on offer, one per line: id (not 0), a tab, the name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverSetRadios<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lines: JString<'l>,
) {
    match string(&mut env, &lines) {
        Ok(lines) => {
            let radios: Vec<Radio> = lines
                .lines()
                .filter_map(|line| {
                    let (id, name) = line.split_once('\t')?;
                    Some(Radio {
                        id: id.parse().ok()?,
                        name: name.to_string(),
                    })
                })
                .collect();
            unsafe { server(handle) }.set_radios(&radios);
        }
        Err(message) => throw(&mut env, message),
    }
}

/// The radios someone listens to now.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverRadiosListened<
    'l,
>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jintArray {
    let ids: Vec<jint> = unsafe { server(handle) }
        .radios_listened()
        .into_iter()
        .map(|id| id as jint)
        .collect();
    match env.new_int_array(ids.len() as i32) {
        Ok(array) => {
            let _ = env.set_int_array_region(&array, 0, &ids);
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverSetTrack<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    channel: jint,
    title: JString<'l>,
    artist: JString<'l>,
    album: JString<'l>,
    duration_ms: jlong,
    cover: JByteArray<'l>,
    lyrics: JString<'l>,
) {
    let result = (|| -> Result<TrackInfo, String> {
        let cover = if cover.is_null() {
            None
        } else {
            Some(env.convert_byte_array(&cover).map_err(|e| e.to_string())?)
        };
        Ok(TrackInfo {
            title: string(&mut env, &title)?,
            artist: optional_string(&mut env, &artist)?.unwrap_or_default(),
            album: optional_string(&mut env, &album)?.unwrap_or_default(),
            duration_ms: duration_ms.max(0) as u64,
            cover,
            lyrics: optional_string(&mut env, &lyrics)?,
        })
    })();
    match result {
        Ok(track) => unsafe { server(handle) }.set_track_on(channel as u32, track),
        Err(message) => throw(&mut env, message),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverSetPosition(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    channel: jint,
    pos_ms: jlong,
    playing: jboolean,
) {
    unsafe { server(handle) }.set_position_on(
        channel as u32,
        pos_ms.max(0) as u64,
        playing == JNI_TRUE,
    );
}

/// PCM as the player outputs it: 16-bit or float, little-endian, interleaved.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_serverPush<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    channel: jint,
    bytes: JByteArray<'l>,
    length: jint,
    rate: jint,
    channels: jint,
    float: jboolean,
) {
    let mut data = vec![0i8; length.max(0) as usize];
    if env.get_byte_array_region(&bytes, 0, &mut data).is_err() || rate <= 0 || channels <= 0 {
        return;
    }
    let server = unsafe { server(handle) };
    let bytes = data.iter().map(|&b| b as u8);
    let channels = channels as usize;
    if float == JNI_TRUE {
        let raw: Vec<u8> = bytes.collect();
        let samples: Vec<f32> = raw
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
            .collect();
        server.push_f32_on(channel as u32, &samples, channels, rate as u32);
    } else {
        let raw: Vec<u8> = bytes.collect();
        let samples: Vec<i16> = raw
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]))
            .collect();
        server.push_i16_on(channel as u32, &samples, channels, rate as u32);
    }
}

// ---------------------------------------------------------------- S'abonner

enum Slot {
    Pending(Box<Pending>),
    Session(Box<Session>),
    Closed,
}

/// The last things received, read by Kotlin after `clientRead` says so.
#[derive(Default)]
struct Received {
    track: TrackInfo,
    pos_ms: u64,
    rate: u32,
    playing: bool,
    error: String,
    /// The channel heard, and the radios on offer.
    channel: u32,
    radios: Vec<Radio>,
}

struct Client {
    slot: Mutex<Slot>,
    closer: Mutex<Option<Closer>>,
    broadcaster: Mutex<String>,
    received: Mutex<Received>,
}

impl Client {
    fn new(slot: Slot, closer: Option<Closer>, broadcaster: String) -> Self {
        Self {
            slot: Mutex::new(slot),
            closer: Mutex::new(closer),
            broadcaster: Mutex::new(broadcaster),
            received: Mutex::new(Received {
                playing: true,
                ..Default::default()
            }),
        }
    }
}

/// # Safety
/// `handle` must come from `clientConnect` and not be freed yet.
unsafe fn client<'a>(handle: jlong) -> &'a Client {
    unsafe { &*(handle as *const Client) }
}

/// Errors reach Kotlin as "kind|message" (see `client::Error::kind`).
fn error_text(err: &client::Error) -> String {
    format!("{}|{err}", err.kind())
}

/// Connects (blocking, a few seconds at most). Throws on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientConnect<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    dir: JString<'l>,
    host: JString<'l>,
    port: jint,
    name: JString<'l>,
    channel: jint,
) -> jlong {
    let result = (|| -> Result<Client, String> {
        let dir = PathBuf::from(string(&mut env, &dir)?);
        let host: IpAddr = string(&mut env, &host)?
            .parse()
            .map_err(|_| "io|bad address".to_string())?;
        let name = string(&mut env, &name)?;
        let addr = SocketAddr::new(host, port as u16);
        match client::connect(&dir, addr, &name, channel as u32).map_err(|e| error_text(&e))? {
            Connected::Ready(session) => {
                let closer = session.closer().ok();
                let broadcaster = session.name().to_string();
                Ok(Client::new(
                    Slot::Session(Box::new(session)),
                    closer,
                    broadcaster,
                ))
            }
            Connected::NeedsCode(pending) => {
                let closer = pending.closer().ok();
                Ok(Client::new(
                    Slot::Pending(Box::new(pending)),
                    closer,
                    String::new(),
                ))
            }
        }
    })();
    match result {
        Ok(client) => Box::into_raw(Box::new(client)) as jlong,
        Err(message) => {
            throw(&mut env, message);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientNeedsCode(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let slot = unsafe { client(handle) }.slot.lock().unwrap();
    if matches!(*slot, Slot::Pending(_)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Sends the code (blocking). Throws "wrong_code|…" and the like; the
/// connection is then spent and a new one must be made.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientPair<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    code: JString<'l>,
) {
    let result = (|| -> Result<(), String> {
        let code = string(&mut env, &code)?;
        let client = unsafe { client(handle) };
        let mut slot = client.slot.lock().unwrap();
        let Slot::Pending(pending) = std::mem::replace(&mut *slot, Slot::Closed) else {
            return Err("protocol|no code was asked".into());
        };
        let session = pending.pair(&code).map_err(|e| error_text(&e))?;
        *client.closer.lock().unwrap() = session.closer().ok();
        *client.broadcaster.lock().unwrap() = session.name().to_string();
        *slot = Slot::Session(Box::new(session));
        Ok(())
    })();
    if let Err(message) = result {
        throw(&mut env, message);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientName<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let name = unsafe { client(handle) }
        .broadcaster
        .lock()
        .unwrap()
        .clone();
    new_string(&mut env, &name)
}

/// Audio was received: that many samples are in `out`.
const _AUDIO: jint = 1;
pub const ENDED: jint = -1;
pub const TRACK: jint = -2;
pub const STATE: jint = -3;
pub const FAILED: jint = -4;
pub const CHANNELS: jint = -5;

/// Waits for the next message. Returns the number of 16-bit stereo samples
/// written to `out`, or one of ENDED, TRACK, STATE, FAILED, CHANNELS.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientRead<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    out: JShortArray<'l>,
) -> jint {
    let client = unsafe { client(handle) };
    let mut slot = client.slot.lock().unwrap();
    let Slot::Session(session) = &mut *slot else {
        return ENDED;
    };
    loop {
        let event = session.receive();
        let mut received = client.received.lock().unwrap();
        return match event {
            Ok(Event::Audio {
                pos_ms,
                rate,
                samples,
            }) => {
                let capacity = env.get_array_length(&out).unwrap_or(0).max(0) as usize;
                let n = samples.len().min(capacity) & !1;
                if n == 0 {
                    continue;
                }
                if env.set_short_array_region(&out, 0, &samples[..n]).is_err() {
                    return FAILED;
                }
                received.pos_ms = pos_ms;
                received.rate = rate;
                n as jint
            }
            Ok(Event::Track(track)) => {
                received.track = track;
                TRACK
            }
            Ok(Event::State { playing, pos_ms }) => {
                received.playing = playing;
                received.pos_ms = pos_ms;
                STATE
            }
            Ok(Event::Channels { current, radios }) => {
                received.channel = current;
                received.radios = radios;
                CHANNELS
            }
            Ok(Event::Ended) => {
                *slot = Slot::Closed;
                ENDED
            }
            Err(err) => {
                received.error = error_text(&err);
                *slot = Slot::Closed;
                FAILED
            }
        };
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientPosMs(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    unsafe { client(handle) }.received.lock().unwrap().pos_ms as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientRate(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    unsafe { client(handle) }.received.lock().unwrap().rate as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientPlaying(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if unsafe { client(handle) }.received.lock().unwrap().playing {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientError<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let error = unsafe { client(handle) }
        .received
        .lock()
        .unwrap()
        .error
        .clone();
    new_string(&mut env, &error)
}

/// The channel heard on the first line, then the radios on offer, one per
/// line: id, a tab, the name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientChannels<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let text = {
        let received = unsafe { client(handle) }.received.lock().unwrap();
        let mut text = received.channel.to_string();
        for radio in &received.radios {
            text.push('\n');
            text.push_str(&format!("{}\t{}", radio.id, radio.name.replace(['\t', '\n'], " ")));
        }
        text
    };
    new_string(&mut env, &text)
}

/// The track: title, artist, album, one per line, then the duration in ms.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientTrack<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let text = {
        let received = unsafe { client(handle) }.received.lock().unwrap();
        let t = &received.track;
        let line = |s: &str| s.replace(['\n', '\r'], " ");
        format!(
            "{}\n{}\n{}\n{}",
            line(&t.title),
            line(&t.artist),
            line(&t.album),
            t.duration_ms
        )
    };
    new_string(&mut env, &text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientCover<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    let cover = unsafe { client(handle) }
        .received
        .lock()
        .unwrap()
        .track
        .cover
        .clone();
    new_bytes(&mut env, cover.as_deref())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientLyrics<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let lyrics = unsafe { client(handle) }
        .received
        .lock()
        .unwrap()
        .track
        .lyrics
        .clone();
    match lyrics {
        Some(lyrics) => new_string(&mut env, &lyrics),
        None => JObject::null().into_raw(),
    }
}

/// Unblocks a waiting `clientRead` or `clientPair` from another thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(closer) = unsafe { client(handle) }.closer.lock().unwrap().as_ref() {
        closer.close();
    }
}

/// Frees the handle, once nothing uses it any more.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_cast_CastNative_clientFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Client) });
    }
}
