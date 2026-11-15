//! Host-testable mobile input, safe-insets, touch-selection filter, theme state, and file staging helpers.

use std::io::Write;
use std::path::Path;

/// Raw Android system window + IME insets in physical pixels.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct SafeInsetsPx {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
    pub ime_bottom: i32,
}

/// System + IME insets scaled to egui logical points (dp).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SafeInsetsDp {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
    pub ime_bottom: f32,
}

impl SafeInsetsDp {
    /// Converts pixel insets into logical points, clamping negatives and invalid scale factors safely.
    pub fn from_px(px: SafeInsetsPx, pixels_per_point: f32) -> Self {
        let scale = if pixels_per_point.is_finite() && pixels_per_point > 0.1 { pixels_per_point } else { 1.0 };
        Self {
            left: (px.left.max(0) as f32) / scale,
            top: (px.top.max(0) as f32) / scale,
            right: (px.right.max(0) as f32) / scale,
            bottom: (px.bottom.max(0) as f32) / scale,
            ime_bottom: (px.ime_bottom.max(0) as f32) / scale,
        }
    }

    /// Effective bottom clearance reserving space for whichever of navigation bar or IME is taller.
    pub fn bottom_for_ime_aware_panel(self) -> f32 {
        self.bottom.max(self.ime_bottom)
    }
}

/// Supported Android keycodes forwarded from IME/hardware keyboards.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AndroidKey {
    DpadUp,
    DpadDown,
    DpadLeft,
    DpadRight,
    DpadCenter,
    Enter,
    Tab,
    Backspace,
    ForwardDelete,
    Escape,
    MoveHome,
    MoveEnd,
    PageUp,
    PageDown,
    Insert,
    F2,
    Letter(char),
}

impl AndroidKey {
    /// Maps an Android `KeyEvent.KEYCODE_*` constant into [`AndroidKey`].
    pub fn from_keycode(code: i32) -> Option<Self> {
        match code {
            19 => Some(Self::DpadUp),
            20 => Some(Self::DpadDown),
            21 => Some(Self::DpadLeft),
            22 => Some(Self::DpadRight),
            23 => Some(Self::DpadCenter),
            29..=54 => {
                let offset = u32::try_from(code - 29).ok()?;
                let ch = char::from_u32('A' as u32 + offset)?;
                Some(Self::Letter(ch))
            }
            61 => Some(Self::Tab),
            66 | 160 => Some(Self::Enter),
            67 => Some(Self::Backspace),
            111 => Some(Self::Escape),
            112 => Some(Self::ForwardDelete),
            122 => Some(Self::MoveHome),
            123 => Some(Self::MoveEnd),
            92 => Some(Self::PageUp),
            93 => Some(Self::PageDown),
            124 => Some(Self::Insert),
            132 => Some(Self::F2),
            _ => None,
        }
    }

    /// Converts an [`AndroidKey`] to the corresponding [`egui::Key`].
    pub fn to_egui_key(self) -> Option<egui::Key> {
        match self {
            Self::DpadUp => Some(egui::Key::ArrowUp),
            Self::DpadDown => Some(egui::Key::ArrowDown),
            Self::DpadLeft => Some(egui::Key::ArrowLeft),
            Self::DpadRight => Some(egui::Key::ArrowRight),
            Self::DpadCenter | Self::Enter => Some(egui::Key::Enter),
            Self::Tab => Some(egui::Key::Tab),
            Self::Backspace => Some(egui::Key::Backspace),
            Self::ForwardDelete => Some(egui::Key::Delete),
            Self::Escape => Some(egui::Key::Escape),
            Self::MoveHome => Some(egui::Key::Home),
            Self::MoveEnd => Some(egui::Key::End),
            Self::PageUp => Some(egui::Key::PageUp),
            Self::PageDown => Some(egui::Key::PageDown),
            Self::Insert => Some(egui::Key::Insert),
            Self::F2 => Some(egui::Key::F2),
            Self::Letter(ch) => letter_to_egui_key(ch),
        }
    }
}

