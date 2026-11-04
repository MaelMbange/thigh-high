//! The messages exchanged inside the encrypted connection.
//!
//! Each message is one byte of kind, a 4-byte big-endian length and the
//! payload. Nothing here can ask the broadcaster for a file or for its
//! library: the listener only says hello and pairs, then just receives.

use std::io::{self, Read, Write};

/// Bumped when the messages change in a way older apps can't read.
pub const PROTOCOL: u16 = 2;

/// The channel of what the broadcaster itself plays; radios have other ids.
pub const LIVE: u32 = 0;

/// Larger messages are refused (a cover is resized well below this).
const MAX_MESSAGE: usize = 2 * 1024 * 1024;

/// What the broadcaster shares about the music being played.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TrackInfo {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    /// The cover as an image file (JPEG or PNG), if any.
    pub cover: Option<Vec<u8>>,
    /// Synced lyrics (LRC) or plain lyrics, if any.
    pub lyrics: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Message {
    // Listener → broadcaster.
    Hello {
        protocol: u16,
        name: String,
        /// Given at a previous pairing, so the code isn't asked again.
        token: Option<[u8; 32]>,
        /// What to listen to: [`LIVE`] or one of the radios.
        channel: u32,
    },
    Pair(Vec<u8>),
    Confirm([u8; 32]),

    // Broadcaster → listener.
    NeedCode,
    PairReply {
        spake: Vec<u8>,
        confirm: [u8; 32],
    },
    Accepted {
        /// The broadcaster's name.
        name: String,
        /// To keep, so the next connection needs no code.
        token: Option<[u8; 32]>,
    },
    Rejected(Reject),
    Track(TrackInfo),
    /// Interleaved 16-bit stereo samples starting `pos_ms` into the track.
    Audio {
        pos_ms: u64,
        rate: u32,
        samples: Vec<i16>,
    },
    State {
        playing: bool,
        pos_ms: u64,
    },
    /// The broadcast is over.
    Bye,
    /// The radios on offer, besides [`LIVE`], and the channel this listener
    /// hears (a radio that is taken off sends its listeners back to live).
    Channels {
        current: u32,
        radios: Vec<Radio>,
    },
}

/// A radio: one of the broadcaster's playlists, played on its own.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Radio {
    pub id: u32,
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reject {
    WrongCode,
    /// Too many wrong codes: a new one is shown on the broadcaster.
    CodeChanged,
    Protocol,
    Full,
}

impl Reject {
    fn byte(self) -> u8 {
        match self {
            Reject::WrongCode => 1,
            Reject::CodeChanged => 2,
            Reject::Protocol => 3,
            Reject::Full => 4,
        }
    }

    fn from_byte(b: u8) -> Self {
        match b {
            1 => Reject::WrongCode,
            2 => Reject::CodeChanged,
            4 => Reject::Full,
            _ => Reject::Protocol,
        }
    }
}

fn invalid(what: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, what.to_string())
}

struct Out(Vec<u8>);

impl Out {
    fn u8(&mut self, v: u8) {
        self.0.push(v);
    }
    fn u16(&mut self, v: u16) {
        self.0.extend_from_slice(&v.to_be_bytes());
    }
    fn u32(&mut self, v: u32) {
        self.0.extend_from_slice(&v.to_be_bytes());
    }
    fn u64(&mut self, v: u64) {
        self.0.extend_from_slice(&v.to_be_bytes());
    }
    fn bytes(&mut self, v: &[u8]) {
        self.u32(v.len() as u32);
        self.0.extend_from_slice(v);
    }
    fn str(&mut self, v: &str) {
        self.bytes(v.as_bytes());
    }
    fn opt_bytes(&mut self, v: Option<&[u8]>) {
        match v {
            Some(v) => {
                self.u8(1);
                self.bytes(v);
            }
            None => self.u8(0),
        }
    }
}

struct In<'a>(&'a [u8]);

