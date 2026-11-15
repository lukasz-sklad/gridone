//! Android `android_main` entry point, SAF file bridge, mobile quick toolbar, and Cast streaming.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime};

use eframe::egui_wgpu;
use eframe::wgpu;
use gridcraft_engine::Session;
use gridcraft_ui_egui::{Chrome, Services, SheetApp, i18n, theme};
use jni::objects::{JString, JValue};
use jni::{jni_sig, jni_str};
use serde_json::json;

use crate::ime;
use crate::mobile::{
    FileAnswer, MobileStrings, SafeInsetsDp, SafeInsetsPx, ShellPrefs, TouchGestureFilter, char_idx_to_utf16, downsample_rgba, fit_cast_dimensions,
    format_prefs, hover_to_egui_event, is_allowed_external_url, offset_char_idx, parse_prefs, prefs_write_atomic, take_ime_frame_slice,
    to_egui_events, wheel_to_egui_event,
};

struct ActivityBridge;

impl ActivityBridge {
    fn files_dir() -> Option<String> {
        ime::with_activity(|env, act| {
            let val = env.call_method(act, jni_str!("jniGetFilesDir"), jni_sig!("()Ljava/lang/String;"), &[])?;
            let obj = val.l()?;
            if obj.is_null() {
                return Ok(None);
            }
            Ok(Some(env.cast_local::<JString>(obj)?.try_to_string(env)?))
        })
        .flatten()
    }

    fn device_locale_tag() -> String {
        ime::with_activity(|env, act| {
            let val = env.call_method(act, jni_str!("jniGetLocaleTag"), jni_sig!("()Ljava/lang/String;"), &[])?;
            let obj = val.l()?;
            if obj.is_null() {
                return Ok(String::from("en"));
            }
            env.cast_local::<JString>(obj)?.try_to_string(env)
        })
        .unwrap_or_else(|| String::from("en"))
    }

    fn device_night_mode() -> bool {
        ime::with_activity(|env, act| env.call_method(act, jni_str!("jniIsNightMode"), jni_sig!("()Z"), &[])?.z()).unwrap_or(false)
    }

    fn is_play_flavor() -> bool {
        ime::with_activity(|env, act| env.call_method(act, jni_str!("jniIsPlayFlavor"), jni_sig!("()Z"), &[])?.z()).unwrap_or(false)
    }

    fn launch_open_picker() {
        let _ = ime::with_activity(|env, act| {
            env.call_method(act, jni_str!("jniLaunchOpenPicker"), jni_sig!("()V"), &[])?;
            Ok(())
        });
    }

    fn launch_save_picker(suggested_name: &str) {
        let _ = ime::with_activity(|env, act| {
            let jname = env.new_string(suggested_name)?;
            env.call_method(act, jni_str!("jniLaunchSavePicker"), jni_sig!("(Ljava/lang/String;)V"), &[JValue::Object(&jname)])?;
            Ok(())
        });
    }

    fn write_uri_bytes(uri: &str, bytes: &[u8]) -> bool {
        ime::with_activity(|env, act| {
            let juri = env.new_string(uri)?;
            let jbytes = env.byte_array_from_slice(bytes)?;
            env.call_method(
                act,
                jni_str!("jniWriteUriBytes"),
                jni_sig!("(Ljava/lang/String;[B)Z"),
                &[JValue::Object(&juri), JValue::Object(&jbytes)],
            )?
            .z()
        })
        .unwrap_or(false)
    }

    fn show_ime(single_line: bool) {
        let _ = ime::with_activity(|env, act| {
            env.call_method(act, jni_str!("jniShowIme"), jni_sig!("(Z)V"), &[JValue::Bool(single_line)])?;
            Ok(())
        });
    }

    fn hide_ime() {
        let _ = ime::with_activity(|env, act| {
            env.call_method(act, jni_str!("jniHideIme"), jni_sig!("()V"), &[])?;
            Ok(())
        });
    }