fn letter_to_egui_key(ch: char) -> Option<egui::Key> {
    match ch {
        'A' => Some(egui::Key::A),
        'B' => Some(egui::Key::B),
        'C' => Some(egui::Key::C),
        'D' => Some(egui::Key::D),
        'E' => Some(egui::Key::E),
        'F' => Some(egui::Key::F),
        'G' => Some(egui::Key::G),
        'H' => Some(egui::Key::H),
        'I' => Some(egui::Key::I),
        'J' => Some(egui::Key::J),
        'K' => Some(egui::Key::K),
        'L' => Some(egui::Key::L),
        'M' => Some(egui::Key::M),
        'N' => Some(egui::Key::N),
        'O' => Some(egui::Key::O),
        'P' => Some(egui::Key::P),
        'Q' => Some(egui::Key::Q),
        'R' => Some(egui::Key::R),
        'S' => Some(egui::Key::S),
        'T' => Some(egui::Key::T),
        'U' => Some(egui::Key::U),
        'V' => Some(egui::Key::V),
        'W' => Some(egui::Key::W),
        'X' => Some(egui::Key::X),
        'Y' => Some(egui::Key::Y),
        'Z' => Some(egui::Key::Z),
        _ => None,
    }
}

/// An atomic IME operation produced by Android's `ImeEditSession`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ImeBatchOp {
    Preedit { text: String, anchor_cp: usize, focus_cp: usize },
    Commit { text: String },
    DeleteSurrounding { before_cp: usize, after_cp: usize },
    SetSelectionRelative { delta_anchor_cp: i32, delta_focus_cp: i32 },
    TapKey { key_code: i32 },
}

/// Queued Android input events delivered to the UI frame hook.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AndroidInput {
    ImeBatch { seq: u64, ops: Vec<ImeBatchOp> },
    KeyDown { key: AndroidKey, ctrl: bool, shift: bool, alt: bool, repeat: u32 },
    KeyUp { key: AndroidKey, ctrl: bool, shift: bool, alt: bool },
}

/// File picker or save-picker outcome reported by the Android activity.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FileAnswer {
    Opened { uri: String, staged_path: String, display_name: String, writable: bool },
    Saved { uri: String, staged_path: String, display_name: String },
    Cancelled,
}

/// Pending file open triggered from `ACTION_VIEW` / `ACTION_EDIT` intents.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PendingOpen {
    pub uri: String,
    pub staged_path: String,
    pub display_name: String,
    pub writable: bool,
}

/// Active external Android `Presentation` display or Google Cast session dimensions.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct PresentationDisplay {
    pub active: bool,
    pub width_px: u32,
    pub height_px: u32,
}

/// Slice of inputs popped per frame from the IME queue.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ImeFrameSlice {
    pub items: Vec<AndroidInput>,
    pub latest_seq: Option<u64>,
}

/// Pops all queued IME/key events for one frame, coalescing consecutive identical cursor-less batches if needed.
pub fn take_ime_frame_slice(queue: &mut Vec<AndroidInput>) -> ImeFrameSlice {
    let items = std::mem::take(queue);
    let latest_seq = items.iter().rev().find_map(|item| match item {
        AndroidInput::ImeBatch { seq, .. } => Some(*seq),
        AndroidInput::KeyDown { .. } | AndroidInput::KeyUp { .. } => None,
    });
    ImeFrameSlice { items, latest_seq }
}

/// Relative caret shift requested by Android `ImeEditSession::setSelection` / `replaceText`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RelativeSelectionMove {
    pub delta_anchor_cp: i32,
    pub delta_focus_cp: i32,
}

/// Result of converting [`AndroidInput`] items into egui events plus optional relative selection moves.
#[derive(Clone, Debug, Default)]
pub struct ConvertedAndroidInput {
    pub events: Vec<egui::Event>,
    pub rel_moves: Vec<RelativeSelectionMove>,
}

/// Converts a slice of queued [`AndroidInput`] events into egui events without exceeding bounded loop counts.
pub fn to_egui_events(slice: &[AndroidInput], ime_active: &mut bool) -> ConvertedAndroidInput {
    let mut out = ConvertedAndroidInput::default();
    for item in slice {
        match item {
            AndroidInput::ImeBatch { ops, .. } => push_batch_ops(ops, ime_active, &mut out),
            AndroidInput::KeyDown { key, ctrl, shift, alt, repeat } => {
                push_key_event(*key, true, *ctrl, *shift, *alt, *repeat > 0, &mut out.events);
            }
            AndroidInput::KeyUp { key, ctrl, shift, alt } => {
                push_key_event(*key, false, *ctrl, *shift, *alt, false, &mut out.events);
            }
        }
    }
    out
}

