# Sodyx design system · Phase 1

## Intent and reference

Calm, direct, editorial. A small number of clear actions and open rows, with typography
and alignment doing the work. No status shields, security claims, gradients, or
decorative dashboards. The static preview is labeled on every screen.

Reference: [portfolio-website](https://github.com/FireFlamingo/portfolio-website),
reviewed at commit `4cda6433daaca20aa4efbab3758ed82fd321cb6a`. Its dark ground,
hairline rules, restrained cyan, and typographic hierarchy informed this translation.
The reference uses Archivo; mobile content uses smaller, readable measures rather
than copying the portfolio's enormous type or scroll effects.

## Tokens

Code: `app/src/main/java/io/sodyx/app/ui/design/SodyxDesign.kt`.

| Role | Value | Use |
| --- | --- | --- |
| Background | `#0A0B0D` | App and launch surface |
| Surface | `#0E1116` | Incoming sample messages, subtle markers |
| Raised | `#171C22` | Outgoing sample messages |
| Ink | `#E9ECEF` | Primary reading text |
| Secondary | `#9DA3AB` | Supporting text, timestamps |
| Accent | `#9DD5E2` | Main actions, selected navigation |
| Destructive | `#E5AEA7` | Deliberate session-ending action |
| Hairline | Ink at 9% | Structural rules, decorative markers |
| Subtle rule | Ink at 4.5% | Low-priority separation |
| Control outline | `#646E79` | Interactive outlined controls |

The brief's `#5C636C` muted shade is deliberately not used for small reading text;
secondary text is brighter. Decorative rules are not the sole means of identifying
a control. Status is expressed in words, not color alone.

Computed contrast: Ink on background 16.60:1; Secondary on background 7.74:1 and
on Raised 6.74:1; Accent on background 12.23:1; Control outline on background
3.80:1. Primary button text reverses to the dark background color.

Spacing: 4, 8, 12, 16, 24, 32, 48 dp. Standard page gutter: 24 dp. Content is centered
and capped at 560 dp on wider displays. Button and icon targets are at least 48 dp;
rows expand with content. Controls have 4 dp corners and messages 8 dp corners.

## Typography and assets

Archivo at weight 500 handles display, headings, names, and the wordmark. Manrope at
400 handles body/caption text; 600 handles buttons. Variable axes are explicitly
set instead of relying on a font file's default instance.

| Style | Size / line height |
| --- | --- |
| Display | 40 / 44 sp |
| Page title | 32 / 38 sp |
| Contact name | 21 / 28 sp |
| Body | 15 / 23 sp |
| Button | 14 / 21 sp |
| Caption | 12 / 18 sp |
| Eyebrow | 11 / 17 sp, 1.4 sp tracking |

The preferred Clash Display/Satoshi redistribution terms were not sufficiently
clear for bundling their raw files in this public source repository. Archivo and
Manrope are visually compatible OFL alternatives. Unmodified font files were obtained
from [Google Fonts Archivo](https://github.com/google/fonts/tree/main/ofl/archivo)
and [Google Fonts Manrope](https://github.com/google/fonts/tree/main/ofl/manrope).
Their notices are included in the APK under `assets/licenses/` and in the repository
at `app/src/main/assets/licenses/`. No runtime font download or font service is used.

Icons use consistent square-capped strokes on a 24 dp grid. All icon-only actions
have spoken labels. The adaptive launcher mark is an angular S and includes a
monochrome layer for themed launcher icons. No third-party icon package is needed.

## Screens and behavior

- **Conversations:** open, ruled rows with relationship-specific sample aliases;
  no public profile ID. Both the plus action and New connection reach invitations.
- **Conversation:** sample messages and a bounded in-memory draft, quiet timestamps,
  and a clearly labeled session-ending action. Preview send only announces that
  nothing was sent. It never appends a fabricated delivery or clears the draft.
- **Add connection:** two explicit entry points, sharing an invitation and scanning
  one. Each reveals explanatory preview copy; no QR/token/camera operation occurs.
- **Settings:** dark appearance, working reduced-motion choice, privacy intentions,
  and controls to explore empty lists or restore sample sessions.
- **Session ending:** a dedicated scrollable page explains what changes in the
  preview, what remains, and that external copies cannot be erased. Both cancel and
  confirm are explicit. Only the selected sample connection is marked ended.
- **Empty states:** empty list, no active session (Frozen Lake), and a closed sample
  session. The connection remains reachable after the sample session closes.

No security preference is presented as a functioning protection when it is not.
No global user identity, domain model, persistence, cryptography, network, BLE, or
mock API was added. The Kotlin fixture objects are UI data, not protocol identities.

## Accessibility and motion

All text uses sp and wraps; scroll containers keep actions reachable with larger
type. The conversation composer accounts for keyboard and system insets. App Back
returns to the list; Back from session confirmation cancels to the conversation.
Controls carry Button, Tab, or Switch roles, selected/checked state, and labels.
At enlarged font scales, navigation omits decorative icons to give full labels
more room. Switch marks use strokes rather than scaling a text glyph.
Screen titles are headings; preview explanations use polite live announcements.
Text remains selectable only within the editable draft; no clipboard functionality
or content logging was added.

Page changes use a 160 ms crossfade without spatial movement, springs, or loops.
Compose follows the system animator duration scale; the in-app Reduce motion choice
also forces immediate transitions. No independent animation timer is used.

Fixture content, drafts, route, and preview choices use `remember`, not
`rememberSaveable`. Activity recreation resets the preview and discards drafts.
This is deliberate for this static phase, not the final session lifecycle.

## Validation

Run the commands in README. Host tests guard the privacy manifest. Device tests
exercise launch/recreation, unsent drafts, cancellation, isolated sample closure,
and empty-list invitation navigation. Manual emulator review covers the five screens,
closed-session and empty-list states, a compact viewport, keyboard, and enlarged text.
These checks do not replace a later TalkBack review or actual device testing.

## Deferred

Phase 2 domain concepts and all subsequent storage, contact, cryptography, and
transport phases remain untouched. The current copy is English; localization,
production identity verification copy, release signing, and distribution need their
own review. No security guarantee follows from the visual design.
