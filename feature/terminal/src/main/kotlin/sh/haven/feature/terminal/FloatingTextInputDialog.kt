/*
 * Ported from org.connectbot.ui.components.FloatingTextInputDialog
 * (connectbot/connectbot, Apache-2.0) — upstream license header retained
 * below per Apache-2.0 §4. Haven changes: re-namespaced, text/send state
 * hoisted to the caller (per-tab drafts + bracket-paste-aware send live in
 * TerminalScreen), Haven string resources, TalkBack custom actions for
 * move/resize.
 *
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sh.haven.feature.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.edit
import kotlin.math.roundToInt

private const val NEWLINE_SYMBOL = "↩"
private const val TAB_SYMBOL = "⇥"

/**
 * Shows embedded newlines as ↩ (followed by the real line break) and tabs as ⇥
 * inline, while the underlying string keeps the real characters — so what's
 * sent to the terminal is exactly what was typed, but the user can *see* the
 * control characters before sending.
 */
private object SpecialCharVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text

        // Build transformed string and a map from original index to transformed index
        val transformedBuilder = StringBuilder()
        val originalToTransformed = IntArray(original.length + 1)
        for (i in original.indices) {
            originalToTransformed[i] = transformedBuilder.length
            when (original[i]) {
                '\n' -> transformedBuilder.append("$NEWLINE_SYMBOL\n")
                '\t' -> transformedBuilder.append(TAB_SYMBOL)
                else -> transformedBuilder.append(original[i])
            }
        }
        originalToTransformed[original.length] = transformedBuilder.length
        val transformed = transformedBuilder.toString()

        // Build reverse map from transformed index to original index
        val transformedToOriginal = IntArray(transformed.length + 1)
        for (i in original.indices) {
            val tStart = originalToTransformed[i]
            val tEnd = originalToTransformed[i + 1]
            for (t in tStart until tEnd) {
                transformedToOriginal[t] = i
            }
        }
        transformedToOriginal[transformed.length] = original.length

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = originalToTransformed[offset.coerceIn(0, original.length)]

            override fun transformedToOriginal(offset: Int): Int = transformedToOriginal[offset.coerceIn(0, transformed.length)]
        }

        return TransformedText(AnnotatedString(transformed), offsetMapping)
    }
}

// Position/size persistence. Same screen-fraction keys as upstream, but in a
// dedicated prefs file (Haven has no androidx.preference default-prefs users;
// DataStore is the app-wide store and isn't worth an async round-trip for a
// window geometry). Shared across all tabs/profiles, matching upstream.
private const val PREFS_NAME = "floating_text_input"
private const val PREF_FLOATING_INPUT_X = "floating_input_x"
private const val PREF_FLOATING_INPUT_Y = "floating_input_y"
private const val PREF_FLOATING_INPUT_WIDTH = "floating_input_width"
private const val PREF_FLOATING_INPUT_HEIGHT = "floating_input_height"
private const val DEFAULT_X_RATIO = 0.05f
private const val DEFAULT_Y_RATIO = 0.3f
private const val DEFAULT_WIDTH_RATIO = 0.9f
private const val DEFAULT_HEIGHT_RATIO = 0.25f
private const val MIN_WIDTH_DP = 200f
private const val MIN_HEIGHT_DP = 80f

/** Screen-fraction step used by the TalkBack custom move/resize actions. */
private const val A11Y_STEP_RATIO = 0.1f

/**
 * One selection-menu request captured from Compose's text-selection machinery:
 * where the selection is (in the popup content's root coordinates) and which
 * actions currently apply. A null callback means the machinery considers that
 * action inapplicable right now (e.g. Paste with an empty clipboard, Select
 * all when everything is already selected) — the menu simply omits the button,
 * mirroring what the platform ActionMode toolbar does.
 *
 * Internal (not private) so unit tests can drive [FloatingInputTextToolbar]
 * directly.
 */
internal class TextSelectionMenuRequest(
    val rect: Rect,
    val onCopyRequested: (() -> Unit)?,
    val onPasteRequested: (() -> Unit)?,
    val onCutRequested: (() -> Unit)?,
    val onSelectAllRequested: (() -> Unit)?,
)

/**
 * Replacement for Compose's default ActionMode-backed text toolbar, needed
 * because this dialog's TextField lives inside a focusable [Popup]: a popup
 * window has no real DecorView, so `View.startActionMode(TYPE_FLOATING)` —
 * which the default `AndroidTextToolbar` relies on — silently does nothing
 * there, and the Copy/Cut/Paste/Select-all toolbar never appears (no crash,
 * no log; see docs/plans/floating-text-input-toolbar-fix.md). Provided via
 * `LocalTextToolbar` scoped to just the TextField.
 *
 * This class only *captures* the show/hide requests as snapshot state; the
 * menu itself is rendered by [TextSelectionMenu] inside the popup's own
 * window — deliberately NOT another Popup window, so there is no second
 * focusable window to recreate the same focus conflict one level down.
 *
 * Signature verified against androidx.compose.ui:ui 1.11.4 (the version the
 * pinned compose-bom 2026.06.01 resolves to): the abstract `showMenu` takes
 * (rect, onCopy, onPaste, onCut, onSelectAll), all callbacks nullable; the
 * 5-callback `onAutofillRequested` overload is an interface default that
 * delegates here, so it needs no override (this menu offers no Autofill
 * entry, matching the pre-fix behavior of this dialog).
 */
