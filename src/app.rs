use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread;

use color_eyre::Result;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::widgets::ListState;

use crate::cover::{self, Palette};
use crate::download;
use crate::filter::{self, Field, Filter};
use crate::genre::{self, Job};
use crate::library::{self, Track};
use crate::player::Player;
use crate::tools::{self, Tools};
use crate::visualizer::{Mode, Spectrum};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Focus {
    Library,
    UrlInput,
    /// The filter picker is open over the library.
    Filter,
}

/// The filter picker: one tab per field, listing that field's values.
pub struct FilterPicker {
    pub field: Field,
    pub values: Vec<(String, usize)>,
    pub state: ListState,
}

/// Messages from background threads to the UI thread.
pub enum AppEvent {
    ToolsStatus(String),
    ToolsReady(Tools),
    ToolsFailed(String),
    DownloadTitle {
        id: usize,
        title: String,
    },
    DownloadProgress {
        id: usize,
        percent: f64,
    },
    DownloadDone {
        id: usize,
        path: PathBuf,
    },
    DownloadFailed {
        id: usize,
        error: String,
    },
    /// The genre model finished a track: Some(genre), None when nothing stood out.
    GenreDetected {
        path: PathBuf,
        result: Result<Option<String>, String>,
    },
}

pub enum ToolsState {
    Preparing(String),
    Ready(Tools),
    Failed(String),
}

pub struct Download {
    pub id: usize,
    pub label: String,
    pub percent: f64,
}

pub struct App {
    pub should_quit: bool,
    pub focus: Focus,
    pub url_input: String,
    pub music_dir: PathBuf,
    pub tracks: Vec<Track>,
    /// Indices into `tracks` shown in the library, after the filter.
    pub visible: Vec<usize>,
    pub filter: Option<Filter>,
    pub picker: Option<FilterPicker>,
    /// Selection within `visible`.
    pub list_state: ListState,
    pub now_playing: Option<PathBuf>,
    /// None when no audio device could be opened.
    pub player: Option<Player>,
    pub spectrum: Spectrum,
    pub visual: Mode,
    /// Colors of the playing track's cover, used across the UI and animations.
    pub palette: Palette,
    pub status: String,
    pub tools: ToolsState,
    pub downloads: Vec<Download>,
    /// URLs submitted before the tools were ready.
    queued: Vec<String>,
    next_download_id: usize,
    /// Sends tracks to the genre analysis thread, once ffmpeg is ready.
    genre_jobs: Option<Sender<Job>>,
    /// Tracks sent for genre analysis and not reported back yet.
    analyzing: HashSet<PathBuf>,
    /// Tracks already analysed this session, so `g` doesn't redo them.
    analyzed: HashSet<PathBuf>,
    /// Detected genres waiting to be written: the file is playing, and
    /// rewriting its tags under ffmpeg could make playback stutter.
    unsaved_genres: HashMap<PathBuf, String>,
    tx: Sender<AppEvent>,
    rx: Receiver<AppEvent>,
}

impl App {
    pub fn new() -> Result<Self> {
        let music_dir = library::music_dir()?;
        let tracks = library::scan(&music_dir)?;
        let mut list_state = ListState::default();
        if !tracks.is_empty() {
            list_state.select(Some(0));
        }
        let (tx, rx) = mpsc::channel();
        let (player, status) = match Player::new() {
            Ok(player) => (
                Some(player),
                format!("Music folder: {}", music_dir.display()),
            ),
            Err(err) => (None, format!("No audio output available: {err}")),
        };
        let app = Self {
            should_quit: false,
            focus: Focus::Library,
            url_input: String::new(),
            status,
            music_dir,
            visible: (0..tracks.len()).collect(),
            tracks,
            filter: None,
            picker: None,
            list_state,
            now_playing: None,
            player,
            spectrum: Spectrum::new(),
            visual: Mode::default(),
            palette: Palette::default(),
            tools: ToolsState::Preparing("Looking for yt-dlp and ffmpeg…".into()),
            downloads: Vec::new(),
            queued: Vec::new(),
            next_download_id: 0,
            genre_jobs: None,
            analyzing: HashSet::new(),
            analyzed: HashSet::new(),
            unsaved_genres: HashMap::new(),
            tx,
            rx,
        };
        app.prepare_tools();
        Ok(app)
    }

