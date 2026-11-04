//! The pairing code, checked with SPAKE2.
//!
//! A 6-digit code is too short to be sent or hashed as is: anyone who saw
//! the exchange could try the million codes offline. SPAKE2 turns it into a
//! shared key in a way that gives an attacker a single guess per attempt,
//! and the broadcaster changes its code after a few wrong ones. Both sides
//! then prove they got the same key *for this TLS connection*, so a device
//! sitting in the middle can't relay the pairing.

use ring::hmac;
use ring::rand::{SecureRandom, SystemRandom};
use spake2::{Ed25519Group, Identity, Password, Spake2};

/// What both sides mix into the exchange, so it can't be reused elsewhere.
const CONTEXT: &[u8] = b"thigh-high cast pairing v1";
/// Label of the value both sides derive from their TLS session.
pub const EXPORTER_LABEL: &[u8] = b"EXPORTER-thigh-high-pairing";

pub fn random<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    SystemRandom::new()
        .fill(&mut out)
        .expect("the system has a random source");
    out
}

/// A new 6-digit code, as shown on the broadcaster.
pub fn new_code() -> String {
    let n = u32::from_be_bytes(random::<4>()) % 1_000_000;
    format!("{n:06}")
}

/// Keeps only the digits the user typed ("123 456" → "123456").
pub fn normalize(code: &str) -> String {
    code.chars().filter(char::is_ascii_digit).collect()
}

pub struct Exchange(Spake2<Ed25519Group>);

/// Starts the exchange: the message to send to the other side.
pub fn start(code: &str) -> (Exchange, Vec<u8>) {
    let (state, message) = Spake2::<Ed25519Group>::start_symmetric(
        &Password::new(normalize(code).as_bytes()),
        &Identity::new(CONTEXT),
    );
    (Exchange(state), message)
}

impl Exchange {
    /// The shared key, equal on both sides only if both used the same code.
    pub fn finish(self, their_message: &[u8]) -> Option<Vec<u8>> {
        self.0.finish(their_message).ok()
    }
}

/// Proof that `role` holds `key` for the TLS session `exported`.
pub fn confirm(key: &[u8], exported: &[u8], role: &[u8]) -> [u8; 32] {
    let key = hmac::Key::new(hmac::HMAC_SHA256, key);
    let mut ctx = hmac::Context::with_key(&key);
    ctx.update(role);
    ctx.update(exported);
    ctx.sign()
        .as_ref()
        .try_into()
        .expect("HMAC-SHA256 is 32 bytes")
}

/// Compares two proofs in constant time.
pub fn same(a: &[u8; 32], b: &[u8; 32]) -> bool {
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

pub const BROADCASTER: &[u8] = b"broadcaster";
pub const LISTENER: &[u8] = b"listener";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_code_same_key() {
        let (a, ma) = start("123456");
        let (b, mb) = start("123 456");
        assert_eq!(a.finish(&mb), b.finish(&ma));
    }

    #[test]
    fn different_code_different_key() {
        let (a, ma) = start("123456");
        let (b, mb) = start("654321");
        assert_ne!(a.finish(&mb), b.finish(&ma));
    }

    #[test]
    fn codes_have_six_digits() {
        for _ in 0..50 {
            let code = new_code();
            assert_eq!(code.len(), 6);
            assert!(code.chars().all(|c| c.is_ascii_digit()));
        }
    }
}