impl In<'_> {
    fn take(&mut self, n: usize) -> io::Result<&[u8]> {
        if self.0.len() < n {
            return Err(invalid("message too short"));
        }
        let (head, rest) = self.0.split_at(n);
        self.0 = rest;
        Ok(head)
    }
    fn u8(&mut self) -> io::Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> io::Result<u16> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> io::Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn u64(&mut self) -> io::Result<u64> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn arr32(&mut self) -> io::Result<[u8; 32]> {
        Ok(self.take(32)?.try_into().unwrap())
    }
    fn bytes(&mut self) -> io::Result<Vec<u8>> {
        let n = self.u32()? as usize;
        Ok(self.take(n)?.to_vec())
    }
    fn str(&mut self) -> io::Result<String> {
        String::from_utf8(self.bytes()?).map_err(|_| invalid("text is not UTF-8"))
    }
    fn opt_bytes(&mut self) -> io::Result<Option<Vec<u8>>> {
        Ok(match self.u8()? {
            0 => None,
            _ => Some(self.bytes()?),
        })
    }
}

impl Message {
    fn kind(&self) -> u8 {
        match self {
            Message::Hello { .. } => 1,
            Message::Pair(_) => 2,
            Message::Confirm(_) => 3,
            Message::NeedCode => 10,
            Message::PairReply { .. } => 11,
            Message::Accepted { .. } => 12,
            Message::Rejected(_) => 13,
            Message::Track(_) => 20,
            Message::Audio { .. } => 21,
            Message::State { .. } => 22,
            Message::Bye => 23,
            Message::Channels { .. } => 24,
        }
    }

    /// The message as sent on the wire, header included.
    pub fn encode(&self) -> Vec<u8> {
        let mut o = Out(Vec::new());
        match self {
            Message::Hello {
                protocol,
                name,
                token,
                channel,
            } => {
                o.u16(*protocol);
                o.str(name);
                o.opt_bytes(token.as_ref().map(|t| &t[..]));
                o.u32(*channel);
            }
            Message::Pair(spake) => o.bytes(spake),
            Message::Confirm(mac) => o.0.extend_from_slice(mac),
            Message::NeedCode | Message::Bye => {}
            Message::PairReply { spake, confirm } => {
                o.bytes(spake);
                o.0.extend_from_slice(confirm);
            }
            Message::Accepted { name, token } => {
                o.str(name);
                o.opt_bytes(token.as_ref().map(|t| &t[..]));
            }
            Message::Rejected(why) => o.u8(why.byte()),
            Message::Track(t) => {
                o.str(&t.title);
                o.str(&t.artist);
                o.str(&t.album);
                o.u64(t.duration_ms);
                o.opt_bytes(t.cover.as_deref());
                o.opt_bytes(t.lyrics.as_deref().map(str::as_bytes));
            }
            Message::Audio {
                pos_ms,
                rate,
                samples,
            } => {
                o.u64(*pos_ms);
                o.u32(*rate);
                o.0.reserve(samples.len() * 2);
                for s in samples {
                    o.0.extend_from_slice(&s.to_le_bytes());
                }
            }
            Message::State { playing, pos_ms } => {
                o.u8(*playing as u8);
                o.u64(*pos_ms);
            }
            Message::Channels { current, radios } => {
                o.u32(*current);
                o.u32(radios.len() as u32);
                for radio in radios {
                    o.u32(radio.id);
                    o.str(&radio.name);
                }
            }
        }
        let mut framed = Vec::with_capacity(o.0.len() + 5);
        framed.push(self.kind());
        framed.extend_from_slice(&(o.0.len() as u32).to_be_bytes());
        framed.extend_from_slice(&o.0);
        framed
    }

