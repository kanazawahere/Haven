# Plan — custom text-selection toolbar for the floating text input dialog

## Context

Batin found (real device, 2026-07-23): highlighting/selecting text inside the floating Text
Input dialog (`FloatingTextInputDialog.kt`) shows the selection handles fine, but the native
Android floating action-mode toolbar (Copy / Cut / Paste / Select all) that normally appears
above a text selection never shows up. Every other native text input on the device shows it
normally — only this dialog is missing it.

Root cause (verified, not guessed): the dialog renders its `TextField` inside a Compose `Popup`
(`Popup(properties = PopupProperties(focusable = true))`, `FloatingTextInputDialog.kt` line ~263).
This is a documented Compose limitation — a `TextField` inside a focusable `Popup` has a known
focus-management conflict with the platform `ActionMode`/text-selection-toolbar machinery, so the
toolbar silently never appears (no crash, no error — it just never shows). Source:
https://github.com/JetBrains/compose-multiplatform/issues/2810 (same underlying Popup+TextField
focus conflict, filed against Compose Multiplatform but the same root mechanism affects Compose
on Android). Not switching to `Dialog` to fix this: the design doc
(`docs/plans/floating-text-input.md`, already in this repo) and the maintainer's own device-test
comment on PR #439 confirm the `Popup` was chosen deliberately (isolated pointer-input dispatch —
verified no touch double-consumption with the terminal underneath; a `Dialog` would change that
touch-dispatch behavior and the tap-outside-to-dismiss-without-clearing-draft behavior, which is
already correct and tested). Fix the toolbar without changing the window type.

## Ground truth read before writing this plan (don't skip re-verifying this against upstream/main
before implementing — Haven moves fast, this repo was at v5.83.3 when this plan was written)

- Current file: `feature/terminal/src/main/kotlin/sh/haven/feature/terminal/FloatingTextInputDialog.kt`
  (405 lines on `upstream/main` as of this writing). `TextField(...)` call is the one composable
  that needs the toolbar fix; nothing else in the file changes.
- `git grep LocalTextToolbar` across the whole repo: **zero hits** — no existing custom
  `TextToolbar` implementation anywhere in Haven to copy from. This is new ground, not a
  copy-paste-from-elsewhere job.
- Existing clipboard-access idiom already used elsewhere in this codebase (match this style, don't
  introduce Compose's `LocalClipboardManager` instead — it isn't used anywhere else here):
  `core/toolbar/src/main/kotlin/sh/haven/core/toolbar/KeyboardToolbar.kt` line ~271:
  ```kotlin
  val view = LocalView.current
  val clipboardManager = remember {
      view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  }
  ```
  Use the same `LocalView.current` → platform `android.content.ClipboardManager` pattern here.

## The fix

Compose's public, documented escape hatch for exactly this situation is `TextToolbar` +
`LocalTextToolbar` (`androidx.compose.ui.platform.TextToolbar`, `LocalTextToolbar` composition
local) — verified against the real API reference:
https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/TextToolbar . A custom
`TextToolbar` implementation gets exactly 4 fixed callback slots — `onCopyRequested`,
`onCutRequested`, `onPasteRequested`, `onSelectAllRequested` — the API does not support adding
extra menu items, which is fine here since those 4 are exactly what's missing.

**Verify the exact `TextToolbar` interface shape against the real installed Compose UI version
before writing code** — check `androidx.compose.ui:ui` version pinned in
`gradle/libs.versions.toml` and read the actual decompiled/sources-jar interface (`showMenu`,
`hide`, `status` members) rather than assuming from memory; the interface has changed slightly
across Compose versions in the past (parameter types on `showMenu`, e.g. `Rect` vs `Offset`-based
overloads).

Implementation shape (adjust exact signatures to whatever the verified real interface requires):

```kotlin
private class DialogTextToolbar(
    private val view: View,
    private val clipboardManager: ClipboardManager?,
) : TextToolbar {
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        // Build a small Compose-based floating menu (a Popup positioned at `rect`,
        // OR — simpler and known to sidestep the same Popup-in-Popup focus issue —
        // a plain Android PopupWindow anchored via `view`) with up to 4 buttons,
        // calling straight through to the passed callbacks. Verify which approach
        // actually renders/dismisses correctly inside the outer floating-text-input
        // Popup before committing to one — this is the one part of the plan that's
        // a genuine implementation risk, not a known-good recipe. Test on a real
        // device/emulator, not just "it compiles".
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
    }
}
```

Wire it in `FloatingTextInputDialog.kt` around just the `TextField`:

```kotlin
val view = LocalView.current
val clipboardManager = remember {
    view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}
val customToolbar = remember(view, clipboardManager) { DialogTextToolbar(view, clipboardManager) }

CompositionLocalProvider(LocalTextToolbar provides customToolbar) {
    TextField(
        value = text,
        onValueChange = onTextChange,
        // ...unchanged...
    )
}
```

`onCopyRequested`/`onCutRequested`/`onPasteRequested`/`onSelectAllRequested` operate on the
`TextFieldValue`'s current selection (`text`/`onTextChange` are already hoisted to the caller per
the existing design — the toolbar callbacks read/write through those, they don't need their own
state) plus `clipboardManager.setPrimaryClip(...)`/`getPrimaryClip()` for the actual clipboard I/O,
matching the existing `KeyboardToolbar.kt` idiom.

## What to verify before calling this done

1. Real device or emulator test (not just compile): open the dialog, type multi-word text, select
   a range by drag, confirm the Copy/Cut/Paste/Select-all toolbar now appears and each button
   actually works (copy → paste back in, cut removes + clipboard has it, select-all selects
   everything).
2. Confirm the toolbar's own popup/menu doesn't visually break or get clipped by the outer
   floating-text-input window's bounds — this is the actual open implementation risk flagged
   above, verify it doesn't recreate a NEW version of the same Popup-focus problem one level down.
3. Confirm existing behavior is unchanged: bracket-paste send path, drag-to-move, resize, TalkBack
   custom actions (move/resize) — this fix should touch nothing except the selection toolbar.
4. Re-run whatever this module's existing Compose UI tests are (check for a
   `FloatingTextInputDialogTest.kt` or similar under `feature/terminal/src/test` /
   `src/androidTest` — verify it exists and what it currently covers before assuming there's
   nothing to run).

## What NOT to do

- Do not switch `Popup` to `Dialog` to sidestep the issue — changes touch-dispatch behavior that's
  deliberate and already tested (see Context section above).
- Do not touch any file other than `FloatingTextInputDialog.kt` (and a new test file if one is
  warranted) — this is a scoped, single-file fix.
- Do not guess the `TextToolbar` interface shape from a general Compose blog post — verify against
  the actual pinned Compose UI version's real interface first.
- Do not commit/push — leave changes as an uncommitted diff on a fresh branch cut from
  `upstream/main` for review before anything ships.