    fn prepare_tools(&self) {
        let tx = self.tx.clone();
        thread::spawn(move || {
            let report = |msg: String| {
                let _ = tx.send(AppEvent::ToolsStatus(msg));
            };
            let event = match tools::ensure(&report) {
                Ok(tools) => AppEvent::ToolsReady(tools),
                Err(err) => AppEvent::ToolsFailed(err.to_string()),
            };
            let _ = tx.send(event);
        });
    }

    /// Applies everything the background threads reported since the last frame.
    pub fn process_events(&mut self) {
        self.advance_when_finished();

        while let Ok(event) = self.rx.try_recv() {
            match event {
                AppEvent::ToolsStatus(msg) => self.tools = ToolsState::Preparing(msg),
                AppEvent::ToolsReady(tools) => {
                    self.genre_jobs =
                        Some(genre::spawn_worker(tools.ffmpeg.clone(), self.tx.clone()));
                    self.tools = ToolsState::Ready(tools);
                    for url in std::mem::take(&mut self.queued) {
                        self.start_download(url);
                    }
                }
                AppEvent::ToolsFailed(err) => {
                    self.tools = ToolsState::Failed(err);
                    self.queued.clear();
                }
                AppEvent::DownloadTitle { id, title } => {
                    if let Some(d) = self.download_mut(id) {
                        d.label = title;
                    }
                }
                AppEvent::DownloadProgress { id, percent } => {
                    if let Some(d) = self.download_mut(id) {
                        d.percent = percent;
                    }
                }
                AppEvent::DownloadDone { id, path } => {
                    let label = self.finish_download(id);
                    self.refresh_library();
                    self.select_path(&path);
                    self.status = format!("Downloaded: {label}");
                    self.analyze_genre(&path);
                }
                AppEvent::DownloadFailed { id, error } => {
                    let label = self.finish_download(id);
                    self.status = format!("Download failed ({label}): {error}");
                }
                AppEvent::GenreDetected { path, result } => self.genre_detected(path, result),
            }
        }
    }

