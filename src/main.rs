mod app;
mod cover;
mod download;
mod filter;
mod genre;
mod library;
mod player;
mod tools;
mod ui;
mod visualizer;

use std::time::Duration;

use color_eyre::Result;
use ratatui::DefaultTerminal;
use ratatui::crossterm::event::{self, Event, KeyEventKind};

use crate::app::App;

fn main() -> Result<()> {
    color_eyre::install()?;
    let terminal = ratatui::init();
    let result = run(terminal);
    ratatui::restore();
    result
}

fn run(mut terminal: DefaultTerminal) -> Result<()> {
    let mut app = App::new()?;
    // ~30 fps so the frequency animation (step 4) can redraw smoothly later.
    let tick = Duration::from_millis(33);

    while !app.should_quit {
        app.process_events();
        terminal.draw(|frame| ui::draw(frame, &mut app))?;

        // Windows reports both press and release; only react to presses.
        if event::poll(tick)?
            && let Event::Key(key) = event::read()?
            && key.kind == KeyEventKind::Press
        {
            app.handle_key(key);
        }
    }
    app.shut_down();
    Ok(())
}
