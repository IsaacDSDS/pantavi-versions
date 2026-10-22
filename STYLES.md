# Pantavi — Styles

Single source of truth for the receiver UI (Android TV, 10-foot experience, 1920×1080 @ 320 dpi → 960×540 dp).
Everything below is defined in `app/src/main/res/values/` — reuse these resources instead of hard-coding values.

## Colors (`values/colors.xml`)

The app is dark-only (TV screens, dimmed rooms, video letterboxing must blend in).

| Token | Value | Use |
|---|---|---|
| `bg` | `#0B0B0F` | Window and idle-screen background; video letterbox |
| `surface` | `#17171D` | Cards (instructions, now playing) |
| `surface_elevated` | `#22222A` | Step-number badges, progress track |
| `outline` | `#2E2E38` | 1 dp card stroke |
| `text_primary` | `#F5F5F7` | Titles, primary text |
| `text_secondary` | `#A1A1AA` | Supporting text |
| `text_tertiary` | `#6B6B76` | Footnotes (IP / port) |
| `accent` | `#0A84FF` | Brand icon, progress, focus, active pill dot |
| `accent_soft` | `#260A84FF` | Pill background when active |
| `success` | `#30D158` | "Ready" dot |
| `warning` | `#FF9F0A` | "Starting" dot |
| `error` | `#FF453A` | Error dot / message |
| `scrim` | `#B30B0B0F` | Overlays drawn on top of video (connection banner) |

## Typography (`values/styles.xml`, `TextAppearance.AirCast.*`)

System Roboto via font families; sizes are tuned for viewing from a couch.

| Style | Family | Size | Color |
|---|---|---|---|
| `TextAppearance.AirCast.Display` | `sans-serif-medium` | 44sp | `text_primary` |
| `TextAppearance.AirCast.Headline` | `sans-serif-medium` | 28sp | `text_primary` |
| `TextAppearance.AirCast.Title` | `sans-serif-medium` | 22sp | `text_primary` |
| `TextAppearance.AirCast.Body` | `sans-serif` | 20sp | `text_secondary` |
| `TextAppearance.AirCast.Caption` | `sans-serif` | 16sp | `text_tertiary` |
| `TextAppearance.AirCast.Label` | `sans-serif-medium` | 16sp | `text_primary` |

## Spacing & shape (`values/dimens.xml`)

- 4 dp grid: `space_xs` 4, `space_s` 8, `space_m` 16, `space_l` 24, `space_xl` 32, `space_xxl` 48.
- TV overscan safe area: `safe_horizontal` 48 dp, `safe_vertical` 27 dp — apply to every full-screen layout root.
- Radii: `radius_card` 20 dp, `radius_artwork` 16 dp, `radius_pill` 24 dp.
- Brand icon: `icon_hero` 96 dp; artwork: `artwork_size` 280 dp; control glyphs: `icon_control` 32 dp; loader spinner: `loader_size` 64 dp.

## Components

