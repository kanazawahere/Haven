# Haven Security Review

**Date:** 2026-06-14
**Scope:** Android app (`app/`), feature modules, core modules, native bridges, build/supply chain.
**Review method:** Static analysis via parallel domain-specific passes (secrets, network, Android surface, input validation, native/proot, dependencies). Key claims spot-checked in source.

---

## Executive Summary

Haven has a **strong security foundation** for an app in this space: credentials are encrypted at rest with Tink + Android Keystore, the consent/audit system is fail-closed, SSH host-key verification exists, and cleartext traffic is restricted to loopback. However, **two critical MITM vulnerabilities** in encrypted remote-desktop paths (VNC VeNCrypt TLS and RDP TLS), plus several high-severity injection and access-control issues, need immediate attention.

| Severity | Count |
|----------|-------|
| Critical | 2 |
| High | 10 |
| Medium | 14 |
| Low | 10 |

---

## Critical

### 1. VNC VeNCrypt TLS accepts any server certificate
- **File:** `core/vnc/src/main/kotlin/sh/haven/core/vnc/protocol/Handshaker.kt:221-259`
- **Issue:** `upgradeToTls()` installs an empty `X509TrustManager` and performs no hostname verification. The connection is encrypted but not authenticated.
- **Impact:** Active MITM can present an arbitrary certificate and intercept or modify the VNC session.
- **Fix:** Implement certificate pinning / TOFU, or validate against the system trust store with hostname verification. At minimum, warn users that VeNCrypt TLS currently provides encryption without authentication.

### 2. RDP TLS accepts any server certificate
- **File:** `rdp-kotlin/rust/src/lib.rs:645-710`
- **Issue:** `create_tls_config()` uses a custom `ServerCertVerifier` that unconditionally returns `ServerCertVerified::assertion()`.
- **Impact:** Same as above — active MITM against RDP connections.
- **Fix:** Store server certificate fingerprint on first connect and prompt on mismatch (TOFU), or allow users to pin certificates.

---

## High

### 3. MCP loopback clients are auto-trusted by default
- **File:** `app/src/main/kotlin/sh/haven/app/agent/McpServer.kt:229-230`
- **Issue:** `trustLoopbackEnabled` defaults to `true`, so any local process (including a malicious/compromised app with `INTERNET`) that reaches `127.0.0.1:8730` can call MCP tools without pairing or per-call consent. Tools include `install_apk_from_url`, `expose_adb`, `read_logcat`, `usb_attach_to_guest`, `queue_terminal_input`, etc.
- **Fix:** Default `trustLoopbackMcpClients` to `false`. Require explicit user opt-in for loopback auto-trust.

### 4. Debug receiver is exported without permission
- **File:** `app/src/debug/kotlin/sh/haven/app/debug/DebugReceiver.kt`; manifest at `app/src/debug/AndroidManifest.xml:8`
- **Issue:** `DebugReceiver` is `android:exported="true"` with no permission guard. Any app can send `sh.haven.app.DEBUG_CREATE_PROFILE` and create profiles, including with `sshPassword`. While this is only in debug builds, a release-signed debug APK would carry the same surface.
- **Fix:** Add a signature-level permission (`sh.haven.app.permission.DEBUG`) or reject non-debug builds inside the receiver. Also avoid release-signing debug builds by default (see `app/build.gradle.kts:58-66`).

### 5. SSH silent connect paths auto-accept new host keys
- **Files:** `feature/terminal/src/main/kotlin/sh/haven/feature/terminal/TerminalViewModel.kt:1917-1922, 2062-2064`; `feature/connections/src/main/kotlin/sh/haven/feature/connections/ConnectionsViewModel.kt:4287-4295, 4355-4362, 4411-4419`
- **Issue:** Workspace launch and new-tab paths silently accept unknown or changed host keys (`HostKeyResult.NewHost -> hostKeyVerifier.accept(...)`), bypassing the interactive TOFU prompt.
- **Fix:** Fail closed in silent paths; surface a notification requiring manual approval.

### 6. Broken shell escaping in Mail Rule `RunCommand`
- **File:** `app/src/main/kotlin/sh/haven/app/agent/mailrules/MailRuleActionExecutor.kt:192-206`
- **Issue:** Email-derived placeholders are wrapped in single quotes with only `'` replaced. Values containing `;`, `&`, backticks, etc. can break out and execute arbitrary commands inside proot.
- **Fix:** Use a robust shell-quoting helper, or (better) change `run_in_proot` to accept an argument array and avoid shell interpretation.

### 7. Shell injection in `present_app` Sway kiosk config
- **File:** `core/local/src/main/kotlin/sh/haven/core/local/DesktopManager.kt:826-838`
- **Issue:** User-supplied command is interpolated directly into a generated Sway config:
  ```sway
  exec $command; swaymsg exit
  ```
  Metacharacters (`;`, `&`, backticks, newlines) break out of the exec statement.
- **Fix:** Shell-quote the command before writing the config, or generate a wrapper script and reference it from Sway.

