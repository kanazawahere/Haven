# Haven Embedded Email — Feature Plan

## Context

Haven's connections feature currently supports SSH / RCLONE / VNC / RDP / SMB / RETICULUM / LOCAL / MOSH connection types. Each is a row in `ConnectionProfile` keyed by `connectionType`, dispatched on connect, and routed to a destination screen (e.g. an rclone remote opens the SFTP **file browser**).

Goal: add an **EMAIL** connection type that, instead of a file view, opens a **K-9-style mail client** (`feature/mail`) — and that composes Haven's existing layered connection security into a *fully flexible email security chain* (tunnel transport + SPA/port-knock pre-connect + a multi-factor auth chain), adding a message-layer (PGP) on top. The differentiators: **ProtonMail on mobile** (no mainstream Android client offers this) and mail that the on-device agent/MCP can reach across providers.

**Locked decisions:**
- **Engine = Hybrid**: JVM IMAP/SMTP/Graph client rides the security chain directly via `TunnelResolver.socketFactory`; a **Go gomobile bridge** handles ProtonMail (`go-proton-api`/`gluon`) and PGP (`gopenpgp`).
- **MVP = ProtonMail-first** vertical slice.
- **Security in v1 = all four layers**: tunnel routing, SPA + port-knock, PGP message crypto, OAuth/XOAUTH2 (as a first-class auth method, even though the Proton slice itself authenticates via SRP).

**Key risk — RETIRED (2026-06-06).** A host compile spike inside `rclone-android/go` confirmed the vendored `rclone/go-proton-api` exposes the full Proton **Mail** vertical (not just Drive): `Manager.NewClientWithLogin` (SRP) + `Client.Auth2FA`; `GetSalts`/`Salts.SaltForKey` + `Unlock` → decryption keyrings; `GetLabels` (folders); `GetMessageMetadata`/`GetMessage`; `BuildRFC822(kr, msg, attData)` (decrypt → standard MIME); `GetAttachment`; `CreateDraft`/`SendDraft`. The spike built clean (`go build`, exit 0).

**Consequence — `gluon` dropped from v1.** Because `BuildRFC822` already decrypts to standard MIME, v1 calls `go-proton-api` directly and parses MIME on the Kotlin side with Apache Mime4j; `gluon` (the local IMAP-server layer) is only needed if we later expose Proton as on-device IMAP — deferred, not v1. (Earlier mentions of `gluon`/`emersion/go-message` in the diagrams below are historical; the shipped read path uses neither directly.)

## Implementation status (updated 2026-06-08)

**Two read engines shipped (Proton + IMAP); the IMAP engine sends (CP-6) and has an on-device compose / reply / forward UI (CP-7). The hybrid two-engine architecture is realised.** Proton (Go bridge / SOCKS) and generic IMAP (JVM / `SocketFactory`) coexist behind one `MailBackend` → one `MimeParser`, both riding the per-profile tunnel and reachable by the agent over MCP:
- **v1 — ProtonMail read-only**, device-verified on a real *free* account. `main`: `09ca46de` (feature), `2bd43992` (registry wiring), `588bef87` (settings move).
- **Stage 2a — generic IMAP/SMTP read**, device-verified against Dovecot-in-proot. `main`: CP-0…CP-5, ending `085d2773` (the JVM engine + the no-clearnet-leak / fail-closed-tunnel fix). Full detail in the **Stage 2a** section + Progress block below.

v1 scope was sharpened from the locked decisions above with the maintainer:

- **v1 = ProtonMail read-only** (connect → folders → list → read decrypted message). **Send/compose deferred to v1.1** — the Go `send` path is a deliberate 501 stub; it's the hardest/riskiest piece (per-recipient encryption) and is kept off the first release.
- **OAuth/XOAUTH2 token store deferred to Stage 2b** (built with the Gmail JVM engine that consumes it) — no dead encrypted-credential code in a Proton-only v1.
- **Security layers in v1:** tunnel routing via `socksEndpoint` ✅ verified; native PGP via the bridge ✅ verified; SPA/port-knock pre-connect wired into `connectEmail` (which `connectRclone` lacked) — code present, **not yet exercised** against a guarded endpoint. OAuth is the deferred fourth. SPA/knock for Proton guards the user's *tunnel ingress* (`profile.host`), not Proton's servers.
- **MIME parsing = Apache James Mime4j** (Apache-2.0); the reader renders **plain text only** (no WebView) so remote images/scripts never load.
- **Reader/session security:** `saltedKeyPass` (keyring-unlock secret) is held in-memory only, never persisted in v1; re-auth on process death.
- **Free account is sufficient** — we use the web/app API (not the paid Bridge). The `appVersion` is rclone's working Drive default `macos-drive@1.0.0-alpha.1+rclone`; **confirmed it also works for Proton Mail data endpoints**, so that risk is retired.
- Built modules: `core/mail` (`MailClient`/`ProtonMailClient`/`MailSessionManager`), `feature/mail` (`MailScreen`/`MailViewModel`/`ProtonMailBackend`/`MimeParser`), `EMAIL` connection type + edit dialog + `connectEmail`, `Screen.Mail` nav, read-only MCP tools (`list_mail_folders`/`list_mail_messages`/`read_mail_message`), and `MailSessionManager` registered in `SessionManagerRegistry` (so `list_sessions`/`disconnect_profile` cover mail, transport `MAIL`). DB schema 60→61.

