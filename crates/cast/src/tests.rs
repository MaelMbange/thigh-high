//! End-to-end tests over the loopback interface.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use crate::client::{self, Connected, Error, Event, Session};
use crate::server::Server;
use crate::wire::{LIVE, Radio, TrackInfo};

fn temp_dir(name: &str) -> PathBuf {
    let dir =
        std::env::temp_dir().join(format!("thigh-high-cast-e2e-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

fn addr(server: &Server) -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], server.port()))
}

/// The session, past the list of channels that always comes first.
fn ready(connected: Connected) -> Session {
    match connected {
        Connected::Ready(mut session) => {
            channels(&mut session);
            session
        }
        Connected::NeedsCode(_) => panic!("asked for a code"),
    }
}

fn channels(session: &mut Session) -> (u32, Vec<Radio>) {
    match session.receive().unwrap() {
        Event::Channels { current, radios } => (current, radios),
        other => panic!("expected the channels, got {other:?}"),
    }
}

/// The next event that is not audio.
fn next_news(session: &mut Session) -> Event {
    loop {
        match session.receive().unwrap() {
            Event::Audio { .. } => continue,
            event => return event,
        }
    }
}

/// Waits until the server counts `n` listeners.
fn wait_listeners(server: &Server, n: usize) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while server.listeners().len() != n {
        assert!(
            Instant::now() < deadline,
            "listeners: {:?}",
            server.listeners()
        );
        std::thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn open_broadcast_streams_track_and_audio() {
    let dir = temp_dir("open");
    let server = Server::start(&dir.join("pc"), "PC", true).unwrap();
    let track = TrackInfo {
        title: "Titre".into(),
        artist: "Artiste".into(),
        lyrics: Some("[00:00.50] bonjour".into()),
        ..Default::default()
    };
    server.set_track(track.clone());
    server.set_position(1_000, true);

    let mut session = ready(client::connect(&dir.join("phone"), addr(&server), "Phone", LIVE).unwrap());
    assert_eq!(session.name(), "PC");
    assert_eq!(session.fingerprint(), server.fingerprint());
    assert_eq!(session.receive().unwrap(), Event::Track(track));
    assert_eq!(
        session.receive().unwrap(),
        Event::State {
            playing: true,
            pos_ms: 1_000
        }
    );
    wait_listeners(&server, 1);
    assert_eq!(server.listeners(), vec!["Phone".to_string()]);

    server.push_f32(&[0.5, -0.5, 1.0, 2.0], 2, 48_000);
    assert_eq!(
        session.receive().unwrap(),
        Event::Audio {
            pos_ms: 1_000,
            rate: 48_000,
            samples: vec![16383, -16383, 32767, 32767],
        }
    );

    server.stop();
    // Anything still queued may come first; the end is always announced.
    loop {
        match session.receive() {
            Ok(Event::Ended) => break,
            Ok(_) => continue,
            Err(err) => panic!("no goodbye: {err}"),
        }
    }
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn code_is_asked_once_then_remembered() {
    let dir = temp_dir("code");
    let server = Server::start(&dir.join("pc"), "PC", false).unwrap();
    let phone = dir.join("phone");

    let Connected::NeedsCode(pending) = client::connect(&phone, addr(&server), "Phone", LIVE).unwrap()
    else {
        panic!("no code asked");
    };
    let wrong = if server.code() == "000000" {
        "111111"
    } else {
        "000000"
    };
    assert!(matches!(pending.pair(wrong), Err(Error::WrongCode)));

    let Connected::NeedsCode(pending) = client::connect(&phone, addr(&server), "Phone", LIVE).unwrap()
    else {
        panic!("no code asked");
    };
    let code = server.code();
    let spaced = format!("{} {}", &code[..3], &code[3..]);
    let mut session = pending.pair(&spaced).unwrap();
    channels(&mut session);
    assert!(matches!(session.receive().unwrap(), Event::State { .. }));
    drop(session);

    // Paired: the next connection goes straight in.
    let session = ready(client::connect(&phone, addr(&server), "Phone", LIVE).unwrap());
    drop(session);

    // Another device still needs the code.
    assert!(matches!(
        client::connect(&dir.join("tablet"), addr(&server), "Tablet", LIVE).unwrap(),
        Connected::NeedsCode(_)
    ));

    // Once forgotten, the phone is asked again.
    server.forget_listeners().unwrap();
    assert!(matches!(
        client::connect(&phone, addr(&server), "Phone", LIVE).unwrap(),
        Connected::NeedsCode(_)
    ));
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn one_paired_listener_is_forgotten() {
    let dir = temp_dir("forget-one");
    let server = Server::start(&dir.join("pc"), "PC", false).unwrap();
    let phone = dir.join("phone");
    let tablet = dir.join("tablet");
    for (device, name) in [(&phone, "Phone"), (&tablet, "Tablet")] {
        let Connected::NeedsCode(pending) = client::connect(device, addr(&server), name, LIVE).unwrap()
        else {
            panic!("no code asked");
        };
        drop(pending.pair(&server.code()).unwrap());
    }
    let paired = server.paired();
    assert_eq!(
        paired.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(),
        ["Phone", "Tablet"]
    );

    // Forgetting the phone while it listens lets it go.
    let mut session = ready(client::connect(&phone, addr(&server), "Phone", LIVE).unwrap());
    wait_listeners(&server, 1);
    server.forget_paired(&paired[0].id).unwrap();
    loop {
        match session.receive() {
            Ok(Event::Ended) | Err(_) => break,
            Ok(_) => continue,
        }
    }
    assert!(matches!(
        client::connect(&phone, addr(&server), "Phone", LIVE).unwrap(),
        Connected::NeedsCode(_)
    ));
    drop(ready(
        client::connect(&tablet, addr(&server), "Tablet", LIVE).unwrap(),
    ));
    assert_eq!(server.paired().len(), 1);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn closing_lets_go_of_listeners_without_code() {
    let dir = temp_dir("closing");
    let server = Server::start(&dir.join("pc"), "PC", false).unwrap();
    let phone = dir.join("phone");
    let Connected::NeedsCode(pending) = client::connect(&phone, addr(&server), "Phone", LIVE).unwrap()
    else {
        panic!("no code asked");
    };
    let mut paired = pending.pair(&server.code()).unwrap();
    server.set_open(true);
    let mut guest = ready(client::connect(&dir.join("guest"), addr(&server), "Guest", LIVE).unwrap());
    wait_listeners(&server, 2);
    let listening = server.listening();
    assert!(
        listening
            .iter()
            .any(|(id, name, channel)| id.is_some() && name == "Phone" && *channel == LIVE)
    );
    assert!(
        listening
            .iter()
            .any(|(id, name, _)| id.is_none() && name == "Guest")
    );

    // Open to code: the guest goes, the paired phone stays.
    server.set_open(false);
    loop {
        match guest.receive() {
            Ok(Event::Ended) | Err(_) => break,
            Ok(_) => continue,
        }
    }
    wait_listeners(&server, 1);
    assert_eq!(server.listeners(), ["Phone"]);
    assert!(paired.receive().is_ok());

    // Forgetting everyone, closed: the phone goes too.
    server.forget_listeners().unwrap();
    wait_listeners(&server, 0);
    assert!(server.paired().is_empty());
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn wrong_codes_change_the_code() {
    let dir = temp_dir("wrong");
    let server = Server::start(&dir.join("pc"), "PC", false).unwrap();
    let first = server.code();
    let wrong = if first == "000000" {
        "111111"
    } else {
        "000000"
    };
    let mut results = Vec::new();
    for _ in 0..3 {
        let Connected::NeedsCode(pending) =
            client::connect(&dir.join("phone"), addr(&server), "Phone", LIVE).unwrap()
        else {
            panic!("no code asked");
        };
        results.push(
            pending
                .pair(wrong)
                .err()
                .expect("wrong code accepted")
                .kind(),
        );
    }
    assert_eq!(results, ["wrong_code", "wrong_code", "code_changed"]);
    assert_ne!(server.code(), first);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn the_newest_track_reaches_late_listeners() {
    let dir = temp_dir("late");
    let server = Server::start(&dir.join("pc"), "PC", true).unwrap();
    let mut first = ready(client::connect(&dir.join("a"), addr(&server), "A", LIVE).unwrap());
    assert!(matches!(first.receive().unwrap(), Event::State { .. }));
    wait_listeners(&server, 1);

    let track = TrackInfo {
        title: "Deuxième".into(),
        ..Default::default()
    };
    server.set_track(track.clone());
    assert_eq!(first.receive().unwrap(), Event::Track(track.clone()));

    let mut late = ready(client::connect(&dir.join("b"), addr(&server), "B", LIVE).unwrap());
    assert_eq!(late.receive().unwrap(), Event::Track(track));
    let _ = std::fs::remove_dir_all(dir);
}

fn radio(id: u32, name: &str) -> Radio {
    Radio {
        id,
        name: name.into(),
    }
}

#[test]
fn radios_play_apart_from_live() {
    let dir = temp_dir("radios");
    let server = Server::start(&dir.join("pc"), "PC", true).unwrap();
    server.set_radios(&[radio(1, "Rock"), radio(2, "Calme")]);
    let live_track = TrackInfo {
        title: "En direct".into(),
        ..Default::default()
    };
    let rock_track = TrackInfo {
        title: "Rock 1".into(),
        ..Default::default()
    };
    server.set_track(live_track.clone());
    server.set_track_on(1, rock_track.clone());
    server.set_position_on(1, 2_000, true);
    assert!(server.radios_listened().is_empty());

    let Connected::Ready(mut rock) =
        client::connect(&dir.join("a"), addr(&server), "A", 1).unwrap()
    else {
        panic!("asked for a code");
    };
    assert_eq!(channels(&mut rock), (1, vec![radio(1, "Rock"), radio(2, "Calme")]));
    assert_eq!(rock.receive().unwrap(), Event::Track(rock_track));
    assert_eq!(
        rock.receive().unwrap(),
        Event::State {
            playing: true,
            pos_ms: 2_000
        }
    );
    let mut live = ready(client::connect(&dir.join("b"), addr(&server), "B", LIVE).unwrap());
    assert_eq!(live.receive().unwrap(), Event::Track(live_track.clone()));
    wait_listeners(&server, 2);
    assert_eq!(server.radios_listened(), vec![1]);

    // Each one hears only its channel.
    server.push_i16(&[1, 1], 2, 48_000);
    server.push_i16_on(1, &[7, 7], 2, 44_100);
    server.push_i16_on(2, &[9, 9], 2, 44_100);
    assert!(matches!(live.receive().unwrap(), Event::State { .. }));
    assert_eq!(
        live.receive().unwrap(),
        Event::Audio {
            pos_ms: 0,
            rate: 48_000,
            samples: vec![1, 1]
        }
    );
    assert_eq!(
        rock.receive().unwrap(),
        Event::Audio {
            pos_ms: 2_000,
            rate: 44_100,
            samples: vec![7, 7]
        }
    );

    // An unknown radio is heard as live.
    let Connected::Ready(mut lost) =
        client::connect(&dir.join("c"), addr(&server), "C", 42).unwrap()
    else {
        panic!("asked for a code");
    };
    assert_eq!(channels(&mut lost).0, LIVE);
    drop(lost);

    // Taking the radio off sends its listener back to live.
    server.set_radios(&[radio(2, "Calme")]);
    assert_eq!(next_news(&mut rock), Event::Channels {
        current: LIVE,
        radios: vec![radio(2, "Calme")]
    });
    assert_eq!(next_news(&mut rock), Event::Track(live_track));
    assert!(matches!(next_news(&mut rock), Event::State { .. }));
    assert_eq!(next_news(&mut live), Event::Channels {
        current: LIVE,
        radios: vec![radio(2, "Calme")]
    });
    assert!(server.radios_listened().is_empty());
    let _ = std::fs::remove_dir_all(dir);
}
