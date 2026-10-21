# Mamoot UI Design — Aurora (v0.5.0, built on the v0.4.2 iMessage base)

Reference: [Apple chat messages ~ iMessage (Figma Community)](https://www.figma.com/design/j44CSdw96tF25wplPePn1v/Apple-chat-messages-~-iMessage--Community-?node-id=0-1&t=IlVtLFb9TWJ6rG2e-1)
— used as *inspiration* (layout language, spacing, color roles), not copied
assets. Everything below is implemented with Android XML shapes only
(zero third-party dependencies, as before). A pixel-faithful web mock of
the shipped tokens lives in `docs/ui-preview/index.html`.


## What v0.5.0 ("Aurora") adds

- **Vazirmatn everywhere** (`res/font/vazirmatn`, Regular+Bold, set as the
  theme `fontFamily` and in the button style) — the single biggest Persian
  typography upgrade; digits/latin fall back cleanly.
- **Real vector icons** (`ic_*`, stroke style, tinted per use): back,
  search, lock, sliders, compose, send-up, timer, chevron-down, shield,
  key, block, trash, logout. Emoji no longer serve as chrome icons on the
  main screens; circular icon buttons are `ImageButton`s on ripple discs.
- **Motion**: theme-level activity transitions (fade+rise / scale),
  staggered conversation-list entrance, new-message bubble entrance
  (fade/rise/scale, recycling-safe), send-button pop + dim-when-empty,
  animated three-dot typing bubble, scroll-to-bottom FAB with pop-in,
  haptic ticks on send and long-press.
- **Depth**: subtle aurora chat wallpaper (`wall_start/center/end`),
  floating elevated composer card, surface chat header with elevation,
  gradient hero headers (conversation list + settings) with a rounded
  28dp sheet overlapping the hero, iOS-Settings-style grouped card in
  Settings, global pill `MamootButton` style (also lifts dialogs).
- **Imagery & identity**: generated 3D illustrations for the empty
  conversation state and the auth hero (`drawable-nodpi/img_*`),
  gradient adaptive launcher icon with a refined bubble mark, branded
  gradient splash (`SplashTheme` swapped to `AppTheme` in `onCreate`),
  green online presence dot on conversation avatars.
- **UX detail**: Telegram-style scroll-to-bottom button with an
  unseen-below count badge; send affordance reflects composer state;
  typing is a bubble in the thread, not only header text.

## Design tokens

| Role | Light | Dark | Where |
|---|---|---|---|
| Brand | `#007AFF` | `#0A84FF` | accents, badges, links |
| Brand accent 2 | `#5E5CE6` | `#5E5CE6` | logo/compose gradient end |
| My bubble | gradient `#0A84FF → #0062E6` | `#1493FF → #0066F0` | `bubble_mine*` drawables |
| Their bubble | `#E9E9EB` | `#26252A` | `bubble_theirs*` |
| Chat canvas | `#FFFFFF` | `#000000` (pure black) | `chat_bg` |
| Grouped bg | `#F2F2F7` | `#000000` | `bg` (settings) |
| Surface / Surface2 | `#FFFFFF` / `#F2F2F7` | `#1C1C1E` / `#2C2C2E` | cards, pills, fields |
| Text / Text2 | `#111113` / `#8E8E93` | `#FFFFFF` / `#98989F` | — |
| Separator | `#E5E5EA` | `#38383A` | inset hairlines |
| Danger / OK | `#FF3B30` / `#34C759` | `#FF453A` / `#30D158` | iOS system red/green |

## Signature components (from the reference, adapted RTL)

- **Centered chat header** (`activity_chat.xml`): 44dp avatar, name in a
  gray pill with chevron, presence/typing line underneath; circular
  gray icon buttons (back / search / security) float at both sides;
  hairline separator below. Height 104dp — tall like iOS on purpose.
- **Bubbles**: 18dp radius, 16sp text, blue vertical gradient for mine.
  The small 5dp "tail" corner appears **only on the last bubble of a
  group**, pointing at the nearer screen edge. Because Persian is RTL
  (mine = left edge, theirs = right edge — mirrored vs LTR), there are
  `_rtl`/`_ltr` drawable variants chosen at runtime from
  `configuration.layoutDirection` in `MessageAdapter`.
- **Message grouping** (`MessageAdapter`): consecutive same-sender,
  same-day bubbles are 1dp apart; groups get 7dp of air. The
  time/status line shows only under the last bubble of a group
  ( failures/edited/forwarded/TTL always keep theirs). Day headers are
  plain centered gray text, no pill.
- **Tapback-style reactions**: white pill with hairline border pulled
  up −7dp to overlap the bubble corner.
- **Composer**: floating pill input (22dp radius, hairline border) and a
  36dp blue circular `↑` send button; the self-destruct timer becomes a
  gray circular button like iOS's `+`. No fake `+`/mic button — Mamoot
  is text-only by design, so every visible control works.
- **Conversation list**: compact brand row + 30sp large title
  («پیام‌ها»), 54dp avatars with iOS system-color palette, 2-line
  previews, inset separators (starting after the avatar), blue unread
  pill + blue unread time, and a 58dp floating compose button
  (blue→indigo gradient, ✎).
- **Segmented controls** (login/signup, DM/group): gray pill track;
  selection is alpha-driven by existing code, unchanged.
- **Avatars**: deterministic initials on iOS system colors
  (`UiUtil.avatarColor`) — blue, green, orange, pink, purple, sky,
  red, indigo.
- **Launcher icon**: adaptive background follows the new brand blue;
  foreground dots recolored to match.

## RTL & honesty notes

- All layouts keep `start/end` attributes; only the bubble-tail corner
  needed explicit RTL/LTR variants (shape corners are physical).
- Group sender names render as a small bold line in the member's
  avatar color *inside* the bubble (Spannable), instead of same-size
  plain text.
- Everything functional kept its view IDs — this release is purely
  visual; no protocol, storage or sync behaviour changed.
