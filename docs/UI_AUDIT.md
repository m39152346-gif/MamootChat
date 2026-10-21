# Mamoot UI/UX Engineering Audit — v0.5.1

Date: 2026-10-21 · Baseline: v0.5.0 `47b051f` (`docs/audit/BASELINE-0.5.0.txt`) ·
Scope: UI/UX, visual identity, icons, typography/RTL, motion, states, device
compatibility, accessibility, render performance. Crypto, sync, backend and
wire formats are out of scope and untouched.

## Method and evidence rules

- Every color claim below is **computed** (WCAG relative-luminance formula,
  script rerun after the fix; raw output in `docs/audit/contrast-0.5.1.txt`).
- Touch-target and accessibility claims come from scripted scans of the real
  layout XML (view sizes, `contentDescription` coverage, text sizes).
- The sandbox has **no Android SDK/emulator/device**. Builds and unit tests run
  on GitHub Actions (same pipeline as every release). Therefore **no score in
  this document claims on-device visual verification**; affected criteria are
  capped (see Scoring) and the manual device checklist is listed under
  "Tests not run". Nothing is marked 10/10.

## Architecture inventory (Phase 0/1)

- Kotlin + Android XML, **zero third-party dependencies**, no AndroidX ·
  compileSdk/targetSdk 35 · minSdk 26 (Android 8+) · plain `Activity`s,
  `ListView`+`BaseAdapter` (no RecyclerView), `findViewById` (no ViewBinding),
  framework `Theme.Material.Light/Dark.NoActionBar`, Vazirmatn (Regular+Bold).
- 7 activities, 10 layouts, 50 drawables, 6 unit-test suites (37 tests).
- Navigation: Main (launcher, splash theme) → Auth / Chat / NewChat / Settings;
  Lock (excluded from recents) and Recovery intercept from Main.
- All activities use `adjustResize`; chat is polling-based (12 s) by design.

### Screen map (prompt checklist → reality)

| # | Area | Real implementation |
|---|------|---------------------|
| 1 | Conversation list | `MainActivity` / `activity_main.xml` |
| 2–3 | 1:1 + group chat | `ChatActivity` (both kinds, `conv.kind`) |
| 4–6 | New chat / contact pick / multi-pick | `NewChatActivity` (DM/group segmented, counter gate exists) |
| 7–9 | Login / signup / complete profile | `AuthActivity` (two layouts in one screen) |
| 10–11 | Settings + security | `SettingsActivity` (+ dialogs) |
| 12 | App lock | `LockActivity` (PIN + biometric) |
| 13–14 | Key recovery / backup file | `RecoveryActivity` + SAF picker + password dialog |
| 15–16 | Menus/dialogs, transient msgs | `AlertDialog`s in Chat/Settings, Toasts, content-free notifications |
| 17–19 | Offline / loading / empty | **defective at baseline → D18 (fixed)** |
| 20–23 | Long data, themes, form states, back paths | audited per screen below |

## Defect register (all reproduced from code/resources, fixed in 0.5.1)

Severity: H = blocks WCAG AA or misleads the user, M = measurable standard
violation, L = consistency.

