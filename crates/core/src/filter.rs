//! Narrowing the library down to one artist, genre or album.

use std::collections::BTreeMap;

use crate::library::Track;

/// Shown for tracks whose file has no value for the field.
pub const UNKNOWN: &str = "Unknown";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Field {
    Artist,
    Genre,
    Album,
}

impl Field {
    pub const ALL: [Field; 3] = [Field::Artist, Field::Genre, Field::Album];

    pub fn label(self) -> &'static str {
        match self {
            Field::Artist => "Artist",
            Field::Genre => "Genre",
            Field::Album => "Album",
        }
    }

    /// The track's value for this field, or [`UNKNOWN`].
    pub fn value(self, track: &Track) -> &str {
        let value = match self {
            Field::Artist => &track.artist,
            Field::Genre => &track.genre,
            Field::Album => &track.album,
        };
        value
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .unwrap_or(UNKNOWN)
    }

    pub fn next(self) -> Self {
        match self {
            Field::Artist => Field::Genre,
            Field::Genre => Field::Album,
            Field::Album => Field::Artist,
        }
    }

    pub fn previous(self) -> Self {
        self.next().next()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Filter {
    pub field: Field,
    pub value: String,
}

impl Filter {
    pub fn matches(&self, track: &Track) -> bool {
        self.field.value(track).eq_ignore_ascii_case(&self.value)
    }
}

/// Every value of `field` in the library with its track count, alphabetically,
/// with "Unknown" last.
pub fn values(tracks: &[Track], field: Field) -> Vec<(String, usize)> {
    let mut counts: BTreeMap<String, (String, usize)> = BTreeMap::new();
    for track in tracks {
        let value = field.value(track);
        // Group case-insensitively, keeping the first spelling seen.
        let entry = counts
            .entry(value.to_lowercase())
            .or_insert_with(|| (value.to_string(), 0));
        entry.1 += 1;
    }
    let (mut known, unknown): (Vec<_>, Vec<_>) = counts
        .into_values()
        .partition(|(value, _)| value != UNKNOWN);
    known.extend(unknown);
    known
}

/// Indices of the tracks that pass `filter` (all of them when there is none).
pub fn visible(tracks: &[Track], filter: Option<&Filter>) -> Vec<usize> {
    tracks
        .iter()
        .enumerate()
        .filter(|(_, track)| filter.is_none_or(|f| f.matches(track)))
        .map(|(i, _)| i)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(title: &str, artist: Option<&str>, genre: Option<&str>) -> Track {
        Track {
            title: title.into(),
            artist: artist.map(Into::into),
            album: None,
            genre: genre.map(Into::into),
            duration: None,
            path: title.into(),
        }
    }

    #[test]
    fn groups_values_and_filters_tracks() {
        let tracks = vec![
            track("a", Some("Daft Punk"), Some("Electro")),
            track("b", Some("daft punk"), None),
            track("c", Some("Aya"), Some("Pop")),
            track("d", None, Some("Pop")),
        ];
        assert_eq!(
            values(&tracks, Field::Artist),
            vec![
                ("Aya".to_string(), 1),
                ("Daft Punk".to_string(), 2),
                (UNKNOWN.to_string(), 1)
            ]
        );
        let daft = Filter {
            field: Field::Artist,
            value: "Daft Punk".into(),
        };
        assert_eq!(visible(&tracks, Some(&daft)), vec![0, 1]);
        let unknown_genre = Filter {
            field: Field::Genre,
            value: UNKNOWN.into(),
        };
        assert_eq!(visible(&tracks, Some(&unknown_genre)), vec![1]);
        assert_eq!(visible(&tracks, None), vec![0, 1, 2, 3]);
    }
}