### Verification done (2026-06-07)

| Tier | Evidence |
|---|---|
| Unit (host) | `core/data` AuthMethodSpec `PROTON_SRP` + EMAIL defaults + Room 60→61; `feature/mail` Mime4j parse (multipart + HTML-strip) |
| Go bridge (host, real account) | env-gated `mailbridge_live_test.go`: SRP login + keyring unlock + 14 folders + list + decrypt 6190 B |
| Kotlin parser (host, real msg) | `MimeParser` parsed a real Proton message → subject/from/to/date/body |
| On-device UI | created EMAIL connection, connected, Mail screen renders folders→list→reader |
| On-device agent (MCP) | `list_mail_folders`/`list_mail_messages`/`read_mail_message` drive the live session; `list_sessions` shows `MAIL`; `disconnect_profile` tears it down |
| Security chain — tunnel | **positive:** routed through the `upwg` WG tunnel → profile holds the tunnel + reads work; **negative:** routed through a blackhole tunnel → connect FAILS via SOCKS (`socks connect … connection refused`), **no clearnet fallback** |

### Forward plan / remaining

Both engines can **read**; neither can **send** yet. Priority order:

- **Send — both engines.** A **shared full-screen compose UI** over one `MailBackend.send` seam; the consent-gated `send_mail` MCP tool also rides that seam:
  - **IMAP/SMTP send (CP-6) — ✅ done, device-verified.** `MailClient.send` → `Transport.sendMessage` over the SMTP `socketFactory` (implicit 465 / opportunistic STARTTLS 587, base socketFactory + fail-closed), append-to-Sent, + the `send_mail` MCP tool (EVERY_CALL + audit). See the Stage 2a Progress block.
  - **Compose / reply / forward UI (CP-7) — ✅ done, device-verified.** Full-screen compose (portrait: no FAB; top-bar Compose/Reply/Reply-all/Forward), plain comma-separated recipients, discard-confirm, sent snackbar. See the Stage 2a Progress block.
  - **Proton v1.1 send:** Go `send` (per-recipient key discovery; internal Proton↔Proton E2E vs PGP-to-external vs encrypt-to-outside; `CreateDraft`/`SendDraft`; attachment encryption). Hardest/riskiest (a mis-scheme leaks plaintext); reuses the same compose seam. Proton `send` stays a **501 stub** until this lands.
- **Stage 2b — OAuth (Gmail / Microsoft)** on the *same* JVM engine — an **auth-only delta**, not a new engine: build the `OAuth2Token` store + reuse `core/stepca`'s deep-link OAuth (`OidcAuthClient`/`Pkce`/`OidcRedirectActivity`); `emailAuthMethods` carries `XOAUTH2:<tokenId>`. (Gmail `https://mail.google.com/` is a restricted scope → CASA for a *published* build; BYO OAuth client for personal use. Microsoft has no CASA.)
- **Exercise SPA/knock** for an EMAIL profile against an SPA-guarded tunnel ingress; confirm the `[spa]`/`[knock]` receipt lands in `verboseLog`. Still the **one unverified security layer** (tunnel routing + no-clearnet-leak are now proven for both engines).
- **Staged 2FA / mailbox-password prompt** (Proton): v1 uses a *stored* mailbox password + a *linked TOTP secret*; the live re-prompt path is untested. Build the staged dialog when a 2FA/two-password account is available.
- **Gaps & polish:** positive **IMAP-over-a-working-tunnel** proof (needs a remote IMAP reachable only via a live tunnel — no single-device infra for it); optional in-process **GreenMail** CI test for the IMAP envelope→`MailMessage` mapping; wire `WithUserAgent` in the Go bridge (ToS-fingerprint hygiene); fix MCP `list_connections` to report `hasStoredPassword` for EMAIL (only checks `sshPassword`); **11-locale translation** of the new Stage-2a strings; Proton system-label de-dup (the API returns two "All Mail" entries).
- **Standing risk:** Proton's unofficial reverse-engineered API — ToS/account-lock risk on any tier; disclosed in the connect dialog. Keep request pacing conservative (reinforces deferring push/Stage 4).

## Stage 2a — Generic IMAP/SMTP (the second engine) — plan (2026-06-07)

**Chosen as the next block** (over Proton send/compose and a harden-v1 block) to *prove the hybrid two-engine architecture*: today `MailClient`/`MailBackend`/`MailSessionManager`/`MailTransportSelector` have exactly one implementation, so they're unproven abstractions. This block stands up the JVM IMAP/SMTP engine **next to** the Go/Proton engine. Payoff: (1) the Gateway security chain composes for *any* provider — IMAP/SMTP ride `TunnelResolver.socketFactory`, proving "hybrid routes two ways" (Go→SOCKS, JVM→SocketFactory); (2) **both engines converge on RFC822 bytes → one `MimeParser` → `ParsedMessage`**, so a single `MailBackend` serves both; (3) IMAP sessions register in `SessionManagerRegistry`, so `list_sessions`/`disconnect_profile` + the three MCP read tools work with **zero per-engine MCP code**. After this, **Gmail/Outlook (Stage 2b) is only an auth delta** (app-password → XOAUTH2) on the same engine.