    fn sync_ime_text(text: &str, anchor_u16: usize, focus_u16: usize, ack_seq: u64) {
        let _ = ime::with_activity(|env, act| {
            let jtext = env.new_string(text)?;
            env.call_method(
                act,
                jni_str!("jniSyncImeText"),
                jni_sig!("(Ljava/lang/String;IIJ)V"),
                &[
                    JValue::Object(&jtext),
                    JValue::Int(i32::try_from(anchor_u16).unwrap_or(i32::MAX)),
                    JValue::Int(i32::try_from(focus_u16).unwrap_or(i32::MAX)),
                    JValue::Long(i64::try_from(ack_seq).unwrap_or(0)),
                ],
            )?;
            Ok(())
        });
    }

    fn set_clipboard_text(text: &str) {
        let _ = ime::with_activity(|env, act| {
            let jtext = env.new_string(text)?;
            env.call_method(act, jni_str!("jniSetClipboardText"), jni_sig!("(Ljava/lang/String;)V"), &[JValue::Object(&jtext)])?;
            Ok(())
        });
    }

    fn get_clipboard_text() -> Option<String> {
        ime::with_activity(|env, act| {
            let val = env.call_method(act, jni_str!("jniGetClipboardText"), jni_sig!("()Ljava/lang/String;"), &[])?;
            let obj = val.l()?;
            if obj.is_null() {
                return Ok(None);
            }
            let s = env.cast_local::<JString>(obj)?.try_to_string(env)?;
            Ok((!s.is_empty()).then_some(s))
        })
        .flatten()
    }

    fn open_url(url: &str) {
        let _ = ime::with_activity(|env, act| {
            let jurl = env.new_string(url)?;
            env.call_method(act, jni_str!("jniOpenUrl"), jni_sig!("(Ljava/lang/String;)V"), &[JValue::Object(&jurl)])?;
            Ok(())
        });
    }

    fn open_cast_picker() {
        let _ = ime::with_activity(|env, act| {
            env.call_method(act, jni_str!("jniOpenCastMenu"), jni_sig!("()V"), &[])?;
            Ok(())
        });
    }

    fn present_rgba_frame(rgba: &[u8], width: u32, height: u32, backdrop_argb: i32) {
        let _ = ime::with_activity(|env, act| {
            let jbytes = env.byte_array_from_slice(rgba)?;
            env.call_method(
                act,
                jni_str!("jniPresentRgbaFrame"),
                jni_sig!("([BIII)V"),
                &[
                    JValue::Object(&jbytes),
                    JValue::Int(i32::try_from(width).unwrap_or(0)),
                    JValue::Int(i32::try_from(height).unwrap_or(0)),
                    JValue::Int(backdrop_argb),
                ],
            )?;
            Ok(())
        });
    }

    fn clear_presentation_frame() {
        let _ = ime::with_activity(|env, act| {
            env.call_method(act, jni_str!("jniClearPresentationFrame"), jni_sig!("()V"), &[])?;
            Ok(())
        });
    }

    fn set_system_bars_dark(dark_ui: bool) {
        let _ = ime::with_activity(|env, act| {
            env.call_method(act, jni_str!("jniSetSystemBarsDark"), jni_sig!("(Z)V"), &[JValue::Bool(dark_ui)])?;
            Ok(())
        });
    }
}

#[derive(Clone)]
struct StagedEntry {
    uri: String,
    writable: bool,
    last_len: u64,
    last_mtime: Option<SystemTime>,
}

enum PendingSaveIntent {
    WorkbookSaveAs,
    ExportBytes { bytes: Vec<u8> },
}

struct SafState {
    tracked: HashMap<String, StagedEntry>,
    pending_save: Arc<Mutex<Option<PendingSaveIntent>>>,
}

impl SafState {
    fn new(pending_save: Arc<Mutex<Option<PendingSaveIntent>>>) -> Self {
        Self { tracked: HashMap::new(), pending_save }
    }

    fn track(&mut self, staged_path: String, uri: String, writable: bool) {
        let (last_len, last_mtime) = file_sig(Path::new(&staged_path));
        self.tracked.insert(staged_path, StagedEntry { uri, writable, last_len, last_mtime });
    }

