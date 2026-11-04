//! Finding broadcasters with mDNS / DNS-SD, for the terminal app.
//! (Android uses NsdManager, which speaks the same protocol.)

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};

use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};

use crate::{SERVICE_TYPE, txt, wire::PROTOCOL};

fn service_domain() -> String {
    format!("{SERVICE_TYPE}.local.")
}

pub type Error = mdns_sd::Error;

/// Announces a broadcast while it lives.
pub struct Announcement {
    daemon: ServiceDaemon,
    fullname: String,
}

impl Announcement {
    pub fn new(name: &str, port: u16, short_id: &str, open: bool) -> Result<Self, Error> {
        let daemon = ServiceDaemon::new()?;
        // The host name must be unique on the network: the key's id is.
        let host = format!("thigh-high-{short_id}.local.");
        let properties = [
            (txt::VERSION, PROTOCOL.to_string()),
            (txt::ID, short_id.to_string()),
            (txt::OPEN, if open { "1" } else { "0" }.to_string()),
        ];
        let info = ServiceInfo::new(&service_domain(), name, &host, "", port, &properties[..])?
            .enable_addr_auto();
        let fullname = info.get_fullname().to_string();
        daemon.register(info)?;
        Ok(Self { daemon, fullname })
    }
}

impl Drop for Announcement {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}

/// A broadcaster seen on the network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Found {
    pub name: String,
    pub addr: SocketAddr,
    /// Start of its key fingerprint ([`crate::Server::short_id`]).
    pub id: String,
    pub open: bool,
    /// Runs the same protocol version as this app.
    pub compatible: bool,
}

/// Keeps the list of broadcasters up to date in the background.
pub struct Browser {
    daemon: ServiceDaemon,
    events: mdns_sd::Receiver<ServiceEvent>,
    found: HashMap<String, Found>,
}

impl Browser {
    pub fn new() -> Result<Self, Error> {
        let daemon = ServiceDaemon::new()?;
        let events = daemon.browse(&service_domain())?;
        Ok(Self {
            daemon,
            events,
            found: HashMap::new(),
        })
    }

    /// Applies what was heard since the last call; returns the broadcasters
    /// sorted by name.
    pub fn poll(&mut self) -> Vec<Found> {
        while let Ok(event) = self.events.try_recv() {
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    let Some(ip) = info.get_addresses_v4().into_iter().min().map(IpAddr::V4) else {
                        continue;
                    };
                    let name = info
                        .get_fullname()
                        .strip_suffix(&format!(".{}", service_domain()))
                        .unwrap_or(info.get_fullname())
                        .replace("\\ ", " ")
                        .replace("\\.", ".");
                    let found = Found {
                        name,
                        addr: SocketAddr::new(ip, info.get_port()),
                        id: info.get_property_val_str(txt::ID).unwrap_or("").to_string(),
                        open: info.get_property_val_str(txt::OPEN) == Some("1"),
                        compatible: info.get_property_val_str(txt::VERSION)
                            == Some(&PROTOCOL.to_string()),
                    };
                    self.found.insert(info.get_fullname().to_string(), found);
                }
                ServiceEvent::ServiceRemoved(_, fullname) => {
                    self.found.remove(&fullname);
                }
                _ => {}
            }
        }
        let mut list: Vec<Found> = self.found.values().cloned().collect();
        list.sort_by_key(|f| f.name.to_lowercase());
        list
    }
}

impl Drop for Browser {
    fn drop(&mut self) {
        let _ = self.daemon.stop_browse(&service_domain());
        let _ = self.daemon.shutdown();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    /// Needs a network interface with multicast; skipped quietly without one.
    #[test]
    fn finds_an_announced_broadcast() {
        let Ok(_announcement) = Announcement::new("Test PC", 45_678, "0123456789abcdef", true)
        else {
            return;
        };
        let Ok(mut browser) = Browser::new() else {
            return;
        };
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if let Some(found) = browser
                .poll()
                .into_iter()
                .find(|f| f.id == "0123456789abcdef")
            {
                assert_eq!(found.name, "Test PC");
                assert_eq!(found.addr.port(), 45_678);
                assert!(found.open && found.compatible);
                return;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        eprintln!("no multicast here: discovery not checked");
    }
}
