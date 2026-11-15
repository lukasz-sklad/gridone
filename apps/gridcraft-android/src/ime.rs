//! Android logcat logger, wake bridge, shared JNI queues, and `nativeOn*` receivers.

use std::ffi::{CString, c_char, c_int};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::EnvUnowned;
use jni::JavaVM;
use jni::objects::{JByteArray, JClass, JIntArray, JObject, JObjectArray, JString};
use jni::refs::Global;
use jni::sys::{JNI_TRUE, jboolean, jfloat, jint, jlong};
use log::{Level, LevelFilter, Log, Metadata, Record};

use crate::mobile::{AndroidInput, AndroidKey, FileAnswer, ImeBatchOp, PendingOpen, PresentationDisplay, SafeInsetsPx};

#[allow(unsafe_code)]
unsafe extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

struct AndroidLogger;

static LOGGER: AndroidLogger = AndroidLogger;

impl Log for AndroidLogger {
    fn enabled(&self, metadata: &Metadata<'_>) -> bool {
        metadata.level() <= Level::Info
    }

    #[allow(unsafe_code)]
    fn log(&self, record: &Record<'_>) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let prio: c_int = match record.level() {
            Level::Error => 6,
            Level::Warn => 5,
            Level::Info => 4,
            Level::Debug => 3,
            Level::Trace => 2,
        };
        let Ok(tag) = CString::new("GridCraft") else { return };
        let msg_text = format!("{}", record.args());
        let Ok(msg) = CString::new(msg_text.replace('\0', " ")) else { return };
        unsafe {
            let _ = __android_log_write(prio, tag.as_ptr(), msg.as_ptr());
        }
    }

    fn flush(&self) {}
}

pub fn init_logcat() {
    if log::set_logger(&LOGGER).is_ok() {
        log::set_max_level(LevelFilter::Info);
    }
}

static WAKER: Mutex<Option<android_activity::AndroidAppWaker>> = Mutex::new(None);
static JVM: OnceLock<JavaVM> = OnceLock::new();
static ACTIVITY: OnceLock<Global<JObject<'static>>> = OnceLock::new();

pub fn register_app_handles(app: &android_activity::AndroidApp) {
    if let Ok(mut guard) = WAKER.lock() {
        *guard = Some(app.create_waker());
    }
    #[allow(unsafe_code)]
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let _ = JVM.set(vm.clone());
    let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        #[allow(unsafe_code)]
        let local = unsafe { JObject::from_raw(env, app.activity_as_ptr().cast()) };
        if !local.is_null() {
            let global = env.new_global_ref(&local)?;
            let _ = ACTIVITY.set(global);
        }
        Ok(())
    });
}

pub fn with_activity<F, R>(f: F) -> Option<R>
where
    F: FnOnce(&mut jni::Env<'_>, &JObject<'_>) -> jni::errors::Result<R>,
{
    let vm = JVM.get()?;
    let activity = ACTIVITY.get()?;
    vm.attach_current_thread(|env| -> jni::errors::Result<Option<R>> {
        match f(env, activity.as_obj()) {
            Ok(val) => Ok(Some(val)),
            Err(err) => {
                if env.exception_check() {
                    let _ = env.exception_clear();
                }
                log::warn!("JNI bridge call failed: {err}");
                Ok(None)
            }
        }
    })
    .ok()
    .flatten()
}

pub fn wake_ui() {
    if let Ok(guard) = WAKER.lock()
        && let Some(waker) = guard.as_ref()
    {
        waker.wake();
    }
}

#[derive(Default)]
pub struct SharedQueues {
    pub inputs: Vec<AndroidInput>,
    pub wheel_deltas: Vec<(f32, f32, bool, bool)>,
    pub mouse_hover_px: Option<(f32, f32)>,
    pub insets: SafeInsetsPx,
    pub ime_dismissed: bool,
    pub file_answer: Option<FileAnswer>,
    pub pending_opens: Vec<PendingOpen>,
}

static QUEUES: OnceLock<Arc<Mutex<SharedQueues>>> = OnceLock::new();
static SYSTEM_NIGHT_MODE: AtomicBool = AtomicBool::new(false);
static POINTER_IS_MOUSE: AtomicBool = AtomicBool::new(false);
static PRESENTATION_DISPLAY: Mutex<PresentationDisplay> = Mutex::new(PresentationDisplay { active: false, width_px: 0, height_px: 0 });
static CAST_CHANGE_PENDING: AtomicBool = AtomicBool::new(false);

