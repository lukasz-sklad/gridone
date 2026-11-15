<p align="center">
  <img src="assets/app-icon/hicolor/256x256/apps/ai.storyteller.gridcraft.png" width="104" height="104" alt="GridOne icon">
</p>

<h1 align="center">GridOne</h1>

<p align="center">
  <b>Unofficial offline-core Rust spreadsheet app for Android based on <a href="https://github.com/storytold/gridcraft">GridCraft</a></b><br>
  <sub>GridOne — unofficial Android spreadsheet editor based on GridCraft (Rust &amp; Kotlin)</sub>
</p>

<p align="center">
  <img alt="Platform: Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B%20(arm64%20%C2%B7%20x86__64)-3b5bdb?style=flat-square&logo=android&logoColor=white">
  <img alt="Base manifest: no INTERNET permission" src="https://img.shields.io/badge/network-no%20INTERNET%20in%20base%20manifest-2e7d32?style=flat-square">
  <img alt="Written in Rust and Kotlin" src="https://img.shields.io/badge/Rust%20%2B%20Kotlin-1.95%2B-2b47b5?style=flat-square&logo=rust&logoColor=white">
  <img alt="License: MIT OR Apache-2.0" src="https://img.shields.io/badge/license-MIT%20%2F%20Apache--2.0-2b47b5?style=flat-square">
</p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#building-apk">Building APK</a> ·
  <a href="#licenses-and-attribution">Licenses &amp; Attribution</a> ·
  <a href="LICENSE-MIT">MIT</a> ·
  <a href="LICENSE-APACHE">Apache-2.0</a> ·
  <a href="NOTICE">NOTICE</a> ·
  <a href="ATTRIBUTION.md">Attribution</a> ·
  <a href="SECURITY.md">Security</a>
</p>

> [!NOTE]
> This repository (`lukasz-sklad/gridone`) is an **unofficial community Android fork** of [storytold/gridcraft](https://github.com/storytold/gridcraft).
> Its base [`AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml) declares no `INTERNET` permission, disables cloud backups (`android:allowBackup="false"`), disables built-in update checks (`craft-update`), and removes proprietary ArtCraft trademark assets (`docs/brand/`) as well as desktop/Discord community links.
> **Please report Android-specific issues in this repository ([Issues](https://github.com/lukasz-sklad/gridone/issues)), not to upstream desktop maintainers.**

## Features

* **Spreadsheet formats & Rust engine:** opens and edits `.xlsx`, `.xltx`, `.xlsm`, `.xltm`, `.xlsb`, `.xls`, `.ods`, `.csv`, and `.tsv` via the native Rust workspace (`gridcraft-engine`, `gridcraft-calc`, `gridcraft-xlsx`, `gridcraft-chart`, `gridcraft-pdf`) rendered with `eframe` / `wgpu` on `GameActivity`.
* **Scoped Android storage:** Storage Access Framework (`SAF`) file open/save streaming with path sanitization and canonical containment inside private app cache (`saf_staged/`).
* **Keyboard, touch & themes:** hardware and soft-keyboard IME composition & relative selection sync (`ImeEditSession`), single-finger selection mode and two-finger pan/zoom handling, cutout/system-bar/keyboard safe insets, external Android `Presentation` output, and `System` / `Light` / `Dark` themes.
* **Security & safety:** no panics allowed in non-test workspace crates (`#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]`), bounded JNI arrays, outbound URL allowlist (`AndroidSecurityPolicy`), no `gridcraft-mcp` in the Android binary, automated `cargo audit` + CodeQL in CI, and native libraries linked with 16 KB ELF page-size alignment.

## Building APK

<details>
<summary>Show local build instructions from source (Rust &gt;= 1.95, Android NDK r27c, JDK 21)</summary>

Requirements: Rust toolchain with `aarch64-linux-android` and `x86_64-linux-android` targets, `cargo-ndk`, Android NDK `r27c`, and JDK 21.

```sh
# 1. Run Rust Android unit & security tests
cargo test -p gridcraft-android

# 2. Build 16 KB page-size aligned native libraries (.so)
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
  cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build --release -p gridcraft-android

# 3. Run Android unit tests and assemble APKs
cd android
./gradlew test assembleRelease --no-daemon
```

Build outputs are written under `android/app/build/outputs/apk/`.

</details>

## Licenses and Attribution

<details>
<summary>License summary: code MIT OR Apache-2.0, assets &amp; fonts documented in NOTICE and ATTRIBUTION.md</summary>

| Component | License | File / source |
|---|---|---|
| GridCraft engine and Android fork (`lukasz-sklad/gridone`) | **`MIT` OR `Apache-2.0`** | [`LICENSE-MIT`](LICENSE-MIT) · [`LICENSE-APACHE`](LICENSE-APACHE) · [`NOTICE`](NOTICE) |
| Icons, UI graphics, and assets | Open source / original | [`ATTRIBUTION.md`](ATTRIBUTION.md) |
| Bundled `egui` icons and monospace fonts | **`MIT`** / Bitstream Vera | [`NOTICE`](NOTICE) |
| Optional build-time font pack (`CRAFT_FONTS_DIR`) | **`OFL-1.1`** | [`NOTICE`](NOTICE) · [`ATTRIBUTION.md`](ATTRIBUTION.md) |

</details>

---

<p align="center">
  <sub>Unofficial Android fork based on <a href="https://github.com/storytold/gridcraft">GridCraft</a> — Rust &amp; Kotlin.</sub>
</p>