| Component | Where | Notes |
|---|---|---|
| `Widget.AirCast.TextButton` | `styles.xml` + `drawable/bg_button.xml` + `color/button_text.xml` | Focusable, clickable `TextView` pill (`radius_pill`), `Label` text. Default: transparent with 1 dp `outline`, `text_secondary`. Focused: `accent_soft` fill, 2 dp `accent` stroke, `text_primary`. The only button style; rows of buttons are spaced `space_m` |
| `Widget.AirCast.Card` | `styles.xml` + `drawable/bg_card.xml` | `surface` fill, 1 dp `outline`, `radius_card`, `space_xl` padding |
| `Widget.AirCast.StatusPill` | `styles.xml` + `drawable/bg_pill.xml` | `accent_soft` fill, `Label` text; leading dot via `drawable/dot_status.xml` tinted with `success` / `warning` / `error` / `accent` |
| `Widget.AirCast.StepBadge` | `styles.xml` + `drawable/bg_step_badge.xml` | 32 dp circle, `surface_elevated`, `Label` text — numbered instruction steps |
| `Widget.AirCast.Progress` | `styles.xml` | Horizontal `ProgressBar`, 4 dp, `accent` on `surface_elevated` |
| `Widget.AirCast.Banner` | `styles.xml` + `drawable/bg_banner.xml` | Top-centered pill over video (`scrim` fill) for "connecting…" messages, auto-hides |
| Free timer | `Widget.AirCast.Banner` (`layout/activity_receiver.xml`, `free_timer`) | Free version only: top-end pill over mirroring or AirPlay video inside the safe area, "Free · 14:32 left". Text is `text_primary`, `warning` in the last minute. It stops while the video is paused or loading. On the home screen the plan line shows the time left instead |
| Stream loader | `layout/activity_receiver.xml` (`stream_loader`) | Full-screen opaque `bg` panel with a centered indeterminate `ProgressBar` (`loader_size`, `accent`) and a `Title` line ("Loading video from …"). Shown while an AirPlay video loads: at once before the first frame, after 500 ms when it rebuffers. Sits over the video and the home screen, under the playback bar, banners and free timer |
| `Widget.AirCast.PlaybackBar` | `styles.xml` + `drawable/bg_banner.xml` | Bottom bar over AirPlay video inside the safe area: play/pause glyph, `Title` text, remote hint and times in `Caption` + `text_secondary` (legible on `scrim`), `Widget.AirCast.Progress`. Hidden until the first frame (the stream loader covers loading). Shown on every remote key or playback change; hides after 4 s while playing, stays while paused or loading |
| Control glyphs | `drawable/ic_play.xml`, `drawable/ic_pause.xml` | 24-unit vectors at `icon_control`, `text_primary` fill; show the action OK performs (pause while playing, play while paused) |
| Artwork | `ImageView` + `drawable/bg_artwork.xml` | `radius_artwork` with `clipToOutline`; placeholder = brand icon on `surface_elevated` |
| `AspectRatioFrameLayout` | `shared/ui/AspectRatioFrameLayout.kt` | Fits the mirrored `SurfaceView` to the stream's aspect ratio, centered, letterboxed with `bg` |
| Brand icon | `drawable/ic_brand.xml` | Phone sending its picture to a TV, tinted `accent`. Must never resemble Apple's AirPlay logo (screen with a triangle) |
| Launcher banner | `drawable-xhdpi/banner.png` (rendered by `scripts/render_banner.swift`) | 320×180 dp Leanback banner, brand icon + name on `bg` |

## Screens

| Screen | Layout | Notes |
|---|---|---|
| Receiver (home) | `layout/activity_receiver.xml` | Left column: brand icon, name, status pill, IP/port and plan line in `Caption`, a row of `TextButton`s (Pro, licenses) and the overlay-permission `TextButton` when needed. Right column: instructions `Card`. During AirPlay audio the now-playing panel replaces it; Back returns here while the sound keeps playing |
| Pro | `layout/activity_pro.xml` | Same two-column grid as the home screen: icon, `Display` title, `Body` subtitle, one `TextButton`, `Caption` note; right `Card` with ✓ `StepBadge` benefit rows. Opened by the free limit, the title and subtitle say the free minutes are up (the title wraps to two lines) |
| Licenses | `layout/activity_licenses.xml` | Focusable `ScrollView` (no focus highlight) that the remote scrolls; one `TextView` per block; license texts in `Caption`. The key hint (`Banner`) sits below the scroll area, never over the text |

## Themes (`values/themes.xml`)

- `Theme.AirCast` — `android:Theme.DeviceDefault.NoActionBar.Fullscreen`, `windowBackground = bg`, no window animations on the video screen.

## Rules

- Never hard-code colors, text sizes or paddings in layouts; add a token here first.
- All text must be legible from 3 m: nothing below 16sp.
- Focusable elements must show an `accent` focus state (use `Widget.AirCast.TextButton`) — the remote has no pointer.
- Screens without focusable views take remote keys in the activity (`onKeyDown`) and must show what they do (e.g. the playback bar's hint).

## Pantavi Pro (key app, `prokey/`)

The key app copies the tokens it uses (`colors.xml`, `dimens.xml`, `drawable/ic_brand.xml`, `drawable/bg_button.xml`,
`color/button_text.xml`) with the same names and values, and mirrors the styles as `TextAppearance.PantaviPro.*`,
`Widget.PantaviPro.TextButton` and `Theme.PantaviPro`. When a shared token changes here, change it there too.
Its banner is rendered with `scripts/render_banner.swift <out.png> "Pantavi Pro" "Unlocks Pro"`.