pub fn queues() -> Arc<Mutex<SharedQueues>> {
    Arc::clone(QUEUES.get_or_init(|| Arc::new(Mutex::new(SharedQueues::default()))))
}

pub fn system_night_mode() -> bool {
    SYSTEM_NIGHT_MODE.load(Ordering::Relaxed)
}

pub fn pointer_is_mouse() -> bool {
    POINTER_IS_MOUSE.load(Ordering::Relaxed)
}

pub fn presentation_display() -> PresentationDisplay {
    PRESENTATION_DISPLAY.lock().map(|g| *g).unwrap_or_default()
}

pub fn take_cast_change_pending() -> bool {
    CAST_CHANGE_PENDING.swap(false, Ordering::AcqRel)
}

pub fn push_input(item: AndroidInput) {
    if let Ok(mut q) = queues().lock() {
        if q.inputs.len() < 512 {
            q.inputs.push(item);
        }
    }
    wake_ui();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnNightModeChanged<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    night: jboolean,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        SYSTEM_NIGHT_MODE.store(night == JNI_TRUE, Ordering::Relaxed);
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnSafeInsetsChanged<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    left: jint,
    top: jint,
    right: jint,
    bottom: jint,
    ime_bottom: jint,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        if let Ok(mut q) = queues().lock() {
            q.insets = SafeInsetsPx { left, top, right, bottom, ime_bottom };
        }
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnImeBatch<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    seq: jlong,
    op_kinds: JIntArray<'caller>,
    int_args: JIntArray<'caller>,
    texts: JObjectArray<'caller>,
) {
    let _ = unowned_env.with_env(|env| -> jni::errors::Result<()> {
        let len = op_kinds.len(env)?;
        if len == 0 {
            return Ok(());
        }
        let mut kinds_buf = vec![0i32; len];
        op_kinds.get_region(env, 0, &mut kinds_buf)?;
        let ints_len = int_args.len(env)?;
        let mut ints_buf = vec![0i32; ints_len];
        if ints_len > 0 {
            int_args.get_region(env, 0, &mut ints_buf)?;
        }
        let mut ops = Vec::with_capacity(len);
        for (i, kind) in kinds_buf.into_iter().enumerate() {
            let a0 = ints_buf.get(i * 2).copied().unwrap_or(0);
            let a1 = ints_buf.get(i * 2 + 1).copied().unwrap_or(0);
            let txt = read_string_element(env, &texts, i).unwrap_or_default();
            match kind {
                1 => ops.push(ImeBatchOp::Preedit {
                    text: txt,
                    anchor_cp: usize::try_from(a0).unwrap_or(0),
                    focus_cp: usize::try_from(a1).unwrap_or(0),
                }),
                2 => ops.push(ImeBatchOp::Commit { text: txt }),
                3 => ops
                    .push(ImeBatchOp::DeleteSurrounding { before_cp: usize::try_from(a0).unwrap_or(0), after_cp: usize::try_from(a1).unwrap_or(0) }),
                4 => ops.push(ImeBatchOp::SetSelectionRelative { delta_anchor_cp: a0, delta_focus_cp: a1 }),
                5 => ops.push(ImeBatchOp::TapKey { key_code: a0 }),
                _ => {}
            }
        }
        if !ops.is_empty() {
            push_input(AndroidInput::ImeBatch { seq: u64::try_from(seq).unwrap_or(0), ops });
        }
        Ok(())
    });
}

