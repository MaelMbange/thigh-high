use std::time::Duration;

use ratatui::Frame;
use ratatui::layout::{Constraint, Flex, Layout, Margin, Position, Rect};
use ratatui::style::{Color, Modifier, Style, Stylize};
use ratatui::text::{Line, Span};
use ratatui::widgets::{
    Block, BorderType, Borders, Clear, LineGauge, List, ListItem, Paragraph, Tabs, Wrap,
};

use crate::app::{App, Focus, Repeat, ToolsState};
use crate::cast::Cast;
use crate::cover::Rgb;
use crate::filter::Field;
use crate::visualizer;

/// The playing track's cover palette as terminal colors, so the whole UI
/// takes on its mood: primary for frames and highlights, secondary for
/// titles and progress, tertiary for details.
#[derive(Clone, Copy)]
struct Colors {
    primary: Color,
    secondary: Color,
    tertiary: Color,
}

fn colors(app: &App) -> Colors {
    let rgb = |(r, g, b): Rgb| Color::Rgb(r, g, b);
    Colors {
        primary: rgb(app.palette.primary),
        secondary: rgb(app.palette.secondary),
        tertiary: rgb(app.palette.tertiary),
    }
}

pub fn draw(frame: &mut Frame, app: &mut App) {
    let help = help_line(app, colors(app));
    // The key list wraps onto a second row in narrow terminals.
    let help_height = if help.width() > frame.area().width as usize {
        2
    } else {
        1
    };
    let [input_area, body_area, status_area, help_area] = Layout::vertical([
        Constraint::Length(3),
        Constraint::Min(5),
        Constraint::Length(1),
        Constraint::Length(help_height),
    ])
    .areas(frame.area());

    let [library_area, player_area] =
        Layout::horizontal([Constraint::Percentage(40), Constraint::Percentage(60)])
            .areas(body_area);

    // Active downloads sit under the library and take no space when idle.
    let downloads_height = if app.downloads.is_empty() {
        0
    } else {
        app.downloads.len() as u16 + 2
    };
    let [library_area, downloads_area] =
        Layout::vertical([Constraint::Min(3), Constraint::Length(downloads_height)])
            .areas(library_area);

    draw_input(frame, app, input_area);
    draw_library(frame, app, library_area);
    draw_downloads(frame, app, downloads_area);
    draw_player(frame, app, player_area);

    frame.render_widget(Paragraph::new(app.status.as_str()).dark_gray(), status_area);
    frame.render_widget(Paragraph::new(help).wrap(Wrap { trim: true }), help_area);

    if app.confirm.is_some() {
        draw_confirm(frame, app);
    }
    if app.focus == Focus::Cast {
        draw_cast(frame, app);
    }
}

/// Groups digits by three: "123456" → "123 456".
fn spaced_code(code: &str) -> String {
    let (a, b) = code.split_at(code.len().min(3));
    format!("{a} {b}")
}

