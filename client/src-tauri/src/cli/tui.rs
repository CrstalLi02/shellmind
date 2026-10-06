//! Terminal UI
//!
//! Terminal UI rendering with ratatui + crossterm.
//! Visual style: OpenCode-inspired — restrained, spacious, low information density
//! Four colors: white (primary), gray (secondary), blue (accent), orange (marker)
//!
//! Two modes:
//! 1. Welcome screen (first open): centered logo + input + shortcut hints
//! 2. Chat window (after input): full-width messages + bottom input + footer

use crate::_cli_app::{App, AppEvent, CliArgs, Message, ToolStatus, ReActEvent, extract_done_text, resolve_connection};
use crate::_cli_sse::resolve_model_id;
use crate::_cli_sse::{SseClient, build_project_context};

use crossterm::{
    event::{Event as CEvent, KeyCode, KeyEvent, KeyModifiers, MouseEvent, MouseEventKind, EnableMouseCapture, DisableMouseCapture},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use ratatui::{
    backend::CrosstermBackend,
    layout::{Alignment, Constraint, Layout, Rect},
    style::{Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, Clear, Paragraph, Scrollbar, ScrollbarOrientation, ScrollbarState, Wrap},
    Frame, Terminal,
};
use std::io::{self, Read};
use std::sync::OnceLock;
use syntect::easy::HighlightLines;
use syntect::highlighting::{FontStyle, ThemeSet};
use syntect::parsing::SyntaxSet;
use tokio::sync::mpsc;

/// Check whether stdin is a TTY
fn is_tty() -> bool {
    use std::io::IsTerminal;
    std::io::stdin().is_terminal()
}

// ── Syntax Highlighting (syntect) ──

fn syntax_set() -> &'static SyntaxSet {
    static SS: OnceLock<SyntaxSet> = OnceLock::new();
    SS.get_or_init(|| SyntaxSet::load_defaults_newlines())
}

fn theme_set() -> &'static ThemeSet {
    static TS: OnceLock<ThemeSet> = OnceLock::new();
    TS.get_or_init(|| ThemeSet::load_defaults())
}

fn syntect_color(color: syntect::highlighting::Color) -> ratatui::style::Color {
    ratatui::style::Color::Rgb(color.r, color.g, color.b)
}

/// Syntax-highlight code with syntect
fn highlight_code(code: &str, lang: &str) -> Vec<Line<'static>> {
    let syntax = syntax_set()
        .find_syntax_by_extension(lang)
        .or_else(|| syntax_set().find_syntax_by_token(lang))
        .unwrap_or_else(|| syntax_set().find_syntax_plain_text());
    let theme = &theme_set().themes["base16-ocean.dark"];
    let mut h = HighlightLines::new(syntax, theme);

    let mut result = Vec::new();
    for line in code.lines() {
        let Ok(ranges) = h.highlight_line(line, syntax_set()) else { continue };
        let mut spans: Vec<Span<'static>> = Vec::new();
        for (style, text) in ranges {
            let fg = syntect_color(style.foreground);
            let mut modifier = Modifier::empty();
            if style.font_style.contains(FontStyle::BOLD) {
                modifier |= Modifier::BOLD;
            }
            if style.font_style.contains(FontStyle::ITALIC) {
                modifier |= Modifier::ITALIC;
            }
            if style.font_style.contains(FontStyle::UNDERLINE) {
                modifier |= Modifier::UNDERLINED;
            }
            spans.push(Span::styled(text.to_string(), Style::default().fg(fg).add_modifier(modifier)));
        }
        if spans.is_empty() {
            spans.push(Span::styled(String::new(), Style::default()));
        }
        result.push(Line::from(spans));
    }
    result
}

