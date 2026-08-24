# GitForge design system

The approved V2 palette, how it maps onto Primer, and the decisions behind the
parts that are not obvious.

**These colours are fixed.** They are not to be changed without approval, and
`frontend/src/theme/palette.test.js` enforces every accessibility threshold — a
token edited below AA fails the build rather than shipping.

## Two themes, one brand

`#22C55E` is the brand green and is the same value in both themes. Every fill
uses it: buttons, badges, the active tab underline, integrity marks.

What differs is **text**. `#22C55E` is tuned for dark surfaces and measures
**2.18:1** on the light canvas, far below the 4.5:1 that text requires, so light
mode renders links and accent text in `#15803D` — the darker step of the same
ramp. The identity is carried by the fill; only the text darkens.

| Role | Light | Dark |
|---|---|---|
| canvas | `#F9FAFB` | `#0D1117` |
| surface | `#FFFFFF` | `#161B22` |
| primary text | `#111827` | `#E6EDF3` |
| secondary text | `#6B7280` | `#8B949E` |
| decorative border | `#E5E7EB` | `#30363D` |
| **control border** | `#6B7280` | `#6B7280` |
| accent text / link | `#15803D` | `#22C55E` |
| brand fill | `#22C55E` | `#22C55E` |
| button fill / label | `#15803D` / white | `#22C55E` / `#0D1117` |
| success · warning · error · info | `#15803D` · `#B45309` · `#DC2626` · `#2563EB` | `#22C55E` · `#F59E0B` · `#EF4444` · `#3B82F6` |
| diff added bg / text | `#DCFCE7` / `#166534` | `#0D2818` / `#4ADE80` |
| diff removed bg / text | `#FEE2E2` / `#991B1B` | `#2D0F0F` / `#F87171` |
| terminal | `#0D1117` | `#0D1117` |

## Decisions worth recording

**The control border is the same value in both themes.** A divider only has to
separate two areas and has no contrast requirement, but an input boundary
identifies a control and needs 3:1. `#E5E7EB` measures 1.24:1 on white and
`#30363D` measures 1.55:1 on the dark canvas — both fail. `#6B7280` clears the
bar on both canvases (4.83 and 3.91), so one token serves both.

**The primary button is treated differently per theme, because it has to be.**
White on `#22C55E` is **2.28:1** in any theme. Light therefore darkens the fill
to `#15803D` so a white label reads; dark keeps the brand fill and darkens the
label to `#0D1117` instead. One of the two has to give, and this way the dark
theme — where the brand green is at its best — keeps it unaltered.

**The dark pressed state is not darker.** In dark mode the label is dark, so
darkening the fill *lowers* contrast. The ramp runs out at `#16A34A` (5.74:1);
`#15803D` would be 3.77:1 and fail. Pressed therefore reuses the hover value and
expresses depth through the border rather than the hue. The contrast test caught
this — the first implementation had `#15803D` and failed on the first run.

**Diff rows use opaque colours, not tints.** The palette specifies exact values
for these rows, and being opaque they also solve the sticky-gutter problem
outright: a translucent row background let the code scrolling underneath show
through the frozen line-number columns.

**`fgSubtle` is gone.** V1 had a third, dimmer foreground at **3.99:1** — below
AA — used in sixty places. There are two foregrounds now and both pass.

## Commit graph lanes

Per theme, hue identity preserved, every lane at least 3:1 against its own
canvas. Lanes 1–3 reuse the semantic colours so the graph speaks the same
language as the rest of the interface.

| Lane | Dark | Light |
|---|---|---|
| 1 trunk | `#22C55E` | `#15803D` |
| 2 | `#3B82F6` | `#2563EB` |
| 3 | `#F59E0B` | `#B45309` |
| 4 | `#A97BFF` | `#7E22CE` |
| 5 | `#00B4AB` | `#0F766E` |
| 6 | `#F34B7D` | `#BE185D` |

Green appears **once**, on the trunk. A graph where every line is the brand
colour cannot be read.

The light lanes are close in luminance, which colour alone would not separate
for a red-green colour-blind reader. That is acceptable because colour is
redundant here: `graphMetrics.laneX` gives every lane its own horizontal
position, so parentage is traceable without hue.

## Language colours

Thirty entries, identical in both themes. Fixed by convention rather than by our
palette — a reader recognises Java's brown and Go's cyan, and theming them would
break that recognition. They are only ever drawn as a swatch beside a text label
naming the language, so they carry no information alone and need no contrast
guarantee.

## Typography

Self-hosted, never a CDN. The CSP is `font-src 'self'`, and a webfont request to
a third party is also a request that tells that third party who is reading the
page.

- **Inter Variable** — interface
- **JetBrains Mono Variable** — code, hashes, paths, diffs, terminal

Only the latin subsets are referenced. The packages ship cyrillic, greek and
vietnamese cuts that browsers would never download thanks to `unicode-range`,
but naming them would still copy roughly two megabytes of unread woff2 into the
build. Variable fonts, so one file covers every weight.

## Theme switching

`system` is the default, and it is the honest one: someone who has set their
operating system to light has already answered the question.

The resolved scheme is mirrored onto `<html data-theme>` so `index.css` and
`primer-vars.css` can paint the page before React mounts — without it the first
frame is the wrong colour and the page visibly flips. `ColorModeProvider` holds
the single subscription to the media query; several copies of the hook would
each attach a listener and race to set the attribute.

## Where the colours live

| File | Contains |
|---|---|
| `theme/palette.js` | the approved values — the source of truth |
| `theme/gitforge.js` | the mapping onto Primer's theme object |
| `theme/primer-vars.css` | the same colours as CSS variables, for the Primer components that read those instead |
| `theme/contrast.js` | WCAG maths, with alpha compositing |
| `theme/palette.test.js` | every threshold, enforced |
| `theme/fonts.css` | the two self-hosted faces |
| `theme/diffTints.js` | diff row colours as concrete values |

Primer is themed two different ways and both have to be covered: most components
read the theme object, but some emit `var(--fgColor-accent, #fd8c73)` — a CSS
variable with a *hardcoded GitHub fallback*. Without `primer-vars.css` those
components render GitHub's palette regardless of what the theme object says.