internal class FloatingInputTextToolbar : TextToolbar {
    var menuRequest by mutableStateOf<TextSelectionMenuRequest?>(null)
        private set

    override val status: TextToolbarStatus
        get() = if (menuRequest != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        menuRequest = TextSelectionMenuRequest(
            rect = rect,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested,
            onCutRequested = onCutRequested,
            onSelectAllRequested = onSelectAllRequested,
        )
    }

    override fun hide() {
        menuRequest = null
    }
}

/**
 * Floating, draggable, resizable text-entry window over the terminal: type a
 * full command/line with the normal IME (autocorrect, swipe typing, voice
 * input, cursor movement), review it, then send the whole string to the
 * session in one shot instead of fighting the raw terminal cell.
 *
 * State is intentionally hoisted: [text] / [onTextChange] are backed by a
 * per-tab draft map in TerminalScreen (so an unsent draft survives tab
 * switches, rotation and process death), and [onSend] owns the
 * bracket-paste-aware injection into the active tab. This composable only
 * renders and reports.
 *
 * @param onSend fired on the send button; the caller sends the current [text]
 *   (bracket-wrapped as needed) and clears the draft.
 * @param onDismiss fired on the close button, back press or a tap outside the
 *   window. The draft is NOT cleared on dismiss — only a successful send
 *   clears it.
 */