/// The broadcast dialog: choose, broadcast, find a device, type its code, listen.
fn draw_cast(frame: &mut Frame, app: &mut App) {
    let colors = colors(app);
    let [area] = Layout::horizontal([Constraint::Max(60)])
        .flex(Flex::Center)
        .areas(frame.area());
    let [area] = Layout::vertical([Constraint::Length(14)])
        .flex(Flex::Center)
        .areas(area);
    frame.render_widget(Clear, area);

    let title = match &app.cast {
        Some(Cast::Broadcasting(_)) => " Broadcasting ",
        Some(Cast::Listening(_)) => " Listening ",
        Some(Cast::Browsing(_) | Cast::Connecting { .. } | Cast::AskingCode(_)) => {
            " Listen to a device "
        }
        _ => " Local network ",
    };
    let block = Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(Style::new().fg(colors.primary))
        .title(Line::from(title).fg(colors.secondary).bold());
    let inner = block.inner(area).inner(Margin::new(1, 0));
    frame.render_widget(block, area);

    let dim = |text: String| Line::from(text).dark_gray();
    let button = |label: &str, selected: bool| {
        let span = Span::from(format!("  {label}  "));
        if selected {
            span.fg(Color::Black).bg(colors.primary).bold()
        } else {
            span.fg(colors.secondary)
        }
    };

    let Some(state) = &mut app.cast else {
        return;
    };
    let lines: Vec<Line> = match state {
        Cast::Menu { listen } => vec![
            Line::from(""),
            Line::from("Share your music with devices on this network,"),
            Line::from("or listen to one of them, live like a radio."),
            Line::from(""),
            Line::from(vec![
                button("Broadcast", !*listen),
                Span::from("    "),
                button("Listen", *listen),
            ]),
            Line::from(""),
            dim("Encrypted. Only the sound, cover and lyrics are sent:".into()),
            dim("no file can be downloaded.".into()),
        ],
        Cast::Broadcasting(broadcast) => {
            let server = &broadcast.server;
            let listeners = server.listeners();
            let mut lines = vec![
                Line::from(vec![
                    Span::from("Visible as "),
                    Span::from(broadcast.name.clone())
                        .fg(colors.secondary)
                        .bold(),
                    Span::from(format!("  (port {})", server.port())).dark_gray(),
                ]),
                Line::from(""),
            ];
            if server.is_open() {
                lines.push(Line::from(vec![
                    Span::from("Open: ").fg(colors.tertiary).bold(),
                    Span::from("anyone on this network can listen"),
                ]));
            } else {
                lines.push(Line::from(vec![
                    Span::from("Code for new devices: "),
                    Span::from(spaced_code(&server.code()))
                        .fg(colors.primary)
                        .bold(),
                ]));
            }
            lines.push(Line::from(""));
            lines.push(match listeners.len() {
                0 => dim("Nobody is listening yet".into()),
                n => Line::from(vec![
                    Span::from(format!("{n} listening: ")).fg(colors.tertiary),
                    Span::from(listeners.join(", ")),
                ]),
            });
            if app.now_playing.is_none() {
                lines.push(dim("Play a track: listeners hear what you hear.".into()));
            }
            if let Some(warning) = &broadcast.warning {
                lines.push(Line::from(warning.clone()).red());
            }
            lines
        }
        Cast::Browsing(browse) => {
            let [message_area, list_area] =
                Layout::vertical([Constraint::Length(2), Constraint::Min(1)]).areas(inner);
            let message = match (&browse.error, browse.found.is_empty()) {
                (Some(err), _) => Line::from(err.clone()).red(),
                (None, true) => dim("Searching for devices broadcasting on this network…".into()),
                (None, false) => Line::from("Choose a device:"),
            };
            frame.render_widget(
                Paragraph::new(message).wrap(Wrap { trim: true }),
                message_area,
            );
            let items: Vec<ListItem> = browse
                .found
                .iter()
                .map(|found| {
                    let detail = match (found.compatible, found.open) {
                        (false, _) => "  other version",
                        (true, true) => "  open",
                        (true, false) => "  code",
                    };
                    ListItem::new(Line::from(vec![
                        Span::from(found.name.clone()),
                        Span::from(detail).fg(colors.tertiary),
                    ]))
                })
                .collect();
            let list = List::new(items).highlight_style(
                Style::new()
                    .fg(Color::Black)
                    .bg(colors.primary)
                    .add_modifier(Modifier::BOLD),
            );
            frame.render_stateful_widget(list, list_area, &mut browse.state);
            return;
        }
        Cast::Connecting { name, .. } => vec![
            Line::from(""),
            Line::from(""),
            Line::from(format!("Connecting to {name}…")).centered(),
        ],
        Cast::AskingCode(ask) => {
            let typed: String = (0..6)
                .map(|i| ask.input.chars().nth(i).unwrap_or('_'))
                .collect();
            let mut lines = vec![
                Line::from(""),
                Line::from(vec![
                    Span::from("Type the code shown on "),
                    Span::from(ask.name.clone()).fg(colors.secondary).bold(),
                ])
                .centered(),
                Line::from(""),
                Line::from(spaced_code(&typed))
                    .fg(colors.primary)
                    .bold()
                    .centered(),
                Line::from(""),
                dim("It is only asked once for this device.".into()).centered(),
            ];
            if let Some(err) = &ask.error {
                lines.push(Line::from(""));
                lines.push(Line::from(err.clone()).red().centered());
            }
            lines
        }
        Cast::Listening(listen) => {
            let mut lines = vec![Line::from(vec![
                Span::from("From "),
                Span::from(listen.name.clone()).fg(colors.secondary).bold(),
                Span::from(if listen.playing { "" } else { "  (paused)" }).dark_gray(),
            ])];
            if !listen.radios.is_empty() {
                // Live, then the broadcaster's radios: the one heard stands out.
                let channels = std::iter::once((thigh_high_cast::LIVE, "Live".to_string()))
                    .chain(listen.radios.iter().map(|r| (r.id, r.name.clone())));
                let mut spans = Vec::new();
                for (id, name) in channels {
                    if !spans.is_empty() {
                        spans.push(Span::from(" · ").dark_gray());
                    }
                    spans.push(if id == listen.channel {
                        Span::from(format!("[{name}]")).fg(colors.primary).bold()
                    } else {
                        Span::from(name).dark_gray()
                    });
                }
                lines.push(Line::from(spans));
            }
            match &listen.track {
                Some(track) => {
                    lines.push(Line::from(track.title.clone()).fg(colors.primary).bold());
                    lines.push(Line::from(track.artist.clone()).fg(colors.tertiary));
                }
                None => lines.push(dim("Waiting for music…".into())),
            }
            lines.push(Line::from(""));
            lines.extend(lyric_lines(listen, colors));
            lines
        }
    };
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: true }), inner);
}