    fn flush_changed_outputs(&mut self) {
        let mut updates = Vec::new();
        for (path, entry) in &mut self.tracked {
            if !entry.writable {
                continue;
            }
            let (len, mtime) = file_sig(Path::new(path));
            if (len, mtime) != (entry.last_len, entry.last_mtime) {
                if let Ok(bytes) = std::fs::read(path)
                    && ActivityBridge::write_uri_bytes(&entry.uri, &bytes)
                {
                    updates.push((path.clone(), len, mtime));
                }
            }
        }
        for (path, len, mtime) in updates {
            if let Some(entry) = self.tracked.get_mut(&path) {
                entry.last_len = len;
                entry.last_mtime = mtime;
            }
        }
    }
}

fn file_sig(path: &Path) -> (u64, Option<SystemTime>) {
    match std::fs::metadata(path) {
        Ok(m) => (m.len(), m.modified().ok()),
        Err(_) => (0, None),
    }
}

fn build_services(pending_save: &Arc<Mutex<Option<PendingSaveIntent>>>) -> Services {
    let pending_dl = Arc::clone(pending_save);
    let pending_sa = Arc::clone(pending_save);
    Services {
        open_async: Some(Box::new(ActivityBridge::launch_open_picker)),
        save_async: Some(Box::new(move |suggested| {
            if let Ok(mut g) = pending_sa.lock() {
                *g = Some(PendingSaveIntent::WorkbookSaveAs);
            }
            ActivityBridge::launch_save_picker(suggested);
        })),
        download: Some(Box::new(move |name, bytes| {
            if let Ok(mut g) = pending_dl.lock() {
                *g = Some(PendingSaveIntent::ExportBytes { bytes: bytes.to_vec() });
            }
            ActivityBridge::launch_save_picker(name);
        })),
        open_url: Some(Box::new(|url| {
            if is_allowed_external_url(url) {
                ActivityBridge::open_url(url);
            } else {
                log::warn!("Blocked disallowed external URL");
            }
        })),
        paste_text: Some(Box::new(ActivityBridge::get_clipboard_text)),
        ..Default::default()
    }
}

struct CastFrameBridge {
    latest: Arc<Mutex<Option<Arc<egui::ColorImage>>>>,
    last_request: Instant,
    in_flight: bool,
    last_revision: u64,
    last_scroll: egui::Vec2,
    was_active: bool,
}

impl Default for CastFrameBridge {
    fn default() -> Self {
        Self {
            latest: Arc::new(Mutex::new(None)),
            last_request: Instant::now() - Duration::from_secs(1),
            in_flight: false,
            last_revision: 0,
            last_scroll: egui::Vec2::ZERO,
            was_active: false,
        }
    }
}

impl CastFrameBridge {
    fn poll(&mut self, app: &SheetApp, ctx: &egui::Context, dark: bool) {
        let disp = ime::presentation_display();
        if !disp.active {
            if self.was_active {
                self.was_active = false;
                self.in_flight = false;
                ActivityBridge::clear_presentation_frame();
            }
            return;
        }
        let became_active = !self.was_active || ime::take_cast_change_pending();
        self.was_active = true;

        let captured = self.latest.lock().ok().and_then(|mut g| g.take());
        if let Some(img) = captured {
            self.in_flight = false;
            let [sw, sh] = img.size;
            let max_w = disp.width_px.clamp(320, 1920);
            let max_h = disp.height_px.clamp(240, 1080);
            if let Some((dw, dh)) = fit_cast_dimensions(sw, sh, max_w, max_h) {
                let rgba = downsample_rgba(&img.pixels, sw, sh, dw, dh);
                let backdrop = if dark { 0xFF292929u32 as i32 } else { 0xFFF3F3F3u32 as i32 };
                ActivityBridge::present_rgba_frame(&rgba, dw, dh, backdrop);
            }
        }

        let rev = app.session.active().map(|d| d.revision).unwrap_or(0);
        let scroll = app.view().scroll;
        let changed = became_active || rev != self.last_revision || scroll != self.last_scroll;
        if !self.in_flight && (changed || self.last_request.elapsed() >= Duration::from_millis(600)) {
            if self.last_request.elapsed() >= Duration::from_millis(180) || became_active {
                self.last_revision = rev;
                self.last_scroll = scroll;
                self.last_request = Instant::now();
                self.in_flight = true;
                let slot = Arc::clone(&self.latest);
                let wake_ctx = ctx.clone();
                let cb = egui::ScreenshotCallback::new(move |image| {
                    if let Ok(mut g) = slot.lock() {
                        *g = Some(image);
                    }
                    wake_ctx.request_repaint();
                });
                ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot(cb));
            }
        }
    }
}

