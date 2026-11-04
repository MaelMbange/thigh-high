//! Reading the lyrics sent with a track, for the terminal listener.

/// Lyrics as sent by the broadcaster.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Lyrics {
    /// Timed lines (LRC), sorted by time in milliseconds.
    Synced(Vec<(u64, String)>),
    Plain(Vec<String>),
}

impl Lyrics {
    pub fn parse(text: &str) -> Option<Self> {
        let mut synced = Vec::new();
        let mut plain = Vec::new();
        for line in text.lines() {
            let mut rest = line.trim();
            let mut times = Vec::new();
            // A line may carry several timestamps: "[00:12.00][01:30.00] chorus".
            while let Some(after) = rest.strip_prefix('[') {
                let Some(end) = after.find(']') else { break };
                match timestamp(&after[..end]) {
                    Some(ms) => times.push(ms),
                    // Tags such as [ar:Artist] carry no lyric.
                    None if after[..end].contains(':') && times.is_empty() => {
                        rest = "";
                        break;
                    }
                    None => break,
                }
                rest = after[end + 1..].trim_start();
            }
            if times.is_empty() {
                if !rest.is_empty() || !plain.is_empty() {
                    plain.push(rest.to_string());
                }
            } else {
                synced.extend(times.into_iter().map(|t| (t, rest.to_string())));
            }
        }
        if !synced.is_empty() {
            synced.sort_by_key(|(t, _)| *t);
            Some(Lyrics::Synced(synced))
        } else {
            while plain.last().is_some_and(|l| l.is_empty()) {
                plain.pop();
            }
            (!plain.is_empty()).then_some(Lyrics::Plain(plain))
        }
    }

    /// Index of the line being sung at `pos_ms`, if any has started.
    pub fn current(&self, pos_ms: u64) -> Option<usize> {
        match self {
            Lyrics::Synced(lines) => lines.partition_point(|(t, _)| *t <= pos_ms).checked_sub(1),
            Lyrics::Plain(_) => None,
        }
    }
}

/// "mm:ss", "mm:ss.xx" or "mm:ss.xxx" in milliseconds.
fn timestamp(text: &str) -> Option<u64> {
    let (minutes, seconds) = text.split_once(':')?;
    let minutes: u64 = minutes.trim().parse().ok()?;
    let (whole, fraction) = seconds.split_once(['.', ':']).unwrap_or((seconds, ""));
    let whole: u64 = whole.trim().parse().ok()?;
    if whole >= 60 || !fraction.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let fraction_ms = match fraction.len() {
        0 => 0,
        1 => fraction.parse::<u64>().ok()? * 100,
        2 => fraction.parse::<u64>().ok()? * 10,
        _ => fraction[..3].parse::<u64>().ok()?,
    };
    Some((minutes * 60 + whole) * 1000 + fraction_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_lrc() {
        let lyrics =
            Lyrics::parse("[ar:Someone]\n[00:01.50] one\n[00:03.00][00:05.25]two\n").unwrap();
        assert_eq!(
            lyrics,
            Lyrics::Synced(vec![
                (1500, "one".into()),
                (3000, "two".into()),
                (5250, "two".into())
            ])
        );
        assert_eq!(lyrics.current(0), None);
        assert_eq!(lyrics.current(1500), Some(0));
        assert_eq!(lyrics.current(4000), Some(1));
        assert_eq!(lyrics.current(99_000), Some(2));
    }

    #[test]
    fn reads_plain_lyrics() {
        assert_eq!(
            Lyrics::parse("\nfirst\n\nsecond\n\n"),
            Some(Lyrics::Plain(vec![
                "first".into(),
                "".into(),
                "second".into()
            ]))
        );
        assert_eq!(Lyrics::parse("  \n"), None);
    }
}