/// The line being sung, with the one before and after.
fn lyric_lines(listen: &crate::cast::Listen, colors: Colors) -> Vec<Line<'static>> {
    use thigh_high_cast::lyrics::Lyrics;
    match &listen.lyrics {
        Some(lyrics @ Lyrics::Synced(lines)) => {
            let current = lyrics.current(listen.heard_ms());
            let at = |i: Option<usize>| {
                i.and_then(|i| lines.get(i))
                    .map(|(_, text)| text.clone())
                    .unwrap_or_default()
            };
            vec![
                Line::from(at(current.and_then(|i| i.checked_sub(1))))
                    .dark_gray()
                    .centered(),
                Line::from(at(current))
                    .fg(colors.secondary)
                    .bold()
                    .centered(),
                Line::from(at(Some(current.map_or(0, |i| i + 1))))
                    .dark_gray()
                    .centered(),
            ]
        }
        Some(Lyrics::Plain(_)) => {
            vec![Line::from("Lyrics (not synced) on the broadcaster").dark_gray()]
        }
        None => Vec::new(),
    }
}

/// The "delete this track?" dialog, centered over everything.
fn draw_confirm(frame: &mut Frame, app: &App) {
    let Some(confirm) = &app.confirm else {
        return;
    };
    let colors = colors(app);
    let [area] = Layout::horizontal([Constraint::Max(56)])
        .flex(Flex::Center)
        .areas(frame.area());
    let [area] = Layout::vertical([Constraint::Length(7)])
        .flex(Flex::Center)
        .areas(area);
    frame.render_widget(Clear, area);

    let block = Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(Style::new().red())
        .title(Line::from(" Delete track ").red().bold());
    let inner = block.inner(area);
    frame.render_widget(block, area);

    let button = |label: &str, selected: bool, color: Color| {
        let span = Span::from(format!("  {label}  "));
        if selected {
            span.fg(Color::Black).bg(color).bold()
        } else {
            span.dark_gray()
        }
    };
    let text = vec![
        Line::from(vec![
            Span::from("Delete "),
            Span::from(confirm.title.as_str())
                .fg(colors.secondary)
                .bold(),
            Span::from("?"),
        ]),
        Line::from("The file is erased from your disk.").dark_gray(),
        Line::from(""),
        Line::from(vec![
            button("Yes", confirm.yes, Color::Red),
            Span::from("   "),
            button("No", !confirm.yes, colors.primary),
        ]),
    ];
    frame.render_widget(
        Paragraph::new(text).centered().wrap(Wrap { trim: true }),
        inner.inner(Margin::new(1, 0)),
    );
}