fn push_batch_ops(ops: &[ImeBatchOp], ime_active: &mut bool, out: &mut ConvertedAndroidInput) {
    const MAX_DELETE_PER_OP: usize = 512;
    for op in ops {
        match op {
            ImeBatchOp::DeleteSurrounding { before_cp, after_cp } => {
                for _ in 0..(*before_cp).min(MAX_DELETE_PER_OP) {
                    tap_egui_key(egui::Key::Backspace, egui::Modifiers::NONE, &mut out.events);
                }
                for _ in 0..(*after_cp).min(MAX_DELETE_PER_OP) {
                    tap_egui_key(egui::Key::Delete, egui::Modifiers::NONE, &mut out.events);
                }
            }
            ImeBatchOp::SetSelectionRelative { delta_anchor_cp, delta_focus_cp } => {
                out.rel_moves.push(RelativeSelectionMove { delta_anchor_cp: *delta_anchor_cp, delta_focus_cp: *delta_focus_cp });
                if delta_anchor_cp == delta_focus_cp {
                    let count = delta_focus_cp.unsigned_abs() as usize;
                    let key = if *delta_focus_cp < 0 { egui::Key::ArrowLeft } else { egui::Key::ArrowRight };
                    for _ in 0..count.min(MAX_DELETE_PER_OP) {
                        tap_egui_key(key, egui::Modifiers::NONE, &mut out.events);
                    }
                }
            }
            ImeBatchOp::Preedit { text, anchor_cp, focus_cp } => {
                *ime_active = !text.is_empty();
                let chars = text.chars().count();
                let lo = (*anchor_cp).min(*focus_cp).min(chars);
                let hi = (*anchor_cp).max(*focus_cp).min(chars);
                out.events.push(egui::Event::Ime(egui::ImeEvent::Preedit {
                    text: text.clone(),
                    active_range_chars: if text.is_empty() { None } else { Some(lo..hi) },
                }));
            }
            ImeBatchOp::Commit { text } => {
                out.events.push(egui::Event::Ime(egui::ImeEvent::Commit(text.clone())));
                *ime_active = false;
            }
            ImeBatchOp::TapKey { key_code } => {
                if let Some(key) = AndroidKey::from_keycode(*key_code).and_then(AndroidKey::to_egui_key) {
                    tap_egui_key(key, egui::Modifiers::NONE, &mut out.events);
                }
            }
        }
    }
}

fn push_key_event(key: AndroidKey, pressed: bool, ctrl: bool, shift: bool, alt: bool, repeat: bool, events: &mut Vec<egui::Event>) {
    let Some(egui_key) = key.to_egui_key() else { return };
    let modifiers = egui::Modifiers { alt, ctrl, shift, mac_cmd: false, command: ctrl };
    events.push(egui::Event::Key { key: egui_key, physical_key: Some(egui_key), pressed, repeat, modifiers });
}

fn tap_egui_key(key: egui::Key, modifiers: egui::Modifiers, events: &mut Vec<egui::Event>) {
    events.push(egui::Event::Key { key, physical_key: Some(key), pressed: true, repeat: false, modifiers });
    events.push(egui::Event::Key { key, physical_key: Some(key), pressed: false, repeat: false, modifiers });
}

/// Converts Android scroll deltas (`VSCROLL`/`HSCROLL` axis units) into an egui `MouseWheel` event.
pub fn wheel_to_egui_event(dx: f32, dy: f32, ctrl: bool, shift: bool) -> Option<egui::Event> {
    if !dx.is_finite() || !dy.is_finite() || (dx == 0.0 && dy == 0.0) {
        return None;
    }
    let modifiers = egui::Modifiers { alt: false, ctrl, shift, mac_cmd: false, command: ctrl };
    Some(egui::Event::MouseWheel {
        unit: egui::MouseWheelUnit::Point,
        delta: egui::vec2(dx, dy),
        phase: egui::TouchPhase::Move,
        source: egui::MouseWheelSource::Unknown,
        modifiers,
    })
}

/// Converts a physical-pixel hover coordinate into a logical `PointerMoved` event.
pub fn hover_to_egui_event(x_px: f32, y_px: f32, pixels_per_point: f32) -> Option<egui::Event> {
    if !x_px.is_finite() || !y_px.is_finite() || !pixels_per_point.is_finite() || pixels_per_point <= 0.1 {
        return None;
    }
    Some(egui::Event::PointerMoved(egui::pos2(x_px / pixels_per_point, y_px / pixels_per_point)))
}

/// Maps a char (codepoint) index inside `s` into a UTF-16 code-unit offset for Android's `InputConnection`.
pub fn char_idx_to_utf16(s: &str, char_idx: usize) -> usize {
    s.chars().take(char_idx).map(char::len_utf16).sum()
}