**Scope:** generic IMAP/SMTP with **password / app-password** (Fastmail, iCloud, Yahoo, Dovecot, self-hosted). OAuth = Stage 2b. Proton `send` stays a 501 stub (we add the *seam*, not the Proton impl). **No DB migration** — every EMAIL field already exists; `emailProvider="imap"` is just populated.

### The central refactor (this *is* the abstraction proof)
- `MailSessionManager`: stop injecting one `MailClient`/exposing `.mailClient`. Inject a **map of engines** (PROTON, IMAP); `SessionState` gains `engine`; expose `clientForProfile/Session(id)`.
- `MailClient.login(…, socks)` → engine-neutral `connectSession(id, params: MailConnectParams)` where `MailConnectParams` is sealed: `Proton(…, socks)` / `Imap(username, password, server, port, smtpPort, tls, socketFactory)`. Transport generalised: SOCKS endpoint **or** `SocketFactory`.
- `MailTransportSelector`: pick the client by the session's engine → **one `RfcMailBackend` for both** (rename of `ProtonMailBackend`, already engine-neutral: `getMessageRaw → MimeParser.parse`).
- MCP tools: call `clientForProfile(profileId)`, not the global `.mailClient`.
- Proton happy-path stays **byte-identical**; existing host live test + device read flow are the regression gates.

### Checkpoints (each gates the next)
- **CP-0 — Library spike gate (do first).** The Jakarta/Angus Android story shifted (old `com.sun.mail` obsolete; `org.eclipse.angus:angus-mail:2.0.4` uses `jakarta.*`+`java.beans` which Android lacks). Try **`com.sun.mail:android-mail:1.6.7` + `android-activation:1.6.7`** first (last Android-targeted build, `javax.mail`, no `java.beans`); fallback `org.eclipse.angus:angus-mail:2.0.4` (+desugaring/shim). **Acceptance:** on a real device (minSdk 26) connect to a real IMAP server over TLS via `socketFactory`, list INBOX, fetch one message's RFC822, parse via `MimeParser` → subject/from/body. Lock dep, **write sha256 verification-metadata** (else build fails — known gotcha), add packaging excludes, confirm EPL-2.0/EDL-1.0 (AGPL-compatible).
- **CP-1 — Engine-routing refactor, no IMAP.** The refactor above. Gate: Proton live test + device read flow green; routing unit tests.
- **CP-2 — `ImapMailClient` (read).** `login` (open `Store` via socketFactory+auth), `listFolders`, `listMessages` (envelopes→`MailMessage`, ids `folderId:UID`), `getMessageRaw` (`message.writeTo`→RFC822), `logout`. IMAP/auth errors → `MailException`. Gate: host integration test vs local Dovecot-in-proot (or env-gated real account) — **no account in CI**.
- **CP-3 — `connectEmail` IMAP branch.** Resolve `socketFactory` (not socksEndpoint), **fail-closed if tunnel set but factory null**, `MailConnectParams.Imap`, register `engine=IMAP`, nav `Screen.Mail`; SPA/knock retained.
- **CP-4 — `ConnectionEditDialog` generic-IMAP fields.** Provider picker + **provider-gated collapsible** section (server, port 993, SMTP port 465/587, TLS, username, app-password). Portrait: hidden unless type=EMAIL ∧ provider=imap; app-password help = single trailing-icon tooltip.
- **CP-5 — Verify read e2e + MCP (release gate).** One backend, two engines; MCP read tools drive an IMAP session; `list_sessions` shows it; disconnect closes the Store. **Negative:** IMAP profile on blackhole tunnel → connect fails via socketFactory, no clearnet fallback.
- **CP-6 — SMTP send seam + `ImapMailClient.send`.** `MailClient.send(...)` added (**Proton keeps 501**). IMAP: build `MimeMessage`, `Transport.send` over SMTP socketFactory (implicit 465 / STARTTLS 587), append to Sent. Gate: send-to-self over an env-gated real account.
- **CP-7 — Compose UI + reply/forward + `send_mail` MCP.** Portrait: **full-screen compose destination, no FAB** — Compose/reply/forward as top-app-bar icon actions; recipient chips wrap/collapse. MCP `send_mail` **consent-gated + audit-logged**, IMAP-only until Proton send lands.

**Milestone split:** CP-0→CP-5 is the gating, independently-shippable abstraction proof (two engines, read path); CP-6→CP-7 add send.