    pub fn handle_key(&mut self, key: KeyEvent) {
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            self.should_quit = true;
            return;
        }
        match self.focus {
            Focus::UrlInput => self.handle_input_key(key),
            Focus::Library => self.handle_library_key(key),
            Focus::Filter => self.handle_picker_key(key),
        }
    }

    fn handle_input_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Esc => self.focus = Focus::Library,
            KeyCode::Enter => self.submit_url(),
            KeyCode::Backspace => {
                self.url_input.pop();
            }
            KeyCode::Char(c) => self.url_input.push(c),
            _ => {}
        }
    }

    fn handle_library_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Char('q') => self.should_quit = true,
            KeyCode::Char('/') | KeyCode::Char('i') => self.focus = Focus::UrlInput,
            KeyCode::Down | KeyCode::Char('j') => self.list_state.select_next(),
            KeyCode::Up | KeyCode::Char('k') => self.list_state.select_previous(),
            KeyCode::Enter => self.play_selected(),
            KeyCode::Char('r') => self.refresh_library(),
            KeyCode::Char(' ') => self.toggle_pause(),
            KeyCode::Char('n') => self.skip(1),
            KeyCode::Char('p') => self.skip(-1),
            KeyCode::Char('s') => self.stop(),
            KeyCode::Right | KeyCode::Char('l') => self.seek_by(5),
            KeyCode::Left | KeyCode::Char('h') => self.seek_by(-5),
            KeyCode::Char('+') | KeyCode::Char('=') => self.change_volume(0.05),
            KeyCode::Char('-') => self.change_volume(-0.05),
            KeyCode::Char('b') => self.cycle_bass(),
            KeyCode::Char('f') => self.open_picker(),
            KeyCode::Char('x') => self.set_filter(None),
            KeyCode::Char('g') => self.analyze_missing_genres(),
            KeyCode::Char('v') => {
                self.visual = self.visual.next();
                self.status = format!("Animation: {}", self.visual.label());
            }
            _ => {}
        }
    }

    fn open_picker(&mut self) {
        let field = self.filter.as_ref().map_or(Field::Artist, |f| f.field);
        self.picker = Some(self.picker_for(field));
        self.focus = Focus::Filter;
    }

    fn picker_for(&self, field: Field) -> FilterPicker {
        let values = filter::values(&self.tracks, field);
        // Start on the active value when reopening the picker.
        let selected = self
            .filter
            .as_ref()
            .filter(|f| f.field == field)
            .and_then(|f| {
                values
                    .iter()
                    .position(|(v, _)| v.eq_ignore_ascii_case(&f.value))
            })
            .unwrap_or(0);
        let mut state = ListState::default();
        if !values.is_empty() {
            state.select(Some(selected));
        }
        FilterPicker {
            field,
            values,
            state,
        }
    }

    fn handle_picker_key(&mut self, key: KeyEvent) {
        let Some(picker) = &mut self.picker else {
            self.focus = Focus::Library;
            return;
        };
        match key.code {
            KeyCode::Esc | KeyCode::Char('q') | KeyCode::Char('f') => self.close_picker(),
            KeyCode::Down | KeyCode::Char('j') => picker.state.select_next(),
            KeyCode::Up | KeyCode::Char('k') => picker.state.select_previous(),
            KeyCode::Right | KeyCode::Char('l') | KeyCode::Tab => {
                let field = picker.field.next();
                self.picker = Some(self.picker_for(field));
            }
            KeyCode::Left | KeyCode::Char('h') | KeyCode::BackTab => {
                let field = picker.field.previous();
                self.picker = Some(self.picker_for(field));
            }
            KeyCode::Char('x') => {
                self.set_filter(None);
                self.close_picker();
            }
            KeyCode::Enter => {
                let chosen = picker
                    .state
                    .selected()
                    .and_then(|i| picker.values.get(i))
                    .map(|(value, _)| Filter {
                        field: picker.field,
                        value: value.clone(),
                    });
                if let Some(chosen) = chosen {
                    self.set_filter(Some(chosen));
                }
                self.close_picker();
            }
            _ => {}
        }
    }

    fn close_picker(&mut self) {
        self.picker = None;
        self.focus = Focus::Library;
    }

    fn set_filter(&mut self, filter: Option<Filter>) {
        self.status = match &filter {
            Some(f) => format!("Showing {}: {} (x to clear)", f.field.label(), f.value),
            None => "Showing all music".into(),
        };
        self.filter = filter;
        self.update_visible();
        self.list_state
            .select((!self.visible.is_empty()).then_some(0));
    }

    fn update_visible(&mut self) {
        self.visible = filter::visible(&self.tracks, self.filter.as_ref());
    }

    fn submit_url(&mut self) {
        let url = self.url_input.trim().to_string();
        if url.is_empty() {
            return;
        }
        if !url.starts_with("http://") && !url.starts_with("https://") {
            self.status = "That doesn't look like a link (it should start with https://)".into();
            return;
        }
        self.url_input.clear();
        self.focus = Focus::Library;

        match &self.tools {
            ToolsState::Ready(_) => self.start_download(url),
            ToolsState::Preparing(_) => {
                self.queued.push(url);
                self.status = "Queued: the download starts once yt-dlp and ffmpeg are ready".into();
            }
            ToolsState::Failed(err) => {
                self.status = format!("Can't download: {err}");
            }
        }
    }

    fn start_download(&mut self, url: String) {
        let ToolsState::Ready(tools) = &self.tools else {
            return;
        };
        let id = self.next_download_id;
        self.next_download_id += 1;
        download::start(id, url.clone(), tools, &self.music_dir, self.tx.clone());
        self.downloads.push(Download {
            id,
            label: url,
            percent: 0.0,
        });
    }

    fn download_mut(&mut self, id: usize) -> Option<&mut Download> {
        self.downloads.iter_mut().find(|d| d.id == id)
    }

    /// Removes a finished download from the list and returns its label.
    fn finish_download(&mut self, id: usize) -> String {
        let index = self.downloads.iter().position(|d| d.id == id);
        index
            .map(|i| self.downloads.remove(i).label)
            .unwrap_or_default()
    }

    fn play_selected(&mut self) {
        if let Some(i) = self.list_state.selected() {
            self.play_index(i);
        }
    }

    /// Plays the track at position `index` of the (filtered) library list.
    fn play_index(&mut self, index: usize) {
        let Some(track) = self.visible.get(index).and_then(|&i| self.tracks.get(i)) else {
            return;
        };
        let Some(player) = &self.player else {
            self.status = "No audio output available".into();
            return;
        };
        let ToolsState::Ready(tools) = &self.tools else {
            self.status = "Still setting up ffmpeg, try again in a moment".into();
            return;
        };
        match player.play(&tools.ffmpeg, &track.path, track.duration) {
            Ok(()) => {
                self.palette = cover::cover_art(&track.path)
                    .and_then(|art| cover::palette(&art))
                    .unwrap_or_default();
                self.now_playing = Some(track.path.clone());
                self.status = format!("Playing: {}", track.title);
                self.list_state.select(Some(index));
                self.save_genres();
            }
            Err(err) => self.status = format!("Could not play {}: {err}", track.title),
        }
    }

    /// Position of the playing track in the (filtered) library list.
    fn playing_index(&self) -> Option<usize> {
        let playing = self.now_playing.as_ref()?;
        self.visible
            .iter()
            .position(|&i| &self.tracks[i].path == playing)
    }

    /// Moves `offset` tracks from the current one, wrapping around the list.
    fn skip(&mut self, offset: isize) {
        let len = self.visible.len() as isize;
        if len == 0 {
            return;
        }
        let next = match self.playing_index() {
            Some(i) => (i as isize + offset).rem_euclid(len) as usize,
            None => self.list_state.selected().unwrap_or(0),
        };
        self.play_index(next);
    }

    /// Plays the next track when the current one ends.
    fn advance_when_finished(&mut self) {
        let finished = self.player.as_ref().is_some_and(|p| p.is_finished());
        if finished && self.now_playing.is_some() {
            let last = self.playing_index() == Some(self.visible.len().saturating_sub(1));
            if last || self.visible.is_empty() {
                self.now_playing = None;
                self.status = "End of list".into();
                self.save_genres();
            } else {
                self.skip(1);
            }
        }
    }

    fn toggle_pause(&mut self) {
        if let Some(player) = &self.player
            && self.now_playing.is_some()
        {
            player.toggle_pause();
        }
    }

    fn stop(&mut self) {
        if let Some(player) = &self.player {
            player.stop();
        }
        self.now_playing = None;
        self.save_genres();
    }

    /// Stops playback and writes any genre still waiting on the playing file.
    pub fn shut_down(&mut self) {
        self.stop();
    }

    fn seek_by(&mut self, seconds: i64) {
        if let Some(player) = &self.player
            && self.now_playing.is_some()
        {
            player.seek_by(seconds);
        }
    }

    fn cycle_bass(&mut self) {
        if let Some(player) = &self.player {
            player.cycle_bass();
            self.status = match player.bass() {
                0 => "Bass boost: off".into(),
                db => format!("Bass boost: +{db} dB"),
            };
        }
    }

    fn change_volume(&mut self, delta: f32) {
        if let Some(player) = &self.player {
            player.change_volume(delta);
            self.status = format!("Volume: {:.0}%", player.volume() * 100.0);
        }
    }

    /// Queues a track for genre detection if its file has no genre yet.
    fn analyze_genre(&mut self, path: &Path) -> bool {
        let Some(jobs) = &self.genre_jobs else {
            return false;
        };
        let Some(track) = self.tracks.iter().find(|t| t.path == path) else {
            return false;
        };
        if track.genre.as_deref().is_some_and(|g| !g.trim().is_empty())
            || self.analyzing.contains(path)
            || self.analyzed.contains(path)
        {
            return false;
        }
        let job = Job {
            path: path.to_path_buf(),
            duration: track.duration,
        };
        if jobs.send(job).is_err() {
            return false;
        }
        self.analyzing.insert(path.to_path_buf());
        true
    }

    /// Queues every track that has no genre.
    fn analyze_missing_genres(&mut self) {
        if self.genre_jobs.is_none() {
            self.status = "Still setting up ffmpeg, try again in a moment".into();
            return;
        }
        let paths: Vec<PathBuf> = self.tracks.iter().map(|t| t.path.clone()).collect();
        let queued = paths.iter().filter(|p| self.analyze_genre(p)).count();
        self.status = if queued == 0 && self.analyzing.is_empty() {
            "Every track already has a genre".into()
        } else {
            format!("Detecting genres: {} left", self.analyzing.len())
        };
    }

    fn genre_detected(&mut self, path: PathBuf, result: Result<Option<String>, String>) {
        self.analyzing.remove(&path);
        self.analyzed.insert(path.clone());
        let Some(index) = self.tracks.iter().position(|t| t.path == path) else {
            return;
        };
        let title = self.tracks[index].title.clone();
        let left = match self.analyzing.len() {
            0 => String::new(),
            n => format!(" ({n} left)"),
        };
        match result {
            Ok(Some(genre)) => {
                self.status = format!("Genre of {title}: {genre}{left}");
                self.tracks[index].genre = Some(genre.clone());
                self.update_visible();
                self.unsaved_genres.insert(path, genre);
                self.save_genres();
            }
            Ok(None) => self.status = format!("No clear genre for {title}{left}"),
            Err(err) => self.status = format!("Could not detect the genre of {title}: {err}"),
        }
    }

    /// Writes detected genres into the files, except the one playing.
    fn save_genres(&mut self) {
        let playing = self.now_playing.clone();
        let ready: Vec<PathBuf> = self
            .unsaved_genres
            .keys()
            .filter(|p| Some(*p) != playing.as_ref())
            .cloned()
            .collect();
        for path in ready {
            if let Some(genre) = self.unsaved_genres.remove(&path)
                && let Err(err) = genre::save_genre(&path, &genre)
            {
                self.status = format!("Could not save the genre of {}: {err}", path.display());
            }
        }
    }

    pub fn refresh_library(&mut self) {
        match library::scan(&self.music_dir) {
            Ok(mut tracks) => {
                // Keep genres detected for a playing file that isn't saved yet.
                for track in &mut tracks {
                    if let Some(genre) = self.unsaved_genres.get(&track.path) {
                        track.genre = Some(genre.clone());
                    }
                }
                self.tracks = tracks;
                self.update_visible();
                if self.list_state.selected().is_none() && !self.visible.is_empty() {
                    self.list_state.select(Some(0));
                }
            }
            Err(err) => self.status = format!("Could not read music folder: {err}"),
        }
    }

    fn select_path(&mut self, path: &Path) {
        if let Some(i) = self
            .visible
            .iter()
            .position(|&i| self.tracks[i].path == path)
        {
            self.list_state.select(Some(i));
        }
    }

    pub fn current_track(&self) -> Option<&Track> {
        let playing = self.now_playing.as_ref()?;
        self.tracks.iter().find(|t| &t.path == playing)
    }
}