/// Clamps a UTF-8 char index after applying a signed codepoint offset.
pub fn offset_char_idx(s: &str, base_idx: usize, delta_cp: i32) -> usize {
    let total = s.chars().count();
    let clamped_base = base_idx.min(total);
    if delta_cp >= 0 {
        clamped_base.saturating_add(delta_cp as usize).min(total)
    } else {
        clamped_base.saturating_sub(delta_cp.unsigned_abs() as usize)
    }
}

/// Filters `RawInput` touch events cleanly so one-finger drags scroll the grid in pan mode
/// and select cell ranges (instead of panning) when `select_mode` is enabled.
#[derive(Default, Debug)]
pub struct TouchGestureFilter {
    active_touches: Vec<egui::TouchId>,
    single_finger_selecting: Option<egui::TouchId>,
}

impl TouchGestureFilter {
    /// Updates active touch tracking and, when single-finger selection mode is active inside
    /// `cells_rect`, drops `Event::Touch` so `grid::show` runs native cell selection/fill drags
    /// while preserving multi-touch pinch zoom.
    pub fn process(&mut self, raw: &mut egui::RawInput, select_mode: bool, cells_rect: Option<egui::Rect>) {
        if !select_mode {
            self.single_finger_selecting = None;
        }
        let mut filtered = Vec::with_capacity(raw.events.len());
        for ev in std::mem::take(&mut raw.events) {
            match ev {
                egui::Event::Touch { device_id, id, phase, pos, force } => {
                    match phase {
                        egui::TouchPhase::Start => {
                            if !self.active_touches.contains(&id) {
                                self.active_touches.push(id);
                            }
                            if self.active_touches.len() > 1 {
                                self.single_finger_selecting = None;
                            } else if select_mode && cells_rect.is_some_and(|r| r.contains(pos)) {
                                self.single_finger_selecting = Some(id);
                            }
                        }
                        egui::TouchPhase::End | egui::TouchPhase::Cancel => {
                            self.active_touches.retain(|t| *t != id);
                            if self.single_finger_selecting == Some(id) {
                                self.single_finger_selecting = None;
                                continue;
                            }
                        }
                        egui::TouchPhase::Move => {}
                    }
                    if self.single_finger_selecting == Some(id) {
                        continue;
                    }
                    filtered.push(egui::Event::Touch { device_id, id, phase, pos, force });
                }
                other => filtered.push(other),
            }
        }
        raw.events = filtered;
    }
}

/// Three-state UI theme preference (System / Light / Dark — no sepia).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum AndroidThemeMode {
    #[default]
    System,
    Light,
    Dark,
}

impl AndroidThemeMode {
    pub fn cycle(self) -> Self {
        match self {
            Self::System => Self::Light,
            Self::Light => Self::Dark,
            Self::Dark => Self::System,
        }
    }

    pub fn engine_mode(self) -> &'static str {
        match self {
            Self::System => "system",
            Self::Light => "light",
            Self::Dark => "dark",
        }
    }

    pub fn parse(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "light" => Self::Light,
            "dark" => Self::Dark,
            _ => Self::System,
        }
    }

    pub fn is_dark(self, system_night: bool) -> bool {
        match self {
            Self::System => system_night,
            Self::Light => false,
            Self::Dark => true,
        }
    }
}

/// Compact Polish and English strings for the mobile shell toolbar.
#[derive(Clone, Copy, Debug)]
pub struct MobileStrings {
    pub is_polish: bool,
    pub backstage: &'static str,
    pub open: &'static str,
    pub new_doc: &'static str,
    pub save: &'static str,
    pub save_as: &'static str,
    pub undo: &'static str,
    pub redo: &'static str,
    pub bold: &'static str,
    pub formula: &'static str,
    pub select: &'static str,
    pub keyboard: &'static str,
    pub ribbon: &'static str,
    pub theme_auto: &'static str,
    pub theme_light: &'static str,
    pub theme_dark: &'static str,
    pub cast: &'static str,
    pub saving: &'static str,
}