**Progress (2026-06-07):**
- ✅ **CP-0** (`13da99df`) — `com.sun.mail:android-mail:1.6.7` locked. Spike-gated: bytecode shows zero `java.beans`/`java.awt` (Android-safe); a throwaway client built against only the android-mail jars did a full IMAP+SMTP+RFC822 round-trip vs a local GreenMail server; verification-metadata written.
- ✅ **CP-1** (`e57f6e76`) — engine-routing refactor. `Map<MailEngine,MailClient>` (Hilt `@IntoMap`), sealed `MailConnectParams`, `SessionState.engine`, `clientForProfile/Session`, one engine-neutral `RfcMailBackend`; MCP tools + selector + `connectEmail` use the routed client. Proton path field-identical; `MailSessionManagerRoutingTest` 3/3; app compiles (Hilt validated).
- ✅ **CP-2** (`42fd6ac6`) — `ImapMailClient` read engine (login/listFolders/listMessages/getMessageRaw/logout over android-mail; explicit provider registration; `TunnelingSSLSocketFactory` for TLS-over-tunnel; `folderFullName uid` message ids). Registered as `MailEngine.IMAP`. `ImapMailClientTest` 4/4. **Gap:** the envelope→`MailMessage` mapping isn't yet exercised against a live server in CI (library path proven by CP-0; mapping device-verified in CP-5; optional in-process GreenMail test would add CI coverage).
- ✅ **CP-3/CP-4** (`a6f554cd`) — IMAP wired into connect + edit dialog. `connectEmail` branches by `emailProvider` (proton→SOCKS / imap→SocketFactory, both fail-closed); edit dialog has a provider segmented-picker with gated IMAP-only fields (server, ports, TLS toggle, app-password hint) so the Proton case stays compact. `:app:compileX64DebugKotlin` green. The Mail read UI is engine-agnostic, so a connected IMAP profile renders through the existing folder/list/reader.
- ✅ **CP-5** (`0d57cf74` create_connection EMAIL + first leak-fix attempt; `085d2773` the real fix) — **device-verified against Dovecot-in-proot** (Debian guest, plaintext IMAP :10143, passwd-file auth, registered as autostart guest-service `gsvc-mq3wqjub`). Drove `create_connection`→`connect_profile`→`list_mail_folders`/`list_mail_messages`/`read_mail_message` over MCP: folders, envelope `folderId UID` ids, and RFC822→`MimeParser` (subject/body/UTF-8) all correct; `list_sessions` shows the IMAP `MAIL` session; `disconnect_profile` tears it down. **`create_connection` extended to EMAIL/IMAP** (host optional) so the whole path is MCP-scriptable.
  - **Two real bugs the negative test caught + fixed (decompiled android-mail `SocketFetcher` to root-cause):** (1) **clearnet leak** — `.ssl.socketFactory` is only the TLS-*wrap* factory, so the BASE socket stayed on the default DIRECT factory; routing an IMAP profile through a *dead* tunnel still reached the server. Fix: set the BASE `mail.imaps.socketFactory` (JavaMail adds implicit TLS) + `socketFactory.fallback=false`. (2) **IMAP-over-tunnel was non-functional** — `SocketFetcher` builds the base socket via the no-arg `createSocket()`+`connect()`, which the tunnel factory didn't implement ("Unconnected sockets not implemented"). Fix: `TunnelSocketFactory.createSocket()` now returns a `DeferredTunneledSocket` that dials on `connect()` (additive; SMB/VNC unaffected). **Final negative test:** blackhole tunnel → `Couldn't connect to imap.gmail.com:993` (dialed THROUGH the dead tunnel, fail-closed, never reached the server) vs direct → reaches it (auth fail). No leak.
- ✅ **CP-6** (`0ce86098`) — **SMTP send + `send_mail` MCP**, device-verified. `MailClient.send(OutgoingMail): SendResult`; `ImapMailClient.send` builds a `MimeMessage` and posts it over SMTP on the same tunneled `SocketFactory` (implicit-TLS 465 / opportunistic STARTTLS 587; the **base** socketFactory + `fallback=false`, i.e. the CP-5 clearnet-leak lesson applied to SMTP), then best-effort appends to Sent via the RFC 6154 `\Sent` special-use attribute (name-match fallback). `ProtonMailClient.send` is a typed **501** stub. New `send_mail` MCP tool — **EVERY_CALL consent + audit-logged** to the connection log, IMAP-only. `ImapMailClientSendTest` 4/4 (SMTP prop selection + MimeMessage build + bare-username From synthesis). **Device-verified** against a stdlib `smtpd` sink + Dovecot-in-proot: `send_mail` → the sink captured the exact RFC822 (From/To/Subject/Message-ID/UTF-8 body), the Sent folder got the `\Seen` copy (`appendedToSent:true`), and an audit row landed in the connection log. (The EVERY_CALL prompt itself was exercised only on the **loopback-trusted** path — #214 bypasses consent there — because the WireGuard MCP carrier kept crashing mid-call; the gate is registered EVERY_CALL and fires for non-trusted clients.)
- ✅ **Mail tab only when open** (`0ce86098`) — the bottom-nav Mail tab now shows only while a mail session is `CONNECTED` (`HavenNavHost` observes `MailSessionManager.sessions`), not merely when an EMAIL profile exists; it reappears on connect and hides on disconnect. Device-verified.
- ✅ **CP-7** (`ebe325e9`; CI-fix prereqs `f7543699`/`33183a92`) — **compose / reply / forward UI**, device-verified over MCP+adb. New `ComposeDrafting` object (pure, JVM-tested: recipient parsing, `Re:`/`Fwd:` prefix dedup, address extraction, reply quoting, reply/forward draft assembly) + `ComposeDraft` holder; `MailViewModel` gains a `COMPOSE` view (top precedence, overlays reader/list) with start/reply/forward/update/discard/send; `MailScreen` adds TopAppBar actions (Compose on list, Reply/Reply-all/Forward on reader), a full-screen `ComposeView` (plain comma-separated To/Cc/Bcc + multiline body, Send w/ spinner, Close+system-back discard-confirm), and a `SnackbarHost` for the sent signal. First `feature/mail` `strings.xml` (`mail_*`). `ComposeDraftingTest` 13/13. **Device-verified** against Dovecot-in-proot + an SMTP sink: Compose→fill→Send showed the "Message sent" snackbar, the sink captured the exact RFC822 (`To: bob@example.org`, `Subject: CP-7 compose UI test`, text/plain UTF-8 body), the Sent folder got the copy, Reply prefilled `To`=sender + `Re:` subject + `> `-quoted body w/ attribution, Forward prefilled empty-To + `Fwd:` + forwarded-header block (unquoted), and the dirty-draft discard dialog returned to the pane underneath. Recommend chip recipients as a fast-follow; **reply-all may Cc the sender** (no self-address surfaced into feature/mail — `selfAddress` passed null). **UI-send audit logging (`caf23501`, device-verified):** a UI send now writes a `CONNECTED` "Sent mail to N recipient(s) — \"subject\"" row to the connection log (parity with the `send_mail` MCP tool) — added in the shared `RfcMailBackend.sendMessage` seam, with `MailTransportSelector` threading the `profileId` + `ConnectionLogRepository`; confirmed on-device (the row appeared + the SMTP sink captured the message). Two stale tests from the earlier email work (`ConnectionsViewModelSessionTest` missing `mail`/`mailSessionManager`, `ScreenTest` asserting 6 screens not 7) surfaced only now because every prior email-work CI run was cancelled mid-flight; both fixed.
- ⏭ **Next:** Proton send (Go `send`: per-recipient key discovery + `CreateDraft`/`SendDraft`; stays 501 until then). Outstanding: add chip recipients (plain comma-separated fields shipped); positive IMAP-over-a-*working*-tunnel is unproven (needs a remote IMAP reachable only via a live tunnel — infra we don't have on one device); the EVERY_CALL `send_mail` prompt over a *non-trusted* (WG/LAN) carrier; optional in-process GreenMail CI test for the mapping; 11-locale translation of the CP-4/CP-7 strings.

### Risks (ranked)
- **R1 (crit)** JVM mail lib Android compat (`java.beans`/activation) → CP-0 spike. **R2 (high)** refactoring verified Proton code → CP-1 pure refactor, Proton-identical, gated before IMAP. **R3 (high)** clearnet leak when socketFactory null but tunnel set → symmetric fail-closed + negative test. **R4 (med)** IMAP identity/folder state (UID vs message-id) → encode `folderId:UID`. **R5 (med)** SMTP send safety → explicit From, consent-gated MCP, compose confirms recipients. **R6 (med, portrait)** dialog/compose density → gated collapsible fields, full-screen compose, no FAB.

## Architecture (mapped to existing seams)

```
ConnectionProfile(connectionType = "EMAIL")
        │
        ▼  feature/connections: connectEmail()  (mirror connectRclone, ConnectionsViewModel.kt:1657)
