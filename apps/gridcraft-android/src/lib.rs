//! GridCraft Android shell: `eframe`/`wgpu` over `GameActivity` with Android SAF, IME, and Cast bridges.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

pub mod mobile;

#[cfg(target_os = "android")]
mod ime;

#[cfg(target_os = "android")]
mod android;

#[cfg(target_os = "android")]
pub use android::android_main;