impl MobileStrings {
    pub fn from_locale_tag(tag: &str) -> Self {
        if tag.trim().to_ascii_lowercase().starts_with("pl") {
            Self {
                is_polish: true,
                backstage: "Plik",
                open: "Otwórz",
                new_doc: "Nowy",
                save: "Zapisz",
                save_as: "Zapisz jako",
                undo: "Cofnij",
                redo: "Ponów",
                bold: "B",
                formula: "fx",
                select: "Zazn",
                keyboard: "Klaw",
                ribbon: "Wst",
                theme_auto: "Auto",
                theme_light: "Jasny",
                theme_dark: "Ciemny",
                cast: "TV",
                saving: "Zapisywanie…",
            }
        } else {
            Self {
                is_polish: false,
                backstage: "File",
                open: "Open",
                new_doc: "New",
                save: "Save",
                save_as: "Save As",
                undo: "Undo",
                redo: "Redo",
                bold: "B",
                formula: "fx",
                select: "Sel",
                keyboard: "Keyb",
                ribbon: "Rib",
                theme_auto: "Auto",
                theme_light: "Light",
                theme_dark: "Dark",
                cast: "TV",
                saving: "Saving…",
            }
        }
    }

    pub fn theme_label(&self, mode: AndroidThemeMode) -> &'static str {
        match mode {
            AndroidThemeMode::System => self.theme_auto,
            AndroidThemeMode::Light => self.theme_light,
            AndroidThemeMode::Dark => self.theme_dark,
        }
    }
}

/// Computes target Cast/Presentation bitmap dimensions keeping aspect ratio within `max_w × max_h`.
pub fn fit_cast_dimensions(src_w: usize, src_h: usize, max_w: u32, max_h: u32) -> Option<(u32, u32)> {
    if src_w == 0 || src_h == 0 || max_w == 0 || max_h == 0 {
        return None;
    }
    let sw = src_w as f64;
    let sh = src_h as f64;
    let scale = ((max_w as f64) / sw).min((max_h as f64) / sh).min(1.0);
    let w = (sw * scale).round().max(1.0) as u32;
    let h = (sh * scale).round().max(1.0) as u32;
    Some((w.min(4096), h.min(4096)))
}

/// Nearest-neighbor scales RGBA pixels from `src` (`src_w × src_h`) into a `dst_w × dst_h` buffer.
pub fn downsample_rgba(pixels: &[egui::Color32], src_w: usize, src_h: usize, dst_w: u32, dst_h: u32) -> Vec<u8> {
    let dw = dst_w as usize;
    let dh = dst_h as usize;
    if src_w == 0 || src_h == 0 || dw == 0 || dh == 0 || pixels.len() < src_w.saturating_mul(src_h) {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(dw.saturating_mul(dh).saturating_mul(4));
    for y in 0..dh {
        let sy = (y.saturating_mul(src_h)) / dh;
        let row_base = sy.saturating_mul(src_w);
        for x in 0..dw {
            let sx = (x.saturating_mul(src_w)) / dw;
            let px = pixels.get(row_base + sx).copied().unwrap_or(egui::Color32::WHITE);
            out.extend_from_slice(&px.to_array());
        }
    }
    out
}

/// Sanitizes an Android SAF display name into a safe staging file name inside private cache storage.
pub fn sanitize_stage_filename(raw: &str) -> String {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("").trim();
    let cleaned: String = base.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_') { c } else { '_' }).collect();
    let trimmed = cleaned.trim_matches('.').trim_matches('_');
    if trimmed.is_empty() { "workbook.xlsx".to_string() } else { trimmed.chars().take(120).collect() }
}

/// Atomically writes small preference text using a temporary sibling file and rename.
pub fn prefs_write_atomic(path: &Path, body: &str) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("tmp");
    {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(body.as_bytes())?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, path)
}

/// Persisted shell settings loaded cleanly at startup.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ShellPrefs {
    pub theme_mode: AndroidThemeMode,
    pub select_mode: bool,
}

pub fn parse_prefs(text: &str) -> ShellPrefs {
    let mut prefs = ShellPrefs::default();
    for line in text.lines() {
        if let Some((k, v)) = line.split_once('=') {
            match k.trim() {
                "theme" => prefs.theme_mode = AndroidThemeMode::parse(v),
                "select_mode" => prefs.select_mode = matches!(v.trim(), "1" | "true"),
                _ => {}
            }
        }
    }
    prefs
}