fn panel(title: impl std::fmt::Display, focused: bool, colors: Colors) -> Block<'static> {
    let border = if focused {
        Style::new().fg(colors.primary)
    } else {
        Style::new().dark_gray()
    };
    Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(border)
        .title(Line::from(format!(" {title} ")).fg(colors.secondary).bold())
}

fn draw_input(frame: &mut Frame, app: &App, area: Rect) {
    let focused = app.focus == Focus::UrlInput;
    let text = if app.url_input.is_empty() {
        match &app.tools {
            ToolsState::Preparing(msg) => Span::from(format!("Setting up: {msg}")).yellow(),
            ToolsState::Failed(err) => Span::from(format!("Downloader unavailable: {err}")).red(),
            ToolsState::Ready(_) if focused => Span::from(""),
            ToolsState::Ready(_) => Span::from("Paste a music URL here (press /)").dark_gray(),
        }
    } else {
        Span::from(app.url_input.as_str())
    };
    frame.render_widget(
        Paragraph::new(text).block(panel("Download", focused, colors(app))),
        area,
    );

    if focused {
        // Keep the cursor at the end of the text, inside the border.
        let x = area.x + 1 + app.url_input.chars().count() as u16;
        frame.set_cursor_position(Position::new(x.min(area.right() - 2), area.y + 1));
    }
}

fn draw_library(frame: &mut Frame, app: &mut App, area: Rect) {
    let focused = matches!(app.focus, Focus::Library | Focus::Filter);
    let title = match &app.filter {
        Some(filter) => format!(
            "Library · {}: {} ({})",
            filter.field.label(),
            filter.value,
            app.visible.len()
        ),
        None => format!("Library ({})", app.tracks.len()),
    };
    let block = panel(title, focused, colors(app));

    if app.visible.is_empty() {
        let message = if app.tracks.is_empty() {
            "No music yet. Paste a URL above to download some."
        } else {
            "No track matches this filter. Press x to clear it."
        };
        let empty = Paragraph::new(message)
            .dark_gray()
            .wrap(Wrap { trim: true })
            .block(block);
        frame.render_widget(empty, area);
        return;
    }

    let colors = colors(app);
    let items: Vec<ListItem> = app
        .visible
        .iter()
        .map(|&i| {
            let track = &app.tracks[i];
            let playing = app.now_playing.as_ref() == Some(&track.path);
            let mut spans = if playing {
                vec![
                    Span::from("♪ ").fg(colors.tertiary),
                    Span::from(track.title.as_str()).fg(colors.secondary),
                ]
            } else {
                vec![Span::from(format!("  {}", track.title))]
            };
            // The artist, dimmed, helps when browsing or filtering.
            if let Some(artist) = &track.artist {
                spans.push(Span::from(format!("  {artist}")).dark_gray());
            }
            ListItem::new(Line::from(spans))
        })
        .collect();

    let list = List::new(items).block(block).highlight_style(
        Style::new()
            .fg(Color::Black)
            .bg(colors.primary)
            .add_modifier(Modifier::BOLD),
    );
    frame.render_stateful_widget(list, area, &mut app.list_state);

    if app.picker.is_some() {
        draw_picker(frame, app, area);
    }
}

