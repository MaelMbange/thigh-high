//! This device's key, and the devices it already paired with.
//!
//! Everything lives in one folder given by the app (its private data folder
//! on Android, the user's data folder on a PC).

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use ring::digest;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};

/// The certificate and key that identify this device, created once.
#[derive(Clone)]
pub struct Identity {
    pub cert: CertificateDer<'static>,
    key: Arc<Vec<u8>>,
    pub fingerprint: [u8; 32],
}

impl Identity {
    pub fn load_or_create(dir: &Path) -> io::Result<Self> {
        let cert_path = dir.join("identity.cert");
        let key_path = dir.join("identity.key");
        let (cert, key) = match (fs::read(&cert_path), fs::read(&key_path)) {
            (Ok(cert), Ok(key)) => (cert, key),
            _ => {
                let made = rcgen::generate_simple_self_signed(vec!["thigh-high".to_string()])
                    .map_err(io::Error::other)?;
                let cert = made.cert.der().to_vec();
                let key = made.signing_key.serialize_der();
                fs::create_dir_all(dir)?;
                write_private(&key_path, &key)?;
                fs::write(&cert_path, &cert)?;
                (cert, key)
            }
        };
        Ok(Self {
            fingerprint: fingerprint(&cert),
            cert: CertificateDer::from(cert),
            key: Arc::new(key),
        })
    }

    pub fn key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key.as_ref().clone()))
    }

    /// The first 16 hex digits of the fingerprint, announced on the network.
    pub fn short_id(&self) -> String {
        hex(&self.fingerprint[..8])
    }
}

pub fn fingerprint(cert: &[u8]) -> [u8; 32] {
    digest::digest(&digest::SHA256, cert)
        .as_ref()
        .try_into()
        .expect("SHA-256 is 32 bytes")
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(text.get(i..i + 2)?, 16).ok())
        .collect()
}

fn write_private(path: &Path, bytes: &[u8]) -> io::Result<()> {
    fs::write(path, bytes)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }
    Ok(())
}

/// One line per entry: two hex fields and an optional name.
struct Lines {
    path: PathBuf,
}

impl Lines {
    fn read(&self) -> Vec<(Vec<u8>, Vec<u8>, String)> {
        let Ok(text) = fs::read_to_string(&self.path) else {
            return Vec::new();
        };
        text.lines()
            .filter_map(|line| {
                let mut parts = line.splitn(3, ' ');
                let a = unhex(parts.next()?)?;
                let b = unhex(parts.next()?)?;
                Some((a, b, parts.next().unwrap_or("").to_string()))
            })
            .collect()
    }

    fn write(&self, entries: &[(Vec<u8>, Vec<u8>, String)]) -> io::Result<()> {
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir)?;
        }
        let text: String = entries
            .iter()
            .map(|(a, b, name)| {
                // A name can't break the format: keep it on one line.
                let name = name.replace(['\n', '\r'], " ");
                format!("{} {} {}\n", hex(a), hex(b), name)
            })
            .collect();
        write_private(&self.path, text.as_bytes())
    }
}

/// Broadcaster side: listeners that entered the code once.
pub struct TrustedListeners(Lines);

impl TrustedListeners {
    pub fn new(dir: &Path) -> Self {
        Self(Lines {
            path: dir.join("listeners.txt"),
        })
    }

    /// Only a hash of each token is kept, so the file alone lets no one in.
    pub fn is_trusted(&self, token: &[u8; 32]) -> bool {
        let hash = fingerprint(token);
        self.0.read().iter().any(|(h, _, _)| h[..] == hash[..])
    }

    pub fn add(&self, token: &[u8; 32], name: &str) -> io::Result<()> {
        let mut entries = self.0.read();
        entries.push((fingerprint(token).to_vec(), Vec::new(), name.to_string()));
        self.0.write(&entries)
    }

    pub fn forget_all(&self) -> io::Result<()> {
        self.0.write(&[])
    }
}

/// Listener side: broadcasters paired with, and the token each one gave.
pub struct KnownBroadcasters(Lines);

impl KnownBroadcasters {
    pub fn new(dir: &Path) -> Self {
        Self(Lines {
            path: dir.join("broadcasters.txt"),
        })
    }

    /// The token is only ever sent to the device whose key it was given by.
    pub fn token_for(&self, fingerprint: &[u8; 32]) -> Option<[u8; 32]> {
        self.0
            .read()
            .into_iter()
            .find(|(fp, _, _)| fp[..] == fingerprint[..])
            .and_then(|(_, token, _)| token.try_into().ok())
    }

    pub fn remember(&self, fingerprint: &[u8; 32], token: &[u8; 32], name: &str) -> io::Result<()> {
        let mut entries: Vec<_> = self
            .0
            .read()
            .into_iter()
            .filter(|(fp, _, _)| fp[..] != fingerprint[..])
            .collect();
        entries.push((fingerprint.to_vec(), token.to_vec(), name.to_string()));
        self.0.write(&entries)
    }

    pub fn forget(&self, fingerprint: &[u8; 32]) -> io::Result<()> {
        let entries: Vec<_> = self
            .0
            .read()
            .into_iter()
            .filter(|(fp, _, _)| fp[..] != fingerprint[..])
            .collect();
        self.0.write(&entries)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("thigh-high-cast-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn identity_is_kept() {
        let dir = temp_dir("identity");
        let a = Identity::load_or_create(&dir).unwrap();
        let b = Identity::load_or_create(&dir).unwrap();
        assert_eq!(a.fingerprint, b.fingerprint);
        assert_eq!(a.short_id().len(), 16);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn stores_tokens() {
        let dir = temp_dir("stores");
        let listeners = TrustedListeners::new(&dir);
        assert!(!listeners.is_trusted(&[1; 32]));
        listeners.add(&[1; 32], "Pixel\nevil").unwrap();
        assert!(listeners.is_trusted(&[1; 32]));
        assert!(!listeners.is_trusted(&[2; 32]));

        let known = KnownBroadcasters::new(&dir);
        known.remember(&[9; 32], &[3; 32], "PC").unwrap();
        known.remember(&[9; 32], &[4; 32], "PC").unwrap();
        assert_eq!(known.token_for(&[9; 32]), Some([4; 32]));
        assert_eq!(known.token_for(&[8; 32]), None);
        known.forget(&[9; 32]).unwrap();
        assert_eq!(known.token_for(&[9; 32]), None);
        let _ = fs::remove_dir_all(dir);
    }
}
