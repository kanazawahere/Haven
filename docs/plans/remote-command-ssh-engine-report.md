# Plan — re-port `remoteCommand`/`requestPty` onto the #58 dual-SSH-engine `SshConnection` seam

## Context

PR #436 (`feature/remote-command-profile`) added an SSH `RemoteCommand`-equivalent: a connection
profile can specify a command to exec instead of starting a login shell (used for
`tmux new -A -s <session>` attach-or-create over the SSH exec channel, mosh-compatible). It was
built directly against JSch's `ChannelShell`/`ChannelExec` before issue #58 (dual JSch/sshlib engine
migration) landed on `main` and refactored the seam underneath it.

The maintainer (GlassOnTin) reviewed and wants the feature re-ported onto the new architecture
before it lands — see their comment on PR #436 (2026-07-22) for the exact ask. This plan captures
what that means concretely, based on reading the current `main` (not the stale branch base).

**Batin is asleep; this was scoped and is being executed autonomously overnight.** GATE-review the
diff against this plan and the maintainer's original comment before pushing — do not skip that.

## What already exists on `main` (read these first, don't re-derive by guessing)

- `core/ssh/src/main/kotlin/sh/haven/core/ssh/SshConnection.kt` — the engine-neutral interface.
  `openShellChannel(term, cols, rows): ShellChannel` is the interactive-shell seam. There is
  already a *separate*, one-shot `execCommand(command, timeoutMs): ExecResult` — that is NOT this
  feature (it's fire-and-capture, not an interactive PTY channel); do not confuse the two or reuse
  `execCommand`'s plumbing.
- `core/ssh/src/main/kotlin/sh/haven/core/ssh/ShellChannel.kt` — now engine-neutral: a closure-based
  class (`resizeFn`, `disconnectFn`, `connectedProbe`, `closedProbe`, `exitStatusProbe`) wrapping
  streams, built once at construction. `openShellOn(session, term, cols, rows, agentForwarding)` is
  the JSch-side builder. **This is the exact shape the new exec-channel builder must also return.**
- `core/ssh/src/main/kotlin/sh/haven/core/ssh/SshClient.kt` — JSch's `SshConnection` implementor.
  `openShellChannel(...)` calls `openShellOn(...)`. No `resizeShell(channel, cols, rows)` method
  exists anymore — resize lives on `ShellChannel` itself (`shell.resize(cols, rows)`). The original
  PR branch's `TerminalSession.kt` calls to `client.resizeShell(shell, cols, rows)` must become
  direct `shell.resize(cols, rows)` calls — re-check every call site, don't just replay the old diff.
- `core/ssh/src/main/kotlin/sh/haven/core/ssh/sshlib/SshlibShell.kt` — the sshlib engine's
  interactive-shell builder (`SshlibShell.open(...)` + `SshlibShell.requestShell(session, ...)`
  which calls `session.requestPty(...)` then `session.requestShell()`). Same `ShellChannel` shape.
  **`SshlibShell` is a standalone utility, not yet wired as a live `SshConnection` implementor** —
  `SshConnectionFactory.create(engine)` still returns `SshClient()` (JSch) for *both*
  `SshEngine.JSCH` and `SshEngine.SSHLIB` (see the comment there: whole-connection sshlib is
  "phase 5+", not live yet; only SFTP is routed to sshlib today). So this re-port's sshlib work is
  scoped to: **extend `SshlibShell.kt` with an exec-command variant + contract-test coverage for it**
  — it does NOT need to be reachable through the live app today, matching how the existing shell
  path in `SshlibShell` is tested (contract tests instantiate it directly) without being wired into
  `SshConnectionFactory` yet. Do not attempt to wire sshlib as a live whole-connection engine —
  that's separately tracked (#58 phase 5+) and out of scope here.
- `core/ssh/src/test/kotlin/sh/haven/core/ssh/ShellChannelContractTest.kt` +
  `JschShellChannelContractTest.kt` — the shared-contract pattern for interactive shell: an abstract
  base spinning up a real in-process Apache MINA `SshServer`, an abstract `openShell(host, port,
  user, pass): ShellChannel` hook, shared assertions (banner-first-bytes, echo roundtrip, resize,
  clean exit). **The new exec-with-command test suite must follow this exact pattern** (real MINA
  server, abstract hook per engine, shared assertions), not a from-scratch test style.
- `core/ssh/src/test/kotlin/sh/haven/core/ssh/ExecContractTest.kt` — the *one-shot* exec contract
  (`execCommand`), unrelated to this feature except as a second reference for "how to script a MINA
  `CommandFactory` deterministically" (see its `ScriptedCommand` inner class).
- `core/ssh/src/test/kotlin/sh/haven/core/ssh/sshlib/SshlibCapabilitySpikeTest.kt` — reference for
  how to stand up a real `org.connectbot.sshlib.SshClient`/`SshClientConfig`/`SshSession` against
  a MINA test server in a JVM unit test (imports, connect sequence). Use this as the template for
  whatever raw-sshlib driving the new sshlib-side contract test needs; don't invent a different
  connect sequence.

## What the original branch did (JSch-only, pre-#58 shape — needs adapting, not replaying verbatim)

Full diff: `git diff main..feature/remote-command-profile` on this fork (or read `git show
f4c61b47` — the `feat(ssh): add profile remote command` commit) for the exact original shape. Do
not blind-cherry-pick it; the `ShellChannel` constructor and `SshClient.resizeShell` it references
no longer exist in that form on `main`. Concretely, it:

- added `remoteCommand: String? = null` and `requestPty: Boolean = true` to `ConnectionConfig`
- added `openRemoteCommandOn(session, command, requestPty, term, cols, rows, agentForwarding):
  ShellChannel` next to `openShellOn` in `ShellChannel.kt` (JSch `ChannelExec`-based)
- added `SshClient.openTerminalChannel(remoteCommand, requestPty, term, cols, rows): ShellChannel`
  that dispatches to `openShellOn` or `openRemoteCommandOn` depending on whether `remoteCommand` is
  blank
- threaded `remoteCommand`/`requestPty` through `SshSessionManager`'s session state and every call
  site that previously called `openShellChannel()` (initial connect, reconnect, resume)
- skipped `buildPendingCommands`/`postLoginCommand` entirely when `remoteCommand` is set (the exec
  request IS the command; there is no login shell to type a post-login command into)
- non-SSH parts (DB migration adding a `remoteCommand`/`requestPty` column pair, `ConnectionProfile`
  entity, `ConnectionEditDialog` UI field, `ConnectionsViewModel` wiring, i18n strings, the
  `haven://connect?command=` deep-link param default) — **maintainer confirmed these merge cleanly
  onto current `main`, do not touch their shape**, just carry them forward through the rebase as-is.

## The re-port

1. **`ConnectionConfig.kt`**: re-add `remoteCommand: String? = null` and `requestPty: Boolean = true`
   (same as original — this file didn't move).

2. **`ShellChannel.kt`**: add a JSch exec-channel builder alongside `openShellOn`, returning the
   *current* closure-based `ShellChannel` (not the old direct-wrap one):
   ```kotlin
   internal fun openExecOn(
       session: Session,
       command: String,
       requestPty: Boolean,
       term: String,
       cols: Int,
       rows: Int,
       agentForwarding: Boolean,
   ): ShellChannel {
       val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
       channel.setCommand(command)
       if (requestPty) channel.setPtyType(term, cols, rows, 0, 0) // ChannelExec extends ChannelSession, has setPty/setPtyType
       if (agentForwarding) channel.setAgentForwarding(true)
       val input = channel.inputStream
       val output = channel.outputStream
       channel.connect()
       return ShellChannel(
           input = input,
           output = output,
           resizeFn = { c, r -> if (requestPty) channel.setPtySize(c, r, 0, 0) },
           disconnectFn = { channel.disconnect() },
           connectedProbe = { channel.isConnected },
           closedProbe = { channel.isClosed },
           exitStatusProbe = { channel.exitStatus },
       )
   }
   ```
   Verify the exact JSch `ChannelExec` API for `setPty`/`setPtyType` (whether it needs `setPty(true)`
   before `setPtyType`, matching the original branch's `channel.setPty(true); channel.setPtyType(...)`
   — check JSch's actual `ChannelExec`/`ChannelSession` class hierarchy, don't assume).

3. **`SshConnection.kt` + `SshClient.kt`**: extend the interface. Prefer extending
   `openShellChannel` with optional trailing params over a sibling method — smaller surface, and
   every existing call site (`SshSessionManager`) already calls `openShellChannel(term, cols,
   rows)` positionally-safe with new optional params defaulting to today's behavior:
   ```kotlin
   fun openShellChannel(
       term: String = "xterm-256color",
       cols: Int = 80,
       rows: Int = 24,
       remoteCommand: String? = null,
       requestPty: Boolean = true,
   ): ShellChannel
   ```
   `SshClient`'s override dispatches to `openShellOn` or `openExecOn` based on
   `remoteCommand?.takeIf { it.isNotBlank() }`, same branch as the original PR's
   `openTerminalChannel` — just fold it into `openShellChannel` itself rather than a separate method,
   since the interface now carries the params directly. Delete the original PR's separate
   `openTerminalChannel` name; there's no reason to keep two names once the interface owns the
   params.

4. **`SshlibShell.kt`**: add the exec-command sibling, matching `open`/`requestShell`'s shape:
   ```kotlin
   suspend fun requestExec(session: SshSession, command: String, requestPty: Boolean, term: String, cols: Int, rows: Int) {
       if (requestPty && !session.requestPty(term, cols, rows)) {
           throw SshIoException("sshlib: server rejected PTY request")
       }
       if (!session.requestExec(command)) {  // verify this method name against the actual sshlib API — check SshSession's real surface, don't guess the name
           throw SshIoException("sshlib: server rejected exec request")
       }
   }
   ```
   The exact sshlib `SshSession` method for "run this command instead of a shell" needs verifying
   against the real library surface (check what's vendored/available — grep the sshlib sources or
   its compiled API for the session-channel exec method; it may not be named `requestExec`). If
   sshlib's session type genuinely has no exec-with-PTY combination and only supports one or the
   other, **say so explicitly in the PR/commit rather than silently no-op** (the maintainer's own
   comment allows gating this to JSch-only for v1 as an acceptable call — flag it, don't hide it).

5. **`SshSessionManager.kt` + `TerminalSession.kt`**: re-thread `remoteCommand`/`requestPty` through
   session state and every `openShellChannel()` call site, same shape as the original branch, but:
   - call `openShellChannel(term, cols, rows, remoteCommand, requestPty)` (or named args) instead of
     the deleted `openTerminalChannel`.
   - replace every `client.resizeShell(shell, cols, rows)` / `client.resizeShell(shell.channel, ...)`
     from the original diff with direct `shell.resize(cols, rows)` — `resizeShell` doesn't exist on
     `SshClient` anymore on current `main`. Grep `TerminalSession.kt` on `main` for how it currently
     calls resize on a `ShellChannel` it already holds, and match that.
   - keep the `buildPendingCommands` skip-when-remoteCommand-set logic from the original branch.

6. **New contract test** — `RemoteCommandChannelContractTest.kt` (or similar name), following
   `ShellChannelContractTest`'s exact pattern: abstract base + real MINA `SshServer` with a scripted
   `CommandFactory` (reuse `ExecContractTest`'s `ScriptedCommand` approach, or a PTY-aware variant if
   the MINA shell/command factories need to differ for a PTY exec vs a plain exec — check MINA's
   API), abstract `openRemoteCommand(host, port, user, pass, command, requestPty): ShellChannel`
   hook, `JschRemoteCommandChannelContractTest` subclass driving `SshClient.openShellChannel(...,
   remoteCommand = ...)`, and an sshlib-side subclass driving `SshlibShell` directly (matching how
   `SshlibCapabilitySpikeTest`/other sshlib tests stand up a raw `org.connectbot.sshlib.SshClient`
   against a MINA test server — do not reinvent that connect sequence, copy the working pattern).
   Assertions to cover, mirroring what matters for the real tmux-attach use case:
   - the command runs instead of a login shell (scripted server command emits a distinct, known
     byte sequence a plain shell never would)
   - PTY-requested mode behaves like an interactive channel (resize doesn't throw, echo/round-trip
     works if the scripted command echoes)
   - PTY-not-requested mode still delivers the command's stdout/stderr correctly
   - exit status surfaces through `ShellChannel.exitStatus` after the remote command exits (JSch
     side at least; sshlib's exitStatusProbe may still be the documented `-1` placeholder per
     `SshlibShell.open`'s existing comment about cbssh#232 — if so, note it, don't fake a value)

7. **Non-SSH files** (DB migration, `ConnectionProfile`, `ConnectionEditDialog`, `ConnectionsViewModel`,
   i18n strings, `MoshEtBootstrap.kt`, the deep-link `command=` default fix): carry forward from the
   original branch's diff essentially unchanged — these are what the maintainer said merge cleanly.
   Double check they still apply cleanly against current `main` (some files, e.g. i18n `strings.json`,
   have moved on since the branch point — expect to re-apply by hand in a few spots, not a clean
   `git cherry-pick`).

## What NOT to do

- Do not touch `execCommand`/`ExecResult`/`ExecContractTest` — unrelated one-shot exec feature.
- Do not wire sshlib as a live whole-connection `SshConnection` implementor in
  `SshConnectionFactory` — out of scope, tracked separately as #58 phase 5+.
- Do not invent an sshlib API method name without checking the actual library surface first.
- Do not silently degrade PTY/exec behavior on either engine without saying so in the commit
  message and PR description — if sshlib genuinely can't do exec+PTY, gate the feature to JSch and
  say exactly that, per the maintainer's own stated tolerance for that outcome.
- Do not touch anything outside the files listed above.
- Do not commit/push without the implementing agent's own build+test run passing locally first
  (`./gradlew :core:ssh:test :core:ssh:compileDebugKotlin` at minimum — check the module's actual
  Gradle task names, don't guess).

## Verification before calling this done

1. `./gradlew :core:ssh:test` — new contract test passes on the JSch subclass at minimum; sshlib
   subclass passes or, if a real library gap blocks it, is documented as an `@Ignore`-with-reason
   matching how `SshlibCapabilitySpikeTest` documents GAP probes (assert-and-explain, not silently
   skip).
2. `./gradlew :feature:connections:compileDebugKotlin :feature:terminal:compileDebugKotlin
   :core:data:compileDebugKotlin` (or whatever the real module names/tasks are — verify against
   `settings.gradle.kts`) — everything the non-SSH carry-forward touches still compiles.
3. Full diff read against this plan + the maintainer's original PR #436 comment, point by point,
   before pushing. This is the GATE step — do not skip it just because tests pass.