### 8. `StepCaConfig.oidcClientSecret` stored in plaintext
- **File:** `core/data/src/main/kotlin/sh/haven/core/data/db/entities/StepCaConfig.kt:31-43`
- **Issue:** OIDC client secret is persisted in Room as a plain `String?`, not encrypted.
- **Fix:** Encrypt/decrypt via `CredentialEncryption`, with a one-time migration.

### 9. Backup encryption uses weak PBKDF2 parameters
- **File:** `core/data/src/main/kotlin/sh/haven/core/data/backup/BackupService.kt:527`
- **Issue:** `PBKDF2_ITERATIONS = 100_000` for `PBKDF2WithHmacSHA256`. OWASP currently recommends 600,000. The backup file contains decrypted passwords and SSH keys.
- **Fix:** Bump iterations to 600,000+, version the backup envelope, and support older imports.

### 10. SSH verbose logger captures unfiltered JSch debug output
- **File:** `core/ssh/src/main/kotlin/sh/haven/core/ssh/SshVerboseLogger.kt:17-34`
- **Issue:** JSch debug output (hostnames, usernames, key fingerprints, accepted algorithms) is captured verbatim and may end up in user-shared connection logs.
- **Fix:** Sanitize or redact sensitive patterns before storing.

### 11. VNC `SEC_NONE` accepted without warning
- **File:** `core/vnc/src/main/kotlin/sh/haven/core/vnc/protocol/Handshaker.kt:112-115`
- **Issue:** If the server offers security type `None`, Haven selects it silently, resulting in an unauthenticated, unencrypted connection.
- **Fix:** Prompt the user or display a non-dismissible security banner.

### 12. Prebuilt native libraries tracked in git
- **Files:** `rclone-android/jniLibs/*/libgojni.so`, `rdp-kotlin/jniLibs/*/librdp_transport.so`, `core/ffmpeg/src/main/jniLibs/*/*.so`, `core/local/src/main/jniLibs/*/*.so`, `core/wayland/src/main/jniLibs/*/*.so`
- **Issue:** Compiled `.so` binaries are checked in without reproducible-build attestation. Gradle verification cannot catch a malicious replacement.
- **Fix:** Remove committed `.so` files and build from source in CI/F-Droid, or publish signed reproducible artifacts with checksum manifests.

---

## Medium

### 13. `HavenDocumentsProvider` path-boundary check is incomplete
- **File:** `app/src/main/kotlin/sh/haven/app/HavenDocumentsProvider.kt:364-370`
- **Issue:** `startsWith(normalParent)` allows `/home/user2/...` when parent is `/home/user`.
- **Fix:** Add delimiter check; sanitize `displayName` in `createDocument`.

### 14. `FileProvider` exposes broad external storage paths
- **File:** `app/src/main/res/xml/file_paths.xml:21-23`
- **Issue:** `external-path path="."` exposes the app-accessible external storage root.
- **Fix:** Narrow to specific subdirectories Haven actually shares.

### 15. VNC auto-pushes remote clipboard to system clipboard
- **File:** `feature/vnc/src/main/kotlin/sh/haven/feature/vnc/VncViewModel.kt:200-208`
- **Issue:** Remote clipboard text overwrites the device clipboard automatically with no user toggle.
- **Fix:** Add a preference (default off) for remote→local clipboard sync.

### 16. IMAP/SMTP STARTTLS is opportunistic
- **File:** `core/mail/src/main/kotlin/sh/haven/core/mail/ImapMailClient.kt:420-453`
- **Issue:** STARTTLS is enabled but not required; fallback to plaintext is possible.
- **Fix:** Add a per-account "Require TLS" option and set `mail.smtp.starttls.required = true` when enabled.

### 17. Debug build can be signed with release keystore
- **File:** `app/build.gradle.kts:58-66`
- **Issue:** `debug` build uses release signing if `KEYSTORE_PASSWORD` is present.
- **Fix:** Require an explicit opt-in property rather than inferring from environment.

### 18. `CredentialEncryption.isEncrypted` is spoofable
- **File:** `core/security/src/main/kotlin/sh/haven/core/security/CredentialEncryption.kt:61`
- **Issue:** Returns true for any string starting with `ENC:`.
- **Fix:** Verify Base64 body and Tink version byte.

### 19. `ConnectionProfile` secret-field list can drift
- **File:** `core/data/src/main/kotlin/sh/haven/core/data/repository/ConnectionRepository.kt:82-106`
- **Issue:** Secret fields are enumerated manually; a new field can be missed.
- **Fix:** Centralize in one list (e.g., `KProperty1<ConnectionProfile, String?>`) and generate encrypt/decrypt loops.

### 20. Native build downloads lack checksum verification
- **File:** `build-ffmpeg/build.sh:118-139`
- **Issue:** Tarballs are downloaded without hash verification.
- **Fix:** Maintain expected SHA-256 hashes and verify before extraction.

### 21. Tarball extraction does not prevent path traversal
- **File:** `core/local/src/main/kotlin/sh/haven/core/local/ProotManager.kt:848-1000`
- **Issue:** Tar paths are used directly; symlinks/hard links are not validated.
- **Fix:** Normalize paths, reject `..` and absolute paths, verify symlink targets.