struct GridAndroidShell {
    app: SheetApp,
    saf: SafState,
    prefs_path: Option<PathBuf>,
    prefs: ShellPrefs,
    strings: MobileStrings,
    play_flavor: bool,
    touch_filter: TouchGestureFilter,
    insets_px: SafeInsetsPx,
    insets_dp: SafeInsetsDp,
    ime_active: bool,
    last_wants_kbd: bool,
    last_ack_seq: u64,
    last_ime_sync: (String, usize, usize, u64),
    pending_rel_moves: Vec<(i32, i32)>,
    last_bars_dark: Option<bool>,
    cast: CastFrameBridge,
}

impl GridAndroidShell {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let locale_tag = ActivityBridge::device_locale_tag();
        let strings = MobileStrings::from_locale_tag(&locale_tag);
        let play_flavor = ActivityBridge::is_play_flavor();
        let files_dir = ActivityBridge::files_dir().map(PathBuf::from);
        let prefs_path = files_dir.map(|d| d.join("gridone_shell.prefs"));
        let prefs = prefs_path.as_deref().and_then(|p| std::fs::read_to_string(p).ok()).map(|s| parse_prefs(&s)).unwrap_or_default();

        let night = ActivityBridge::device_night_mode();
        let initial_dark = prefs.theme_mode.is_dark(night);
        let lang = i18n::Language::parse(&locale_tag).unwrap_or(i18n::Language::En);
        SheetApp::setup_context_for_language(&cc.egui_ctx, initial_dark, lang);

        let mut session = Session::new();
        session.new_workbook();
        let pending_save = Arc::new(Mutex::new(None));
        let services = build_services(&pending_save);
        let mut app = SheetApp::new(session, services);
        app.chrome = Chrome { title_bar: false, file_tab: true };
        app.ui.language = lang;
        app.ui.ribbon_collapsed = true;
        app.ui.highlight_active_row = true;
        app.set_system_dark(night);
        let _ = app.run("view.theme", json!({"mode": prefs.theme_mode.engine_mode()}));

        ActivityBridge::set_system_bars_dark(initial_dark);