| ID | Sev | Evidence (0.5.0) | Fix (0.5.1) | Verify |
|----|-----|------------------|-------------|--------|
| D1 | H | Light `text2` #8E8E93 = 3.26∶1 on white, 2.69∶1 on incoming bubble — used for previews, times, hints, meta | `text2` → #636369 (4.92–5.97∶1 everywhere) | contrast script |
| D2 | H | White on brand/bubble gradient start 3.16–4.02∶1 (primary buttons + own messages, both themes) | `brand` light #0066E0 (5.28∶1); bubble gradient #0069D2→#0057D9 (5.31/6.24∶1); new `brand_button` token for white-labelled fills because **no single blue passes both directions in dark theme** (needs L≤0.183 for white-on, L≥0.228 as text on #1C1C1E) | contrast script |
| D3 | H | `on_brand_muted` on hero gradient 2.72∶1 (Main/Settings subtitles); hero center used `@color/brand` (fails in dark) | muted → #F2F7FF; hero center → `bubble_mine_end`; worst point now 4.71∶1 | contrast script |
| D4 | M | Light `danger` text 3.55∶1; white-on-danger (dark recovery button) 3.41∶1 | `danger` light #D93025 (4.77∶1); new `danger_button` token | contrast script |
| D5 | M | Light `ok` green 2.22∶1 (online dot, status icon) | #1E7E34 (5.14∶1) | contrast script |
| D6 | M | `input_border` 1.52/1.86∶1 **and `field_bg` had no stroke at all** — fields were a 1.1∶1 tint patch | stroke added to `field_bg`; border tokens → #7A7A81 / #7D7D84 (≥3.4∶1 both sides) | contrast script + XML |
| D7 | M | Avatar palette: white initials on #FF9500/#34C759/#32ADE6 = 2.20–2.54∶1 | same 8 families darkened, all ≥4.55∶1 (per-user color index unchanged) | contrast script |
| D8 | H | Touch targets 36–44dp: chat header ×3 (38), send (38), TTL (36), cancel-reply (40), settings (40/38), back (38 ×2), scroll FAB (44), segmented (44) | all 48dp (`@dimen/touch_min`); circle drawables reworked with 4–5dp insets + ripple mask so the **visual** circles stay 38–40dp | layout scan |
| D9 | M | Send looked disabled (alpha) but stayed clickable; TalkBack announced it enabled | real `isEnabled` state driven by composer text; `updateSessionUi` no longer force-enables | code review |
| D10 | M | Emoji as primary icon: 🔒 Lock screen, 🗝️ Recovery, 💬/👥 segmented, 🔒 per-user row, 🔒 in secure hint & auth footer | brand vectors (`ic_lock`, `ic_key`) / plain text labels | layout+code grep = none left in controls |
| D11 | M | `includeFontPadding=false` on message bubble — clips Vazirmatn ascenders/dots on some renderers | removed (default padding) | XML |
| D12 | M | Bubble `maxWidth=300dp` fixed ≈94% of a 320dp phone, cramped on tablets | adapter sets 78% of screen width per bind | code review |
| D13 | M | Typing dots = three 8sp "●" TextViews announced by TalkBack; scroll badge 10.5sp | dots `importantForAccessibility=no`, container labelled «در حال نوشتن است»; badge → `text_meta` 11sp | XML |
| D14 | L | Search prev/next/close + cancel-reply were ▲▼✕ glyph Buttons | vector `ImageButton`s (`ic_chevron_up` added), 48dp, labelled | XML+build |
| D15 | H | targetSdk 35 ⇒ Android 15 enforces edge-to-edge and **ignores `statusBarColor`**; no insets handling anywhere → heroes/composer under system bars | `values-v35` + `values-night-v35` with the official temporary `windowOptOutEdgeToEdgeEnforcement`. **Migration to real WindowInsets is required before targetSdk 36** (recorded, not hidden) | build (AAPT, SDK 35) |
| D16 | M | System "reduce motion" ignored: typing-dot loop, message entrance, send pop ran unconditionally | `UiUtil.motionReduced()` (ANIMATOR_DURATION_SCALE) gates all three; static end-states render | code review |
| D17 | M | Auth: hint-only labels, errors only in a global line, no password visibility control, no autofill hints | persistent labels + `labelFor`, per-field `EditText.error` + focus on every validation branch (auth & profile), show-password checkbox, `autofillHints` | code review + build |
| D18 | H | Main: no loading state (empty art could flash), and on network error with an empty list it showed the **"no chats yet" illustration** — error disguised as empty | `ProgressBar` until first answer; dedicated error banner with the real message + «تلاش دوباره» retry; empty art only after a successful empty response | code review |
| D19 | M | Launcher foreground bubble corners at r≈55dp from center — outside the 66dp adaptive-icon safe circle (r≤33) → clipped by circle/squircle masks | foreground redrawn fully inside r≤32; `logo_mark` (splash, unmasked) unchanged | geometry in file comment |
| D20 | L | No `dimens.xml`; 19 distinct text sizes incl. fractions (14.5/12.5/10.5sp) | token file created (touch/spacing/radius/type scale) and applied to all edited surfaces + shared button style; legacy literals counted below, migrated gradually | resource scan |

Contrast result: **12 of 34 measured pairs failed WCAG AA at baseline → 36/36
pass after the fix** (table: `docs/audit/contrast-0.5.1.txt`).

### Checked and found correct (kept deliberately)

- No hardcoded hex colors in layouts; no `left/right` layout attrs (start/end everywhere).
- Every icon-only `ImageButton` already had a Persian `contentDescription`.
- Notification small icon is a proper monochrome silhouette (`ic_stat_mamoot`).
- Group-create button stays disabled until ≥1 member + the label carries the count (not color-only).
- Dark `text2` already passed everywhere (4.86–7.33∶1).
- `ListView` retained: no measured jank evidence justifies a RecyclerView rewrite (Phase 8.2 rule).