/// The filter picker, drawn over the library: a tab per field and its values.
fn draw_picker(frame: &mut Frame, app: &mut App, library: Rect) {
    let colors = colors(app);
    let Some(picker) = &mut app.picker else {
        return;
    };
    let [area] = Layout::horizontal([Constraint::Max(48)])
        .flex(Flex::Center)
        .areas(library.inner(Margin::new(1, 1)));
    let [area] = Layout::vertical([Constraint::Max(18)])
        .flex(Flex::Center)
        .areas(area);
    frame.render_widget(Clear, area);

    let block = Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(Style::new().fg(colors.secondary))
        .title(Line::from(" Filter ").fg(colors.primary).bold())
        .title_bottom(Line::from(" ←→ field  Enter apply  x clear  Esc close ").dark_gray());
    let inner = block.inner(area);
    frame.render_widget(block, area);

    let [tabs_area, list_area] =
        Layout::vertical([Constraint::Length(2), Constraint::Min(1)]).areas(inner);
    let selected_tab = Field::ALL
        .iter()
        .position(|&f| f == picker.field)
        .unwrap_or(0);
    let tabs = Tabs::new(Field::ALL.iter().map(|f| f.label()))
        .select(selected_tab)
        .style(Style::new().dark_gray())
        .highlight_style(Style::new().fg(colors.primary).bold().underlined())
        .divider(Span::from(" │ ").dark_gray());
    frame.render_widget(tabs, tabs_area);

    let items: Vec<ListItem> = picker
        .values
        .iter()
        .map(|(value, count)| {
            ListItem::new(Line::from(vec![
                Span::from(value.as_str()),
                Span::from(format!("  {count}")).fg(colors.tertiary),
            ]))
        })
        .collect();
    let list = List::new(items).highlight_style(
        Style::new()
            .fg(Color::Black)
            .bg(colors.secondary)
            .add_modifier(Modifier::BOLD),
    );
    frame.render_stateful_widget(list, list_area, &mut picker.state);
}

fn draw_downloads(frame: &mut Frame, app: &App, area: Rect) {
    if app.downloads.is_empty() {
        return;
    }
    let block = panel("Downloading", false, colors(app));
    let inner = block.inner(area);
    frame.render_widget(block, area);

    let rows = Layout::vertical(vec![Constraint::Length(1); app.downloads.len()]).split(inner);
    for (download, row) in app.downloads.iter().zip(rows.iter()) {
        let gauge = LineGauge::default()
            .ratio((download.percent / 100.0).clamp(0.0, 1.0))
            .label(format!("{:>3.0}% {}", download.percent, download.label))
            .filled_style(Style::new().fg(colors(app).secondary));
        frame.render_widget(gauge, *row);
    }
}