### 22. Recursive rootfs deletion can chmod symlink targets
- **File:** `core/local/src/main/kotlin/sh/haven/core/local/ProotManager.kt:48-58`
- **Issue:** `setReadable`/`setWritable`/`setExecutable` operate on symlink targets.
- **Fix:** Skip permission changes on symlinks or re-verify containment.

### 23. JNI bounds check missing in termlib
- **File:** `termlib/lib/src/main/cpp/Terminal.cpp:1168-1177`
- **Issue:** `GetByteArrayElements` result is not null-checked; `offset` is not validated against array length.
- **Fix:** Add null and bounds checks.

### 24. Potential stack buffer overflow in `Terminal::getCellRun`
- **File:** `termlib/lib/src/main/cpp/Terminal.cpp:438-467`
- **Issue:** `jchar chars[256]` with `runLength < 256` can overflow on surrogate pairs.
- **Fix:** Guard to `runLength < 254` or allocate dynamically.

### 25. JitPack repository used for Tesseract4Android
- **File:** `settings.gradle.kts:23-31`
- **Issue:** JitPack builds artifacts on demand, has no PGP signatures, and is a supply-chain risk.
- **Fix:** Build from source or mirror to a project-owned repository.

### 26. Gradle verification does not verify PGP signatures
- **File:** `gradle/verification-metadata.xml:4-5`
- **Issue:** `<verify-signatures>false</verify-signatures>`.
- **Fix:** Enable PGP signature verification where possible.

---

## Low

27. **LAN/WireGuard MCP binders expose server to network** — add prominent Settings warnings (`McpServer.kt:472-506, 527-632`).
28. **CORS allows arbitrary origins** — restrict to loopback when on loopback (`McpServer.kt:876-888`).
29. **OIDC redirect activity does not validate redirect origin** — harden state correlation (`OidcRedirectActivity.kt:31-46`).
30. **`screenSecurity` defaults to off** — consider defaulting on or tying to biometric lock (`MainActivity.kt:283-292`).
31. **`REQUEST_INSTALL_PACKAGES` declared** — document in F-Droid metadata; ensure system-installer fallback remains.
32. **Wayland socket chmod `0666`** and **`/dev/shm` world-writable** — use more restrictive modes (`wayland-android/jni_bridge.c:678`; `ProotManager.kt:1193-1199`).
33. **VNC password temp file may persist plaintext** — create with `0600` and delete in `finally` (`ProotManager.kt:1505-1514`).
34. **RDP native library path override via system property** — remove in release builds (`rdp-kotlin/kotlin/sh/haven/rdp/rdp_transport.kt:373-379`).
35. **SNAPSHOT dependencies in production** — use stable versions (`app/build.gradle.kts:127-129`, `rnsh-kt`, `reticulum-kt`).
36. **F-Droid metadata stale** — license listed as GPL-3.0-only but repo is AGPL-3.0; version/flavor mismatch (`metadata/sh.haven.app.yml`).

---

## Positive Observations

- Credentials at rest use **Tink AES-256-GCM + Android Keystore** (`core/security/KeyEncryption.kt`, `CredentialEncryption.kt`).
- **MCP consent/audit system is fail-closed** — denied when not foreground, with per-call consent for dangerous tools.
- **Agent forwarding scopes identities correctly** — clears JSch identity repo and re-adds only forwarded keys.
- **Cleartext traffic restricted to loopback** via `network_security_config.xml`.
- **SSH interactive path implements TOFU** with user prompts and host-certificate verification.
- **No SQL injection or mail header injection** found; Room queries use named parameters and JavaMail encodes headers.
- **No hardcoded production secrets** found in source or manifests.

---

## Recommended Remediation Roadmap

| Priority | Action |
|----------|--------|
| **Now** | Fix VNC VeNCrypt TLS and RDP TLS certificate validation. |
| **Now** | Default MCP loopback trust to `false`; add Settings warning for LAN/WG binds. |
| **This sprint** | Harden `DebugReceiver` permissions; remove release-signing of debug builds. |
| **This sprint** | Fix SSH silent host-key acceptance; fail closed. |
| **This sprint** | Fix shell escaping in mail-rule `RunCommand` and `present_app` Sway config. |
| **Next** | Encrypt `StepCaConfig.oidcClientSecret`; strengthen backup PBKDF2. |
| **Next** | Remove committed `.so` files; build native libraries from source with checksum attestation. |
| **Backlog** | Complete medium/low items: DocumentsProvider path checks, FileProvider narrowing, STARTTLS require option, JNI bounds checks, termlib buffer sizing, Gradle PGP verification, JitPack removal. |

---

## Gaps / Not Verified

- **Runtime behavior** on device was not exercised; findings are static.
- **Proguard/R8 rules** were not deeply reviewed — ensure `BuildConfig` fields that leak debug info are not kept.
- **PiP windows and WebView surfaces** were not exhaustively checked for `FLAG_SECURE` coverage.
- **Third-party native libraries** were assumed to match source; no binary diffing was performed.