## Scoring (weights fixed before scoring, unchanged after)

Weights: hierarchy 8 · typography 8 · color/contrast 14 · spacing 8 · icons 7 ·
brand 6 · interaction clarity 10 · state handling 10 · RTL/long-content 7 ·
accessibility 12 · device compatibility 5 · performance 5 (Σ=100).
Weighted mean per screen, 0–10. **Caps:** with no device screenshots, device
compatibility ≤7 and no criterion is scored 10; anything not executed is
"unverified", not full marks.

| Screen | 0.5.0 | 0.5.1 | Main movers |
|--------|-------|-------|-------------|
| Main | 6.84 | 8.09 | contrast 5→9, states 5→8 (D18), a11y 6→8 (D8) |
| Chat | 6.55 | 8.09 | contrast 5→9, a11y 5→8, icons 6→8, RTL/long 7→8 (D12) |
| NewChat | 7.01 | 7.85 | icons 5→8 (D10), a11y 6→8 |
| Auth | 6.66 | 7.88 | interaction 6→8 + a11y 5→8 (D17) |
| Settings | 7.40 | 7.99 | contrast 6→9 (D3), a11y 7→8 |
| Lock | 6.54 | 7.46 | icons 3→8 (D10), brand 5→7 |
| Recovery | 6.54 | 7.46 | icons 3→8 (D10), brand 5→7 |
| **Mean** | **6.79** | **7.83** | |

## Tests run

- Contrast computation over every real fg/bg token pair, both themes — before/after (evidence files above).
- Scripted layout scans: touch sizes, `contentDescription`, text-size inventory, hardcoded colors, left/right attrs.
- Full XML parse (79 files) + resource-reference resolution (colors/drawables/dimens/styles/fonts/layouts) + Kotlin `R.id` cross-check — all clean.
- GitHub Actions on the release commit: `:app:testDebugUnitTest :app:assembleRelease` (includes AAPT2, lint-vital, R8) + APK signature/package verification published to `apk-latest` (`VERIFICATION.txt`). Result recorded in the PR comment for this version.

## Tests NOT run (and why)

- **On-device/emulator screenshots (Phase 10)** — no Android runtime exists in the build sandbox. Required manual pass on a real phone: small screen, font scale 1.3×, dark+light, keyboard open/closed in chat & auth, TalkBack traversal of chat header/list/composer, Android 15 device for D15, launcher icon under circle/squircle masks (D19).
- TalkBack order, focus order and 1.3× font clipping are therefore **unverified**, and scores are capped accordingly.
- No instrumented/UI tests exist in the project (unit tests only); none were added for view-layout behavior that cannot run here (Robolectric would break the zero-dependency rule).

## Remaining issues (honest backlog)

1. **Strings are still hardcoded** (52 layout literals + Kotlin UI strings; `strings.xml` holds only `app_name`). Full migration is mechanical but large; scheduled separately so this release stays reviewable. User-visible impact today: none (single-locale app), maintenance impact: real.
2. **Dimens migration is partial**: tokens exist and cover shared styles + all surfaces touched here; ~200 legacy dp/sp literals remain in untouched rows.
3. **Edge-to-edge (D15)** uses the temporary opt-out; real insets migration is mandatory before targetSdk 36.
4. Fractional text sizes remain in a few rows (14.5sp chat title, 12.5sp times) — visually harmless, normalized when those rows are next edited.
5. Knowledge gaps needing a device: splash/logo rendering under OEM masks, ripple inset appearance on API 26, header wrap-height at 1.3× font scale.

## Changed files (0.5.1)

27 files +411/−146 plus 6 new files. Colors (both themes), `dimens.xml` (new),
`values-v35`/`values-night-v35` (new), 8 drawables (+`ic_chevron_up`), all 10
layouts, `UiUtil`, `Adapters`, `ChatActivity`, `MainActivity`, `AuthActivity`,
version bump, this document and `docs/audit/*`. No backend, crypto, sync,
database or wire-format file was touched.

---

## Round 2 — v0.5.2 (2026-10-21): closing the documented backlog

Baseline: v0.5.1 `33efe8e` (`docs/audit/BASELINE-0.5.1-round2.txt`). Same rules:
computed/scripted evidence only, no on-device claims, caps unchanged.

### New defects found and fixed

