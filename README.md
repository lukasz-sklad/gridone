# GridOne (GridCraft for Android)

**Unofficial open-source Rust spreadsheet app for Android (`one.lmk.gridcraft`).**

Based on [storytold/gridcraft](https://github.com/storytold/gridcraft). Proprietary ArtCraft trademark assets (`docs/brand/`) and desktop community/Discord links are removed in this Android fork.

## Features
- **Spreadsheet formats & engine:** Native Rust engine (`gridcraft-engine`, `gridcraft-calc`, `gridcraft-xlsx`, `gridcraft-chart`, `gridcraft-pdf`) for `.xlsx`, `.xltx`, `.xlsm`, `.xltm`, `.xlsb`, `.xls`, `.ods`, `.csv`, and `.tsv` rendered via `eframe` / `wgpu` on Android (`GameActivity`).
- **Offline core & SAF storage:** Works offline without `INTERNET` permission in the base Android manifest, with Storage Access Framework (`SAF`) open/save streaming bounded inside private cache (`saf_staged/`).
- **Keyboard, touch & themes:** Hardware/soft-keyboard composition and relative selection bridge (`ImeEditSession`), `light` / `dark` / `system` themes, and edge-to-edge display cutout + keyboard safe insets.
- **External screen support:** Supports external Android `Presentation` screen output.
- **16 KB page-size ready:** Native shared libraries (`libgridcraft_android.so` for `arm64-v8a` and `x86_64`) linked cleanly with 16 KB ELF `LOAD` alignment.

## Repository Layout
- `apps/gridcraft-android/` — Rust Android entry point, JNI IME/SAF bridge, mobile toolbar, and unit/security tests
- `android/` — Android Gradle project, Kotlin `GridCraftActivity`, security policy, and JUnit tests
- `crates/` — Core spreadsheet engine, formula parser/evaluator, XLSX/XLS/ODS/CSV I/O, charts, PDF export, and `egui` UI
- `.github/workflows/android.yml` — Automated Rust CVE audit (`cargo audit --deny unsound`), CodeQL Java/Kotlin analysis, Rust host/security tests, Android JVM tests, NDK build (`arm64-v8a` / `x86_64`), 16 KB alignment check, and APK builds

## Security & Vulnerability Management
See [SECURITY.md](SECURITY.md) for security hardening controls, dependency CVE policy, and vulnerability reporting.

## License
Dual-licensed under [LICENSE-MIT](LICENSE-MIT) or [LICENSE-APACHE](LICENSE-APACHE), at your option. Required notices and asset attributions are tracked in [NOTICE](NOTICE) and [ATTRIBUTION.md](ATTRIBUTION.md).