/// Render a bordered container around a code block
fn render_code_in_box(highlighted: Vec<Line<'static>>, lang: &str, term_width: u16) -> Vec<Line<'static>> {
    let mut result = Vec::new();
    let max_content_width = highlighted.iter()
        .map(|l| l.width())
        .max()
        .unwrap_or(0)
        .min(term_width.saturating_sub(4) as usize);

    let lang_label = if lang.is_empty() { String::new() } else { format!(" {}", lang) };
    let border_content = format!("─{} ", lang_label);
    let dash_count = max_content_width.saturating_sub(border_content.len().saturating_sub(1));
    let top = format!("┌─{} ─{}┐", lang_label, "─".repeat(dash_count));
    result.push(Line::from(Span::styled(top, Style::default().fg(theme::FG_MUTED))));

    for line in highlighted {
        let content_width = line.width();
        let padding = max_content_width.saturating_sub(content_width);
        let mut spans = vec![
            Span::styled("│ ", Style::default().fg(theme::FG_MUTED)),
        ];
        for span in line.spans {
            spans.push(Span::styled(span.content.to_string(), span.style.clone()));
        }
        if padding > 0 {
            spans.push(Span::styled(" ".repeat(padding), Style::default()));
        }
        spans.push(Span::styled(" │", Style::default().fg(theme::FG_MUTED)));
        result.push(Line::from(spans));
    }

    let bottom = format!("└─{}─┘", "─".repeat(max_content_width + 1));
    result.push(Line::from(Span::styled(bottom, Style::default().fg(theme::FG_MUTED))));
    result
}

/// ── Theme: warm, low-contrast, easy on the eyes ──
/// Warm background, low saturation, no pure white, soft transitions
mod theme {
    use ratatui::style::Color;

    // ── Primary background: warm dark gray ──
    pub const BG: Color = Color::Rgb(30, 31, 35);

    // ── Foreground: warm white → soft gray (no pure white) ──
    pub const FG: Color = Color::Rgb(200, 202, 208);
    pub const FG_DIM: Color = Color::Rgb(155, 157, 165);
    pub const FG_MUTED: Color = Color::Rgb(120, 122, 130);
    pub const FG_PLACEHOLDER: Color = Color::Rgb(140, 142, 150);

    // ── Borders: very low contrast ──
    #[allow(dead_code)]
    pub const BORDER: Color = Color::Rgb(48, 49, 54);
    pub const BORDER_FOCUS: Color = Color::Rgb(78, 145, 210);

    // ── Accent: low saturation ──
    pub const ACCENT: Color = Color::Rgb(78, 148, 212);
    pub const ORANGE: Color = Color::Rgb(208, 148, 62);

    // ── Status ──
    pub const SUCCESS: Color = Color::Rgb(68, 175, 110);
    pub const ERROR: Color = Color::Rgb(196, 78, 78);
    #[allow(dead_code)]
    pub const WARNING: Color = Color::Rgb(218, 173, 58);

    // ── Input area ──
    pub const INPUT_PROMPT: Color = Color::Rgb(78, 148, 212);
    pub const INPUT_CURSOR: Color = Color::Rgb(78, 148, 212);

    // ── Right panel: warm light gray ──
    pub const PANEL_BG: Color = Color::Rgb(215, 216, 222);
    pub const PANEL_FG: Color = Color::Rgb(50, 51, 56);
    pub const PANEL_DIM: Color = Color::Rgb(115, 116, 125);
    pub const PANEL_MUTED: Color = Color::Rgb(155, 156, 165);
    pub const PANEL_SEP: Color = Color::Rgb(185, 186, 194);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Entry
// ═══════════════════════════════════════════════════════════════════════════════

pub async fn run_cli(mut args: CliArgs) {
    // Resolve connection: env vars > ~/.shellmind/runtime.json > CLI flags
    let conn = resolve_connection(&args);
    args.server = conn.server_url;
    args.token = conn.token;

    // Resolve model: explicit --model-id > first user model on the server (same as GUI chat)
    let model_id = resolve_model_id(&args.server, args.token.as_deref(), args.model_id.as_deref()).await;
    args.model_id = model_id.map(|id| id.to_string());

    // In one-shot mode, fall back to cwd as project context when workdir is omitted
    if args.workdir.is_none() {
        args.workdir = std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string());
    }

    if let Some(message) = args.message.clone() {
        run_one_shot(args, message).await;
        return;
    }
    run_tui(args).await;
}

async fn run_one_shot(args: CliArgs, message: String) {
    let (event_tx, mut event_rx) = mpsc::unbounded_channel::<AppEvent>();
    let sse = SseClient::new(
        args.server.clone(),
        args.token.clone(),
        args.model_id.as_deref().and_then(|v| v.trim().parse::<i64>().ok()),
        event_tx,
    );

    let session_id = match sse.create_session(&args.agent_id, &args.user_id).await {
        Ok(id) => id,
        Err(e) => {
            eprintln!("Error: {}", e);
            return;
        }
    };

    let project_context = build_project_context(&args.workdir);

    let result = sse
        .chat_stream(&args.agent_id, &args.user_id, &session_id, &message, project_context)
        .await;

    let mut full_text = String::new();
    while let Some(event) = event_rx.recv().await {
        match event {
            AppEvent::SseEvent(e) => {
                match e.event.as_str() {
                    "text" => {
                        full_text = e.full_text.or(e.content).unwrap_or_default();
                    }
                    "done" => {
                        // Fallback: a done event with no text (e.g. abnormal stop); show the server summary
                        if full_text.is_empty() {
                            full_text = extract_done_text(e.content.as_deref());
                        }
                        if !full_text.is_empty() {
                            println!("{}", full_text);
                        }
                        return;
                    }
                    "error" => {
                        eprintln!("Error: {}", e.content.unwrap_or_default());
                        return;
                    }
                    _ => {}
                }
            }
            AppEvent::Done => {
                if !full_text.is_empty() {
                    println!("{}", full_text);
                }
                return;
            }
            AppEvent::Error(e) => {
                eprintln!("Error: {}", e);
                return;
            }
            _ => {}
        }
    }

    if let Err(e) = result {
        eprintln!("Error: {}", e);
    }
}

async fn run_tui(args: CliArgs) {
    if !is_tty() {
        eprintln!("Non-interactive environment detected, switching to non-interactive mode");
        let mut message = String::new();
        if std::io::stdin().read_to_string(&mut message).is_ok() && !message.trim().is_empty() {
            run_one_shot(args, message.trim().to_string()).await;
        } else {
            eprintln!("Non-interactive mode requires a message: shellmind-cli -m 'message'");
        }
        return;
    }

    if let Err(e) = enable_raw_mode() {
        eprintln!("Cannot enable raw mode ({}), switching to non-interactive", e);
        let mut message = String::new();
        if std::io::stdin().read_to_string(&mut message).is_ok() && !message.trim().is_empty() {
            run_one_shot(args, message.trim().to_string()).await;
        } else {
            eprintln!("Non-interactive mode requires a message: shellmind-cli -m 'message'");
        }
        return;
    }

    // Restore the terminal on panic (register before entering the alternate screen)
    let prev_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let _ = disable_raw_mode();
        let _ = execute!(io::stdout(), LeaveAlternateScreen, DisableMouseCapture);
        prev_hook(info);
    }));

    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen, EnableMouseCapture).expect("Failed to enter alternate screen");

    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend).expect("Failed to create terminal");
    terminal.clear().expect("Failed to clear terminal");

    let mut app = App::new(&args);
    let (event_tx, mut event_rx) = mpsc::unbounded_channel::<AppEvent>();
    let sse = SseClient::new(
        args.server.clone(),
        args.token.clone(),
        app.model_id,
        event_tx.clone(),
    );

    let mut show_welcome = true;
    let session_result = sse.create_session(&args.agent_id, &args.user_id).await;

    if let Ok(session_id) = session_result {
        app.session_id = Some(session_id);
    } else {
        show_welcome = false;
        app.messages.push(Message::Error {
            text: format!("Cannot connect to ShellMind server: {}", session_result.unwrap_err())
        });
    }

    loop {
        terminal.draw(|f| {
            if show_welcome {
                render_welcome(f, &app);
            } else {
                render_chat(f, &app);
            }
            if app.command_palette_open {
                render_command_palette(f, &app);
            }
        }).expect("Failed to draw");

        if crossterm::event::poll(std::time::Duration::from_millis(50)).expect("Event poll failed") {
            if let Ok(c_event) = crossterm::event::read() {
                match c_event {
                    CEvent::Key(key) => {
                        if show_welcome {
                            if handle_welcome_key(&mut app, key, &sse, &event_tx) {
                                show_welcome = false;
                            }
                        } else {
                            handle_chat_key(&mut app, key, &sse, &event_tx);
                        }
                    }
                    CEvent::Mouse(mouse) => handle_mouse_event(&mut app, mouse),
                    CEvent::Resize(_, _) => {}
                    _ => {}
                }
            }
        }

        while let Ok(event) = event_rx.try_recv() {
            match event {
                AppEvent::SseEvent(e) => {
                    if e.event == "_session_created" {
                        if let Some(sid) = e.content {
                            app.session_id = Some(sid);
                        }
                        continue;
                    }
                    app.handle_event(e);
                }
                AppEvent::Done => {
                    app.is_streaming = false;
                }
                AppEvent::Error(e) => {
                    app.messages.push(Message::Error { text: e });
                    app.is_streaming = false;
                }
                _ => {}
            }
        }

        if app.should_quit {
            break;
        }
    }

    let _ = std::panic::take_hook(); // drop the hook we installed
    restore_terminal(&mut terminal);
}

