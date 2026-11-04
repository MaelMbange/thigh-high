//! "S'abonner": listens to a broadcasting device, like a radio.
//!
//! Received audio is only handed to the app for playing: nothing is stored.

use std::fmt;
use std::io;
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::time::Duration;

use rustls::{ClientConnection, StreamOwned};

use crate::identity::{self, KnownBroadcasters};
use crate::pairing;
use crate::wire::{self, Message, PROTOCOL, Radio, Reject, TrackInfo};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
/// The broadcaster sends something at least every 2 s.
const READ_TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug)]
pub enum Error {
    Io(io::Error),
    WrongCode,
    /// Too many wrong codes: the broadcaster shows a new one.
    CodeChanged,
    /// The broadcaster already has as many listeners as it accepts.
    Full,
    /// Not the same version of thigh-high, or not thigh-high at all.
    Protocol,
}

impl Error {
    /// A short stable name, for the Android app to pick its message.
    pub fn kind(&self) -> &'static str {
        match self {
            Error::Io(_) => "io",
            Error::WrongCode => "wrong_code",
            Error::CodeChanged => "code_changed",
            Error::Full => "full",
            Error::Protocol => "protocol",
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Io(err) => write!(f, "connection failed: {err}"),
            Error::WrongCode => write!(f, "wrong code"),
            Error::CodeChanged => write!(f, "too many wrong codes, a new code is shown"),
            Error::Full => write!(f, "the broadcaster has too many listeners"),
            Error::Protocol => write!(f, "this device runs another version of thigh-high"),
        }
    }
}

impl std::error::Error for Error {}

impl From<io::Error> for Error {
    fn from(err: io::Error) -> Self {
        Error::Io(err)
    }
}

impl From<Reject> for Error {
    fn from(why: Reject) -> Self {
        match why {
            Reject::WrongCode => Error::WrongCode,
            Reject::CodeChanged => Error::CodeChanged,
            Reject::Full => Error::Full,
            Reject::Protocol => Error::Protocol,
        }
    }
}

type Tls = StreamOwned<ClientConnection, TcpStream>;

/// What came out of connecting.
pub enum Connected {
    Ready(Session),
    /// The broadcaster asks for its code: call [`Pending::pair`].
    NeedsCode(Pending),
}

/// Connects to a broadcaster, to hear `channel` ([`crate::wire::LIVE`] or
/// one of its radios). `dir` holds the tokens of earlier pairings.
pub fn connect(dir: &Path, addr: SocketAddr, name: &str, channel: u32) -> Result<Connected, Error> {
    let stream = TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT)?;
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(READ_TIMEOUT))?;
    stream.set_write_timeout(Some(READ_TIMEOUT))?;
    let config = crate::tls::client_config().map_err(io::Error::other)?;
    let conn =
        ClientConnection::new(config, crate::tls::server_name()).map_err(io::Error::other)?;
    let mut tls = StreamOwned::new(conn, stream);
    while tls.conn.is_handshaking() {
        tls.conn.complete_io(&mut tls.sock)?;
    }
    let cert = tls
        .conn
        .peer_certificates()
        .and_then(|certs| certs.first())
        .ok_or(Error::Protocol)?;
    let fingerprint = identity::fingerprint(cert);

    let known = KnownBroadcasters::new(dir);
    let token = known.token_for(&fingerprint);
    wire::write(
        &mut tls,
        &Message::Hello {
            protocol: PROTOCOL,
            name: name.to_string(),
            token,
            channel,
        },
    )?;
    match wire::read(&mut tls)? {
        Message::Accepted { name, token } => {
            if let Some(token) = token {
                known.remember(&fingerprint, &token, &name)?;
            }
            Ok(Connected::Ready(Session::new(tls, name, fingerprint)))
        }
        Message::NeedCode => {
            if token.is_some() {
                // The broadcaster forgot this device: the old token is useless.
                let _ = known.forget(&fingerprint);
            }
            Ok(Connected::NeedsCode(Pending {
                tls,
                fingerprint,
                dir: dir.to_path_buf(),
            }))
        }
        Message::Rejected(why) => Err(why.into()),
        _ => Err(Error::Protocol),
    }
}