fn read_string_element(env: &mut jni::Env<'_>, arr: &JObjectArray<'_>, idx: usize) -> Option<String> {
    let elem = arr.get_element(env, idx).ok()?;
    if elem.is_null() {
        return None;
    }
    let jstr = env.cast_local::<JString>(elem).ok()?;
    jstr.try_to_string(env).ok()
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnImeKey<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    pressed: jboolean,
    key_code: jint,
    ctrl: jboolean,
    shift: jboolean,
    alt: jboolean,
    repeat: jint,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        let Some(key) = AndroidKey::from_keycode(key_code) else { return Ok(()) };
        let item = if pressed == JNI_TRUE {
            AndroidInput::KeyDown {
                key,
                ctrl: ctrl == JNI_TRUE,
                shift: shift == JNI_TRUE,
                alt: alt == JNI_TRUE,
                repeat: u32::try_from(repeat).unwrap_or(0),
            }
        } else {
            AndroidInput::KeyUp { key, ctrl: ctrl == JNI_TRUE, shift: shift == JNI_TRUE, alt: alt == JNI_TRUE }
        };
        push_input(item);
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnImeDismissed<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        if let Ok(mut q) = queues().lock() {
            q.ime_dismissed = true;
        }
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnTouchPointerType<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    is_mouse: jboolean,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        POINTER_IS_MOUSE.store(is_mouse == JNI_TRUE, Ordering::Relaxed);
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnWheelEvent<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    dx: jfloat,
    dy: jfloat,
    ctrl: jboolean,
    shift: jboolean,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        if let Ok(mut q) = queues().lock()
            && q.wheel_deltas.len() < 128
        {
            q.wheel_deltas.push((dx, dy, ctrl == JNI_TRUE, shift == JNI_TRUE));
        }
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnMouseHover<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    x_px: jfloat,
    y_px: jfloat,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        if let Ok(mut q) = queues().lock() {
            q.mouse_hover_px = Some((x_px, y_px));
        }
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnFilePicked<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    uri: JString<'caller>,
    staged_path: JString<'caller>,
    display_name: JString<'caller>,
    writable: jboolean,
) {
    let _ = unowned_env.with_env(|env| -> jni::errors::Result<()> {
        let uri = uri.try_to_string(env).unwrap_or_default();
        let staged_path = staged_path.try_to_string(env).unwrap_or_default();
        let display_name = display_name.try_to_string(env).unwrap_or_default();
        if let Ok(mut q) = queues().lock() {
            q.file_answer = Some(FileAnswer::Opened { uri, staged_path, display_name, writable: writable == JNI_TRUE });
        }
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnFileSavePicked<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    uri: JString<'caller>,
    staged_path: JString<'caller>,
    display_name: JString<'caller>,
) {
    let _ = unowned_env.with_env(|env| -> jni::errors::Result<()> {
        let uri = uri.try_to_string(env).unwrap_or_default();
        let staged_path = staged_path.try_to_string(env).unwrap_or_default();
        let display_name = display_name.try_to_string(env).unwrap_or_default();
        if let Ok(mut q) = queues().lock() {
            q.file_answer = Some(FileAnswer::Saved { uri, staged_path, display_name });
        }
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnFileCancelled<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        if let Ok(mut q) = queues().lock() {
            q.file_answer = Some(FileAnswer::Cancelled);
        }
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnPendingOpen<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    uri: JString<'caller>,
    staged_path: JString<'caller>,
    display_name: JString<'caller>,
    writable: jboolean,
) {
    let _ = unowned_env.with_env(|env| -> jni::errors::Result<()> {
        let uri = uri.try_to_string(env).unwrap_or_default();
        let staged_path = staged_path.try_to_string(env).unwrap_or_default();
        let display_name = display_name.try_to_string(env).unwrap_or_default();
        if let Ok(mut q) = queues().lock()
            && q.pending_opens.len() < 16
        {
            q.pending_opens.push(PendingOpen { uri, staged_path, display_name, writable: writable == JNI_TRUE });
        }
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnPresentationDisplayChanged<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    active: jboolean,
    width_px: jint,
    height_px: jint,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> {
        if let Ok(mut g) = PRESENTATION_DISPLAY.lock() {
            *g = PresentationDisplay {
                active: active == JNI_TRUE,
                width_px: u32::try_from(width_px).unwrap_or(0),
                height_px: u32::try_from(height_px).unwrap_or(0),
            };
        }
        CAST_CHANGE_PENDING.store(true, Ordering::Release);
        wake_ui();
        Ok(())
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_gridcraft_GridCraftActivity_nativeOnInsetsTestPing<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    _bytes: JByteArray<'caller>,
) {
    let _ = unowned_env.with_env(|_env| -> jni::errors::Result<()> { Ok(()) });
}