        Self {
            app,
            saf: SafState::new(pending_save),
            prefs_path,
            prefs,
            strings,
            play_flavor,
            touch_filter: TouchGestureFilter::default(),
            insets_px: SafeInsetsPx::default(),
            insets_dp: SafeInsetsDp::default(),
            ime_active: false,
            last_wants_kbd: false,
            last_ack_seq: 0,
            last_ime_sync: (String::new(), 0, 0, 0),
            pending_rel_moves: Vec::new(),
            last_bars_dark: Some(initial_dark),
            cast: CastFrameBridge::default(),
        }
    }

    fn save_prefs(&self) {
        if let Some(path) = &self.prefs_path {
            let _ = prefs_write_atomic(path, &format_prefs(self.prefs));
        }
    }

    fn drain_file_events(&mut self) {
        let (pending_opens, file_answer) = if let Ok(mut q) = ime::queues().lock() {
            (std::mem::take(&mut q.pending_opens), q.file_answer.take())
        } else {
            return;
        };
        for open in pending_opens {
            self.saf.track(open.staged_path.clone(), open.uri, open.writable);
            self.app.open_path(&open.staged_path);
        }
        if let Some(answer) = file_answer {
            match answer {
                FileAnswer::Opened { uri, staged_path, writable, .. } => {
                    self.saf.track(staged_path.clone(), uri, writable);
                    self.app.open_path(&staged_path);
                }
                FileAnswer::Saved { uri, staged_path, .. } => {
                    let pending = self.saf.pending_save.lock().ok().and_then(|mut g| g.take());
                    match pending {
                        Some(PendingSaveIntent::ExportBytes { bytes }) => {
                            let _ = std::fs::write(&staged_path, &bytes);
                            let _ = ActivityBridge::write_uri_bytes(&uri, &bytes);
                        }
                        _ => {
                            self.saf.track(staged_path.clone(), uri, true);
                            self.app.run_or_alert("file.save", json!({"path": staged_path}));
                            self.saf.flush_changed_outputs();
                        }
                    }
                }
                FileAnswer::Cancelled => {
                    if let Ok(mut g) = self.saf.pending_save.lock() {
                        *g = None;
                    }
                }
            }
        }
    }

    fn apply_editor_relative_moves(&mut self) {
        if self.pending_rel_moves.is_empty() {
            return;
        }
        let moves = std::mem::take(&mut self.pending_rel_moves);
        if let Some(ed) = self.app.editor.as_mut() {
            for (d_anchor, d_focus) in moves {
                let base = ed.caret;
                ed.anchor = offset_char_idx(&ed.text, base, d_anchor);
                ed.caret = offset_char_idx(&ed.text, base, d_focus);
                ed.sync_caret = true;
            }
        }
    }

    fn sync_ime_bridge(&mut self, ime_output_active: bool) {
        let wants_kbd = self.app.editor.is_some() || self.app.name_box.is_some() || self.app.text_box_editor.is_some() || ime_output_active;

        let ime_dismissed = ime::queues().lock().map(|mut q| std::mem::take(&mut q.ime_dismissed)).unwrap_or(false);
        if ime_dismissed {
            self.last_wants_kbd = wants_kbd;
        } else if wants_kbd && !self.last_wants_kbd {
            let single_line = self.app.name_box.is_some();
            ActivityBridge::show_ime(single_line);
        } else if !wants_kbd && self.last_wants_kbd {
            ActivityBridge::hide_ime();
        }
        self.last_wants_kbd = wants_kbd;

        if let Some(ed) = self.app.editor.as_ref() {
            let anchor_u16 = char_idx_to_utf16(&ed.text, ed.anchor);
            let focus_u16 = char_idx_to_utf16(&ed.text, ed.caret);
            let snapshot = (ed.text.clone(), anchor_u16, focus_u16, self.last_ack_seq);
            if snapshot != self.last_ime_sync {
                ActivityBridge::sync_ime_text(&snapshot.0, snapshot.1, snapshot.2, snapshot.3);
                self.last_ime_sync = snapshot;
            }
        } else if self.app.name_box.is_some() {
            let txt = self.app.name_box.as_deref().unwrap_or("");
            let len_u16 = txt.encode_utf16().count();
            let snapshot = (txt.to_string(), len_u16, len_u16, self.last_ack_seq);
            if snapshot != self.last_ime_sync {
                ActivityBridge::sync_ime_text(&snapshot.0, snapshot.1, snapshot.2, snapshot.3);
                self.last_ime_sync = snapshot;
            }
        }
    }

    fn draw_mobile_toolbar(&mut self, ui: &mut egui::Ui) {
        let t = theme::Tokens::get(ui.ctx());
        let s = self.strings;
        egui::Panel::top("android_mobile_bar")
            .exact_size(40.0)
            .frame(egui::Frame::NONE.fill(t.window).inner_margin(egui::Margin::symmetric(6, 4)))
            .show(ui, |ui| {
                egui::ScrollArea::horizontal().id_salt("android_toolbar_scroll").show(ui, |ui| {
                    ui.horizontal_centered(|ui| {
                        ui.spacing_mut().item_spacing.x = 4.0;
                        if ui.button(s.backstage).clicked() {
                            if self.app.backstage.is_some() {
                                self.app.backstage = None;
                            } else {
                                self.app.open_backstage(gridcraft_ui_egui::backstage::Page::Home);
                            }
                        }
                        if ui.button(s.open).clicked() {
                            ActivityBridge::launch_open_picker();
                        }
                        if ui.button(s.new_doc).clicked() {
                            self.app.run_or_alert("file.new", json!({}));
                        }
                        if ui.button(s.save).clicked() {
                            let has_path = self.app.session.active().and_then(|d| d.path.as_deref()).is_some();
                            if has_path {
                                self.app.run_or_alert("file.save", json!({}));
                                self.saf.flush_changed_outputs();
                            } else {
                                self.app.open_dialog("saveAs", json!({}));
                            }
                        }
                        ui.separator();
                        if ui.button(s.undo).clicked() {
                            self.app.run_or_alert("edit.undo", json!({}));
                        }
                        if ui.button(s.redo).clicked() {
                            self.app.run_or_alert("edit.redo", json!({}));
                        }
                        ui.separator();
                        if ui.button(s.bold).clicked() {
                            self.app.run_or_alert("home.bold", json!({}));
                        }
                        if ui.button(s.formula).clicked() {
                            if self.app.editor.is_none() {
                                self.app.begin_edit(Some(String::from("=")), true);
                            } else if let Some(ed) = self.app.editor.as_mut() {
                                ed.from_formula_bar = true;
                                ed.request_focus = true;
                            }
                            ActivityBridge::show_ime(false);
                        }
                        if ui.selectable_label(self.prefs.select_mode, s.select).clicked() {
                            self.prefs.select_mode = !self.prefs.select_mode;
                            self.save_prefs();
                        }
                        let editing = self.app.editor.is_some();
                        if ui.selectable_label(editing, s.keyboard).clicked() {
                            if !editing {
                                self.app.begin_edit(None, false);
                                ActivityBridge::show_ime(false);
                            } else {
                                ActivityBridge::hide_ime();
                            }
                        }
                        let rib_open = !self.app.ui.ribbon_collapsed;
                        if ui.selectable_label(rib_open, s.ribbon).clicked() {
                            self.app.ui.ribbon_collapsed = !self.app.ui.ribbon_collapsed;
                        }
                        ui.separator();
                        if ui.button("-").clicked() {
                            self.step_zoom(-15);
                        }
                        if ui.button("+").clicked() {
                            self.step_zoom(15);
                        }
                        if ui.button(s.theme_label(self.prefs.theme_mode)).clicked() {
                            self.prefs.theme_mode = self.prefs.theme_mode.cycle();
                            let _ = self.app.run("view.theme", json!({"mode": self.prefs.theme_mode.engine_mode()}));
                            self.save_prefs();
                        }
                        let disp = ime::presentation_display();
                        if self.play_flavor || disp.active {
                            if ui.selectable_label(disp.active, s.cast).clicked() {
                                ActivityBridge::open_cast_picker();
                            }
                        }
                    });
                });
            });
    }

    fn step_zoom(&mut self, delta: i32) {
        let cur = self.app.session.active().and_then(|d| d.wb.active()).map(|s| s.zoom as i32).unwrap_or(100);
        let next = (cur + delta).clamp(40, 250);
        self.app.run_or_alert("view.zoom", json!({"percent": next}));
    }
}

