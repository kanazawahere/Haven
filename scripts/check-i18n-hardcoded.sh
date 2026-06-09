#!/usr/bin/env bash
# Guard against the #210/#233 regression class: hardcoded user-facing strings in
# Compose UI that bypass stringResource(). Android's built-in HardcodedText lint
# only inspects XML View layouts, not Compose, so it never catches this.
#
# Two high-signal, low-false-positive patterns are flagged:
#   1. `title = "..."` / `subtitle = "..."` named-argument prose.
#   2. Positional `Text("...")` / `Text(text = "...")` prose — the form that let
#      the whole Tunnels screen ship unlocalized (#233), because rule 1 misses it.
#
# Excluded to keep false positives near zero: test/androidTest sources, string
# interpolations (`"$…"` / `"…${…}"`), all-caps technical tokens (e.g. WORKGROUP),
# and multi-line config templates (literals containing \n). The word boundary
# before `Text(` keeps `newPlainText(` (clipboard labels) and wrapper composables
# like `CenterText(` out.
#
# A hit means: move the literal into <module>/src/main/res/values/strings.xml and
# reference it with stringResource(R.string.<key>) — or context.getString outside
# a @Composable.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

# Rule 1 — title=/subtitle= named-arg prose.
named=$(grep -rEn '(^|[(,][[:space:]]*)(title|subtitle)[[:space:]]*=[[:space:]]*"[A-Za-z]' \
    --include='*.kt' app core feature 2>/dev/null \
    | grep -vE '/(test|androidTest)/' \
    | grep -vE '\bval[[:space:]]+(title|subtitle)[[:space:]]*=' \
    | grep -vE '=[[:space:]]*"\$' || true)

if [ -n "$named" ]; then
  echo "✖ Hardcoded user-facing title=/subtitle= strings (localize them — see #210):"
  echo "$named"
  echo
  fail=1
fi

# Rule 2 — positional Text("Prose") / Text(text = "Prose").
positional=$(grep -rEn '(^|[^A-Za-z0-9_.])Text[[:space:]]*\([[:space:]]*(text[[:space:]]*=[[:space:]]*)?"[A-Z]' \
    --include='*.kt' app core feature 2>/dev/null \
    | grep -vE '/(test|androidTest)/' \
    | grep -vE 'Text[[:space:]]*\([[:space:]]*(text[[:space:]]*=[[:space:]]*)?"[A-Z][A-Z0-9_]*"' \
    | grep -vE 'Text[[:space:]]*\([^"]*"[^"]*\$\{' \
    | grep -vE 'Text[[:space:]]*\([^"]*"[^"]*\\n' || true)

if [ -n "$positional" ]; then
  echo "✖ Hardcoded user-facing Text(\"…\") strings (localize them — see #233):"
  echo "$positional"
  echo
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "Move each literal into <module>/src/main/res/values/strings.xml and reference it"
  echo "with stringResource(R.string.<key>) (or context.getString outside a @Composable)."
  echo "If a hit is a genuine technical token (not prose), make it non-prose-shaped"
  echo "(an interpolation/constant) or keep it out of a Text() call."
  exit 1
fi

echo "✓ No hardcoded title=/subtitle= or Text(\"…\") UI strings."
