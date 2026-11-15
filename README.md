# GridO (GridCraft for Android)

**Open-source Rust spreadsheet app for Android (`one.lmk.gridcraft`).**

Based on [GridCraft](https://github.com/storytold/gridcraft) by the ArtCraft team. ArtCraft trademark assets and desktop-only community links (including Discord invites and upstream CI bots) are removed in this Android fork.

## Features
- Native Rust spreadsheet engine (`gridcraft-engine`, `gridcraft-calc`, `gridcraft-xlsx`, `gridcraft-chart`, `gridcraft-pdf`) rendered via `eframe` / `wgpu` on Android (`GameActivity`)
- Storage Access Framework (`SAF`) file open/save bridge with bounded streaming into private app cache
- Full IME hardware/soft-keyboard composition and relative selection bridge (`ImeEditSession`)
- `light` / `dark` / `system` themes and edge-to-edge display cutout + keyboard safe insets
- External Presentation display & Chromecast LAN mirror (`play` flavor) alongside pure open-source `foss` flavor
- 16 KB Android page-size aligned ELF native shared libraries (`libgridcraft_android.so`)

## Repository Layout
- `apps/gridcraft-android/` — Rust Android entry point, JNI IME/SAF bridge, mobile toolbar, and unit/security tests
- `android/` — Android Gradle project (`foss` and `play` flavors), Kotlin `GridCraftActivity`, security policy, and JUnit tests
- `crates/` — Core spreadsheet engine, formula parser/evaluator, XLSX/XLS/ODS/CSV I/O, charts, PDF export, and `egui` UI
- `.github/workflows/android.yml` — Automated Rust CVE audit (`cargo-audit`), CodeQL, Rust host/security tests, Android JVM tests, NDK build (`arm64-v8a` / `x86_64`), 16 KB alignment check, and APK builds

## Security & Vulnerability Management
See [SECURITY.md](SECURITY.md) for security hardening controls, dependency CVE policy, and vulnerability reporting.

## License
Dual-licensed under [LICENSE-MIT](LICENSE-MIT) or [LICENSE-APACHE](LICENSE-APACHE), at your option. Required notices and asset attributions are tracked in [NOTICE](NOTICE) and [ATTRIBUTION.md](ATTRIBUTION.md).