| ID | Sev | Evidence (0.5.1) | Fix (0.5.2) |
|----|-----|------------------|-------------|
| D21 | H | Recovery backup-passphrase dialog used a plain text `EditText` — the passphrase echoed **in clear text** while typing | proper password input type (crypto behavior unchanged) |
| D22 | M | Programmatic dialog fields (Chat prompt, Recovery, Settings `field()`) had no token colors; dialog paddings were raw **pixels** (48px, 56px — density-dependent); the chat prompt field was glued to the dialog edges | token text/hint colors, 16sp, padded containers, `UiUtil.dp()` for all programmatic spacing |
| D23 | M | Emoji still acted as list/menu icons in Kotlin-built dialogs (advanced-security items, message actions, member management, dialog titles) and TalkBack read symbol names before every label | emoji removed from all control labels/titles/list items (text-matched menu entries updated at both sites). Kept by design: reaction emojis (the feature itself), ✓/✓✓ ticks, the per-message 🔒 meta mark (announced as "locked", and it is the only per-message E2EE indicator in mixed normal/secret conversations), emoji inside user-facing *explanatory paragraphs* |
| D24 | M | Message meta: "sending" was emoji-only (🕐), TTL was symbol-only (⏱) — meaning carried by glyph alone | words: «در حال ارسال», «ارسال نشد», «حذف خودکار», «بازارسال‌شده» |
| D25 | M | Mixed-direction content: Latin messages/usernames in RTL bubbles aligned as RTL; email/username fields typed in an RTL field | `textDirection="firstStrong"` on bubble, preview, chat title, user name; `textDirection="ltr"` on email/username inputs (component-level, page direction untouched) |
| D26 | L | 85 hardcoded strings in layouts; 19 text sizes incl. fractions; padding/radius literals | **all layout strings migrated** to `strings.xml` (66 unique keys, map in `docs/audit/strings-migration-0.5.2.txt`; only the ‹ / ● / م glyphs remain literal — symbols, not copy). Type scale completed (`text_label`, `text_subtitle` added from real usage) and applied to every layout text size except the decorative typing dots; value-equal spacing/radius literals tokenized |
| D27 | L | Borderless text buttons (forgot-password, retry, biometric…) relied on OEM theme min-height | explicit `minHeight=@dimen/touch_min` (48dp) |
| D28 | L | `list_item_in` ran 280ms | 240ms (all motion now in the 160–250ms band; reduced-motion gates from D16 apply) |

### Re-verified, unchanged from round 1

Contrast tokens untouched (round-1 computation stands, 36/36) · touch targets
still 48dp · no backend/crypto/sync file touched · XML parse (79) + reference
resolution (incl. the new `@string/` refs) + `R.id` cross-check all clean ·
CI (unit tests + release build) on the release commit, result in the PR comment.

### Scores after round 2 (same weights, same caps)

| Screen | 0.5.0 | 0.5.1 | 0.5.2 | Round-2 movers |
|--------|-------|-------|-------|----------------|
| Main | 6.84 | 8.09 | 8.17 | spacing tokens |
| Chat | 6.55 | 8.09 | 8.32 | type scale, firstStrong, spacing |
| NewChat | 7.01 | 7.85 | 7.93 | spacing tokens |
| Auth | 6.66 | 7.88 | 8.11 | LTR fields, type scale, spacing |
| Settings | 7.40 | 7.99 | 8.14 | emoji-free dialogs, spacing |
| Lock | 6.54 | 7.46 | 7.54 | spacing |
| Recovery | 6.54 | 7.46 | 7.64 | masked passphrase (D21), spacing |
| **Mean** | **6.79** | **7.83** | **7.98** | |

### Still open (unchanged honesty)

1. ~369 Persian string literals remain in Kotlin UI code (dialogs, toasts,
   dynamic status copy). Layouts are fully migrated; the Kotlin half needs a
   dedicated, carefully-reviewed pass (hundreds of call sites, several matched
   by string equality) — deliberately not crammed into a UI-polish release.
2. 139 padding/margin literals use off-scale values (6/10/14dp…). Tokenizing
   them 1:1 is impossible without changing the scale or the pixels; they are
   counted, not hidden.
3. Edge-to-edge remains on the documented temporary opt-out (D15). The real
   WindowInsets migration needs a physical device to verify per-screen
   (hero/composer under transparent bars); shipping it blind risks breaking
   every screen, so it stays a planned, device-gated task before targetSdk 36.
4. Phase 10 (real screenshots: TalkBack order, 1.3× font, small screens,
   Android 15, OEM icon masks) is still not executable in this environment —
   scores remain capped and these items **unverified**.