impl eframe::App for GridAndroidShell {
    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if let Ok(q) = ime::queues().lock() {
            self.insets_px = q.insets;
        }
        self.insets_dp = SafeInsetsDp::from_px(self.insets_px, ctx.pixels_per_point());
        self.drain_file_events();
        self.app.set_system_dark(ime::system_night_mode());
        self.app.logic(ctx);
        self.saf.flush_changed_outputs();
        let dark = self.prefs.theme_mode.is_dark(ime::system_night_mode());
        if self.last_bars_dark != Some(dark) {
            self.last_bars_dark = Some(dark);
            ActivityBridge::set_system_bars_dark(dark);
        }
        self.cast.poll(&self.app, ctx, dark);
    }

    fn raw_input_hook(&mut self, ctx: &egui::Context, raw_input: &mut egui::RawInput) {
        let (items, wheels, hover) = if let Ok(mut q) = ime::queues().lock() {
            (take_ime_frame_slice(&mut q.inputs), std::mem::take(&mut q.wheel_deltas), q.mouse_hover_px.take())
        } else {
            return;
        };
        if let Some(seq) = items.latest_seq {
            self.last_ack_seq = seq;
        }
        let converted = to_egui_events(&items.items, &mut self.ime_active);
        for mv in converted.rel_moves {
            if mv.delta_anchor_cp != mv.delta_focus_cp {
                self.pending_rel_moves.push((mv.delta_anchor_cp, mv.delta_focus_cp));
            }
        }
        if !self.pending_rel_moves.is_empty() && self.app.editor.is_some() {
            self.apply_editor_relative_moves();
        }
        raw_input.events.extend(converted.events);
        for (dx, dy, ctrl, shift) in wheels {
            if let Some(ev) = wheel_to_egui_event(dx, dy, ctrl, shift) {
                raw_input.events.push(ev);
            }
        }
        if ime::pointer_is_mouse()
            && let Some((hx, hy)) = hover
            && let Some(ev) = hover_to_egui_event(hx, hy, ctx.pixels_per_point())
        {
            raw_input.events.push(ev);
        }
        self.touch_filter.process(raw_input, self.prefs.select_mode, self.app.grid.cells_rect);
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        let t = theme::Tokens::get(&ctx);
        let insets = self.insets_dp;
        if insets.top > 0.0 {
            egui::Panel::top("android_safe_top").exact_size(insets.top).frame(egui::Frame::NONE.fill(t.window)).show(ui, |_| {});
        }
        let bottom = insets.bottom_for_ime_aware_panel();
        if bottom > 0.0 {
            egui::Panel::bottom("android_safe_bottom").exact_size(bottom).frame(egui::Frame::NONE.fill(t.window)).show(ui, |_| {});
        }
        if insets.left > 0.0 {
            egui::Panel::left("android_safe_left").exact_size(insets.left).frame(egui::Frame::NONE.fill(t.window)).show(ui, |_| {});
        }
        if insets.right > 0.0 {
            egui::Panel::right("android_safe_right").exact_size(insets.right).frame(egui::Frame::NONE.fill(t.window)).show(ui, |_| {});
        }
        self.draw_mobile_toolbar(ui);
        self.app.ui(ui);

        let ime_output_active = ctx.output(|out| {
            for cmd in &out.commands {
                match cmd {
                    egui::OutputCommand::CopyText(text) => ActivityBridge::set_clipboard_text(text),
                    egui::OutputCommand::OpenUrl(open) => {
                        if is_allowed_external_url(&open.url) {
                            ActivityBridge::open_url(&open.url);
                        } else {
                            log::warn!("Blocked disallowed external URL");
                        }
                    }
                    _ => {}
                }
            }
            out.ime.is_some()
        });
        self.sync_ime_bridge(ime_output_active);
    }

    fn on_exit(&mut self) {
        self.saf.flush_changed_outputs();
    }
}