fn draw_player(frame: &mut Frame, app: &mut App, area: Rect) {
    let colors = colors(app);
    // The showcase panel always wears the cover's main color.
    let title = match (app.broadcasting(), app.listening()) {
        (Some(b), _) => match b.server.listeners().len() {
            0 => "Now playing · broadcasting".to_string(),
            n => format!("Now playing · broadcasting to {n}"),
        },
        (_, Some(listen)) => format!("Listening to {}", listen.name),
        _ => "Now playing".to_string(),
    };
    let block = panel(title, false, colors).border_style(Style::new().fg(colors.primary));
    let inner = block.inner(area);
    frame.render_widget(block, area);

    // Same frame, two sections: the animation on top, the track details below.
    let [visual_area, info_section] =
        Layout::vertical([Constraint::Min(3), Constraint::Length(5)]).areas(inner);

    let mode = app.visual;
    if let Some(player) = &app.player {
        visualizer::render(
            mode,
            &mut app.spectrum,
            player.tap(),
            &app.palette,
            visual_area,
            frame.buffer_mut(),
        );
    }
    let label = Line::from(format!(" {} · v ", mode.label()))
        .right_aligned()
        .fg(colors.tertiary);
    frame.render_widget(
        label,
        Rect {
            height: 1,
            ..visual_area
        },
    );

    // A divider in the frame's color, joined to its borders like ├───┤.
    let divider = Block::new()
        .borders(Borders::TOP)
        .border_style(Style::new().fg(colors.primary))
        .title(Line::from(vec![
            Span::from("─ ").fg(colors.primary),
            Span::from("Track ").fg(colors.secondary).bold(),
        ]));
    let divider_area = Rect {
        x: area.x,
        width: area.width,
        ..info_section
    };
    let info_inner = divider.inner(info_section);
    frame.render_widget(divider, divider_area);
    let buf = frame.buffer_mut();
    buf[(area.x, info_section.y)].set_symbol("├");
    buf[(area.right() - 1, info_section.y)].set_symbol("┤");

    let app = &*app;
    if let (Some(listen), Some(player)) = (app.listening(), app.player.as_ref()) {
        draw_listening(frame, listen, player, colors, info_inner);
        return;
    }
    let (Some(track), Some(player)) = (app.current_track(), app.player.as_ref()) else {
        let idle = Paragraph::new("Nothing playing").dark_gray().centered();
        let [middle] = Layout::vertical([Constraint::Length(1)])
            .flex(Flex::Center)
            .areas(info_inner);
        frame.render_widget(idle, middle);
        return;
    };

    let [info_area, gauge_area, controls_area] = Layout::vertical([
        Constraint::Length(2),
        Constraint::Length(1),
        Constraint::Length(1),
    ])
    .areas(info_inner.inner(Margin::new(2, 0)));

    let subtitle = match (&track.artist, &track.album) {
        (Some(artist), Some(album)) => format!("{artist} · {album}"),
        (Some(artist), None) => artist.clone(),
        (None, Some(album)) => album.clone(),
        (None, None) => String::new(),
    };
    let info = Paragraph::new(vec![
        Line::from(track.title.as_str()).fg(colors.primary).bold(),
        Line::from(subtitle).fg(colors.tertiary),
    ])
    .centered();
    frame.render_widget(info, info_area);

    let position = player.position();
    let position = track.duration.map_or(position, |total| position.min(total));
    let ratio = match track.duration {
        Some(total) if !total.is_zero() => position.as_secs_f64() / total.as_secs_f64(),
        _ => 0.0,
    };
    let total = track
        .duration
        .map(format_time)
        .unwrap_or_else(|| "--:--".into());
    let gauge = LineGauge::default()
        .ratio(ratio.clamp(0.0, 1.0))
        .label(format!("{} / {total}", format_time(position)))
        .filled_style(Style::new().fg(colors.secondary))
        .unfilled_style(Style::new().dark_gray());
    frame.render_widget(gauge, gauge_area);

    let state = if player.is_paused() {
        "⏸  Paused"
    } else {
        "▶  Playing"
    };
    let controls = Line::from(vec![
        Span::from(state).fg(colors.secondary).bold(),
        Span::from("    Volume ").dark_gray(),
        Span::from(format!("{:.0}%", player.volume() * 100.0)).fg(colors.tertiary),
        Span::from(match player.bass() {
            0 => String::new(),
            db => format!("    Bass +{db} dB"),
        })
        .fg(colors.primary),
        Span::from(match app.repeat {
            Repeat::Off => String::new(),
            repeat => format!("    ⟳ Loop {}", repeat.label()),
        })
        .fg(colors.secondary),
    ]);
    frame.render_widget(Paragraph::new(controls).centered(), controls_area);
}