@Composable
internal fun FloatingTextInputDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // Calculate screen dimensions
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val minWidthPx = with(density) { MIN_WIDTH_DP.dp.toPx() }
    val minHeightPx = with(density) { MIN_HEIGHT_DP.dp.toPx() }

    // Load saved position/size or use defaults
    val savedX = prefs.getFloat(PREF_FLOATING_INPUT_X, DEFAULT_X_RATIO)
    val savedY = prefs.getFloat(PREF_FLOATING_INPUT_Y, DEFAULT_Y_RATIO)
    val savedWidth = prefs.getFloat(PREF_FLOATING_INPUT_WIDTH, DEFAULT_WIDTH_RATIO)
    val savedHeight = prefs.getFloat(PREF_FLOATING_INPUT_HEIGHT, DEFAULT_HEIGHT_RATIO)

    // Current position and size in pixels
    var offsetX by remember { mutableFloatStateOf(screenWidthPx * savedX) }
    var offsetY by remember { mutableFloatStateOf(screenHeightPx * savedY) }
    var windowWidthPx by remember { mutableFloatStateOf(screenWidthPx * savedWidth) }
    var windowHeightPx by remember { mutableFloatStateOf(screenHeightPx * savedHeight) }

    val textFieldFocusRequester = remember { FocusRequester() }

    // Custom selection toolbar (Copy/Cut/Paste/Select all) — the platform
    // ActionMode one never shows inside this focusable Popup; see the class
    // docs on FloatingInputTextToolbar.
    val selectionTextToolbar = remember { FloatingInputTextToolbar() }

    // Focus the field as soon as the window opens so typing can start
    // immediately.
    LaunchedEffect(Unit) {
        textFieldFocusRequester.requestFocus()
    }

    // Save position and size when dialog closes
    DisposableEffect(Unit) {
        onDispose {
            prefs.edit {
                putFloat(PREF_FLOATING_INPUT_X, offsetX / screenWidthPx)
                putFloat(PREF_FLOATING_INPUT_Y, offsetY / screenHeightPx)
                putFloat(PREF_FLOATING_INPUT_WIDTH, windowWidthPx / screenWidthPx)
                putFloat(PREF_FLOATING_INPUT_HEIGHT, windowHeightPx / screenHeightPx)
            }
        }
    }

    fun moveBy(dx: Float, dy: Float) {
        offsetX = (offsetX + dx).coerceIn(0f, (screenWidthPx - windowWidthPx).coerceAtLeast(0f))
        offsetY = (offsetY + dy).coerceIn(0f, (screenHeightPx - windowHeightPx).coerceAtLeast(0f))
    }

    fun resizeBy(dw: Float, dh: Float) {
        windowWidthPx = (windowWidthPx + dw).coerceIn(minWidthPx, screenWidthPx - offsetX)
        windowHeightPx = (windowHeightPx + dh).coerceIn(minHeightPx, screenHeightPx - offsetY)
    }

    // TalkBack: the raw drag gestures below are invisible to accessibility
    // services, so expose move/resize as semantic custom actions (on the
    // title and the resize handle respectively), stepping by a fixed screen
    // fraction per activation.
    val moveStepX = screenWidthPx * A11Y_STEP_RATIO
    val moveStepY = screenHeightPx * A11Y_STEP_RATIO
    val moveActions = listOf(
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_move_up)) {
            moveBy(0f, -moveStepY); true
        },
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_move_down)) {
            moveBy(0f, moveStepY); true
        },
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_move_left)) {
            moveBy(-moveStepX, 0f); true
        },
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_move_right)) {
            moveBy(moveStepX, 0f); true
        },
    )
    val resizeActions = listOf(
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_expand)) {
            resizeBy(moveStepX, moveStepY); true
        },
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_shrink)) {
            resizeBy(-moveStepX, -moveStepY); true
        },
    )
    val resizeHandleDescription = stringResource(R.string.terminal_text_input_resize_handle)

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Tap outside the window dismisses (without clearing the
                    // draft — it's hoisted). The full-screen scrim also
                    // deliberately keeps terminal gestures frozen while the
                    // dialog is up, matching upstream.
                    detectTapGestures(onTap = { onDismiss() })
                },
        ) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .width(with(density) { windowWidthPx.toDp() })
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                // Draggable header with title and close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        )
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                moveBy(dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.terminal_text_input_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { customActions = moveActions },
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_close),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // TextField with send button and resize handle to the right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { (windowHeightPx - 36.dp.toPx()).coerceAtLeast(minHeightPx).toDp() }),
                ) {
                    // LocalTextToolbar is scoped to just this TextField: the
                    // selection machinery inside it routes its ActionMode
                    // requests to our toolbar, while everything else in the
                    // dialog keeps the ambient default.
                    CompositionLocalProvider(LocalTextToolbar provides selectionTextToolbar) {
                        TextField(
                            value = text,
                            onValueChange = onTextChange,
                            placeholder = {
                                Text(stringResource(R.string.terminal_text_input_placeholder))
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                            ),
                            visualTransformation = SpecialCharVisualTransformation,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            shape = RoundedCornerShape(bottomStart = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .focusRequester(textFieldFocusRequester),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(bottomEnd = 12.dp),
                            ),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IconButton(
                            onClick = onSend,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.terminal_text_input_send),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .semantics {
                                    contentDescription = resizeHandleDescription
                                    customActions = resizeActions
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        resizeBy(dragAmount.x, dragAmount.y)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.OpenInFull,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // Selection menu, rendered INSIDE this popup's own window (a
            // sibling drawn after — therefore above — the dialog itself).
            // Not a nested Popup/PopupWindow on purpose: a second window
            // would reintroduce the exact focus/window problem this replaces.
            // The empty area of the overlay doesn't consume touches, so the
            // scrim's tap-outside-to-dismiss keeps working.
            selectionTextToolbar.menuRequest?.let { request ->
                TextSelectionMenu(request = request)
            }
        }
    }
}

/**
 * The floating Copy/Cut/Paste/Select-all menu for [FloatingInputTextToolbar].
 * Positioned above the selection rect ([TextSelectionMenuRequest.rect], in
 * root coordinates — the popup content fills its window, so root == window),
 * falling back to below the rect when there is no room above, clamped to the
 * window horizontally. Button order matches the platform ActionMode toolbar
 * (Cut, Copy, Paste, Select all) and labels reuse the system's own localized
 * android.R strings, so no new string resources are needed.
 */
@Composable
private fun TextSelectionMenu(
    request: TextSelectionMenuRequest,
    modifier: Modifier = Modifier,
) {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    Layout(
        content = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                // horizontalScroll: on very narrow windows four buttons can
                // exceed the width; the native toolbar overflows into a "⋮"
                // menu, we just let the row scroll instead.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    request.onCutRequested?.let {
                        TextSelectionMenuButton(android.R.string.cut, it)
                    }
                    request.onCopyRequested?.let {
                        TextSelectionMenuButton(android.R.string.copy, it)
                    }
                    request.onPasteRequested?.let {
                        TextSelectionMenuButton(android.R.string.paste, it)
                    }
                    request.onSelectAllRequested?.let {
                        TextSelectionMenuButton(android.R.string.selectAll, it)
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
            val x = (request.rect.center.x - placeable.width / 2f)
                .roundToInt()
                .coerceIn(0, maxX)
            val maxY = (constraints.maxHeight - placeable.height).coerceAtLeast(0)
            val yAbove = (request.rect.top - margin - placeable.height).roundToInt()
            val y = if (yAbove >= 0) {
                yAbove
            } else {
                (request.rect.bottom + margin).roundToInt().coerceIn(0, maxY)
            }
            placeable.place(x, y)
        }
    }
}

@Composable
private fun TextSelectionMenuButton(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