SECURITY CHAIN  ── all reused, not rebuilt ──
  Transport   TunnelResolver.dial / .socketFactory / .socksEndpoint   (core/tunnel/TunnelResolver.kt:47,62,116)
  Pre-connect SpaSender + PortKnocker via buildKnockHook              (core/spa, core/knock; ConnectionsViewModel ~969-1012)
  Auth        emailAuthMethods: {Password | XOAUTH2 | ProtonSRP | TOTP}  (extend ConnectionProfile.authMethods model)
  Message     MailCryptoService → gopenpgp (Go bridge)
        │
        ▼  ENGINE (hybrid)
  Proton  → Go mailbridge (gomobile): go-proton-api + gopenpgp           [v1, read]
  IMAP/SMTP → JVM android-mail (JavaMail) + Mime4j, socketFactory        [Stage 2a, read ✅]
  Gmail/Outlook → same JVM engine + XOAUTH2 token                        [Stage 2b]
        │
        ▼  core/mail: MailSessionManager (mirror RcloneSessionManager) + MailBackend + MailTransportSelector
        ▼  feature/mail: MailScreen → folder list → message list → reader → compose   (mirror feature/sftp)
        ▼  app/navigation: Screen.Mail + navigateToEmail receiver (mirror onNavigateToRclone, HavenNavHost ~473-477)