/// The track details while listening to another device.
fn draw_listening(
    frame: &mut Frame,
    listen: &crate::cast::Listen,
    player: &crate::player::Player,
    colors: Colors,
    area: Rect,
) {
    let [info_area, gauge_area, controls_area] = Layout::vertical([
        Constraint::Length(2),
        Constraint::Length(1),
        Constraint::Length(1),
    ])
    .areas(area.inner(Margin::new(2, 0)));
    let Some(track) = &listen.track else {
        frame.render_widget(
            Paragraph::new(format!("Waiting for {}…", listen.name))
                .dark_gray()
                .centered(),
            info_area,
        );
        return;
    };
    let subtitle = [track.artist.as_str(), track.album.as_str()]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" · ");
    frame.render_widget(
        Paragraph::new(vec![
            Line::from(track.title.clone()).fg(colors.primary).bold(),
            Line::from(subtitle).fg(colors.tertiary),
        ])
        .centered(),
        info_area,
    );
    let position = Duration::from_millis(listen.heard_ms());
    let total = Duration::from_millis(track.duration_ms);
    let ratio = if total.is_zero() {
        0.0
    } else {
        position.as_secs_f64() / total.as_secs_f64()
    };
    let gauge = LineGauge::default()
        .ratio(ratio.clamp(0.0, 1.0))
        .label(format!(
            "{} / {}",
            format_time(position),
            format_time(total)
        ))
        .filled_style(Style::new().fg(colors.secondary))
        .unfilled_style(Style::new().dark_gray());
    frame.render_widget(gauge, gauge_area);
    let state = if player.is_paused() || !listen.playing {
        "⏸  Paused"
    } else {
        "📻  Live"
    };
    let controls = Line::from(vec![
        Span::from(state).fg(colors.secondary).bold(),
        Span::from("    Volume ").dark_gray(),
        Span::from(format!("{:.0}%", player.volume() * 100.0)).fg(colors.tertiary),
        Span::from("    c lyrics").dark_gray(),
    ]);
    frame.render_widget(Paragraph::new(controls).centered(), controls_area);
}

fn format_time(duration: Duration) -> String {
    let secs = duration.as_secs();
    format!("{}:{:02}", secs / 60, secs % 60)
}

fn help_line(app: &App, colors: Colors) -> Line<'static> {
    let keys: &[(&str, &str)] = match app.focus {
        Focus::Library => &[
            ("/", "URL"),
            ("Enter", "play"),
            ("Space", "pause"),
            ("n/p", "track"),
            ("←→", "seek"),
            ("+/-", "vol"),
            ("b", "bass"),
            ("v", "anim"),
            ("f", "filter"),
            ("g", "genres"),
            ("o", "loop"),
            ("d", "delete"),
            ("c", "broadcast"),
            ("r", "rescan"),
            ("q", "quit"),
        ],
        Focus::UrlInput => &[("Enter", "download"), ("Esc", "back")],
        Focus::ConfirmDelete => &[
            ("←→", "choose"),
            ("Enter", "confirm"),
            ("y", "yes"),
            ("n/Esc", "no"),
        ],
        Focus::Filter => &[
            ("←→", "field"),
            ("↑↓", "value"),
            ("Enter", "apply"),
            ("x", "clear"),
            ("Esc", "close"),
        ],
        Focus::Cast => match &app.cast {
            Some(Cast::Menu { .. }) | None => &[
                ("←→", "choose"),
                ("Enter", "start"),
                ("b", "broadcast"),
                ("s", "listen"),
                ("Esc", "close"),
            ],
            Some(Cast::Broadcasting(_)) => &[
                ("o", "open/code"),
                ("f", "forget paired"),
                ("Space", "pause"),
                ("x", "stop broadcast"),
                ("Esc", "hide"),
            ],
            Some(Cast::Browsing(_)) => &[("↑↓", "device"), ("Enter", "listen"), ("Esc", "back")],
            Some(Cast::Connecting { .. }) => &[("Esc", "cancel")],
            Some(Cast::AskingCode(_)) => &[("0-9", "code"), ("Enter", "send"), ("Esc", "cancel")],
            Some(Cast::Listening(_)) => &[
                ("Tab", "radio"),
                ("Space", "pause"),
                ("+/-", "vol"),
                ("x", "stop listening"),
                ("Esc", "hide"),
            ],
        },
    };
    // Keys take the three palette colors in turn.
    let key_colors = [colors.primary, colors.secondary, colors.tertiary];
    let spans: Vec<Span> = keys
        .iter()
        .zip(key_colors.iter().cycle())
        .flat_map(|((key, action), &color)| {
            [
                // A non-breaking space keeps each key with its action when wrapping.
                Span::from(format!(" {key}\u{a0}")).fg(color).bold(),
                Span::from(format!("{action}  ")),
            ]
        })
        .collect();
    Line::from(spans)
}