fn configure_mobile_wgpu() -> egui_wgpu::WgpuConfiguration {
    let mut cfg = egui_wgpu::WgpuConfiguration::default();
    if let egui_wgpu::WgpuSetup::CreateNew(ref mut setup) = cfg.wgpu_setup {
        setup.instance_descriptor = wgpu::InstanceDescriptor {
            backends: wgpu::Backends::VULKAN | wgpu::Backends::GL,
            flags: wgpu::InstanceFlags::empty(),
            backend_options: wgpu::BackendOptions::default(),
            memory_budget_thresholds: wgpu::MemoryBudgetThresholds::default(),
            display: None,
        };
        setup.device_descriptor = Arc::new(|adapter| {
            let limits = wgpu::Limits::downlevel_webgl2_defaults().using_resolution(adapter.limits());
            wgpu::DeviceDescriptor {
                label: Some("gridcraft-android"),
                required_features: wgpu::Features::empty(),
                required_limits: limits,
                memory_hints: wgpu::MemoryHints::MemoryUsage,
                experimental_features: wgpu::ExperimentalFeatures::disabled(),
                trace: wgpu::Trace::Off,
            }
        });
    }
    cfg
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub fn android_main(app: android_activity::AndroidApp) {
    ime::init_logcat();
    ime::register_app_handles(&app);
    log::info!("GridCraft Android starting");

    let options = eframe::NativeOptions {
        android_app: Some(app),
        renderer: eframe::Renderer::Wgpu,
        wgpu_options: configure_mobile_wgpu(),
        persist_window: false,
        ..Default::default()
    };

    if let Err(err) = eframe::run_native("GridCraft", options, Box::new(|cc| Ok(Box::new(GridAndroidShell::new(cc))))) {
        log::error!("eframe::run_native failed: {err}");
    }
}