```

**Why hybrid routes two ways:** a JVM client accepts a `javax.net.SocketFactory`, so Jakarta Mail plugs straight into `TunnelResolver.socketFactory(profile)` and the JVM `authMethods` chain. The Go/Proton client is an FFI consumer, so it rides the tunnel via the localhost **SOCKS5** `socksEndpoint(profile)` — the same mechanism rclone already uses (`RbSetProxy`).

## Provider matrix

| Provider | Auth | Protocol (v1) | Engine | Security-chain hook | Distribution caveat | Stage |
|---|---|---|---|---|---|---|
| **ProtonMail** | SRP + mailbox pw + TOTP | Proton API (`go-proton-api`) | Go bridge | SOCKS `socksEndpoint` + SPA/knock; **PGP native** | Unofficial/reverse-engineered API; fragile to Proton changes | **v1** |
| **Generic IMAP/SMTP** (Dovecot, Fastmail, iCloud, Yahoo) | password / **app-password** | IMAP 993 + SMTP 465/587 | JVM android-mail (JavaMail) | `socketFactory` + SPA/knock; PGP optional | None — app-passwords need no OAuth verification | **✅ read + send + compose UI (Stage 2a, CP-6/CP-7)** |
| **Gmail / Workspace** | **XOAUTH2** (token as password) | IMAP 993 + SMTP 465 | JVM Jakarta Mail | `socketFactory`; token = auth layer; PGP optional | Scope `https://mail.google.com/` is **restricted** → CASA assessment for a *published* app (Testing/personal-use exempt). Android loopback redirect deprecated → **deep-link**. BYO OAuth client per user. | stage 2 |
| **Microsoft** (Outlook.com personal + M365 work/school) | **OAuth2** (token) | IMAP 993 + SMTP 587 | JVM Jakarta Mail | `socketFactory`; token = auth layer | Basic auth dead (SMTP 100% by 2026-04-30). Free Azure app reg (multi-tenant + personal), **no CASA**. | stage 2 |
| **Future:** Gmail REST · MS Graph · Fastmail JMAP | OAuth2 | REST / JMAP | JVM (HTTP, via `socketFactory`/proxy) | `socketFactory` | Modern alternatives to IMAP; not needed for parity | stage 4+ |

All non-Proton providers share one JVM code path (Jakarta Mail + Mime4j); they differ only in **auth method** (`emailAuthMethods`) and **server coordinates**. The PGP message layer (`MailCryptoService`) is provider-agnostic and opt-in for any account.

### Per-provider notes
- **Gmail** — XOAUTH2 over IMAP/SMTP; scope `https://mail.google.com/`. Refresh tokens need consent with `access_type=offline`. Pragmatic path: user supplies their own OAuth **Desktop** client (Testing mode) → unverified-app warning is expected. A published F-Droid build with this restricted scope would need annual CASA; surface this, don't hide it. (Gmail REST API is an alt but uses the same restricted scope.)
- **Microsoft** — OAuth2 scopes `https://outlook.office.com/IMAP.AccessAsUser.All`, `.../SMTP.Send`, `offline_access`. Works for personal MSA (outlook.com) and Entra work/school via one multi-tenant+personal app registration; delegated consent, no admin approval. Lighter than Google (no CASA).
- **Generic IMAP/SMTP** — plain password or provider app-password (Fastmail, iCloud, Yahoo all require app-passwords with 2FA on; Yahoo has dropped plain passwords). Self-hosted Dovecot: plain or XOAUTH2/OAUTHBEARER at the admin's choice. This is the lowest-friction path and the stage-2 proof of the JVM engine.
- **Future protocols** — Microsoft **Graph** (`Mail.Read`/`Mail.Send`) and Fastmail **JMAP** are cleaner than IMAP and worth adding once the IMAP path is solid; both are HTTP and ride `socketFactory`/proxy through the same tunnel.

## OAuth / XOAUTH2 sub-architecture (Gmail + Microsoft)

**Reuse `core/stepca`'s proven OAuth machinery — do not write a new flow:**
- `OidcAuthClient.kt` — generic PKCE authorization-code client (`authorize()` builds the auth URL, exchanges the code at the token endpoint, supports an optional client secret). Parameterize it (or add a thin `OAuth2AuthClient` sibling) for provider auth/token endpoints, scopes, and to extract **access + refresh tokens** (not `id_token`).
- `Pkce.kt` — RFC 7636 verifier/challenge. Reuse as-is.
- `OidcRedirectActivity` + `OidcRedirectBus` — **deep-link** redirect catcher (custom scheme `haven://…`, state-keyed `CompletableDeferred`). Register a mail callback host in the manifest. Chosen over a 127.0.0.1 loopback because **Google deprecated Android loopback redirects**.
- `CustomTabsIntent` launcher (`OidcAuthClient.DefaultLauncher`) — reuse for the consent screen.

**Build new (the only gap): OAuth token persistence.**
- `OAuth2Token` entity + `OAuth2TokenRepository` mirroring `TotpSecret`/`TotpSecretRepository`, with both `accessToken`/`refreshToken` encrypted at rest via `core/security/CredentialEncryption.kt`. Fields: `provider`, `userEmail`, `accessToken`, `refreshToken?`, `expiresAt?`, `scopes`. Add a `refreshAccessToken()` path (uses `offline_access`).
- `emailAuthMethods` carries `XOAUTH2:<tokenId>` referencing this store (parallel to `TOTP:<secretId>`).

**XOAUTH2 wiring into the JVM engine (Jakarta Mail):**
- `mail.imap.auth.mechanisms=XOAUTH2` / `mail.smtp.auth.mechanisms=XOAUTH2`; pass the access token as the password.
- Inject `TunnelResolver.socketFactory(profile)` via `mail.imap.ssl.socketFactory` / `mail.smtp.ssl.socketFactory` so the authenticated session rides the per-profile tunnel; SPA/knock run in the same pre-connect hook as all other connections.