pub fn format_prefs(prefs: ShellPrefs) -> String {
    format!("theme={}\nselect_mode={}\n", prefs.theme_mode.engine_mode(), usize::from(prefs.select_mode))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn theme_mode_cycles_only_system_light_dark_without_sepia() {
        assert_eq!(AndroidThemeMode::System.cycle(), AndroidThemeMode::Light);
        assert_eq!(AndroidThemeMode::Light.cycle(), AndroidThemeMode::Dark);
        assert_eq!(AndroidThemeMode::Dark.cycle(), AndroidThemeMode::System);
        assert_eq!(AndroidThemeMode::parse("sepia"), AndroidThemeMode::System);
        assert_eq!(AndroidThemeMode::parse("DARK"), AndroidThemeMode::Dark);
        assert!(AndroidThemeMode::System.is_dark(true));
        assert!(!AndroidThemeMode::System.is_dark(false));
        assert!(!AndroidThemeMode::Light.is_dark(true));
        assert!(AndroidThemeMode::Dark.is_dark(false));
    }

    #[test]
    fn safe_insets_scales_and_picks_taller_bottom() {
        let dp = SafeInsetsDp::from_px(SafeInsetsPx { left: 0, top: 150, right: 0, bottom: 72, ime_bottom: 900 }, 3.0);
        assert_eq!(dp.top, 50.0);
        assert_eq!(dp.bottom, 24.0);
        assert_eq!(dp.ime_bottom, 300.0);
        assert_eq!(dp.bottom_for_ime_aware_panel(), 300.0);
    }

    #[test]
    fn ime_batch_converts_delete_surrounding_and_commit() {
        let slice = [AndroidInput::ImeBatch {
            seq: 7,
            ops: vec![ImeBatchOp::DeleteSurrounding { before_cp: 2, after_cp: 0 }, ImeBatchOp::Commit { text: "Żółć".into() }],
        }];
        let mut active = false;
        let out = to_egui_events(&slice, &mut active);
        assert!(!active);
        assert_eq!(out.events.len(), 5);
        assert!(matches!(out.events.last(), Some(egui::Event::Ime(egui::ImeEvent::Commit(s))) if s == "Żółć"));
    }

    #[test]
    fn touch_filter_strips_single_finger_touch_only_in_select_mode_grid() {
        let mut filter = TouchGestureFilter::default();
        let grid = Some(egui::Rect::from_min_max(egui::pos2(0.0, 100.0), egui::pos2(400.0, 800.0)));
        let mut raw = egui::RawInput {
            events: vec![
                egui::Event::Touch {
                    device_id: egui::TouchDeviceId(1),
                    id: egui::TouchId(0),
                    phase: egui::TouchPhase::Start,
                    pos: egui::pos2(50.0, 200.0),
                    force: None,
                },
                egui::Event::PointerMoved(egui::pos2(50.0, 200.0)),
            ],
            ..Default::default()
        };
        filter.process(&mut raw, true, grid);
        assert_eq!(raw.events.len(), 1);
        assert!(matches!(raw.events[0], egui::Event::PointerMoved(_)));

        let mut pinch = egui::RawInput {
            events: vec![egui::Event::Touch {
                device_id: egui::TouchDeviceId(1),
                id: egui::TouchId(1),
                phase: egui::TouchPhase::Start,
                pos: egui::pos2(150.0, 250.0),
                force: None,
            }],
            ..Default::default()
        };
        filter.process(&mut pinch, true, grid);
        assert_eq!(pinch.events.len(), 1);
        assert!(matches!(pinch.events[0], egui::Event::Touch { .. }));
    }

    #[test]
    fn sanitize_filename_and_prefs_roundtrip() {
        assert_eq!(sanitize_stage_filename("../Raport Q3 (final).xlsx"), "Raport_Q3__final_.xlsx");
        assert_eq!(sanitize_stage_filename("///"), "workbook.xlsx");
        let prefs = ShellPrefs { theme_mode: AndroidThemeMode::Dark, select_mode: true };
        assert_eq!(parse_prefs(&format_prefs(prefs)), prefs);
    }

    #[test]
    fn char_and_utf16_offsets_handle_polish_and_emoji() {
        let s = "A😊ż";
        assert_eq!(char_idx_to_utf16(s, 0), 0);
        assert_eq!(char_idx_to_utf16(s, 1), 1);
        assert_eq!(char_idx_to_utf16(s, 2), 3);
        assert_eq!(char_idx_to_utf16(s, 3), 4);
        assert_eq!(offset_char_idx(s, 2, -1), 1);
        assert_eq!(offset_char_idx(s, 1, 5), 3);
    }

    #[test]
    fn cast_dimensions_and_downsample_respect_limits() {
        assert_eq!(fit_cast_dimensions(2560, 1440, 1920, 1080), Some((1920, 1080)));
        let pixels = vec![egui::Color32::from_rgb(10, 20, 30); 4];
        let rgba = downsample_rgba(&pixels, 2, 2, 1, 1);
        assert_eq!(rgba, vec![10, 20, 30, 255]);
    }
}
