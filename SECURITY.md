# Security Policy

## Scope
This policy covers the Android fork `lukasz-sklad/gridone` (`android/`, `apps/gridcraft-android/`, and workspace crates in this repository).

## Security Controls
- **No Cloud Backups of Spreadsheet Data:** `android:allowBackup="false"` in Android manifest prevents unencrypted cloud/ADB extraction of local staged files and preferences.
- **SAF Staging Containment (`AndroidSecurityPolicy` & `mobile.rs`):** Untrusted Storage Access Framework filenames are sanitized to strip path separators, NUL bytes, control characters, hidden-file prefixes, and `..` segments; destination files are canonically verified to stay inside private cache (`saf_staged/`) and partial files are deleted on stream limit exceedance (`MAX_STAGED_BYTES`).
- **Outbound URL Allowlist (`AndroidSecurityPolicy` & `mobile.rs`):** Both Rust and Kotlin validate external URLs before invoking Android `ACTION_VIEW` intents—permitting only `http://` and `https://` without userinfo (`user@host`), control/whitespace characters, or blocked Discord domains.
- **Hardened Local Cast HTTP Server (`play` flavor):** `LocalCastHttpServer` and `CastHttpProtocol` enforce concurrent client caps, bounded request-line/header reading (`MAX_REQUEST_LINE_CHARS`, `MAX_HEADERS`), path traversal rejection, strict route matching (`/` and `/frame_<rev>.png`), sanitized response headers, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Content-Security-Policy`.
- **Memory & Panic Safety:** Workspace crates forbid `unsafe` in library logic (aside from narrow, guarded JNI/FFI shims) and enforce `#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]` to keep malformed workbooks, truncated ZIP/XML payloads, and pathological formulas from crashing the app.
- **Dependency Auditing:** CI runs `cargo audit` against the RustSec Advisory Database and CodeQL static analysis on Kotlin/Java sources.

## Reporting a Vulnerability
Please open a private security advisory on `https://github.com/lukasz-sklad/gridone/security/advisories` or contact the maintainer with reproduction details and affected version/flavor (`foss` / `play`).
