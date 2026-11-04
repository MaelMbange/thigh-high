//! Broadcasting on the local network: one device plays ("Diffuser"), others
//! listen to it live like a radio ("S'abonner").
//!
//! - Devices find each other with mDNS (DNS-SD service [`SERVICE_TYPE`]):
//!   NsdManager on Android, [`discovery`] on a PC.
//! - Each listener has its own TLS 1.3 connection (unicast, not IP
//!   multicast). New listeners type a 6-digit code shown on the broadcaster,
//!   unless it is open; a paired listener is remembered.
//! - Only what is playing is sent: 16-bit stereo audio, the title, the cover
//!   and the lyrics. No file, no library, and no way to ask for either.

mod identity;
pub mod lyrics;
mod pairing;
mod tls;
pub mod wire;

pub mod client;
pub mod server;

#[cfg(feature = "mdns")]
pub mod discovery;

pub use client::{Connected, Event, Pending, Session};
pub use identity::{Identity, Paired};
pub use server::Server;
pub use wire::TrackInfo;

/// The DNS-SD service type both apps announce and browse.
pub const SERVICE_TYPE: &str = "_thighhigh._tcp";

/// Keys of the announcement's TXT record.
pub mod txt {
    /// Protocol version ([`crate::wire::PROTOCOL`]).
    pub const VERSION: &str = "v";
    /// Start of the device's key fingerprint, to tell devices apart.
    pub const ID: &str = "id";
    /// "1" when no code is needed.
    pub const OPEN: &str = "open";
}

#[cfg(test)]
mod tests;