**Honest distribution caveat (F-Droid):** the Google-recommended Android OAuth path (Google Identity Services SDK) needs Play Services and isn't F-Droid-friendly. Haven's path is BYO OAuth client + deep-link, which works for personal/Testing use with an unverified-app warning; a widely-published build with Gmail's restricted scope would require CASA. Microsoft has no equivalent gate.

## Embedding decisions (the "termlib" question)

Haven embeds engines as **git submodules** wired via `includeBuild()` + `dependencySubstitution()` (`settings.gradle.kts`), in three flavours: pure-Kotlin (et-kotlin, mosh-kotlin), Go→gomobile→`libgojni.so` (rclone-android), and native JNI (termlib C/C++, rdp-kotlin Rust).

- **Proton + PGP → Go, via the existing rclone-android gomobile bind.** Add a `./mailbridge` Go package to the bind invocation that already builds `./wgbridge ./tsbridge` (`rclone-android/tools/build-android.sh`), so it lands in the **same `libgojni.so`** — no new native module. Deps are already vendored & building for Android (`rclone-android/go/go.mod`: `ProtonMail/gluon`, `gopenpgp/v2`, `go-crypto`, `go-srp`, `go-mime` lines 28-33; `rclone/go-proton-api`, `Proton-API-Bridge` lines 192-193; `emersion/go-message` line 95).
- **Generic IMAP/SMTP + Gmail/Outlook → JVM Jakarta Mail (Apache-2.0) + Apache Mime4j**, added as plain Gradle deps. Rides the security chain natively.
- **Do NOT fork the K-9/Thunderbird app.** Its backend is tightly coupled to its UI; extraction cost is high. Borrow ideas (offline store, sync state machine) in stage 3, not as a dependency.
- **Licensing:** Haven is **AGPLv3** (`LICENSE:1`). MIT (emersion/Proton), Apache-2.0 (Jakarta Mail/Mime4j, new Thunderbird), and GPLv2-or-later/EPL+CPE are all compatible.

## Components to add / change

**core/data** — `ConnectionProfile.kt`
- Add EMAIL fields: `emailProvider` ("proton" | "imap" | "gmail" | "outlook"), `emailServer`, `emailPort`, `emailSmtpPort`, `emailTls`, `emailUsername`, plus `emailAuthMethods` (extends the existing ordered `authMethods` spec at ~`:259-324` with `XOAUTH2:<tokenId>` and `PROTON_SRP`).
- Add `val isEmail get() = connectionType == "EMAIL"`; include in the non-terminal guards.
- Room migration (follow existing migration pattern in `core/data`).
- New `OAuth2Token` entity + `OAuth2TokenRepository` (token + refresh), mirroring `TotpSecret`/`TotpSecretRepository` and reusing `CredentialEncryption` for at-rest encryption. The OAuth flow itself reuses `core/stepca` (`OidcAuthClient`/`Pkce`/`OidcRedirectActivity`+`OidcRedirectBus`/`CustomTabsIntent`) — deep-link redirect, not loopback. See "OAuth / XOAUTH2 sub-architecture".

**core/mail** (new module, mirrors `core/rclone`)
- `MailSessionManager` — mirror `RcloneSessionManager` (`SessionState`, `registerSession/connectSession/isProfileConnected/getClientForProfile`, OAuth loopback like `startOAuthFlow`).
- `MailClient` interface + `MailFolder`/`MailMessage`/`MailAddress`/`MailAuthSpec` models.
- `MailCryptoService` (encrypt/decrypt/sign/verify) → gopenpgp via the bridge.
- `ProtonMailClient` (Go bridge) for v1.

**rclone-android** (extend, no new module)
- `go/mailbridge/mailbridge.go` — exposed RPCs: `protonLogin`, `listFolders`, `listMessages`, `fetchMessage` (returns decrypted MIME), `sendMessage` (sign), `pgpEncrypt/Decrypt/Sign/Verify`. Dialer/HTTP client bound to the SOCKS endpoint passed from Kotlin.
- `tools/build-android.sh` — add `./mailbridge` to the `gomobile bind` package list; `-javapkg=sh.haven.mail.binding`.
- `kotlin/.../bridge/MailBridge.kt` — thin wrapper mirroring `RcloneBridge.rpc`.

**feature/mail** (new module, mirrors `feature/sftp`)
- `MailScreen` (accepts `pendingProfileId`), `MailViewModel`, composables: folder list → threaded message list → MIME reader → compose/send.
- `MailTransportSelector` — mirror `feature/sftp/.../transport/TransportSelector.kt` (factory dispatch by profileId; resolves Proton-Go vs JVM backend).
- `MailBackend` interface (message-centric; do **not** reuse `FileBackend`).

**feature/connections**
- `connectEmail(profile)` dispatch in `connectProfile` (next to `connectRclone`, ~`:1380`); `_navigateToEmail` StateFlow (mirror `_navigateToRclone` `:608`).
- Reuse the SPA/knock pre-connect hook before opening the mail socket.
- `ConnectionEditDialog`: EMAIL branch with provider picker (Proton / Generic IMAP / Gmail / Outlook) and per-provider fields. OAuth providers (Gmail/Outlook) launch the deep-link consent flow and store a token id; app-password/password providers (Proton, generic IMAP) take server/port/user/secret.