fn restore_terminal(terminal: &mut Terminal<CrosstermBackend<io::Stdout>>) {
    disable_raw_mode().expect("Failed to disable raw mode");
    execute!(
        terminal.backend_mut(),
        LeaveAlternateScreen,
        DisableMouseCapture
    ).expect("Failed to leave alternate screen");
    terminal.show_cursor().expect("Failed to show cursor");
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Welcome screen
// ═══════════════════════════════════════════════════════════════════════════════

fn render_welcome(f: &mut Frame, app: &App) {
    let size = f.area();

    // Vertically center
    let chunks = Layout::vertical([
        Constraint::Percentage(25),  // top spacer
        Constraint::Length(6),       // Logo
        Constraint::Length(3),       // input box
        Constraint::Length(1),       // shortcut hints
        Constraint::Length(1),       // Tip
        Constraint::Min(0),          // bottom spacer
    ]).split(size);

    // 1. Logo: "Shell" in blue · "Mind" in orange
    let shell_color = theme::ACCENT;
    let mind_color = theme::ORANGE;

    let logo_lines = vec![
        Line::from(vec![
            Span::styled("███████╗██╗  ██╗███████╗██╗     ██╗     ", Style::default().fg(shell_color).add_modifier(Modifier::BOLD)),
            Span::styled(" ███╗   ███╗██╗███╗   ██╗██████╗ ", Style::default().fg(mind_color).add_modifier(Modifier::BOLD)),
        ]),
        Line::from(vec![
            Span::styled("██╔════╝██║  ██║██╔════╝██║     ██║     ", Style::default().fg(shell_color).add_modifier(Modifier::BOLD)),
            Span::styled(" ████╗ ████║██║████╗  ██║██╔══██╗", Style::default().fg(mind_color).add_modifier(Modifier::BOLD)),
        ]),
        Line::from(vec![
            Span::styled("███████╗███████║█████╗  ██║     ██║     ", Style::default().fg(shell_color).add_modifier(Modifier::BOLD)),
            Span::styled(" ██╔████╔██║██║██╔██╗ ██║██║  ██║", Style::default().fg(mind_color).add_modifier(Modifier::BOLD)),
        ]),
        Line::from(vec![
            Span::styled("╚════██║██╔══██║██╔══╝  ██║     ██║     ", Style::default().fg(shell_color).add_modifier(Modifier::BOLD)),
            Span::styled(" ██║╚██╔╝██║██║██║╚██╗██║██║  ██║", Style::default().fg(mind_color).add_modifier(Modifier::BOLD)),
        ]),
        Line::from(vec![
            Span::styled("███████║██║  ██║███████╗███████╗███████╗", Style::default().fg(shell_color).add_modifier(Modifier::BOLD)),
            Span::styled(" ██║ ╚═╝ ██║██║██║ ╚████║██████╔╝", Style::default().fg(mind_color).add_modifier(Modifier::BOLD)),
        ]),
        Line::from(vec![
            Span::styled("╚══════╝╚═╝  ╚═╝╚══════╝╚══════╝╚══════╝", Style::default().fg(shell_color).add_modifier(Modifier::BOLD)),
            Span::styled(" ╚═╝     ╚═╝╚═╝╚═╝  ╚═══╝╚═════╝ ", Style::default().fg(mind_color).add_modifier(Modifier::BOLD)),
        ]),
    ];
    let logo = Paragraph::new(logo_lines).alignment(Alignment::Center);
    f.render_widget(logo, chunks[1]);

    // 2. Input box (centered card)
    let input_width = (size.width as f32 * 0.75) as u16;
    let input_x = (size.width.saturating_sub(input_width)) / 2;
    let input_area = Rect {
        x: input_x,
        y: chunks[2].y,
        width: input_width,
        height: chunks[2].height,
    };

    let input_block = Block::default()
        .borders(Borders::ALL)
        .border_style(Style::default().fg(theme::BORDER_FOCUS))
        .style(Style::default().bg(theme::BG));
    f.render_widget(input_block, input_area);

    let input_inner = Rect {
        x: input_area.x + 3,
        y: input_area.y + 1,
        width: input_area.width.saturating_sub(5),
        height: input_area.height.saturating_sub(2),
    };

    let input_text = app.get_input_text();
    let input_display: Vec<Line> = if input_text.is_empty() {
        vec![Line::from(vec![
            Span::styled("> ", Style::default().fg(theme::INPUT_PROMPT)),
            Span::styled("Ask anything... (Enter send, Shift+Enter newline)",
                Style::default().fg(theme::FG_PLACEHOLDER).add_modifier(Modifier::ITALIC)),
        ])]
    } else {
        let mut lines = Vec::new();
        for (i, line) in app.input_lines.iter().enumerate() {
            let (cursor_line, cursor_col) = app.cursor_pos;
            let mut spans = vec![Span::styled("> ", Style::default().fg(theme::INPUT_PROMPT))];
            if i == cursor_line {
                let col = cursor_col.min(line.chars().count());
                let before: String = line.chars().take(col).collect();
                let after: String = line.chars().skip(col).collect();
                spans.push(Span::styled(before, Style::default().fg(theme::FG)));
                spans.push(Span::styled("▎", Style::default().fg(theme::INPUT_CURSOR)));
                spans.push(Span::styled(after, Style::default().fg(theme::FG)));
            } else {
                spans.push(Span::styled(line.clone(), Style::default().fg(theme::FG)));
            }
            lines.push(Line::from(spans));
        }
        lines
    };

    let input_widget = Paragraph::new(input_display)
        .style(Style::default().fg(theme::FG).bg(theme::BG));
    f.render_widget(input_widget, input_inner);

    // 3. Shortcut hints
    let hint = Paragraph::new(Line::from(vec![
        Span::styled("tab ", Style::default().fg(theme::FG_DIM)),
        Span::styled("switch agent", Style::default().fg(theme::ACCENT)),
        Span::styled("  ctrl+p ", Style::default().fg(theme::FG_DIM)),
        Span::styled("command palette", Style::default().fg(theme::ACCENT)),
    ])).alignment(Alignment::Center);
    f.render_widget(hint, chunks[3]);

    // 4. Tip
    let tip = Paragraph::new(Line::from(vec![
        Span::styled("Tip ", Style::default().fg(theme::ORANGE).add_modifier(Modifier::BOLD)),
        Span::styled("/help ", Style::default().fg(theme::ACCENT)),
        Span::styled("show help  ", Style::default().fg(theme::FG_DIM)),
        Span::styled("/status ", Style::default().fg(theme::ACCENT)),
        Span::styled("show status", Style::default().fg(theme::FG_DIM)),
    ])).alignment(Alignment::Center);
    f.render_widget(tip, chunks[4]);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Chat view
// ═══════════════════════════════════════════════════════════════════════════════

const PANEL_WIDTH: u16 = 26;

/// Chat view: messages on the left, info panel on the right, input + footer at the bottom
fn render_chat(f: &mut Frame, app: &App) {
    let size = f.area();

    let input_height = (app.input_lines.len() as u16 + 2).clamp(3, 8);

    let main_chunks = Layout::vertical([
        Constraint::Min(5),              // messages + info panel
        Constraint::Length(input_height), // input area
        Constraint::Length(1),           // Footer
    ]).split(size);

    // Split the message area: messages left, panel right
    let pad: u16 = 1;
    let panel_w = if size.width > 80 { PANEL_WIDTH } else { 0 };
    let msg_w = size.width.saturating_sub(panel_w);
    let mid_chunks = Layout::horizontal([
        Constraint::Length(msg_w),
        Constraint::Length(panel_w),
    ]).split(main_chunks[0]);

    // Fill the message-area background, then render content with an inner offset
    let msg_bg = Block::default().style(Style::default().bg(theme::BG));
    f.render_widget(msg_bg, mid_chunks[0]);
    let msg_area = Rect { x: mid_chunks[0].x + pad, width: mid_chunks[0].width.saturating_sub(pad), ..mid_chunks[0] };
    render_messages(f, msg_area, app);
    if panel_w > 0 {
        render_right_panel(f, mid_chunks[1], app);
    }
    render_input(f, main_chunks[1], app);
    render_footer(f, main_chunks[2], app);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Message rendering (OpenCode-style: compact labels + blank-line gaps)
// ═══════════════════════════════════════════════════════════════════════════════

fn render_messages(f: &mut Frame, area: Rect, app: &App) {
    let mut lines: Vec<Line> = Vec::new();

    let term_width = area.width;

    for msg in &app.messages {
        match msg {
            Message::User { text } => {
                lines.push(Line::from(""));
                lines.push(Line::from(vec![
                    Span::styled("You", Style::default().fg(theme::FG).add_modifier(Modifier::BOLD)),
                ]));
                let md_lines = parse_markdown_owned(text, term_width);
                for md_line in md_lines {
                    lines.push(md_line);
                }
                lines.push(Line::from(""));
            }

            Message::Assistant { text, done } => {
                let status = if *done { " ✓" } else { " ◐" };
                let status_color = if *done { theme::SUCCESS } else { theme::ORANGE };
                lines.push(Line::from(""));
                lines.push(Line::from(vec![
                    Span::styled("Ai", Style::default().fg(theme::FG).add_modifier(Modifier::BOLD)),
                    Span::styled(status, Style::default().fg(status_color)),
                ]));
                let content_lines = parse_content_with_thinking_opencode(text, term_width);
                for line in content_lines {
                    lines.push(line);
                }
                lines.push(Line::from(""));
            }

            Message::ToolCall { tool_name, args, status, .. } => {
                // Tool call: ■ status + tool name + arg summary (single line)
                let (icon, color) = match status {
                    ToolStatus::InProgress => ("■", theme::ORANGE),
                    ToolStatus::Success => ("■", theme::SUCCESS),
                    ToolStatus::Failure => ("■", theme::ERROR),
                };
                let args_summary = if args.len() > 50 {
                    format!("{}...", &args.chars().take(50).collect::<String>())
                } else {
                    args.clone()
                };
                lines.push(Line::from(vec![
                    Span::styled(format!("{} ", icon), Style::default().fg(color)),
                    Span::styled(tool_name.clone(), Style::default().fg(theme::ACCENT).add_modifier(Modifier::BOLD)),
                    Span::styled(format!(" {}", args_summary), Style::default().fg(theme::FG_DIM)),
                ]));
            }

            Message::ToolResult { tool_name, result, status, .. } => {
                // Tool result: ■ status + tool name + truncated result (single line)
                let (icon, color) = match status {
                    ToolStatus::Success => ("■", theme::SUCCESS),
                    ToolStatus::Failure => ("■", theme::ERROR),
                    ToolStatus::InProgress => ("■", theme::ORANGE),
                };
                let display = if result.len() > 80 {
                    format!("{}... ({} chars)", &result.chars().take(80).collect::<String>(), result.len())
                } else {
                    result.clone()
                };
                lines.push(Line::from(vec![
                    Span::styled(format!("{} ", icon), Style::default().fg(color)),
                    Span::styled(format!("{}: ", tool_name), Style::default().fg(theme::ACCENT)),
                    Span::styled(display, Style::default().fg(theme::FG_DIM)),
                ]));
            }

            Message::Error { text } => {
                lines.push(Line::from(""));
                lines.push(Line::from(vec![
                    Span::styled("Error: ", Style::default().fg(theme::ERROR).add_modifier(Modifier::BOLD)),
                    Span::styled(text.clone(), Style::default().fg(theme::ERROR)),
                ]));
                lines.push(Line::from(""));
            }

            Message::System { text } => {
                lines.push(Line::from(vec![
                    Span::styled(text.clone(), Style::default().fg(theme::FG_DIM)),
                ]));
            }

            Message::Diff { summary } => {
                lines.push(Line::from(""));
                lines.push(Line::from(vec![
                    Span::styled("File Changes", Style::default().fg(theme::ACCENT).add_modifier(Modifier::BOLD)),
                ]));
                if let Some(ref desc) = summary.description {
                    lines.push(Line::from(vec![
                        Span::styled(format!("  {}", desc), Style::default().fg(theme::FG_DIM)),
                    ]));
                }
                for f in &summary.created {
                    lines.push(Line::from(vec![
                        Span::styled("  + ", Style::default().fg(theme::SUCCESS)),
                        Span::styled(f.path.clone(), Style::default().fg(theme::FG)),
                        Span::styled(format!(" (+{} lines)", f.added_lines), Style::default().fg(theme::FG_DIM)),
                    ]));
                }
                for f in &summary.modified {
                    lines.push(Line::from(vec![
                        Span::styled("  ~ ", Style::default().fg(theme::ACCENT)),
                        Span::styled(f.path.clone(), Style::default().fg(theme::FG)),
                        Span::styled(format!(" (+{}, -{})", f.added_lines, f.removed_lines), Style::default().fg(theme::FG_DIM)),
                    ]));
                }
                for f in &summary.deleted {
                    lines.push(Line::from(vec![
                        Span::styled("  - ", Style::default().fg(theme::ERROR)),
                        Span::styled(f.path.clone(), Style::default().fg(theme::FG)),
                    ]));
                }
                lines.push(Line::from(""));
            }
        }
    }

    // Streaming animation
    if app.is_streaming && app.streaming_text.is_empty() {
        lines.push(Line::from(""));
        lines.push(Line::from(vec![
            Span::styled("Ai", Style::default().fg(theme::FG).add_modifier(Modifier::BOLD)),
            Span::styled(" ◐", Style::default().fg(theme::ORANGE)),
        ]));
        lines.push(Line::from(vec![
            Span::styled("Thinking...", Style::default().fg(theme::FG_DIM).add_modifier(Modifier::ITALIC)),
        ]));
    }

    // Render the message area
    let scroll = calculate_scroll(area, &lines, app);
    let total_lines = lines.len() as u16;

    let messages_widget = Paragraph::new(lines)
        .style(Style::default().fg(theme::FG).bg(theme::BG))
        .scroll((scroll, 0))
        .wrap(Wrap { trim: false });

    f.render_widget(messages_widget, area);

    // Scrollbar (always rendered, no flicker; track hidden, thumb muted)
    let scrollbar = Scrollbar::default()
        .orientation(ScrollbarOrientation::VerticalRight)
        .begin_symbol(None)
        .end_symbol(None)
        .track_symbol(Some("░"))
        .thumb_symbol("▓")
        .style(Style::default().fg(theme::FG_MUTED))
        .thumb_style(Style::default().fg(theme::FG_DIM));
    let mut scrollbar_state = ScrollbarState::new(total_lines as usize)
        .position(scroll as usize)
        .viewport_content_length(area.height as usize);
    f.render_stateful_widget(scrollbar, area, &mut scrollbar_state);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Input + footer
// ═══════════════════════════════════════════════════════════════════════════════

fn render_input(f: &mut Frame, area: Rect, app: &App) {
    let input_block = Block::default()
        .borders(Borders::ALL)
        .border_style(Style::default().fg(theme::BORDER_FOCUS))
        .style(Style::default().bg(theme::BG));
    let raw_inner = input_block.inner(area);
    let inner = Rect { x: raw_inner.x + 1, width: raw_inner.width.saturating_sub(1), ..raw_inner };
    f.render_widget(input_block, area);

    if app.is_streaming {
        let waiting = Paragraph::new(Line::from(vec![
            Span::styled(" ◐ ", Style::default().fg(theme::ORANGE)),
            Span::styled("AI responding... Esc to cancel", Style::default().fg(theme::FG_DIM).add_modifier(Modifier::ITALIC)),
        ]))
        .style(Style::default().fg(theme::FG).bg(theme::BG));
        f.render_widget(waiting, inner);
    } else {
        let (cursor_line, cursor_col) = app.cursor_pos;
        let visible_height = inner.height as usize;
        let total_lines = app.input_lines.len().max(1);

        let scroll_offset = if cursor_line >= visible_height {
            cursor_line.saturating_sub(visible_height - 1)
        } else {
            0
        };

        let mut input_render_lines: Vec<Line> = Vec::new();
        let end_line = (scroll_offset + visible_height).min(total_lines);

        for i in scroll_offset..end_line {
            let line = app.input_lines.get(i).map(|s| s.as_str()).unwrap_or("");
            let mut spans = vec![Span::styled("> ", Style::default().fg(theme::INPUT_PROMPT))];

            if i == cursor_line {
                let col = cursor_col.min(line.chars().count());
                let before: String = line.chars().take(col).collect();
                let after: String = line.chars().skip(col).collect();
                spans.push(Span::styled(before, Style::default().fg(theme::FG)));
                spans.push(Span::styled("▎", Style::default().fg(theme::INPUT_CURSOR)));
                spans.push(Span::styled(after, Style::default().fg(theme::FG)));
            } else {
                spans.push(Span::styled(line.to_string(), Style::default().fg(theme::FG)));
            }
            input_render_lines.push(Line::from(spans));
        }

        // Placeholder
        if app.input_lines.len() == 1 && app.input_lines[0].is_empty() {
            input_render_lines = vec![
                Line::from(vec![
                    Span::styled("> ", Style::default().fg(theme::INPUT_PROMPT)),
                    Span::styled("Ask anything... (Enter send, Shift+Enter newline, ↑↓ history)",
                        Style::default().fg(theme::FG_PLACEHOLDER).add_modifier(Modifier::ITALIC)),
                ]),
            ];
        }

        let input = Paragraph::new(input_render_lines)
            .style(Style::default().fg(theme::FG).bg(theme::BG))
            .wrap(Wrap { trim: false });
        f.render_widget(input, inner);
    }
}

/// Footer: OpenCode-style — dense, single line
fn render_footer(f: &mut Frame, area: Rect, app: &App) {
    let msg_count = app.messages.len();
    let total_chars: usize = app.messages.iter().map(|m| match m {
        Message::User { text } => text.len(),
        Message::Assistant { text, .. } => text.len(),
        Message::ToolCall { args, .. } => args.len(),
        Message::ToolResult { result, .. } => result.len(),
        Message::Error { text } => text.len(),
        Message::System { text } => text.len(),
        Message::Diff { summary } => {
            let mut len = 0usize;
            if let Some(ref d) = summary.description { len += d.len(); }
            for f in &summary.created { len += f.path.len(); }
            for f in &summary.modified { len += f.path.len(); }
            for f in &summary.deleted { len += f.path.len(); }
            len
        }
    }).sum();
    let token_display = if total_chars > 1000 {
        format!("{}.{}K", total_chars / 1000, (total_chars % 1000) / 100)
    } else {
        format!("{}", total_chars)
    };

    // Project name + status
    let project_name = app.workdir.as_ref()
        .and_then(|d| d.split('/').last())
        .unwrap_or("unknown");

    let status_indicator = if app.is_streaming {
        vec![
            Span::styled("◐ ", Style::default().fg(theme::ORANGE)),
            Span::styled(project_name, Style::default().fg(theme::FG).add_modifier(Modifier::BOLD)),
        ]
    } else if app.manual_scroll {
        vec![
            Span::styled("↑ ", Style::default().fg(theme::ACCENT)),
            Span::styled(project_name, Style::default().fg(theme::FG).add_modifier(Modifier::BOLD)),
        ]
    } else {
        vec![
            Span::styled("● ", Style::default().fg(theme::SUCCESS)),
            Span::styled(project_name, Style::default().fg(theme::FG).add_modifier(Modifier::BOLD)),
        ]
    };

    let footer_text = Line::from({
        let mut spans = status_indicator;
        spans.push(Span::styled(format!(" │ {}msg │ {} ", msg_count, token_display), Style::default().fg(theme::FG_DIM)));
        spans.push(Span::styled("ctrl+p", Style::default().fg(theme::ACCENT)));
        spans.push(Span::styled(" palette", Style::default().fg(theme::FG_DIM)));
        spans.push(Span::raw("  "));
        spans.push(Span::styled("● v0.1.0", Style::default().fg(theme::FG_MUTED)));
        spans
    });

    let footer = Paragraph::new(footer_text).style(Style::default().bg(theme::BG));
    f.render_widget(footer, area);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Right info panel
// ═══════════════════════════════════════════════════════════════════════════════

fn render_right_panel(f: &mut Frame, area: Rect, app: &App) {
    // Light-gray panel background + left separator
    let panel = Block::default()
        .borders(Borders::LEFT)
        .border_style(Style::default().fg(theme::PANEL_SEP))
        .style(Style::default().bg(theme::PANEL_BG));
    f.render_widget(panel, area);

    let inner = Rect {
        x: area.x + 2,
        y: area.y + 1,
        width: area.width.saturating_sub(3),
        height: area.height.saturating_sub(2),
    };
    if inner.height < 3 || inner.width < 5 {
        return;
    }

    let session_ok = app.session_id.is_some();

    let msg_count = app.messages.len();
    let total_chars: usize = app.messages.iter().map(|m| match m {
        Message::User { text } => text.len(),
        Message::Assistant { text, .. } => text.len(),
        Message::ToolCall { args, .. } => args.len(),
        Message::ToolResult { result, .. } => result.len(),
        Message::Error { text } => text.len(),
        Message::System { text } => text.len(),
        Message::Diff { summary } => {
            let mut l = 0;
            if let Some(ref d) = summary.description { l += d.len(); }
            for f in &summary.created { l += f.path.len(); }
            for f in &summary.modified { l += f.path.len(); }
            for f in &summary.deleted { l += f.path.len(); }
            l
        }
    }).sum();
    let token_display = if total_chars > 1000 {
        format!("{}.{}K", total_chars / 1000, (total_chars % 1000) / 100)
    } else {
        format!("{}", total_chars)
    };

    let sid = app.session_id.as_deref().unwrap_or("-");
    let sid_short = if sid.len() > 12 { format!("{}..", &sid[..12]) } else { sid.to_string() };

    let rows = vec![
        Line::from(vec![
            Span::styled(if session_ok { "●" } else { "○" },
                Style::default().fg(if session_ok { theme::SUCCESS } else { theme::PANEL_MUTED })),
        ]),
        Line::from(""),
        Line::from(vec![
            Span::styled("session", Style::default().fg(theme::PANEL_MUTED)),
        ]),
        Line::from(vec![
            Span::styled(sid_short, Style::default().fg(theme::PANEL_DIM)),
        ]),
        Line::from(""),
        Line::from(vec![
            Span::styled("agent", Style::default().fg(theme::PANEL_MUTED)),
        ]),
        Line::from(vec![
            Span::styled(&app.agent_id, Style::default().fg(theme::PANEL_FG)),
        ]),
        Line::from(""),
        Line::from(vec![
            Span::styled(format!("{} msgs", msg_count), Style::default().fg(theme::PANEL_DIM)),
        ]),
        Line::from(vec![
            Span::styled(token_display, Style::default().fg(theme::PANEL_MUTED)),
        ]),
    ];

    let content = Paragraph::new(rows)
        .style(Style::default().fg(theme::PANEL_FG).bg(theme::PANEL_BG));
    f.render_widget(content, inner);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Command palette
// ═══════════════════════════════════════════════════════════════════════════════

fn render_command_palette(f: &mut Frame, app: &App) {
    let size = f.area();
    let popup_width = (size.width as f32 * 0.6) as u16;
    let popup_height = (COMMAND_PALETTE_ITEMS.len() as u16 + 3).min(size.height.saturating_sub(4));
    let popup_x = (size.width.saturating_sub(popup_width)) / 2;
    let popup_y = (size.height.saturating_sub(popup_height)) / 3;

    let popup_area = Rect {
        x: popup_x,
        y: popup_y,
        width: popup_width,
        height: popup_height,
    };

    f.render_widget(Clear, popup_area);

    let block = Block::default()
        .borders(Borders::ALL)
        .border_style(Style::default().fg(theme::ACCENT))
        .style(Style::default().bg(theme::BG));
    f.render_widget(block, popup_area);

    let inner = Rect {
        x: popup_area.x + 2,
        y: popup_area.y + 1,
        width: popup_area.width.saturating_sub(4),
        height: popup_area.height.saturating_sub(2),
    };

    // Filter input
    let filter_text = if app.command_palette_filter.is_empty() {
        format!("> ")
    } else {
        format!("> {}", app.command_palette_filter)
    };
    let filter_line = Line::from(Span::styled(filter_text, Style::default().fg(theme::INPUT_PROMPT)));
    f.render_widget(Paragraph::new(filter_line), Rect {
        x: inner.x,
        y: inner.y,
        width: inner.width,
        height: 1,
    });

    // Filtered command list
    let items: &[(&str, &str)] = COMMAND_PALETTE_ITEMS;
    let filtered: Vec<_> = items.iter()
        .filter(|(cmd, _)| cmd.contains(&app.command_palette_filter))
        .collect();

    let list_y = inner.y + 2;
    let list_area = Rect {
        x: inner.x,
        y: list_y,
        width: inner.width,
        height: inner.height.saturating_sub(2),
    };

    let mut list_lines: Vec<Line> = Vec::new();
    for (i, (cmd, desc)) in filtered.iter().enumerate() {
        let selected = i == app.command_palette_selection;
        let prefix = if selected { "▸ " } else { "  " };
        let cmd_style = if selected {
            Style::default().fg(theme::BG).bg(theme::ACCENT)
        } else {
            Style::default().fg(theme::ACCENT)
        };
        let desc_style = if selected {
            Style::default().fg(theme::BG).bg(theme::ACCENT)
        } else {
            Style::default().fg(theme::FG_DIM)
        };
        list_lines.push(Line::from(vec![
            Span::styled(format!("{}{}", prefix, cmd), cmd_style),
            Span::styled(format!("  {}", desc), desc_style),
        ]));
    }
    f.render_widget(Paragraph::new(list_lines), list_area);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Markdown parsing
// ═══════════════════════════════════════════════════════════════════════════════

/// Markdown → Line<'static> (code blocks / headings / lists / bold / code / tables)
fn parse_markdown_owned(text: &str, term_width: u16) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    let mut in_table = false;
    let mut table_lines: Vec<&str> = Vec::new();
    let mut pending_setext: Option<String> = None;
    let mut in_code_block = false;
    let mut code_lang = String::new();
    let mut code_lines: Vec<String> = Vec::new();

    for line in text.lines() {
        // Detect fenced code blocks
        if line.trim_start().starts_with("```") {
            if in_code_block {
                let lang = std::mem::take(&mut code_lang);
                let code = code_lines.join("\n");
                let highlighted = highlight_code(&code, &lang);
                lines.extend(render_code_in_box(highlighted, &lang, term_width));
                code_lines.clear();
                in_code_block = false;
            } else {
                in_code_block = true;
                code_lang = line.trim_start().trim_start_matches("```").trim().to_string();
            }
            continue;
        }

        if in_code_block {
            code_lines.push(line.to_string());
            continue;
        }

        let trimmed = line.trim();

        if trimmed.is_empty() {
            if in_table {
                lines.extend(render_table(&table_lines));
                table_lines.clear();
                in_table = false;
            }
            pending_setext = None;
            lines.push(Line::from(""));
            continue;
        }

        // Table
        if is_table_line(trimmed) {
            in_table = true;
            table_lines.push(trimmed);
            pending_setext = None;
            continue;
        } else if in_table {
            lines.extend(render_table(&table_lines));
            table_lines.clear();
            in_table = false;
        }

        // Setext heading
        if is_setext_underline(trimmed) && pending_setext.is_some() {
            let header_text = pending_setext.take().unwrap();
            let level = if trimmed.starts_with('=') { 1 } else { 2 };
            let spans = parse_inline_markdown_owned(&header_text);
            let mut header_spans = vec![
                Span::styled("#".repeat(level) + " ",
                    Style::default().fg(theme::ACCENT).add_modifier(Modifier::BOLD)),
            ];
            header_spans.extend(spans);
            lines.push(Line::from(header_spans));
            continue;
        }

        // ATX heading
        if let Some(header_level) = detect_header(trimmed) {
            let header_text = &trimmed[header_level..].trim();
            let spans = parse_inline_markdown_owned(header_text);
            let mut header_spans = vec![
                Span::styled("#".repeat(header_level) + " ",
                    Style::default().fg(theme::ACCENT).add_modifier(Modifier::BOLD)),
            ];
            header_spans.extend(spans);
            lines.push(Line::from(header_spans));
            pending_setext = None;
            continue;
        }

        pending_setext = Some(trimmed.to_string());
        let spans = apply_list_style(parse_inline_markdown_owned(line));
        lines.push(Line::from(spans));
    }

    if in_code_block && !code_lines.is_empty() {
        let lang = std::mem::take(&mut code_lang);
        let code = code_lines.join("\n");
        let highlighted = highlight_code(&code, &lang);
        lines.extend(render_code_in_box(highlighted, &lang, term_width));
    }

    if in_table && !table_lines.is_empty() {
        lines.extend(render_table(&table_lines));
    }

    lines
}

/// List styling: -, numbers, and * all use the same blue (no multi-color)
fn apply_list_style(spans: Vec<Span<'static>>) -> Vec<Span<'static>> {
    if spans.is_empty() {
        return spans;
    }
    let first_text = spans[0].content.to_string();
    let trimmed = first_text.trim();

    // - list item
    if trimmed.starts_with("- ") {
        let mut result = vec![
            Span::styled("- ", Style::default().fg(theme::ACCENT)),
        ];
        let rest = if first_text.len() > 2 { first_text[2..].to_string() } else { "".to_string() };
        if !rest.is_empty() {
            result.push(Span::styled(rest, Style::default().fg(theme::FG)));
        }
        result.extend(spans.into_iter().skip(1));
        return result;
    }

    // * list item
    if trimmed.starts_with("* ") {
        let mut result = vec![
            Span::styled("* ", Style::default().fg(theme::ACCENT)),
        ];
        let rest = if first_text.len() > 2 { first_text[2..].to_string() } else { "".to_string() };
        if !rest.is_empty() {
            result.push(Span::styled(rest, Style::default().fg(theme::FG)));
        }
        result.extend(spans.into_iter().skip(1));
        return result;
    }

    // Ordered list
    if !trimmed.is_empty() && trimmed.as_bytes()[0].is_ascii_digit() {
        if let Some(dot_pos) = trimmed.find('.') {
            if dot_pos <= 2 {
                let num_part = &trimmed[..dot_pos + 1];
                let rest_text = trimmed[dot_pos + 1..].trim_start().to_string();
                let mut result = vec![
                    Span::styled(format!("{} ", num_part), Style::default().fg(theme::ACCENT)),
                ];
                if !rest_text.is_empty() {
                    result.push(Span::styled(rest_text, Style::default().fg(theme::FG)));
                }
                result.extend(spans.into_iter().skip(1));
                return result;
            }
        }
    }

    spans
}

/// Thinking + Markdown parsing (OpenCode-style: collapse Thought to one line)
fn parse_content_with_thinking_opencode(content: &str, term_width: u16) -> Vec<Line<'static>> {
    let mut lines: Vec<Line<'static>> = Vec::new();
    let mut normal_content = String::new();

    let mut remaining = content;
    while let Some(start_idx) = find_thinking_start(remaining) {
        normal_content.push_str(&remaining[..start_idx]);
        remaining = &remaining[start_idx..];

        if let Some(end_idx) = find_thinking_end(remaining) {
            let _thinking_content = get_thinking_content(remaining, end_idx);
            remaining = &remaining[end_idx..];

            for md_line in parse_markdown_owned(&normal_content, term_width) {
                lines.push(md_line);
            }
            normal_content.clear();

            lines.push(Line::from(vec![
                Span::styled("+ Thought", Style::default().fg(theme::ORANGE).add_modifier(Modifier::BOLD)),
            ]));
        } else {
            for md_line in parse_markdown_owned(&normal_content, term_width) {
                lines.push(md_line);
            }
            normal_content.clear();

            lines.push(Line::from(vec![
                Span::styled("+ Thought", Style::default().fg(theme::ORANGE).add_modifier(Modifier::BOLD)),
                Span::styled(" ...", Style::default().fg(theme::FG_DIM)),
            ]));
            break;
        }
    }

    normal_content.push_str(remaining);
    for md_line in parse_markdown_owned(&normal_content, term_width) {
        lines.push(md_line);
    }

    lines
}

/// Find the start of a thinking block (🔍 or <think>)
fn find_thinking_start(text: &str) -> Option<usize> {
    // Prefer the <think> tag
    if let Some(idx) = text.find("<think>") {
        return Some(idx);
    }
    // Then look for the 🔍 tag (7-byte UTF-8)
    if let Some(idx) = text.find("🔍") {
        return Some(idx);
    }
    None
}

/// Find the end of a thinking block (offset after the closing tag)
fn find_thinking_end(text_from_start: &str) -> Option<usize> {
    // <think>...</think>
    if text_from_start.starts_with("<think>") {
        if let Some(idx) = text_from_start[7..].find("</think>") {
            return Some(7 + idx + 8);
        }
    }
    // 🔍...ConstraintMaker
    if text_from_start.starts_with("🔍") {
        if let Some(idx) = text_from_start[7..].find("ConstraintMaker") {
            return Some(7 + idx + 8);
        }
    }
    None
}

/// Extract thinking content
fn get_thinking_content(text_from_start: &str, end_idx: usize) -> String {
    if text_from_start.starts_with("<think>") {
        text_from_start[7..end_idx - 8].trim().to_string()
    } else if text_from_start.starts_with("🔍") {
        text_from_start[7..end_idx - 8].trim().to_string()
    } else {
        String::new()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Markdown helpers
// ═══════════════════════════════════════════════════════════════════════════════

fn is_table_line(trimmed: &str) -> bool {
    if !trimmed.starts_with('|') { return false; }
    if trimmed.ends_with('|') { return true; }
    trimmed.split('|').filter(|s| !s.is_empty()).count() >= 2
}

fn is_setext_underline(trimmed: &str) -> bool {
    if trimmed.len() < 3 { return false; }
    let first = trimmed.chars().next().unwrap();
    if first != '=' && first != '-' { return false; }
    trimmed.chars().all(|c| c == first)
}

fn detect_header(line: &str) -> Option<usize> {
    let mut count = 0;
    for ch in line.chars() {
        if ch == '#' { count += 1; }
        else if ch.is_whitespace() {
            if count > 0 && count <= 6 { return Some(count); }
            return None;
        } else { return None; }
    }
    None
}

fn parse_table_row(line: &str) -> Vec<String> {
    let trimmed = line.trim();
    if trimmed == "|" { return vec![]; }
    let inner = if trimmed.starts_with('|') && trimmed.ends_with('|') && trimmed.len().saturating_sub(0) >= 2 {
        &trimmed[1..trimmed.len().saturating_sub(1)]
    } else if trimmed.starts_with('|') {
        &trimmed[1..]
    } else if trimmed.ends_with('|') {
        &trimmed[..trimmed.len().saturating_sub(1)]
    } else {
        trimmed
    };
    inner.split('|')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect()
}

fn render_table(lines: &[&str]) -> Vec<Line<'static>> {
    let mut result = Vec::new();
    if lines.len() < 2 {
        for line in lines {
            result.push(Line::from(line.to_string()));
        }
        return result;
    }

    let headers = parse_table_row(lines[0]);
    let num_cols = headers.len();
    if num_cols == 0 {
        for line in lines {
            result.push(Line::from(line.to_string()));
        }
        return result;
    }

    let data_start = if lines.len() > 1 && lines[1].contains("---") { 2 } else { 1 };
    let data_rows: Vec<Vec<String>> = lines[data_start..].iter()
        .map(|l| parse_table_row(l))
        .collect();

    let mut col_widths: Vec<usize> = headers.iter().map(|h| h.len()).collect();
    for row in &data_rows {
        for (i, cell) in row.iter().enumerate() {
            if i < col_widths.len() {
                col_widths[i] = col_widths[i].max(cell.len());
            }
        }
    }
    for w in col_widths.iter_mut() {
        *w = (*w).min(30);
    }

    // Header row
    let mut header_spans = vec![Span::styled("│ ", Style::default().fg(theme::FG_MUTED))];
    for (i, header) in headers.iter().enumerate() {
        if i > 0 {
            header_spans.push(Span::styled(" │ ", Style::default().fg(theme::FG_MUTED)));
        }
        header_spans.push(Span::styled(
            format!("{:<width$}", header, width = col_widths[i]),
            Style::default().fg(theme::FG).add_modifier(Modifier::BOLD)
        ));
    }
    header_spans.push(Span::styled(" │", Style::default().fg(theme::FG_MUTED)));
    result.push(Line::from(header_spans));

    // Separator
    let mut sep_spans = vec![Span::styled("├─", Style::default().fg(theme::FG_MUTED))];
    for i in 0..num_cols {
        if i > 0 {
            sep_spans.push(Span::styled("─┼─", Style::default().fg(theme::FG_MUTED)));
        }
        sep_spans.push(Span::styled("─".repeat(col_widths[i]), Style::default().fg(theme::FG_MUTED)));
    }
    sep_spans.push(Span::styled("─┤", Style::default().fg(theme::FG_MUTED)));
    result.push(Line::from(sep_spans));

    // Data rows
    for row in &data_rows {
        let mut row_spans = vec![Span::styled("│ ", Style::default().fg(theme::FG_MUTED))];
        for (i, cell) in row.iter().enumerate() {
            if i > 0 {
                row_spans.push(Span::styled(" │ ", Style::default().fg(theme::FG_MUTED)));
            }
            row_spans.push(Span::styled(
                format!("{:<width$}", cell, width = if i < col_widths.len() { col_widths[i] } else { cell.len() }),
                Style::default().fg(theme::FG)
            ));
        }
        row_spans.push(Span::styled(" │", Style::default().fg(theme::FG_MUTED)));
        result.push(Line::from(row_spans));
    }

    result
}

/// Inline Markdown: bold + code
fn parse_inline_markdown_owned(text: &str) -> Vec<Span<'static>> {
    let mut spans = Vec::new();
    let mut remaining = text;

    while !remaining.is_empty() {
        // Inline `code`
        if let Some(start) = remaining.find('`') {
            if start > 0 {
                spans.extend(parse_inline_formatting(&remaining[..start]));
            }
            remaining = &remaining[start + 1..];
            if let Some(end) = remaining.find('`') {
                spans.push(Span::styled(
                    remaining[..end].to_string(),
                    Style::default().fg(theme::ACCENT).add_modifier(Modifier::BOLD)
                ));
                remaining = &remaining[end + 1..];
            } else {
                spans.push(Span::styled(format!("`{}", remaining), Style::default().fg(theme::FG)));
                break;
            }
            continue;
        }
        spans.extend(parse_inline_formatting(remaining));
        break;
    }

    if spans.is_empty() {
        spans.push(Span::styled(text.to_string(), Style::default().fg(theme::FG)));
    }
    spans
}

fn parse_inline_formatting(text: &str) -> Vec<Span<'static>> {
    let mut spans = Vec::new();
    let mut remaining = text;

    while !remaining.is_empty() {
        if let Some(start) = remaining.find("**") {
            if start > 0 {
                spans.push(Span::styled(remaining[..start].to_string(), Style::default().fg(theme::FG)));
            }
            remaining = &remaining[start + 2..];
            if let Some(end) = remaining.find("**") {
                spans.push(Span::styled(
                    remaining[..end].to_string(),
                    Style::default().fg(theme::FG).add_modifier(Modifier::BOLD)
                ));
                remaining = &remaining[end + 2..];
            } else {
                spans.push(Span::styled(format!("**{}", remaining), Style::default().fg(theme::FG)));
                break;
            }
        } else {
            spans.push(Span::styled(remaining.to_string(), Style::default().fg(theme::FG)));
            break;
        }
    }

    spans
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Scroll calculation
// ═══════════════════════════════════════════════════════════════════════════════

fn calculate_scroll(area: Rect, lines: &[Line], app: &App) -> u16 {
    let total_lines = lines.len() as u16;
    let visible_lines = area.height;

    if app.manual_scroll {
        let bottom_scroll = if total_lines > visible_lines {
            total_lines.saturating_sub(visible_lines)
        } else { 0 };
        bottom_scroll.saturating_sub(app.scroll_offset)
    } else {
        if total_lines > visible_lines {
            total_lines.saturating_sub(visible_lines)
        } else { 0 }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Keyboard handling
// ═══════════════════════════════════════════════════════════════════════════════

fn handle_welcome_key(app: &mut App, key: KeyEvent, _sse: &SseClient, event_tx: &mpsc::UnboundedSender<AppEvent>) -> bool {
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
        app.should_quit = true;
        return false;
    }
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('u') {
        app.clear_input();
        return false;
    }
    if key.code == KeyCode::Up && !key.modifiers.contains(KeyModifiers::SHIFT) {
        if let Some(prev) = app.input_history.navigate_up(&app.get_input_text()) {
            app.set_input_text(prev);
        }
        return false;
    }
    if key.code == KeyCode::Down && !key.modifiers.contains(KeyModifiers::SHIFT) {
        if let Some(next) = app.input_history.navigate_down() {
            app.set_input_text(next);
        }
        return false;
    }
    if key.code == KeyCode::Enter && key.modifiers.contains(KeyModifiers::SHIFT) {
        app.insert_newline();
        return false;
    }
    if key.code == KeyCode::Enter {
        let text = app.get_input_text().trim().to_string();
        if text.is_empty() { return false; }

        if text.starts_with('/') {
            if app.handle_slash_command(&text) {
                app.clear_input();
                return false;
            }
        }

        app.send_message(text.clone());

        let session_id = app.session_id.clone().unwrap_or_default();
        let agent_id = app.agent_id.clone();
        let user_id = app.user_id.clone();
        let project_context = build_project_context(&app.workdir);
        let server_url = app.server_url.clone();
        let server_token = app.token.clone();
        let server_model_id = app.model_id;
        let tx = event_tx.clone();

        tokio::spawn(async move {
            let sse = SseClient::new(server_url, server_token, server_model_id, tx.clone());
            let sid = if session_id.is_empty() {
                match sse.create_session(&agent_id, &user_id).await {
                    Ok(id) => {
                        let session_id_str: String = id;
                        let _ = tx.send(AppEvent::SseEvent(ReActEvent {
                            event: "_session_created".to_string(),
                            content: Some(session_id_str.clone()),
                            tool_call_id: None,
                            tool_name: None,
                            full_text: None,
                            args: None,
                            summary: None,
                            status: None,
                            cmd_id: None,
                            command: None,
                            cwd: None,
                            timeout_ms: None,
                            step_info: None,
                            change_summary: None,
                        }));
                        session_id_str
                    }
                    Err(e) => {
                        let _ = tx.send(AppEvent::Error(e));
                        return;
                    }
                }
            } else { session_id };
            let _ = sse.chat_stream(&agent_id, &user_id, &sid, &text, project_context).await;
        });

        return true;
    }
    if key.code == KeyCode::Backspace { app.backspace(); return false; }
    if key.code == KeyCode::Delete { app.delete_char(); return false; }
    if key.code == KeyCode::Left { app.cursor_left(); return false; }
    if key.code == KeyCode::Right { app.cursor_right(); return false; }
    if key.code == KeyCode::Home { app.cursor_home(); return false; }
    if key.code == KeyCode::End { app.cursor_end(); return false; }
    if key.code == KeyCode::Tab { return false; }
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('p') {
        app.command_palette_open = !app.command_palette_open;
        if app.command_palette_open {
            app.command_palette_filter.clear();
            app.command_palette_selection = 0;
        }
        return false;
    }
    if let KeyCode::Char(c) = key.code { app.insert_char(c); }
    false
}

const COMMAND_PALETTE_ITEMS: &[(&str, &str)] = &[
    ("/help", "Show help information"),
    ("/clear", "Clear conversation"),
    ("/new", "Start new session"),
    ("/sessions", "Show session list"),
    ("/export", "Export conversation to file"),
    ("/status", "Show connection and session status"),
    ("/model <id>", "Switch AI agent"),
    ("/theme <name>", "Switch color theme"),
    ("/quit", "Exit ShellMind CLI"),
];

fn handle_chat_key(app: &mut App, key: KeyEvent, _sse: &SseClient, event_tx: &mpsc::UnboundedSender<AppEvent>) {
    // Command palette mode
    if app.command_palette_open {
        let items: &[(&str, &str)] = COMMAND_PALETTE_ITEMS;
        match key.code {
            KeyCode::Esc => {
                app.command_palette_open = false;
            }
            KeyCode::Enter => {
                let selected = app.command_palette_selection;
                let filtered: Vec<_> = items.iter()
                    .filter(|(cmd, _)| cmd.contains(&app.command_palette_filter))
                    .collect();
                if selected < filtered.len() {
                    let (cmd, _) = filtered[selected];
                    app.command_palette_open = false;
                    app.set_input_text(cmd.to_string() + " ");
                }
            }
            KeyCode::Up => {
                let filtered_count = items.iter()
                    .filter(|(cmd, _)| cmd.contains(&app.command_palette_filter))
                    .count();
                if filtered_count > 0 {
                    app.command_palette_selection = if app.command_palette_selection == 0 {
                        filtered_count.saturating_sub(1)
                    } else {
                        app.command_palette_selection.saturating_sub(1)
                    };
                }
            }
            KeyCode::Down => {
                let filtered_count = items.iter()
                    .filter(|(cmd, _)| cmd.contains(&app.command_palette_filter))
                    .count();
                if filtered_count > 0 {
                    app.command_palette_selection = (app.command_palette_selection + 1) % filtered_count;
                }
            }
            KeyCode::Backspace => {
                app.command_palette_filter.pop();
                app.command_palette_selection = 0;
            }
            KeyCode::Char(c) => {
                app.command_palette_filter.push(c);
                app.command_palette_selection = 0;
            }
            _ => {}
        }
        return;
    }

    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
        app.should_quit = true;
        return;
    }

    if key.code == KeyCode::Esc {
        if app.is_streaming {
            app.is_streaming = false;
            app.messages.push(Message::System { text: "Cancelled".to_string() });
        } else {
            app.manual_scroll = false;
            app.scroll_offset = 0;
        }
        return;
    }

    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('u') {
        app.clear_input();
        return;
    }

    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('k') {
        let (line, col) = app.cursor_pos;
        if line < app.input_lines.len() {
            app.input_lines[line].truncate(col);
        }
        return;
    }

    // Ctrl+A — start of line
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('a') {
        app.cursor_home();
        return;
    }

    // Ctrl+E — end of line
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('e') {
        app.cursor_end();
        return;
    }

    // Ctrl+B — move left
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('b') {
        app.cursor_left();
        return;
    }

    // Ctrl+F — move right
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('f') {
        app.cursor_right();
        return;
    }

    // Ctrl+D — delete char under cursor (or quit if input is empty)
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('d') {
        let text = app.get_input_text();
        if text.is_empty() {
            app.should_quit = true;
        } else {
            app.delete_char();
        }
        return;
    }

    // Ctrl+L — clear screen (keep message history)
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('l') {
        return; // terminal.clear() is handled by the ratatui framework
    }

    // Ctrl+P — command palette
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('p') {
        if !app.is_streaming {
            app.command_palette_open = !app.command_palette_open;
            if app.command_palette_open {
                app.command_palette_filter.clear();
                app.command_palette_selection = 0;
            }
        }
        return;
    }

    // Ctrl+N — next history item
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('n') {
        let (line, _) = app.cursor_pos;
        if line >= app.input_lines.len() - 1 {
            if let Some(next) = app.input_history.navigate_down() {
                app.set_input_text(next);
            }
        } else {
            app.cursor_down();
        }
        return;
    }

    // Tab — slash-command autocomplete
    if key.code == KeyCode::Tab {
        let text = app.get_input_text();
        let trimmed = text.trim();
        if trimmed.starts_with('/') {
            let matching: Vec<_> = COMMAND_PALETTE_ITEMS.iter()
                .map(|(cmd, _)| *cmd)
                .filter(|cmd| cmd.starts_with(trimmed))
                .collect();
            if !matching.is_empty() {
                let best = matching[matching.len() - 1];
                app.set_input_text(best.to_string() + " ");
            }
        }
        return;
    }

    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('w') {
        let (line, col) = app.cursor_pos;
        if line < app.input_lines.len() && col > 0 {
            let content = &app.input_lines[line];
            let word_start = content[..col].trim_end().rfind(' ').map(|i| i + 1).unwrap_or(0);
            app.input_lines[line] = content[..word_start].to_string() + &content[col..];
            app.cursor_pos.1 = word_start;
        }
        return;
    }

    // Scrolling
    match key.code {
        KeyCode::PageUp => {
            app.manual_scroll = true;
            app.scroll_offset = app.scroll_offset.saturating_add(10);
            return;
        }
        KeyCode::PageDown => {
            app.scroll_offset = app.scroll_offset.saturating_sub(10);
            if app.scroll_offset == 0 { app.manual_scroll = false; }
            return;
        }
        KeyCode::Up if key.modifiers.contains(KeyModifiers::SHIFT) => {
            app.manual_scroll = true;
            app.scroll_offset = app.scroll_offset.saturating_add(3);
            return;
        }
        KeyCode::Down if key.modifiers.contains(KeyModifiers::SHIFT) => {
            app.scroll_offset = app.scroll_offset.saturating_sub(3);
            if app.scroll_offset == 0 { app.manual_scroll = false; }
            return;
        }
        _ => {}
    }

    if app.is_streaming { return; }

    // ↑↓ input history (only on first/last line)
    if key.code == KeyCode::Up && !key.modifiers.contains(KeyModifiers::SHIFT) {
        let (line, _) = app.cursor_pos;
        if line == 0 {
            if let Some(prev) = app.input_history.navigate_up(&app.get_input_text()) {
                app.set_input_text(prev);
            }
        } else { app.cursor_up(); }
        return;
    }
    if key.code == KeyCode::Down && !key.modifiers.contains(KeyModifiers::SHIFT) {
        let (line, _) = app.cursor_pos;
        if line >= app.input_lines.len() - 1 {
            if let Some(next) = app.input_history.navigate_down() {
                app.set_input_text(next);
            }
        } else { app.cursor_down(); }
        return;
    }

    if key.code == KeyCode::Left { app.cursor_left(); return; }
    if key.code == KeyCode::Right { app.cursor_right(); return; }
    if key.code == KeyCode::Home { app.cursor_home(); return; }
    if key.code == KeyCode::End { app.cursor_end(); return; }

    if key.code == KeyCode::Enter && key.modifiers.contains(KeyModifiers::SHIFT) {
        app.insert_newline();
        return;
    }

    if key.code == KeyCode::Enter {
        let text = app.get_input_text().trim().to_string();
        if text.is_empty() { return; }

        if text.starts_with('/') {
            if app.handle_slash_command(&text) { return; }
        }

        app.send_message(text.clone());

        let session_id = app.session_id.clone().unwrap_or_default();
        let agent_id = app.agent_id.clone();
        let user_id = app.user_id.clone();
        let project_context = build_project_context(&app.workdir);
        let server_url = app.server_url.clone();
        let server_token = app.token.clone();
        let server_model_id = app.model_id;
        let tx = event_tx.clone();

        tokio::spawn(async move {
            let sse = SseClient::new(server_url, server_token, server_model_id, tx.clone());
            let sid = if session_id.is_empty() {
                match sse.create_session(&agent_id, &user_id).await {
                    Ok(id) => {
                        let session_id_str: String = id;
                        let _ = tx.send(AppEvent::SseEvent(ReActEvent {
                            event: "_session_created".to_string(),
                            content: Some(session_id_str.clone()),
                            tool_call_id: None,
                            tool_name: None,
                            full_text: None,
                            args: None,
                            summary: None,
                            status: None,
                            cmd_id: None,
                            command: None,
                            cwd: None,
                            timeout_ms: None,
                            step_info: None,
                            change_summary: None,
                        }));
                        session_id_str
                    }
                    Err(e) => {
                        let _ = tx.send(AppEvent::Error(e));
                        return;
                    }
                }
            } else { session_id };
            let _ = sse.chat_stream(&agent_id, &user_id, &sid, &text, project_context).await;
        });

        return;
    }

    if key.code == KeyCode::Backspace { app.backspace(); return; }
    if key.code == KeyCode::Delete { app.delete_char(); return; }
    if let KeyCode::Char(c) = key.code { app.insert_char(c); }
}

fn handle_mouse_event(app: &mut App, mouse: MouseEvent) {
    match mouse.kind {
        MouseEventKind::ScrollUp => {
            app.manual_scroll = true;
            app.scroll_offset = app.scroll_offset.saturating_add(3);
        }
        MouseEventKind::ScrollDown => {
            app.scroll_offset = app.scroll_offset.saturating_sub(3);
            if app.scroll_offset == 0 { app.manual_scroll = false; }
        }
        _ => {}
    }
}