    fn decode(kind: u8, payload: &[u8]) -> io::Result<Self> {
        let mut i = In(payload);
        let message = match kind {
            1 => Message::Hello {
                protocol: i.u16()?,
                name: i.str()?,
                token: match i.opt_bytes()? {
                    Some(t) => Some(t.try_into().map_err(|_| invalid("bad token"))?),
                    None => None,
                },
                channel: i.u32()?,
            },
            2 => Message::Pair(i.bytes()?),
            3 => Message::Confirm(i.arr32()?),
            10 => Message::NeedCode,
            11 => Message::PairReply {
                spake: i.bytes()?,
                confirm: i.arr32()?,
            },
            12 => Message::Accepted {
                name: i.str()?,
                token: match i.opt_bytes()? {
                    Some(t) => Some(t.try_into().map_err(|_| invalid("bad token"))?),
                    None => None,
                },
            },
            13 => Message::Rejected(Reject::from_byte(i.u8()?)),
            20 => Message::Track(TrackInfo {
                title: i.str()?,
                artist: i.str()?,
                album: i.str()?,
                duration_ms: i.u64()?,
                cover: i.opt_bytes()?,
                lyrics: match i.opt_bytes()? {
                    Some(b) => Some(String::from_utf8(b).map_err(|_| invalid("lyrics"))?),
                    None => None,
                },
            }),
            21 => {
                let pos_ms = i.u64()?;
                let rate = i.u32()?;
                let samples =
                    i.0.chunks_exact(2)
                        .map(|b| i16::from_le_bytes([b[0], b[1]]))
                        .collect();
                Message::Audio {
                    pos_ms,
                    rate,
                    samples,
                }
            }
            22 => Message::State {
                playing: i.u8()? != 0,
                pos_ms: i.u64()?,
            },
            23 => Message::Bye,
            24 => {
                let current = i.u32()?;
                let count = i.u32()? as usize;
                let mut radios = Vec::with_capacity(count.min(256));
                for _ in 0..count {
                    radios.push(Radio {
                        id: i.u32()?,
                        name: i.str()?,
                    });
                }
                Message::Channels { current, radios }
            }
            _ => return Err(invalid("unknown message")),
        };
        Ok(message)
    }
}

pub fn write(stream: &mut impl Write, message: &Message) -> io::Result<()> {
    stream.write_all(&message.encode())?;
    stream.flush()
}

pub fn read(stream: &mut impl Read) -> io::Result<Message> {
    let mut header = [0u8; 5];
    stream.read_exact(&mut header)?;
    let len = u32::from_be_bytes(header[1..].try_into().unwrap()) as usize;
    if len > MAX_MESSAGE {
        return Err(invalid("message too large"));
    }
    let mut payload = vec![0u8; len];
    stream.read_exact(&mut payload)?;
    Message::decode(header[0], &payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips() {
        let messages = [
            Message::Hello {
                protocol: PROTOCOL,
                name: "Téléphone".into(),
                token: Some([7; 32]),
                channel: 3,
            },
            Message::Channels {
                current: 3,
                radios: vec![Radio {
                    id: 3,
                    name: "Été".into(),
                }],
            },
            Message::Accepted {
                name: "PC".into(),
                token: None,
            },
            Message::Track(TrackInfo {
                title: "Titre".into(),
                artist: "Artiste".into(),
                album: String::new(),
                duration_ms: 123_456,
                cover: Some(vec![1, 2, 3]),
                lyrics: Some("[00:01.00] la".into()),
            }),
            Message::Audio {
                pos_ms: 42,
                rate: 48_000,
                samples: vec![-32768, 0, 32767, 5],
            },
            Message::State {
                playing: true,
                pos_ms: 9,
            },
            Message::Rejected(Reject::WrongCode),
            Message::Bye,
        ];
        for message in messages {
            let bytes = message.encode();
            let back = read(&mut &bytes[..]).unwrap();
            assert_eq!(back, message);
        }
    }

    #[test]
    fn refuses_huge_messages() {
        let mut bytes = vec![21u8];
        bytes.extend_from_slice(&u32::MAX.to_be_bytes());
        assert!(read(&mut &bytes[..]).is_err());
    }
}