**core/ui** — `Screen.kt`: add `Mail("mail", R.string.nav_mail, Icons.Filled.Mail)`.

**app** — `HavenNavHost.kt`: render `Screen.Mail -> MailScreen(...)` in the pager `when`; add `navigateToEmail` receiver + `pendingEmailProfileId` (mirror the rclone wiring ~`:473-477`).

## Staged delivery

- **Stage 1 — Proton vertical slice (v1 MVP), proves the whole chain.**
  0. Go spike: confirm `go-proton-api` + `gluon` do Proton **Mail** (login/list/fetch). *Gate.*
  1. EMAIL connection type + edit dialog (Proton + Generic IMAP fields) + nav to `feature/mail`.
  2. `mailbridge.go`: Proton SRP login, folders, list, fetch+**PGP-decrypt**, send+**sign** — dialer pinned to `socksEndpoint` (**tunnel routing**) with **SPA/knock** pre-connect.
  3. `feature/mail` UI: folder list → message list → reader (MIME) → compose/send.
  4. OAuth/XOAUTH2 auth-method scaffolding implemented so a Gmail account can be added in the same build (JVM path stub).
- **Stage 2 — JVM path** (Jakarta Mail + Mime4j, all riding `TunnelResolver.socketFactory`):
  - 2a. **Generic IMAP/SMTP** password/app-password — proves the JVM engine over the tunnel against a local Dovecot (lowest friction, no OAuth).
  - 2b. **Gmail** XOAUTH2 — deep-link consent (step-ca reuse) + `OAuth2Token` store + refresh.
  - 2c. **Microsoft** OAuth2 — IMAP/SMTP for personal MSA + work/school.
- **Stage 3 — Offline:** Room store + sync state machine + threading + search.
- **Stage 4 — Push & polish:** IMAP IDLE / Gmail Pub-Sub / Graph subscriptions, notifications, attachments.
- **Stage 5 — Agent/MCP mail tools:** list/search/send/triage, added to the existing MCP tool surface (`app/.../agent/McpTools.kt`).
- **Stage 6 — Modern protocols:** Microsoft **Graph** (`Mail.Read`/`Mail.Send`) and Fastmail **JMAP** as cleaner HTTP alternatives to IMAP (still ride `socketFactory`/proxy).

## Verification

- **Unit:** `MailCryptoService` PGP round-trips (encrypt→decrypt, sign→verify) against known vectors; `emailAuthMethods` parsing tests (mirror existing `ConnectionProfile` tests).
- **Bridge:** gomobile build emits `libgojni.so` containing mailbridge symbols; Go test for Proton login/list/fetch against a test account or recorded fixtures.
- **End-to-end (real Proton test account):** add an EMAIL(Proton) connection bound to a Tailscale tunnel + a knock/SPA-guarded path; connect → confirm pre-connect packets in the connection log → folders list → open an encrypted message (decrypts) → send a signed message. **Negative test:** with the tunnel down / source IP not SPA-authorized, the connection must fail — proving traffic actually rides the chain.
- **Stage 2 (generic IMAP):** connect to a local Dovecot (in proot) over the tunnel; list/read/send via the JVM path.
- **Stage 2 (Gmail/Microsoft OAuth):** with a BYO OAuth client, the deep-link consent returns to the app, the token persists encrypted, an **expired access token refreshes** via `offline_access`, and an XOAUTH2 IMAP/SMTP round-trip succeeds over the tunnel. **Negative:** a revoked/expired token surfaces a re-auth prompt, not a silent failure.
- **Build/F-Droid:** `./gradlew assembleRelease` with the extended gomobile bind; deps already build in the rclone module, so reproducibility should hold — confirm.
- **Explicitly out of scope for v1:** offline store, push, threading, full-text search, attachments beyond inline — deferred to stages 3-4.

## Reused building blocks (don't rebuild)
- Transport: `core/tunnel/TunnelResolver.kt` (`dial`/`socketFactory`/`socksEndpoint`), `Tunnel.kt`, `TunnelManager.kt`.
- Pre-connect: `core/spa/SpaSender.kt`, `core/knock/PortKnocker.kt`, hook in `ConnectionsViewModel` (~`:969-1012`).
- Auth/crypto at rest: `ConnectionProfile.authMethods` model, `core/security/CredentialEncryption.kt`, `Totp.kt`, `SshKeyRepository`/`TotpSecretRepository`.
- OAuth (Gmail/Microsoft): `core/stepca/OidcAuthClient.kt`, `Pkce.kt`, `OidcRedirectActivity.kt`, `OidcRedirectBus.kt`, and the `CustomTabsIntent` launcher — generic PKCE auth-code + deep-link redirect, reused rather than rebuilt.
- Session/bridge/nav patterns: `core/rclone/RcloneSessionManager.kt`, `RcloneClient.kt`, `rclone-android` gomobile build, `feature/sftp` (TransportSelector/Screen/ViewModel), `HavenNavHost` rclone wiring.
- Message crypto libs (vendored): `gopenpgp/v2`, `go-proton-api`, `gluon`, `go-message` in `rclone-android/go/go.mod`.