/// Connected, waiting for the user to type the broadcaster's code.
pub struct Pending {
    tls: Tls,
    fingerprint: [u8; 32],
    dir: PathBuf,
}

impl Pending {
    pub fn pair(mut self, code: &str) -> Result<Session, Error> {
        let (exchange, ours) = pairing::start(code);
        wire::write(&mut self.tls, &Message::Pair(ours))?;
        let (theirs, their_confirm) = match wire::read(&mut self.tls)? {
            Message::PairReply { spake, confirm } => (spake, confirm),
            Message::Rejected(why) => return Err(why.into()),
            _ => return Err(Error::Protocol),
        };
        let key = exchange.finish(&theirs).ok_or(Error::Protocol)?;
        let exported = self
            .tls
            .conn
            .export_keying_material([0u8; 32], pairing::EXPORTER_LABEL, None)
            .map_err(io::Error::other)?;
        let expected = pairing::confirm(&key, &exported, pairing::BROADCASTER);
        if !pairing::same(&their_confirm, &expected) {
            // Wrong code, or someone in the middle: prove nothing, but ask
            // the broadcaster whether it now shows a new code.
            let _ = wire::write(&mut self.tls, &Message::Confirm([0; 32]));
            return Err(match wire::read(&mut self.tls) {
                Ok(Message::Rejected(Reject::CodeChanged)) => Error::CodeChanged,
                _ => Error::WrongCode,
            });
        }
        let ours = pairing::confirm(&key, &exported, pairing::LISTENER);
        wire::write(&mut self.tls, &Message::Confirm(ours))?;
        match wire::read(&mut self.tls)? {
            Message::Accepted { name, token } => {
                if let Some(token) = token {
                    KnownBroadcasters::new(&self.dir).remember(&self.fingerprint, &token, &name)?;
                }
                Ok(Session::new(self.tls, name, self.fingerprint))
            }
            Message::Rejected(why) => Err(why.into()),
            _ => Err(Error::Protocol),
        }
    }

    /// Lets another thread give up while the user is typing the code.
    pub fn closer(&self) -> io::Result<Closer> {
        Ok(Closer(self.tls.sock.try_clone()?))
    }
}

/// What the broadcaster sends.
#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    Track(TrackInfo),
    /// Interleaved 16-bit stereo audio starting `pos_ms` into the track.
    Audio {
        pos_ms: u64,
        rate: u32,
        samples: Vec<i16>,
    },
    State {
        playing: bool,
        pos_ms: u64,
    },
    /// The radios on offer and the channel heard: sent first, then on
    /// every change.
    Channels { current: u32, radios: Vec<Radio> },
    /// The broadcast ended.
    Ended,
}

pub struct Session {
    tls: Tls,
    name: String,
    fingerprint: [u8; 32],
}

impl Session {
    fn new(tls: Tls, name: String, fingerprint: [u8; 32]) -> Self {
        Self {
            tls,
            name,
            fingerprint,
        }
    }

    /// The broadcaster's name.
    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn fingerprint(&self) -> [u8; 32] {
        self.fingerprint
    }

    /// Waits for the next message from the broadcaster.
    pub fn receive(&mut self) -> Result<Event, Error> {
        Ok(match wire::read(&mut self.tls)? {
            Message::Track(track) => Event::Track(track),
            Message::Audio {
                pos_ms,
                rate,
                samples,
            } => Event::Audio {
                pos_ms,
                rate,
                samples,
            },
            Message::State { playing, pos_ms } => Event::State { playing, pos_ms },
            Message::Channels { current, radios } => Event::Channels { current, radios },
            Message::Bye => Event::Ended,
            _ => return Err(Error::Protocol),
        })
    }

    /// Lets another thread end the session while `receive` waits.
    pub fn closer(&self) -> io::Result<Closer> {
        Ok(Closer(self.tls.sock.try_clone()?))
    }
}

pub struct Closer(TcpStream);

impl Closer {
    pub fn close(&self) {
        let _ = self.0.shutdown(std::net::Shutdown::Both);
    }
}
