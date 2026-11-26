# Design tokens

**Current app policy:** Velo uses the workspace's dark appearance throughout. The Light columns
below record reference and comparison values, not a selectable Android theme. For the current
settings layout, its controls and the reasons for their spacing, see [Settings design](settings-design.md).

Velo's colours, corner radii, sizes and type were read from images of Rex for iOS, and the
redesign canvas (boards Tokens, Controls, Overlays, Tile, KeyBar, Rail and Pane) then set the
values it draws. The redesign keeps the dark colours read from the images, adds a group for the
settings sheet and the raised card, and gives its sizes at the **Medium** size; a source that
names a board is the redesign's. This file lists what was read, where, what is built from it,
and what is still open. The tokens live in `app/src/main/java/io/github/yhzion/velo/ui/theme/`:
the colours in `Tokens.kt`, the radii, sizes, spacing steps and text styles in `Metrics.kt`.
`RedesignTokensTest` holds them against `app/src/test/resources/redesign/tokens.json`, the
Tokens board's values copied by hand.

This document records the reference measurements and the tokens derived from them, then
describes the theme, glass surfaces, reusable components, and debug gallery.

## Images

The original images are not bundled. The table identifies the published references used for the
measurements, including frame positions for video clips.

| ID | Source | Size | View |
|---|---|---|---|
| D12 | [Dark demo video](https://x.com/almonk/status/2113677839853572233), frame 172 (zero-based) | 2902×2066 | Unfolded, dark |
| D01 | Same clip, frame 7 | 2902×2066 | Folded, dark |
| L01 | [Light demo video](https://x.com/almonk/status/2114390982049329442), 1 s | 2276×1714 | Folded, light |
| L06 | Same clip, 11 s | 2276×1714 | Unfolded with sidebar |
| L09 | Same clip, 17 s | 2276×1714 | Unfolded, overview |
| L20 | Same clip, 39 s | 2276×1714 | Unfolded with keyboard |
| M01 | [Device demo video](https://x.com/mschoening/status/2115407137127928160), 0.5 s | 2854×2008 | Unfolded, light |
| M03 | Same clip, 2.5 s | 2854×2008 | System context menu |
| PK | [Phone screenshot](https://x.com/pearkes/status/2114770906811863515) | 942×2048 | Dark |
| AK | [Go to Directory screenshot](https://x.com/almonk/status/2119199060217241721) | 942×2048 | Dark |
| MB1 | [Pair via QR Code screenshot](https://x.com/MattBond21/status/2115451898656637089), first image | 942×2048 | Dark |
| MB2 | Same post, second image | 942×2048 | Session list |
| CM | [Third-party write-up](https://dev.classmethod.jp/en/articles/reona-superlogical-rex-beta-try/) | 588×1200 | Dark chrome accent sample |

Accuracy. D*, L* and M* are H.264 frames: a flat area reads within about 3 levels per
channel of its neighbours (the median of a rectangle is given where one was taken); thin
text strokes are blended with what is behind them, so a text colour read from a frame is the
lightest (on dark) or darkest (on light) pixel of the line and is still a little short of the
real colour. PK, AK, MB1 and MB2 are JPEG stills with larger text; where a still shows the
same role, the still's value is preferred for text. CM is a scaled third-party screenshot;
only its dark-chrome accent was sampled.

## Converting image pixels to dp

- **Duo, unfolded** (D12, L06, L09, L20): the reference screen is mapped by width onto a
  1088dp Android window (2448px at 360dpi). D12's screen spans x 587..2266 (1680px):
  **1 px = 0.6476 dp**. L06 and L20 span x 203..2024 (1822px): **1 px = 0.5971 dp**.
  Height-based scaling would give sizes about 7.6% larger. Width is the relevant measure
  because the sidebar, rail, and key bar are laid out along it.
- **Duo, folded** (D01, L01): the scale of the unfolded frame of the same clip (the render is
  the same device at the same zoom: the screen is 1198 px tall folded and 1178 px unfolded
  in the dark clip).
- **Real Duo** (M01, M03): the frame is the whole screen, 2854 px = 1088 dp, **1 px = 0.3812 dp**.
- **iPhone stills** (PK, AK, MB1, MB2): 942 px corresponds to the phone's 402 pt width,
  so **1 px = 0.4268 pt**, with 1 pt treated as 1 dp.

Cross-check with an element whose size does not depend on the app: the status clock's digits.
They are 31 px = 13.2 pt tall in PK and MB2. On the Duo, by the mappings above: D12 20 px =
13.0 dp, L06 22 px = 13.1 dp, M01 35 px = 13.3 dp. The three mappings agree with the stills
within 2 % (rows "status clock" of the table "Type").

Text sizes. A glyph is boxed at half ink (a pixel counts when it is at least halfway from the
background to the stroke colour), which is where the outline of an antialiased glyph lies. A
size is the height of a capital divided by 0.705, or of a lower-case letter without ascender
divided by 0.526: the cap height and x-height of the iOS system font as shares of its size.
Android's Roboto has 0.711 and 0.528 (read from `/system/fonts/Roboto-Regular.ttf` of the
emulator image), so a size in sp set from an iOS size draws glyphs 0.5 % taller. Checked a
second way, by width: "Search" is 58.3 dp wide in D12 and 58.5 dp in L06 and "Open Link" 82.3 dp
in M03; Roboto at 19 sp sets them 57.0 and 82.5 dp wide, at 17 sp 51.0 and 73.8.

## Tokens

Read in code as `VeloTheme.colors`, `VeloTheme.radii`, `VeloTheme.sizes` and `VeloTheme.type`.
A source names the image by its id above and the reading by its name in "Read from the images"
below, where the pixel, line or rectangle and the value read are listed. "derived" means that no
image shows the value; what it is derived from follows.

Before the redesign: 87 tokens measured and 86 derived, counted per token and appearance (a
colour that differs between Dark and Light counts twice); a token whose value was moved from a
measured one to meet a contrast ratio counts as derived. The redesign's values are not in
those counts; each names its board.

Colours come in five groups, one per kind of surface, and every group has the same seventeen
roles. A screen area reads the group of the surface it sits on. That is what lets the sidebar
stay dark in Light: it has its own group, dark in both appearances, while the chrome and the
overlays of Light are light. The fifth group, `sheet`, is the redesign's settings: chrome's
fill with the sidebar's controls. The workspace's chrome (the rail, the key bar) keeps its own
glass and its `#7FA6F5` accent, as the Rail and KeyBar boards draw them.

Ruling kept: in every image, the light ones included, the sidebar, its "Search" field and the
overview backdrop are black or near-black (L06, L09, D12, MB2). In Light they stay so.

What the unit test `TokensContrastTest` holds, in both appearances and for every group, and for
the raised card on the sheet and on the sidebar: text, secondary text and destructive text
(`dangerText`) reach 4.5 on the surface, on a selected row and on glass; a paragraph on a card
(`textOnRaised`) reaches 4.5 on it;
text on an accent or danger fill reaches 4.5; every state colour and every glyph of an icon-only
control reaches 3 on what it is drawn on; a selected row differs from its surface by 1.1 or more
(the Duo's own pill is 1.12) and a glass control by 1.25 or more through its fill or its edge. An
overlay is drawn over a blur of the terminals, and a terminal can show any colour, so its
surface is tested as the fill the app really draws (`VeloColors.overlayOver`) over each of 729
colours, the corners of the colour cube among them. The same test holds the other way: red,
green and blue behind an overlay must give fills 15 % of a channel apart or more, so that the
blur can be seen. The focus border, `paneFocus`, composited over the terminal's default background
(black, the renderer's clear colour before a palette arrives), reaches 3 against that background and
against the backdrop's surface. Appearance → Active pane can replace the colour of `paneFocus`
but not its strength (`VeloTheme.paneFocus`, `ui/PaneHighlight.kt`); every preset there reaches 3
on black at that strength (`PaneHighlightTest`), and a custom colour below it gets a note, not a
refusal. The overview's selected edge follows the same choice (`VeloTheme.selectedCardEdge`).

Three measured values were moved to pass, and are marked derived for it: the secondary text of
dark chrome (PK reads `#85888D`, 4.15 on glass; the sidebar's `#8C8F94` is used), the secondary
text of the light sidebar (L06 reads `#808088`, 4.27 on glass) and the secondary text of both
overlays (AK `#A8AAB1`, M03 `#727271`; each moved until it reaches 4.5 over the worse backdrop).

### Colours

**Backdrop: around the panes and behind the tab overview. One set for both appearances** (`VeloTheme.colors.backdrop`)

The app requests light system-bar icons over its dark surfaces, regardless of device appearance.

| token | for | value | source |
|---|---|---|---|
| `surface` | The fill of the area itself. | `#000000` | L09 overview backdrop; L20 gutters |
| `text` | Body text and the icons that go with it. | `#FFFFFF` | L09 status clock in the overview |
| `secondary` | Captions, section headers, placeholders and icons that are not the subject of their row. | `#8C8F94` | derived: the sidebar's secondary (MB2); the overview shows no secondary text |
| `disabled` | Text and icons of something that takes no tap. Not held to a contrast ratio. | `#5F6064` | derived: the sidebar's |
| `glass` | The fill of a glass control (a disc, a capsule, a field) on this surface. | `#777777` | L09 rail discs in the overview |
| `glassEdge` | The light edge of a glass control: one line along its rim. | `#7A7A7A` | L09 rail discs in the overview, their first rows |
| `onGlass` | The glyph or label inside a glass control. | `#2E2E2E` | L09 glyph of a rail disc in the overview |
| `glassSelected` | The cell of a glass capsule that is selected or switched on. | `#9A9A9A` | derived: the disc a step lighter, the step of chrome's selected cell |
| `selected` | The pill behind the selected row. | `#131313` | derived: the pill of the sidebar on black (L06) |
| `divider` | A hairline between rows or groups. | `#2B2C30` | derived: the sidebar's |
| `accent` | "This one is active": a switched-on toggle, an armed key, the working mark, a link. | `#63A2F6` | MB2 activity dot |
| `onAccent` | Text and glyphs on an [accent] fill. | `#000000` | derived: the surface under the accent |
| `ok` | Connected, running, finished. | `#30D158` | derived: the iOS system green of the dark appearance |
| `warning` | Connecting, starting, waiting for the user. | `#FF9F0A` | derived: the iOS system orange of the dark appearance |
| `danger` | Failed or not connected; a destructive action, as a mark, an outline or a fill. | `#FF453A` | derived: the iOS system red of the dark appearance |
| `dangerText` | A destructive action and an error, as text. | `#FF6B61` | derived: the sheet's |
| `onDanger` | Text on a [danger] fill (a destructive confirm button). | `#000000` | derived: as onAccent |

**Sidebar: the sidebar and its search field. Dark in both appearances** (`VeloTheme.colors.sidebar`)

| token | for | Dark | source | Light | source |
|---|---|---|---|---|---|
| `surface` | The fill of the area itself. | `#0B0C11` | D12 | `#000000` | L06 |
| `text` | Body text and the icons that go with it. | `#F3F4F9` | MB2 row title (D12 reads #E8ECF0 through thin video strokes) | `#D5DDE4` | L06 row title, host label |
| `secondary` | Captions, section headers, placeholders and icons that are not the subject of their row. | `#8C8F94` | MB2 group header | `#8C8F94` | derived: Dark's (MB2); L06's group header #808088 is 4.27 on glass |
| `disabled` | Text and icons of something that takes no tap. Not held to a contrast ratio. | `#5F6064` | MB2 row age | `#5F6064` | derived: Dark's |
| `glass` | The fill of a glass control (a disc, a capsule, a field) on this surface. | `#24262B` | D12 "Search" capsule, eye and gear capsule | `#1D1D1D` | L06 "Search" capsule, eye and gear capsule |
| `glassEdge` | The light edge of a glass control: one line along its rim. | `#383C42` | D12 eye and gear capsule top rim | `#434343` | L06 "Search" capsule top rim |
| `onGlass` | The glyph or label inside a glass control. | `#F3F4F9` | derived: text (D12 eye glyph reads #E1E6EC) | `#D5DDE4` | derived: text (L06 eye glyph reads #D0D8DD) |
| `glassSelected` | The cell of a glass capsule that is selected or switched on. | `#3A3D44` | derived: chrome's selected cell (D12 tab capsule) | `#383838` | derived: Dark's step from glass to its selected cell |
| `selected` | The pill behind the selected row. | `#191A1F` | D12 selected row pill | `#131313` | L06 selected row pill |
| `divider` | A hairline between rows or groups. | `#2B2C30` | derived: the list card of MB2, the next step above the surface | `#2B2C30` | derived: Dark's |
| `accent` | "This one is active": a switched-on toggle, an armed key, the working mark, a link. | `#63A2F6` | MB2 activity dot (D12 reads #5C9EF3) | `#72ACFA` | L06 dot on the eye |
| `onAccent` | Text and glyphs on an [accent] fill. | `#0B0C11` | derived: the surface | `#000000` | derived: the surface |
| `ok` | Connected, running, finished. | `#30D158` | derived: the iOS system green of the dark appearance | `#30D158` | derived: the iOS system green of the dark appearance |
| `warning` | Connecting, starting, waiting for the user. | `#FF9F0A` | derived: the iOS system orange of the dark appearance | `#FF9F0A` | derived: the iOS system orange of the dark appearance |
| `danger` | Failed or not connected; a destructive action, as a mark, an outline or a fill. | `#FF453A` | derived: the iOS system red of the dark appearance | `#FF453A` | derived: the iOS system red of the dark appearance |
| `dangerText` | A destructive action and an error, as text. | `#FF6B61` | derived: the sheet's | `#FF453A` | derived: as danger |
| `onDanger` | Text on a [danger] fill (a destructive confirm button). | `#0B0C11` | derived: the surface | `#000000` | derived |

**Chrome: the pane header, the key bar, the rail, the full screens** (`VeloTheme.colors.chrome`)

| token | for | Dark | source | Light | source |
|---|---|---|---|---|---|
| `surface` | The fill of the area itself. | `#0F1015` | D12 pane, key bar and rail | `#F9F9F9` | L06 pane card, L20 key bar strip |
| `text` | Body text and the icons that go with it. | `#F5F8FE` | PK pane header title | `#151616` | M01 pane header title |
| `secondary` | Captions, section headers, placeholders and icons that are not the subject of their row. | `#8C8F94` | derived: MB2 group header; PK's subtitle #85888D is 4.15 on glass | `#626164` | L01 pane header subtitle |
| `disabled` | Text and icons of something that takes no tap. Not held to a contrast ratio. | `#5F6064` | derived: the sidebar's | `#A7A7A7` | derived: the glass rim, the lightest grey the Duo draws on this surface |
| `glass` | The fill of a glass control (a disc, a capsule, a field) on this surface. | `#25282F` | D12 command disc, keyboard capsule, tab capsule | `#F5F5F5` | L06 rail discs, L01 tab capsule |
| `glassEdge` | The light edge of a glass control: one line along its rim. | `#424348` | D12 command disc top rim | `#A7A7A7` | M01 back disc rim |
| `onGlass` | The glyph or label inside a glass control. | `#F5F8FE` | derived: text (PK key bar glyph reads #F0F3F8) | `#2D2D2F` | L06 rail "…" dots |
| `glassSelected` | The cell of a glass capsule that is selected or switched on. | `#3A3D44` | D12 tab capsule, selected cell | `#D4D2D3` | L01 tab capsule, selected cell |
| `selected` | The pill behind the selected row. | `#1D1E23` | derived: the sidebar's pill step (#0B0C11 to #191A1F) on this surface | `#E5E5E5` | derived: halfway from glass to its selected cell |
| `divider` | A hairline between rows or groups. | `#2B2C30` | derived: the sidebar's | `#C7C7CA` | L06 layout thumbnail, the cell that is not selected |
| `accent` | "This one is active": a switched-on toggle, an armed key, the working mark, a link. | `#7FA6F5` | CM layout thumbnail, selected cell | `#336FB1` | L06 layout thumbnail, selected cell |
| `onAccent` | Text and glyphs on an [accent] fill. | `#0B0C11` | derived: the darkest surface | `#FFFFFF` | derived |
| `ok` | Connected, running, finished. | `#30D158` | derived: the iOS system green of the dark appearance | `#248A3D` | derived: the iOS system green of the light appearance, darker variant |
| `warning` | Connecting, starting, waiting for the user. | `#FF9F0A` | derived: the iOS system orange of the dark appearance | `#C93400` | derived: the iOS system orange, darker variant |
| `danger` | Failed or not connected; a destructive action, as a mark, an outline or a fill. | `#FF453A` | derived: the iOS system red of the dark appearance | `#D70015` | derived: the iOS system red, darker variant |
| `dangerText` | A destructive action and an error, as text. | `#FF6B61` | derived: the sheet's, on the same fill | `#D70015` | derived: as danger |
| `onDanger` | Text on a [danger] fill (a destructive confirm button). | `#0B0C11` | derived | `#FFFFFF` | derived |

**Overlay: menus, dialogs, popovers, the command palette** (`VeloTheme.colors.overlay`)

| token | for | Dark | source | Light | source |
|---|---|---|---|---|---|
| `surface` | The fill of the area itself. | `#2E323E` | AK sheet | `#ECEDF1` | MB1 sheet |
| `text` | Body text and the icons that go with it. | `#FCFEFF` | AK row text | `#18191E` | MB1 body text |
| `secondary` | Captions, section headers, placeholders and icons that are not the subject of their row. | `#C2C3C9` | derived: AK row chevron #A8AAB1, lightened to 4.5 over the lightest the material gets | `#555556` | derived: M03 link line #727271, darkened to 4.5 over the darkest the material gets |
| `disabled` | Text and icons of something that takes no tap. Not held to a contrast ratio. | `#8B8E98` | derived: halfway from the surface to secondary | `#9A9BA0` | derived: halfway from the surface to secondary |
| `glass` | The fill of a glass control (a disc, a capsule, a field) on this surface. | `#393D49` | AK field and grouped card | `#F2F1F6` | MB1 inner card |
| `glassEdge` | The light edge of a glass control: one line along its rim. | `#727588` | AK close disc top rim | `#AEAFB3` | MB1 back disc rim |
| `onGlass` | The glyph or label inside a glass control. | `#FCFEFF` | derived: text | `#191A1F` | MB1 back chevron |
| `glassSelected` | The cell of a glass capsule that is selected or switched on. | `#4A4D5C` | AK close disc, the lighter control on the sheet | `#C2C3C8` | derived: chrome's step from glass to its selected cell |
| `selected` | The pill behind the selected row. | `#4A4E59` | derived: lighter than the material over any backdrop | `#CACBCE` | derived: darker than the material over any backdrop |
| `divider` | A hairline between rows or groups. | `#444854` | AK hairline between rows | `#C6C6C8` | derived: chrome's divider on this surface |
| `accent` | "This one is active": a switched-on toggle, an armed key, the working mark, a link. | `#7FA6F5` | CM, as chrome | `#336FB1` | L06, as chrome |
| `onAccent` | Text and glyphs on an [accent] fill. | `#0B0C11` | derived | `#FFFFFF` | derived |
| `ok` | Connected, running, finished. | `#30D158` | derived: the iOS system green of the dark appearance | `#1E7B34` | derived: chrome's green darkened to 3 on a selected row |
| `warning` | Connecting, starting, waiting for the user. | `#FF9F0A` | derived: the iOS system orange of the dark appearance | `#C93400` | derived: chrome's |
| `danger` | Failed or not connected; a destructive action, as a mark, an outline or a fill. | `#FF9994` | derived: the dark red lightened to 4.5 as text over the lightest the material gets | `#B80012` | derived: chrome's red darkened to 4.5 as text over the darkest the material gets |
| `dangerText` | A destructive action and an error, as text. | `#FF9994` | Tokens board "dangerOnOverlay": the destructive menu item | `#B80012` | derived: as danger |
| `onDanger` | Text on a [danger] fill (a destructive confirm button). | `#0B0C11` | derived | `#FFFFFF` | derived |

**Sheet: Settings, as the redesign draws it. Dark only** (`VeloTheme.colors.sheet`; Light uses chrome's)

| token | Dark | source |
|---|---|---|
| `surface` | `#0F1015` | Tokens board "chrome": the settings' fill, as chrome's |
| `text`, `onGlass` | `#F3F4F9` | Tokens board "text", as the sidebar's |
| `secondary` | `#8C8F94` | Tokens board "secondary" |
| `disabled` | `#5F6064` | Tokens board "disabled" |
| `glass` | `#24262B` | Tokens board "glass": buttons, fields, the segmented track |
| `glassEdge` | `#383C42` | Tokens board "glassEdge" |
| `glassSelected` | `#3A3D44` | Tokens board "glassSelected": the selected cell, a switch that is off |
| `selected` | `#1D1E23` | Tokens board "selected": the selected row, a disabled button |
| `divider` | `#2B2C30` | Tokens board "divider": a group's border and its hairlines |
| `accent` | `#63A2F6` | Tokens board "accent": links, a switch that is on, the primary button |
| `onAccent` | `#0B0C11` | Tokens board "onAccent" |
| `ok`, `warning` | `#30D158`, `#FF9F0A` | Tokens board |
| `danger` | `#FF453A` | Tokens board: a field's error ring, the outlined destructive button's rim at 50 %, a destructive dialog's confirm |
| `dangerText` | `#FF6B61` | Controls board: the outlined destructive button and a field's error line |
| `onDanger` | `#0B0C11` | derived: the Overlays board writes white on the red, 3.4; dark text reaches 5.7 |

**Of the appearance** (`VeloTheme.colors`)

| token | for | Dark | source | Light | source |
|---|---|---|---|---|---|
| `overlayAnchor` | What is behind an overlay, as it looks under the scrim, where the overlay is exactly its [SurfaceColors.surface]: the ground the surface colour was measured over. | `#15161B` | AK the screen above the sheet | `#BDBEC2` | MB1 backdrop behind the sheet |
| `overlayLight` | How much of the lightness of what is behind an overlay shows in it: 0 none, 1 all of it. | 10 % | derived: no image shows an overlay over a bright picture; the most, with overlayTint, at which every text keeps 4.5 over any colour | 10 % | derived: Dark's; here too the most at which every text keeps 4.5 over any colour |
| `overlayTint` | How much of the colour of what is behind an overlay shows in it. | 45 % | derived: as overlayLight | 45 % | derived: as overlayLight |
| `scrim` | Laid over everything behind an open overlay. | `#000000` at 45 % | Tokens and Overlays boards (AK against PK read 40 %) | `#000000` at 23 % | M03 against M01: the pane behind the menu is dimmed by black at 23 % |
| `raised` | A card that stands out of the sheet or the start screen. | `#15171D` | Tokens board "raised", the one value the redesign adds | `#FFFFFF` | derived: a step lighter than chrome |
| `textOnRaised` | A paragraph in a card or a dialog. | `#C2C3C9` | Tokens board "textOnRaised", the overlay's secondary | `#3C3C40` | derived |
| `paneFocus` | The edge of the pane that takes the keys. | `#7FA6F5` at 70 % | derived: chrome's accent at 70 %, the strength the app uses today | `#63A2F6` at 70 % | derived: the backdrop's accent at 70 %, as Dark's strength; chrome's #336FB1 reads 2.46 on black |

The settings categories' icon tiles have a tint each (`CategoryTints`, Controls board):
appearance `#5B4FC4`, keyboard `#3E4A5E`, notifications `#9A3B62`, remotes `#2F6B57`, about `#2F5E9E`.

What the redesign's boards draw that is not a token, and why:

- The Overlays board writes the destructive confirm's label in white, 3.4 on `#FF453A`; the app
  keeps `onDanger`, 5.7. The board fills that button with the sheet's `danger`; the overlay's own
  `danger` stays `#FF9994`, since `#FF453A` is 2.98 as a mark over a bright terminal.
- A dialog's field rim is the overlay's `glassSelected`, `#4A4D5C`, as the board draws it; the
  overlay's `glassEdge` stays `#727588`, since `#4A4D5C` as the rim of glass reads 1.248 over a
  bright terminal, under the 1.25 the test holds.
- The state chip's green words, `#7EE29A`, belong to `StateChip`; other states lighten their colour as much.
- Tints made from a token with an alpha (the state chip at 12 %, the warning note at 10 %, the
  chosen key chip at 16 % with a rim at 55 %, the destructive rim at 50 %) are drawn by their component.
- Brand colours of the tab tiles are in `TileBrand` (Tile board).

### Corner radii (`VeloTheme.radii`), the same in both appearances

A value given at Medium is the redesign's; the Large value is it divided by 0.85.

| token | dp at Medium | for, and source |
|---|---|---|
| `pane` | 16 | A pane card: Tokens board "card", the Pane board's terminal pane. |
| `paneCompact` | 10.2 (12 at Large) | A pane card on the folded screen. derived: the app's compact pane style; the folded Duo shows one pane edge to edge. |
| `overlay` | 32 | A dialog, popover or the palette (Tokens board "dialog"). |
| `card` | 16 | A card: the raised card on the sheet, a glass card inside a dialog (Tokens board "card"). |
| `bar` | 28 | The key bar strip, a capsule 56 high (KeyBar board). |
| `tile` | 8 | A tab tile of 30 (Tokens board "tile": the tile's size times 0.27; `TabTile` computes it from its size). |
| `badge` | 11 | A small badge, such as a license's name (Tokens board "badge"). |
| `group` | 12 | A group of setting rows (Tokens board "group"). |
| `sheet` | 20 | The settings sheet on the unfolded screen (Tokens board "sheet"). |
| `pill` | 24 | A button, a field, a segmented track, a menu (Tokens board "pill"). |
| `nav` | 30 | The category list beside the details on the unfolded screen (Tokens board "nav"). |

### Spacing steps (`VeloTheme.space`)

The Tokens board uses the steps 4, 8, 12, 16, 20 and 24 at Medium: `space.s4` to `space.s24`,
scaled with the Size setting. A number a board draws that no token holds is taken at Medium
with `VeloTheme.medium(14.dp)` (`mediumAt(size, value)`), so that it scales as the tokens do.

### Sizes (`VeloTheme.sizes`), the same in both appearances

A plain number is the Large size read from the images; "at Medium" is the redesign's.

| token | dp | for, and source |
|---|---|---|
| `control` | 48 | A round glass control and the least touch target (D12 rail disc 47.3, L06 49.6). |
| `capsule` | 52 at Medium | The thickness of a capsule that holds several icons: its round controls (`control`) and 2 at Medium around them (Sidebar board, sidebar-actions). |
| `capsuleCell` | 56 | One icon's share of a vertical capsule's length (L06 55.5). A `CapsuleIcon` is a round control (`control`). |
| `cellInset` | 3 at Medium | How far a selected cell stays inside its capsule (Controls board: the segmented track's 3). |
| `icon` | 28 | An icon in a control (D12 ink 22.0 to 27.8; a Material icon inks five sixths of its box). |
| `iconSmall` | 20 | An icon beside a header or inside a row (L06 "+" ink 17.3). |
| `row` | 60 at Medium | A row of settings and a list row (Tokens board: "설정 행은 최소 60 높이"). |
| `tabRow` | 56 at Medium | A tab's row in the sidebar (Controls board, sample-tab-row). |
| `sectionHeader` | 44 at Medium, kept at 48 | A section header of the list (Sidebar board, session header). |
| `rowInset` | 18 | The selected pill's distance from the list's edge (D12 18.1, L06 17.9). |
| `rowPadding` | 20 | From the pill's edge to the row's tile (L06 19.7). |
| `rowGap` | 14 | From the tile to the title (D12 15.5; L06 puts the title 69.3 from the pill's edge, which is padding, tile and this). |
| `mark` | 8 at Medium | A state dot (Controls board, sample-status-dot). |
| `dot` | 9 at Medium | The update dot and a tab tile's state dot, each in a ring of the surface (Controls board). |
| `tabTile` | 30 at Medium | A tab tile's face in a sidebar row (Controls board, sample-tab-row). |
| `field` | 48 | The search field and a field in a dialog (D12 45.3, L06 50.2). |
| `sidebar` | 320 at Medium | The sidebar beside the panes (WsOpenDocked board; D12 365.9). |
| `rail` | 60 at Medium | The rail: a control and 6 on each side (Rail board); never narrower than that at any size. |
| `keyBar` | 56 at Medium | The key bar strip (KeyBar board). |
| `keyBarMax` | 560 at Medium | The widest the key bar grows, in the middle of a wide pane column (WsOpenPanes board). |
| `key` | 32 at Medium | The least width of a key's cap (KeyBar board). A key takes the taps in its share of the bar, up to halfway to the next key. |
| `keyHeight` | 48 at Medium | A key's height in the strip (KeyBar board); a modifier's cap is a pill 40 high inside it. |
| `gap` | 8 at Medium | Between panes, and between panes and the screen's edge (Tokens board). |
| `gapCompact` | 4 | Between pane cards and adjacent chrome in Compact style. |
| `overlayPadding` | 16 | Inside an overlay, from its edge to its content (AK 15.4, MB1 17.1). |
| `menuItem` | 48 | A menu item. derived: M03 45.7 and AK 44.8, raised to Android's least touch target. |
| `menuWidth` | 285 | The widest a menu grows (M03 284.8). |
| `menuPadding` | 8 | Above the first and under the last item of a menu, and around its divider. derived: half the overlay padding. |
| `menuMinWidth` | 200 | The narrowest a menu is. derived: wide enough for an icon and two short words at the body size. |
| `dialogWidth` | 386 at Medium | The widest a dialog grows (Overlays board, DlgAddRemote). |
| `paletteWidth` | 420 | The command palette. derived: the app's width today; no image shows the palette. |
| `paletteHeight` | 520 | The tallest the command palette grows. derived: the app's height today. |
| `overlayMargin` | 8 | An overlay's distance from the window's edge and from its anchor (MB1 sheet inset 8.1). |
| `blur` | 24 | How far the picture behind an overlay is blurred. derived: far enough that no letter of a terminal can be read through it. |
| `overlayShadow` | 12 | The shadow under an overlay, as an elevation. derived: M03 shows a soft shadow of about 6 px around the menu. |
| `edge` | 1 | The glass edge, a group's border and a divider (Controls board). Not scaled. |
| `paneFocus` | 2 | The edge of the pane that takes the keys. derived: the app's width today. |
| `switchWidth`, `switchHeight` | 51, 31 at Medium | A switch's track (Controls board). |
| `switchInset` | 2 at Medium | From a switch's track to its knob (Controls board). |
| `sliderTrack` | 4 at Medium | A slider's track (Controls board). |
| `sliderKnob` | 28 at Medium | A slider's knob (Controls board). |
| `sheetWidth` | 820 at Medium | Maximum width of the settings sheet on wide windows. Derived from its proportion within a 1088dp reference window; the canvas fills the unfolded Fold8 (805). |
| `sheetList` | 236 at Medium | The list of categories beside the details in the settings sheet (the redesign's settings boards). |
| `statusIcon` | 16 | A tab's state icon, and the height of its working dots (1.5 times as wide). derived: the app's size today. |
| `gauge`, `gaugeStroke` | 46 at Medium, 4.3 | The rail's battery gauge (Rail board) and ring stroke; the stroke is based on the proportions of M01's status ring. |

### Text styles (`VeloTheme.type`), the same in both appearances

The family is the device's own (Roboto on Android; the boards set Roboto). A size "at Medium" is
the redesign's type scale (Tokens board); a plain one is the Large size of a style the boards do
not name. A line of interface text is as tall as the browser sets a line of Roboto: its ascent
(1900 of 2048 units) and its descent (500), each rounded to a whole dp, 19 at 16 and 15 at 13
(`robotoLine`). The boards set no line height on a label, so the browser gives it that one, and
the app's labels then line up with the boards'. A paragraph sets its own (1.4, 1.45 or 1.5 em, as
its board does). The line heights of `key`, `clock`, `wordmark` and the glyph styles are derived.

| token | size / line, weight | for, and source |
|---|---|---|
| `screenTitle` | 26 / 30 sp at Medium, SemiBold | The title of a full screen: Settings (Tokens board "screen-title"). |
| `pageTitle` | 22 / 25 sp at Medium, SemiBold | A page's title beside the categories on the unfolded screen (Tokens board "page-title"). |
| `overlayTitle` | 20 / 24 sp at Medium, SemiBold | The title of a dialog or a card (Tokens board "dialog-title"). |
| `title` | 18 / 21 sp at Medium, SemiBold | The title of a header: a settings page's, a pane's (Tokens board "header-title"). |
| `body` | 16 / 19 sp at Medium, Normal | Row titles, buttons, menu items, a field's text (Tokens board "body"). |
| `bodyStrong` | 16 / 19 sp at Medium, Medium | The same, for a label that stands out. derived: the boards' button weight. |
| `value`, `control` | 15 / 18 sp at Medium, Normal | A row's value, a menu button, a segmented cell (Medium when chosen) (Tokens board "value"). |
| `header` | 15 / 18 sp at Medium, Medium | Section headers of the sidebar's list (Sidebar board, session header). |
| `caption` | 13 / 15 sp at Medium, Normal | Second lines, notes; at Medium weight, a settings group's heading (Tokens board "caption"). |
| `eyebrow` | 12 / 14 sp at Medium, Bold, tracking 0.06 em | A small heading in capitals: NEW VERSION (Tokens board "eyebrow"). |
| `key` | 13 / 18 sp at Medium, Medium, monospace | Key labels (Controls and KeyBar boards). |
| `clock` | 17 / 22 sp at Medium, SemiBold | The rail's clock (Rail board). |
| `wordmark` | 38 / 44 sp at Medium, SemiBold, tracking -0.01 em | The wordmark of the start screen (StartKit board; 44 on the unfolded screen). |

The Tokens board's terminal style, JetBrains Mono at 12.5, is not a text style here: the
terminal keeps its own Font and Font size settings (default 13).

### Measures on whole pixels

A Medium phone has 2.625 px a dp, so many of the boards' measures fall between pixels, where the
browser keeps fractions and Android rounds. These helpers in `ui/theme` keep the app's measures
as near the boards' as whole pixels allow.

| helper | what it does |
|---|---|
| `TextStyle.onPixels`, `VeloType.onPixels` | Puts every style's line on a whole pixel; the theme applies it to the type scale. |
| `TextStyle.paragraph(height)` | A paragraph's line on a whole pixel, centred in it without Compose's trim, so that its leading splits as CSS splits it. |
| `textAt(size, weight)` | A style of a size the scale does not name (14, 12.5), scaled with the Size setting, on the browser's line of Roboto. |
| `monoAt(size, weight)` | The same in JetBrains Mono (`rememberMono`), on the browser's line of that face (ascent 1020, descent 300 of 1000 units): 17 at 13, 20 at 15. Keys, addresses, fingerprints and license texts use it. |
| `Modifier.lineBox(style)` | Holds a title or a heading to its style's line: Android never makes a line shorter than the font's own (25.8 at 22 sp, where the board's is 25). |
| `hairline()` | A group's border and dividers: `edge` on whole pixels, rounded down, as the browser paints a 1 px border (2 px at 420 dpi). |

### The Size setting (`UiSize`)

The tokens are kept at the **Large** size, the size read from the Duo; a value the redesign
gives at Medium is kept as the Large value that Medium scales to it. Settings > Appearance >
Size also offers **Medium** (the default) and **Small**, which scale them by a factor
(`veloSizes`, `veloType`, `veloRadii`, `veloSpace` in `ui/theme/Metrics.kt`). The terminal's text keeps its own
Font size setting and is not scaled.

| size | factor | body text | settings row |
|---|---|---|---|
| Large | 1.00 | 18.8 sp | 70.6 dp |
| Medium | 0.85 | 16 sp | 60 dp |
| Small | 0.72 | 13.6 sp | 50.8 dp |

Medium and Small scale the Large measurements. The following values do not scale directly:

- What takes a tap stays at the least touch target, 48 dp (`MinTouch`): `control`, `capsule`,
  `capsuleCell`, `row`, `sectionHeader`, `field`, `menuItem`. A key of the key bar stays at
  44 dp (`MinKey`), the iOS least touch target, so that a row of keys still fits the bar.
- The rail stays a control and a gap on each side; the key bar stays as much taller than its
  keys as it is at Large.
- `edge` (and a field's ring, twice it), `paneFocus`, `blur` and `overlayShadow` do not scale, nor do the physical pixels of
  the Compact pane style.
- No text is smaller than 11 sp (`MinTextSp`); a style keeps its line height in proportion.
- The breakpoint between the folded and the unfolded layout is the window's width in dp, so a
  size never moves it.

## Read from the images

Coordinates are image pixels, inclusive: a
pixel `(x, y)`, a rectangle `(left,top)-(right,bottom)`, or runs of one colour along a row or
a column. "shape at (x,y)" is the filled shape around that pixel: every pixel within the
tolerance of its colour that is connected to it; its radius is the circle that fits its
top-left corner best, and "capsule" means the radius is half the height. "lightest fiftieth"
is the median of the lightest 2 % of a rectangle: the colour of text strokes on a dark ground.
dp follow the mappings above.

### Bounds

| image | what | read | value |
|---|---|---|---|
| D12 | canvas, bezel, screen along a row | row y=1000, x 0..2901, runs of 20 px or more | x 0..551 #1F1E21, x 559..586 #000000, x 587..1207 #0B0C11, …, x 1704..2266 #0F1015, x 2267..2294 #000000, x 2302..2901 #1F1E21 |
| D12 | screen along a column | column x=1180, y 0..2065, runs of 20 px or more | y 0..377 #1F1E21, y 385..413 #000000, y 414..1049 #0F1015, …, y 1312..1591 #0F1015, y 1592..1620 #000000, y 1627..2065 #1F1E21 |
| D12 | sidebar meets pane | row y=1300, x 1100..1300, runs of 40 px or more | x 1100..1152 #0B0C11, x 1153..1207 #0F1015, x 1232..1300 #0F1015 |
| D01 | screen along a row | row y=1000, x 0..2901, runs of 14 px or more | x 0..976 #1F1E21, x 977..1008 #3B3B3B, x 1009..1023 #000000, x 1024..1850 #0D1017, x 1851..1865 #000000, x 1879..2901 #1F1E21 |
| D01 | screen along a column | column x=1300, y 0..2065, runs of 14 px or more | y 0..375 #1F1E21, y 389..403 #000000, y 404..581 #0D1017, …, y 1588..1601 #0D1017, y 1602..1616 #000000, y 1630..2065 #1F1E21 |
| L06 | canvas, bezel, sidebar, pane along a row | row y=1000, x 0..2275, runs of 20 px or more | x 0..164 #FFFFFF, x 172..262 #000000, x 328..347 #000000, …, x 1725..2024 #F9F9F9, x 2025..2055 #000000, x 2063..2275 #FFFFFF |
| L06 | screen along a column | column x=1700, y 0..1713, runs of 20 px or more | y 0..171 #FFFFFF, y 184..214 #000000, y 215..251 #F9F9F9, …, y 1344..1492 #F9F9F9, y 1493..1523 #000000, y 1532..1713 #FFFFFF |
| L20 | screen along a row | row y=700, x 0..2275, runs of 20 px or more | x 0..164 #FFFFFF, x 172..201 #000000, x 203..1294 #F9F9F9, x 1311..2024 #F9F9F9, x 2025..2055 #000000, x 2069..2275 #FFFFFF |
| L01 | screen along a column | column x=1100, y 0..1713, runs of 14 px or more | y 0..166 #FFFFFF, y 187..202 #000000, y 203..270 #F9F9F9, …, y 1354..1503 #F9F9F9, y 1505..1520 #000000, y 1535..1713 #FFFFFF |

### Surfaces and fills

| image | what | read | value |
|---|---|---|---|
| D12 | sidebar surface | median of (600,600)-(1100,690) | #0B0C11 |
| D12 | pane surface | median of (1300,1250)-(2200,1350) | #0F1015 |
| D12 | key bar surface | column x=1560, y 1438..1487, runs of 20 px or more | y 1438..1483 #0F1015 |
| D12 | rail surface | row y=455, x 2050..2249, runs of 50 px or more | x 2050..2247 #0F1015 |
| D12 | selected row pill | shape at (1100,755), tolerance 5 | #191A1F; box (615,738)-(1123,829) = 509x92 px = 329.6x59.6 dp; radius capsule (46.5 px) |
| D12 | "Search" capsule | shape at (900,1510), tolerance 6 | #24262B; box (626,1473)-(1133,1542) = 508x70 px = 329.0x45.3 dp; radius capsule (33.9 px) |
| D12 | eye and gear capsule | shape at (915,497), tolerance 6 | #24262B; box (808,459)-(1009,537) = 202x79 px = 130.8x51.2 dp; radius capsule (40.0 px) |
| D12 | sidebar toggle disc | shape at (1050,470), tolerance 6 | #24252A; box (1033,459)-(1115,537) = 83x79 px = 53.8x51.2 dp; radius capsule (38.0 px) |
| D12 | command disc | shape at (1208,1464), tolerance 6 | #25282F; box (1195,1458)-(1291,1529) = 97x72 px = 62.8x46.6 dp; radius capsule (34.5 px) |
| D12 | keyboard capsule | shape at (1995,1464), tolerance 6 | #25282F; box (1978,1457)-(2074,1529) = 97x73 px = 62.8x47.3 dp; radius capsule (37.0 px) |
| D12 | "+" pill | shape at (1712,1402), tolerance 6 | #272A2F; box (1700,1397)-(1754,1434) = 55x38 px = 35.6x24.6 dp; radius capsule (19.5 px) |
| D12 | tab capsule and its selected cell along a row | row y=1416, x 1500..1700, runs of 8 px or more | x 1500..1515 #0F1015, x 1520..1530 #3A3D44, x 1559..1567 #3A3D44, x 1569..1589 #25282F, x 1611..1639 #25282F, x 1667..1684 #24282E, x 1685..1697 #0F1015 |
| D12 | tab capsule down a column: rim, fill, glyph, fill, rim | column x=1600, y 1390..1440 | y 1390..1392 #0F1015, y 1393 #090C13, y 1394 #13161D, y 1395 #4C4F56, y 1396 #3D4047, y 1397..1398 #2F3239, y 1399..1401 #2B2E34, y 1402..1403 #292D31, y 1404..1405 #262A2E, y 1406 #84888C, y 1407..1409 #F8FAFD, y 1410 #F6F7FA, y 1411 #BFC0C3, y 1412 #959699, y 1413 #9A9B9E, y 1414..1416 #919295, y 1417 #7F8083, y 1418 #7A7B80, y 1419 #7E7F84, y 1420 #6C6D72, y 1421 #2E2F34, y 1422 #232429, y 1423..1424 #282B30, y 1425..1428 #24272E, y 1429..1432 #282C33, y 1433..1434 #2A3036, y 1435 #32373D, y 1436 #4A4F55, y 1437 #23282E, y 1438..1440 #0D1115 |
| D12 | rail "…" disc down a column | column x=2180, y 620..720, runs of 3 px or more | y 620..632 #0F1015, y 636..668 #25282F, y 670..675 #E1E6EC, y 678..706 #24292F, y 712..720 #0F1015 |
| D12 | command disc top rim | column x=1243, y 1450..1466 | y 1450..1453 #0F1015, y 1454 #16171C, y 1455 #424348, y 1456 #3D4047, y 1457 #2F3239, y 1458..1461 #282C32, y 1462..1466 #25282F |
| D12 | eye and gear capsule top rim | column x=960, y 450..470 | y 450..454 #0B0C11, y 455 #0F1015, y 456..457 #383C42, y 458..459 #2B2E35, y 460..464 #24272E, y 465..467 #23272B, y 468..470 #212529 |
| D12 | home indicator along a row | row y=1574, x 1150..1700, runs of 5 px or more | x 1150..1210 #0F1015, x 1211..1222 #1F2025, x 1223..1642 #2A2B2E, x 1643..1700 #0F1015 |
| D12 | home indicator down a column | column x=1500, y 1560..1585 | y 1560..1568 #0F1015, y 1569 #1D1E21, y 1570..1573 #2D2E31, y 1574..1577 #2A2A2E, y 1578 #14151A, y 1579..1585 #0F1015 |
| D12 | row app tile | shape at (650,790), tolerance 40 | #D1523A; box (644,774)-(700,815) = 57x42 px = 36.9x27.2 dp; radius 12.5 px = 8.1 dp |
| L06 | sidebar surface | median of (330,560)-(900,590) | #000000 |
| L06 | selected row pill | shape at (700,1170), tolerance 4 | #131313; box (233,1151)-(914,1249) = 682x99 px = 407.3x59.1 dp; radius capsule (50.0 px) |
| L06 | "Search" capsule down a column: rim, fill, rim | column x=700, y 1350..1450 | y 1350..1358 #000000, y 1359 #1B1B1B, y 1360 #434343, y 1361 #2D2D2D, y 1362..1367 #202020, y 1368..1439 #1D1D1D, y 1440..1441 #262626, y 1442 #373737, y 1443 #3B3B3B, y 1444..1450 #000000 |
| L06 | "Search" capsule along a row (the list scrolls under its left part) | row y=1400, x 230..980, runs of 12 px or more | x 230..262 #000000, x 265..282 #303131, x 301..318 #333534, x 328..339 #252525, x 467..479 #262528, x 499..518 #222222, x 523..630 #212121, x 631..944 #1D1D1D, x 946..980 #000000 |
| L06 | eye and gear capsule (vertical) | shape at (1000,340), tolerance 5 | #1D1D1D; box (969,263)-(1059,448) = 91x186 px = 54.3x111.1 dp; radius 44.9 px = 26.8 dp |
| L06 | pane card | shape at (1300,900), tolerance 4 | #F9F9F9; box (1123,215)-(2024,1492) = 902x1278 px = 538.6x763.2 dp; radius 68.6 px = 41.0 dp |
| L06 | rail "…" disc along a row: rim, fill, dots | row y=496, x 1880..1985 | x 1880..1888 #F9F9F9, x 1889 #F5F5F5, x 1890 #C1C1C1, x 1891 #D7D7D7, x 1892..1905 #F5F5F5, x 1906..1907 #F8F8F8, x 1908 #F3F3F3, x 1909 #F9F9F9, x 1910 #EDECEF, x 1911 #5D5C5F, x 1912..1913 #313035, x 1914 #2B2931, x 1915 #302E36, x 1916..1918 #333139, x 1919 #353439, x 1920 #A3A2A5, x 1921..1924 #FAFAFA, x 1925..1927 #FBFBFB, x 1928 #CFCED1, x 1929 #3B3A3D, x 1930 #2F2E31, x 1931..1933 #323134, x 1934 #2F2E31, x 1935 #323134, x 1936 #343434, x 1937 #414141, x 1938 #DCDCDC, x 1939..1943 #F9F9F9, x 1944..1945 #FAFAFA, x 1946 #939393, x 1947 #2E2E2E, x 1948 #323232, x 1949..1952 #2F2F2F, x 1953..1954 #313033, x 1955 #626164, x 1956 #ECEBEE, x 1957..1962 #F6F5F8, x 1963..1967 #F5F4F9, x 1968..1973 #F5F5F2, x 1974 #F2F2EF, x 1975 #D7D7D4, x 1976 #C1C3C0, x 1977 #F2F4F1, x 1978..1980 #F7F9F9, x 1981..1982 #F7F7F7, x 1983..1985 #F9F9F9 |
| L06 | rail "…" disc down a column: rim, fill | column x=1933, y 444..490 | y 444..451 #F9F9F9, y 452 #E8E8E8, y 453 #F1F1F1, y 454..457 #FCFCFC, y 458..461 #F9F9F9, y 462..486 #F5F5F5, y 487 #F6F6F3, y 488..489 #F8F8F8, y 490 #F1F1F1 |
| L06 | rail keyboard disc | shape at (1905,604), tolerance 3 | #F6F5F6; box (1892,586)-(1974,657) = 83x72 px = 49.6x43.0 dp; radius capsule (36.5 px) |
| L06 | layout thumbnail, selected cell | median of (1645,276)-(1715,373) | #336FB1 |
| L06 | layout thumbnail, other cell | median of (1736,276)-(1804,373) | #C7C7CA |
| L06 | home indicator over the sidebar and over the pane | row y=1473, x 860..1370, runs of 20 px or more | x 886..918 #222222, x 919..1128 #282828, x 1141..1343 #595959, x 1349..1370 #F9F9F9 |
| L09 | overview backdrop | median of (300,300)-(500,500) | #000000 |
| L09 | overview backdrop, second place | median of (1000,1300)-(1400,1400) | #000000 |
| L09 | rail discs in the overview, down a column | column x=1933, y 440..680, runs of 4 px or more | y 440..452 #000000, y 456..461 #7A7A7A, y 462..490 #777777, y 495..499 #303030, y 502..505 #7B7B7B, y 506..534 #7A7A7A, y 538..577 #000000, y 583..603 #797979, y 610..616 #787980, y 617..621 #757679, y 640..660 #7A7A7A, y 664..680 #000000 |
| L09 | glyph of a rail disc in the overview | darkest fiftieth of (1900,470)-(1966,520) | #2E2E2E |
| L20 | key bar strip | shape at (1000,1010), tolerance 4 | #F9F9F9; box (219,953)-(2008,1057) = 1790x105 px = 1068.9x62.7 dp; radius 44.9 px = 26.8 dp |
| L20 | command capsule inside the strip | pixel (300,990) | #F1F1F1 |
| L20 | gutter between two panes | row y=600, x 1280..1330 | x 1280..1292 #F9F9F9, x 1293 #5F5F5F, x 1294..1309 #000000, x 1310 #747474, x 1311..1330 #F9F9F9 |
| L20 | gutter between a pane and the key bar | column x=700, y 925..965 | y 925..935 #F9F9F9, y 936 #F1F1F1, y 937 #2B2B2B, y 938..951 #000000, y 952 #797979, y 953..965 #F9F9F9 |
| L20 | pane card | shape at (700,700), tolerance 4 | #F9F9F9; box (203,546)-(1293,935) = 1091x390 px = 651.5x232.9 dp; radius 67.5 px = 40.3 dp |
| L20 | system keyboard: tray, key (not tokens) | column x=1100, y 1060..1150, runs of 8 px or more | y 1066..1092 #B9BABF, y 1098..1147 #F3F4F9 |
| L01 | tab capsule fill | pixel (1102,1317) | #F5F5F5 |
| L01 | tab capsule down a column: rim, highlight, fill | column x=1102, y 1300..1360 | y 1300..1304 #F9F9F9, y 1305 #F3F3F3, y 1306 #E8E8E8, y 1307 #FFFFFF, y 1308..1311 #FAFAFA, y 1312..1316 #F8F8F8, y 1317..1335 #F5F5F5, y 1336..1349 #F9F9F9, y 1350..1352 #FBFBFB, y 1353 #E4E4E4, y 1354 #F1F1F1, y 1355..1360 #F5F5F5 |
| L01 | tab capsule, selected cell | shape at (1039,1336), tolerance 4 | #D4D2D3; box (1029,1312)-(1081,1345) = 53x34 px = 31.6x20.3 dp; radius capsule (17.5 px) |
| L01 | "+" pill | shape at (1177,1317), tolerance 3 | #F5F5F5; box (1165,1310)-(1224,1349) = 60x40 px = 35.8x23.9 dp; radius capsule (18.2 px) |
| L01 | "…" disc | pixel (1464,1422) | #FAFAFA |
| M01 | pane | median of (815,1427)-(1223,1488) | #FFFFFF |
| M01 | back disc along a row: rim, fill, chevron, fill, rim | row y=142, x 60..225 | x 60..68 #FFFFFF, x 69 #D9D9D9, x 70 #A7A7A7, x 71 #F3F3F3, x 72..119 #F9F9F9, x 120 #FFFFFF, x 121 #F9F9F9, x 122 #E1E1E1, x 123 #5E5E5E, x 124 #161616, x 125 #272727, x 126..127 #212121, x 128..129 #202020, x 130 #1A1A1A, x 131 #252525, x 132 #4A4A4A, x 133 #D3D3D3, x 134..137 #FFFFFF, x 138..213 #FBFBFB, x 214 #F1F1F1, x 215 #A7A7A7, x 216 #D0D0D0, x 217 #FCFCFC, x 218..225 #FFFFFF |
| M01 | "+" disc | shape at (672,1869), tolerance 4 | #FAFAFA; box (656,1833)-(750,1901) = 95x69 px = 36.2x26.3 dp; radius capsule (35.0 px) |
| M03 | context menu | shape at (1141,611), tolerance 8 | #E6E6E6; box (575,254)-(1321,920) = 747x667 px = 284.8x254.3 dp; radius 97.7 px = 37.2 dp |
| M03 | context menu, four more places | pixel (1223,366) | #E1E1E1 |
| M03 | context menu | pixel (1141,896) | #E9E4E6 |
| M03 | context menu | pixel (652,896) | #E2E0E1 |
| M03 | context menu | pixel (1263,713) | #E6E6E6 |
| M03 | the pane behind the menu (M01 reads #FFFFFF there) | median of (815,1427)-(1223,1488) | #C4C4C4 |
| M03 | menu edge along a row: shadow, rim, fill | row y=611, x 550..600 | x 550..565 #BBBBBB, x 566..571 #B6B6B6, x 572 #969696, x 573 #6B6B6B, x 574 #A8A8A8, x 575 #E3E3E3, x 576..599 #E0E0E0, x 600 #E3E3E3 |
| PK | terminal | median of (100,700)-(800,1000) | #0E1116 |
| PK | back disc | shape at (59,165), tolerance 8 | #2D3238; box (38,150)-(139,242) = 102x93 px = 43.5x39.7 dp; radius capsule (46.2 px) |
| PK | "…" disc | shape at (852,236), tolerance 8 | #2C3137; box (802,148)-(903,245) = 102x98 px = 43.5x41.8 dp; radius capsule (49.5 px) |
| PK | "+" pill | shape at (451,1170), tolerance 8 | #2C3137; box (434,1145)-(507,1193) = 74x49 px = 31.6x20.9 dp; radius capsule (25.0 px) |
| PK | command capsule | shape at (67,1246), tolerance 8 | #2C3137; box (38,1230)-(167,1324) = 130x95 px = 55.5x40.5 dp; radius capsule (47.0 px) |
| PK | keyboard capsule | shape at (793,1246), tolerance 8 | #2C3137; box (774,1230)-(903,1324) = 130x95 px = 55.5x40.5 dp; radius capsule (47.4 px) |
| AK | sheet | median of (63,520)-(631,544) | #2E323E |
| AK | sheet corner | shape at (300,560), tolerance 5 | #2E323E; box (8,472)-(933,699) = 926x228 px = 395.2x97.3 dp; radius 90.8 px = 38.7 dp |
| AK | field in the sheet | shape at (473,741), tolerance 6 | #393D49; box (44,661)-(897,757) = 854x97 px = 364.4x41.4 dp; radius capsule (49.0 px) |
| AK | grouped card | shape at (473,883), tolerance 5 | #393D49; box (44,867)-(897,1202) = 854x336 px = 364.4x143.4 dp; radius 60.5 px = 25.8 dp |
| AK | close disc | shape at (820,536), tolerance 8 | #4A4D5C; box (755,524)-(887,647) = 133x124 px = 56.8x52.9 dp; radius capsule (61.0 px) |
| AK | close disc top rim | column x=821, y 515..535 | y 515 #2F333F, y 516 #2A2D3C, y 517 #373A4B, y 518..519 #727588, y 520 #5B5E71, y 521..523 #525568, y 524..526 #4F5261, y 527..533 #4B4E5D, y 534..535 #494C5B |
| PK | back disc top rim | column x=88, y 140..160 | y 140..143 #0E1116, y 144 #080D13, y 145 #4F545A, y 146 #61666C, y 147 #44494F, y 148 #383D43, y 149 #353A40, y 150..155 #30363C, y 156..160 #2D3238 |
| PK | back disc side rim | row y=196, x 28..50 | x 28..35 #0E1116, x 36 #04070C, x 37 #0F1217, x 38 #2C2F36, x 39 #2F3239, x 40..50 #2C3137 |
| AK | hairline between two rows of the card | column x=470, y 975..995 | y 975..983 #393D49, y 984 #404450, y 985 #444854, y 986 #3F434F, y 987 #383C48, y 988..989 #3A3E4A, y 990..995 #393D49 |
| AK | the screen above the sheet | pixel (470,300) | #15161B |
| AK | header "…" disc under the sheet's dimming (PK: #2C3137) | pixel (870,236) | #1D2027 |
| AK | header title under the sheet's dimming (PK: #F5F8FE) | lightest fiftieth of (262,160)-(400,198) | #868687 |
| AK | keyboard return key (the system's) | median of (725,1798)-(788,1814) | #3478F3 |
| MB1 | sheet | shape at (473,1104), tolerance 5 | #ECEDF1; box (19,981)-(922,2028) = 904x1048 px = 385.8x447.2 dp; radius 89.3 px = 38.1 dp |
| MB1 | inner card | shape at (473,1262), tolerance 3 | #F2F1F6; box (59,1169)-(882,1992) = 824x824 px = 351.6x351.6 dp; radius 74.1 px = 31.6 dp |
| MB1 | back disc | shape at (86,1068), tolerance 4 | #F1F2F6; box (55,1022)-(152,1109) = 98x88 px = 41.8x37.6 dp; radius capsule (44.5 px) |
| MB1 | back disc along a row: rim, fill, chevron, fill, rim | row y=1068, x 45..165 | x 45..52 #ECEDF1, x 53 #D2D3D7, x 54 #AEAFB3, x 55..71 #EFF0F4, x 72..79 #F0F2F6, x 80..87 #F2F3F8, x 88 #F7F8FD, x 89 #D6D7DC, x 90 #4A4B50, x 91 #14151A, x 92 #292A2F, x 93 #24252A, x 94 #1D1E23, x 95 #202126, x 96 #17181D, x 97 #494A4F, x 98 #C8C9CE, x 99..100 #F6F7FC, x 101 #FDFEFF, x 102 #F8F9FE, x 103 #EAEBF0, x 104..127 #F3F4F8, x 128..152 #EFF0F4, x 153 #DCDDE1, x 154 #A8A9AD, x 155 #E6E7EB, x 156..157 #EAEBEF, x 158..165 #ECEDF1 |
| MB1 | backdrop behind the sheet | pixel (473,473) | #BDBEC2 |
| MB2 | list background | pixel (473,157) | #0A0D12 |
| MB2 | group | pixel (47,883) | #201F24 |
| MB2 | card | shape at (473,883), tolerance 4 | #2B2C30; box (52,750)-(889,992) = 838x243 px = 357.6x103.7 dp; radius 45.7 px = 19.5 dp |
| MB2 | floating capsule (gear, eye) | shape at (197,1901), tolerance 8 | #2C2F36; box (66,1874)-(342,1978) = 277x105 px = 118.2x44.8 dp; radius capsule (53.0 px) |
| MB2 | search disc | shape at (781,1893), tolerance 8 | #2B2E35; box (764,1874)-(875,1979) = 112x106 px = 47.8x45.2 dp; radius capsule (52.7 px) |
| CM | layout thumbnail, selected cell (dark) | median of (520,120)-(558,142) | #7FA6F5 |

### Text and glyph colours

| image | what | read | value |
|---|---|---|---|
| D12 | host label | lightest pixel of (700,585)-(1000,635) | #ECEDF2 |
| D12 | row title | lightest pixel of (725,765)-(1050,812) | #E8ECF0 |
| D12 | group header | lightest pixel of (640,676)-(900,715) | #7C7D82 |
| D12 | "Search" placeholder | lightest pixel of (700,1485)-(800,1532) | #62626A |
| D12 | magnifier | lightest fiftieth of (630,1480)-(690,1535) | #787A7F |
| D12 | key labels | lightest pixel of (1320,1470)-(1620,1520) | #E7ECF2 |
| D12 | status clock | lightest fiftieth of (2130,460)-(2240,500) | #FDFEFF |
| L06 | host label | lightest pixel of (345,285)-(700,335) | #D5DDE4 |
| L06 | row title | lightest pixel of (345,485)-(880,530) | #D5DDE4 |
| L06 | group header | lightest pixel of (262,385)-(670,430) | #808088 |
| L06 | "+" beside a group header | lightest fiftieth of (790,387)-(832,426) | #7A7A80 |
| L06 | "Search" placeholder | lightest pixel of (350,1380)-(470,1425) | #6D6C6F |
| L06 | magnifier | lightest fiftieth of (285,1375)-(335,1430) | #777B82 |
| L06 | eye glyph | lightest fiftieth of (985,286)-(1040,325) | #D0D8DD |
| L06 | dot on the eye | pixel (1045,289) | #72ACFA |
| L06 | status clock | darkest fiftieth of (1886,273)-(1980,309) | #000000 |
| L06 | rail "…" dots | darkest fiftieth of (1910,484)-(1956,507) | #2D2D2F |
| L06 | rail keyboard glyph | darkest fiftieth of (1905,605)-(1961,641) | #2B2D2F |
| L06 | terminal text (the server's theme, not a token) | darkest fiftieth of (1183,553)-(1788,585) | #323234 |
| L20 | key bar glyphs | darkest fiftieth of (640,975)-(1570,1040) | #303033 |
| L01 | pane header title | darkest pixel of (780,255)-(1340,300) | #1E1B1F |
| L01 | pane header subtitle | darkest pixel of (780,300)-(1060,335) | #626164 |
| L09 | status clock in the overview | lightest pixel of (1886,273)-(1980,309) | #FFFFFF |
| M01 | pane header title | darkest fiftieth of (436,98)-(1215,139) | #151616 |
| M01 | pane header subtitle | darkest fiftieth of (436,147)-(624,183) | #7D7D7D |
| M03 | menu item text | darkest fiftieth of (762,571)-(979,620) | #060405 |
| M03 | menu item icon | darkest fiftieth of (665,571)-(718,620) | #030303 |
| M03 | the link line at the top of the menu | darkest fiftieth of (656,306)-(1223,347) | #727271 |
| PK | pane header title | lightest fiftieth of (260,160)-(420,200) | #F5F8FE |
| PK | pane header subtitle | lightest fiftieth of (260,205)-(400,235) | #85888D |
| PK | key bar glyph | lightest fiftieth of (210,1240)-(320,1310) | #F0F3F8 |
| AK | sheet title | lightest fiftieth of (55,560)-(378,615) | #FEFEFB |
| AK | row text | lightest fiftieth of (181,907)-(284,955) | #FCFEFF |
| AK | row chevron | lightest fiftieth of (820,907)-(860,955) | #A8AAB1 |
| MB1 | sheet title | darkest fiftieth of (284,1041)-(678,1097) | #1A1B20 |
| MB1 | body text | darkest fiftieth of (173,1633)-(789,1680) | #18191E |
| MB1 | back chevron | darkest fiftieth of (94,1044)-(129,1092) | #191A1F |
| MB2 | host label | lightest fiftieth of (178,208)-(678,256) | #F3F6FD |
| MB2 | row title | lightest fiftieth of (181,773)-(741,817) | #F3F4F9 |
| MB2 | group header | lightest fiftieth of (76,328)-(315,369) | #8C8F94 |
| MB2 | row status "Done" | lightest fiftieth of (181,820)-(260,855) | #8C8D92 |
| MB2 | row age | lightest fiftieth of (741,820)-(805,852) | #5F6064 |
| MB2 | row chevron | lightest fiftieth of (833,536)-(868,571) | #6F7075 |
| MB2 | activity dot on a row tile, along a row | row y=531, x 139..159 | x 139 #513939, x 140 #0B0E21, x 141 #455F84, x 142 #80AEE2, x 143 #6FA8ED, x 144..154 #63A2F6, x 155 #6EA1E0, x 156 #80A2D0, x 157 #7689A7, x 158..159 #1A1D29 |
| D12 | activity dot on a row tile | median of (693,951)-(698,956) | #5C9EF3 |

### Type

| image | what | read | value |
|---|---|---|---|
| PK | status clock, digits "2" and "6" | glyphs 3,4 of (120,50)-(215,100) at half ink: rows 60..90, 60..90 | 31 px = 13.2 dp tall; / 0.705 = 18.8 |
| MB2 | status clock, digits | glyphs 3,4 of (120,50)-(222,100) at half ink: rows 60..90, 60..90 | 31 px = 13.2 dp tall; / 0.705 = 18.8 |
| D12 | status clock, digits | glyphs 1,2,4,5 of (2140,465)-(2230,502) at half ink: rows 474..493, 474..493, 474..493, 474..493 | 20 px = 13.0 dp tall; / 0.705 = 18.4 |
| L06 | status clock, digits | glyphs 1,2,4,5 of (1886,273)-(1980,309) at half ink: rows 280..301, 280..301, 280..301, 280..301 | 22 px = 13.1 dp tall; / 0.705 = 18.6 |
| M01 | status clock, digits | glyphs 1,2,4,5 of (2640,290)-(2780,340) at half ink: rows 298..332, 298..332, 298..331, 298..332 | 35 px = 13.3 dp tall; / 0.705 = 18.9 |
| D12 | host label, capital "A" | glyphs 1 of (700,585)-(1000,635) at half ink: rows 594..614 | 21 px = 13.6 dp tall; / 0.705 = 19.3 |
| D12 | host label, x-height "a", "s" | glyphs 3,4 of (700,585)-(1000,635) at half ink: rows 599..614, 599..614 | 16 px = 10.4 dp tall; / 0.526 = 19.7 |
| D12 | row title, capital "R" | glyphs 1 of (725,765)-(1050,812) at half ink: rows 774..794 | 21 px = 13.6 dp tall; / 0.705 = 19.3 |
| D12 | row title, x-height "e", "x" | glyphs 2,3 of (725,765)-(1050,812) at half ink: rows 779..794, 779..794 | 16 px = 10.4 dp tall; / 0.526 = 19.7 |
| D12 | group header, x-height "o", "s", "c", "r", "a" | glyphs 2,3,5,6,7 of (640,676)-(900,715) at half ink: rows 687..701, 687..701, 687..701, 687..700, 687..701 | 15 px = 9.7 dp tall; / 0.526 = 18.5 |
| D12 | "Search", capital "S" | glyphs 1 of (700,1485)-(800,1532) at half ink: rows 1498..1518 | 21 px = 13.6 dp tall; / 0.705 = 19.3 |
| D12 | "Search", x-height "e", "a", "r", "c" | glyphs 2,3,4,5 of (700,1485)-(800,1532) at half ink: rows 1503..1518, 1503..1518, 1503..1518, 1503..1518 | 16 px = 10.4 dp tall; / 0.526 = 19.7 |
| D12 | key labels (monospace), x-height "e", "s", "c" | glyphs 1,2,3 of (1320,1470)-(1620,1520) at half ink: rows 1489..1502, 1489..1502, 1489..1502 | 14 px = 9.1 dp tall; / 0.530 = 17.1 |
| L06 | host label, capital "A" | glyphs 1 of (345,285)-(700,335) at half ink: rows 299..321 | 23 px = 13.7 dp tall; / 0.705 = 19.5 |
| L06 | host label, x-height "a", "s" | glyphs 3,4 of (345,285)-(700,335) at half ink: rows 305..321, 305..321 | 17 px = 10.2 dp tall; / 0.526 = 19.3 |
| L06 | row title, capital "B" | glyphs 1 of (345,485)-(880,530) at half ink: rows 494..516 | 23 px = 13.7 dp tall; / 0.705 = 19.5 |
| L06 | row title, x-height | glyphs 2,3,5 of (345,485)-(880,530) at half ink: rows 500..516, 500..516, 500..516 | 17 px = 10.2 dp tall; / 0.526 = 19.3 |
| L06 | group header, x-height "c", "o", "m" | glyphs 1,2,3 of (262,385)-(670,430) at half ink: rows 400..415, 400..415, 400..415 | 16 px = 9.6 dp tall; / 0.526 = 18.2 |
| L06 | "Search", capital "S" | glyphs 1 of (350,1380)-(470,1425) at half ink: rows 1390..1413 | 24 px = 14.3 dp tall; / 0.705 = 20.3 |
| L06 | "Search", x-height | glyphs 2,3,4,5 of (350,1380)-(470,1425) at half ink: rows 1397..1413, 1397..1413, 1397..1413, 1397..1413 | 17 px = 10.2 dp tall; / 0.526 = 19.3 |
| L01 | pane header title, capital "A" | glyphs 1 of (780,255)-(1340,300) at half ink: rows 267..286 | 20 px = 11.9 dp tall; / 0.705 = 16.9 |
| L01 | pane header title, x-height "c", "v" | glyphs 2,5 of (780,255)-(1340,300) at half ink: rows 271..286, 272..286 | 16 px = 9.6 dp tall; / 0.526 = 18.2 |
| L01 | pane header subtitle, x-height "a", "c", "v" | glyphs 1,2,5 of (780,300)-(1060,335) at half ink: rows 306..317, 306..317, 306..316 | 12 px = 7.2 dp tall; / 0.526 = 13.6 |
| M01 | pane header title, capital "C" | glyphs 1 of (430,90)-(1220,145) at half ink: rows 101..131 | 31 px = 11.8 dp tall; / 0.705 = 16.8 |
| M01 | pane header title, x-height "e", "c" | glyphs 3,4 of (430,90)-(1220,145) at half ink: rows 108..130, 108..130 | 23 px = 8.8 dp tall; / 0.526 = 16.7 |
| M01 | pane header subtitle, x-height "r", "n" | glyphs 2,7 of (430,146)-(625,186) at half ink: rows 159..175, 159..175 | 17 px = 6.5 dp tall; / 0.526 = 12.3 |
| M03 | menu item, capital "O" | glyphs 1 of (760,565)-(985,625) at half ink: rows 575..610 | 36 px = 13.7 dp tall; / 0.705 = 19.5 |
| M03 | menu item, x-height "e", "n" | glyphs 3,4 of (760,565)-(985,625) at half ink: rows 584..609, 584..609 | 26 px = 9.9 dp tall; / 0.526 = 18.8 |
| PK | pane header subtitle, x-height "w", "s" | glyphs 1,3 of (260,203)-(400,240) at half ink: rows 214..228, 214..228 | 15 px = 6.4 dp tall; / 0.526 = 12.2 |
| AK | sheet title, capital "G" | glyphs 1 of (50,555)-(380,620) at half ink: rows 568..601 | 34 px = 14.5 dp tall; / 0.705 = 20.6 |
| AK | sheet title, x-height "o", "o" | glyphs 2,4 of (50,555)-(380,620) at half ink: rows 576..600, 576..601 | 26 px = 11.1 dp tall; / 0.526 = 21.1 |
| MB2 | host label, capital "M" | glyphs 1 of (176,204)-(680,258) at half ink: rows 216..244 | 29 px = 12.4 dp tall; / 0.705 = 17.6 |
| MB2 | host label, x-height "a", "s" | glyphs 2,5 of (176,204)-(680,258) at half ink: rows 223..244, 223..244 | 22 px = 9.4 dp tall; / 0.526 = 17.8 |
| MB2 | row title, x-height | glyphs 2,3,4,5,6 of (178,768)-(745,820) at half ink: rows 785..805, 785..805, 785..805, 785..805, 785..805 | 21 px = 9.0 dp tall; / 0.526 = 17.0 |
| MB2 | group header, x-height "a", "s" | glyphs 2,6 of (72,324)-(318,372) at half ink: rows 340..358, 340..358 | 19 px = 8.1 dp tall; / 0.526 = 15.4 |
| MB2 | row status "Done", x-height | glyphs 2,3,4 of (178,818)-(262,856) at half ink: rows 830..845, 830..845, 830..845 | 16 px = 6.8 dp tall; / 0.526 = 13.0 |

### Sizes of glyphs and gaps

| image | what | read | value |
|---|---|---|---|
| D12 | sidebar width | 587..1151 | 565 px = 365.9 dp |
| D12 | selected pill, inset from the sidebar's left edge | 587..614 | 28 px = 18.1 dp |
| D12 | selected pill, inset from the sidebar's right edge | 1124..1151 | 28 px = 18.1 dp |
| D12 | row app tile to the row title | 701..724 | 24 px = 15.5 dp |
| D12 | from one group's row tile to the next group's (a header and a row) | 784..963 | 180 px = 116.6 dp |
| D12 | key labels, centre to centre ("esc" 1346, "ctrl" 1424, "opt" 1501, "tab" 1579) | 1346..1423 | 78 px = 50.5 dp |
| D12 | rail: the clock's centre (2181) to the screen's right edge, doubled | 2097..2266 | 170 px = 110.1 dp |
| D12 | magnifier in "Search", height | 1490..1526 | 37 px = 24.0 dp |
| D12 | gear glyph, height | 477..519 | 43 px = 27.8 dp |
| D12 | activity dot on a row tile, width | 690..701 | 12 px = 7.8 dp |
| L06 | row and group header pitch: a header's centre (1101) to the next row's (1202) | 1101..1200 | 100 px = 59.7 dp |
| D12 | command glyph, height | 1477..1510 | 34 px = 22.0 dp |
| L06 | selected pill, inset from the sidebar's left edge | 203..232 | 30 px = 17.9 dp |
| L06 | row tile's left edge from the pill's | 233..265 | 33 px = 19.7 dp |
| L06 | row title's left edge from the pill's | 233..348 | 116 px = 69.3 dp |
| L06 | magnifier in "Search", height | 1382..1421 | 40 px = 23.9 dp |
| L06 | "+" beside a group header, height | 391..419 | 29 px = 17.3 dp |
| L06 | rail disc, width | 1892..1974 | 83 px = 49.6 dp |
| L06 | rail: the clock's centre (1933) to the screen's right edge, doubled | 1843..2024 | 182 px = 108.7 dp |
| L20 | key bar strip, inset from the screen's left edge | 203..218 | 16 px = 9.6 dp |
| L20 | gutter between two pane fills | 1294..1310 | 17 px = 10.2 dp |
| L20 | gutter between a pane's fill and the key bar's | 936..952 | 17 px = 10.2 dp |
| M01 | back disc, rim to rim | 70..215 | 146 px = 55.7 dp |
| M03 | menu items, centre to centre (593, 713, 833) | 593..712 | 120 px = 45.7 dp |
| AK | sheet, inset from the screen's left edge | 0..6 | 7 px = 3.0 dp |
| AK | card, one of three rows | 867..971 | 105 px = 44.8 dp |
| MB1 | sheet, inset from the screen's left edge | 0..18 | 19 px = 8.1 dp |

## The theme

`ui/theme/Theme.kt`.

```kotlin
@Composable fun VeloTheme(size: UiSize = UiSize.Large, content: @Composable () -> Unit)
object VeloTheme { colors: VeloColors; surface: SurfaceColors; radii: VeloRadii; sizes: VeloSizes; type: VeloType; space: VeloSpace; size: UiSize; fun medium(value: Dp): Dp }
@Composable fun VeloSurface(colors: SurfaceColors, content: @Composable () -> Unit)
fun rounded(radius: Dp): Shape
val Capsule: Shape
fun materialColors(s: SurfaceColors, scrim: Color): ColorScheme
```

- `VeloTheme(size = app.settings.uiSize)` uses the same dark palette on every launch. An old
  `colorScheme` preference does not override it. Interface size and terminal font remain independent.
- A screen area declares the surface it is on with `VeloSurface(VeloTheme.colors.sidebar) { … }`
  (or `backdrop`, `chrome`, `overlay`, `sheet`); `VeloTheme` starts on chrome. The settings
  panel (`SettingsPanelSurface`) is on `sheet`. Inside, every component
  reads `VeloTheme.surface` for the area beneath it.
- The Material bridge: `VeloSurface` wraps its content in a `MaterialTheme` whose colour scheme,
  typography and shapes are made from the tokens of that surface (`materialColors`), and sets
  `LocalContentColor` and `LocalTextStyle`. A `Switch`, `Slider`, `FilterChip`, `Snackbar` or
  unstyled `Text` that remains is right on every surface in both appearances; the test
  `materialComponentsThatRemainAreReadableOnEverySurface` holds the scheme's text pairs to 4.5.

## Glass

Two kinds, `ui/theme/Glass.kt` and `ui/theme/Backdrop.kt`.

**In the window, beside the panes.** `Modifier.glass(shape: Shape = Capsule, selected: Boolean
= false)`: the surface's `glass` fill (`glassSelected` for a switched-on cell), clipped to the
shape, with the `glassEdge` rim `sizes.edge` wide. On dark glass the rim is lit at the top and
bottom and falls to 35 % at the sides, as D12 and PK show; on light glass it is one line all
round (M01, MB1). It shows nothing through: chrome never overlaps a pane.

`Modifier.glassControl(shape = Capsule, selected = false, ring: Color? = null)` is glass as the
redesign draws a control (Controls board): the fill and one even rim of `glassEdge` all round
(on an overlay, its `glassSelected`, as a dialog's field). `ring` replaces the rim with a line
twice as wide: a field's focus (`colors.chrome.accent`) or its error (`danger`). Buttons,
fields, the segmented track and the menu button use it.

**Over the panes.** `OverlaySurface(modifier, shape = rounded(radii.overlay)) { … }` is the
surface of every menu (with `radii.pill`), dialog, popover and of the palette. Its rim is one
even line of the overlay's `divider` (Overlays board). Its fill is a blur of what is behind
it, made by the app:

```kotlin
class BackdropSource(val views: () -> List<SurfaceView>, val window: Window? = null)
val LocalBackdropSource: ProvidableCompositionLocal<BackdropSource?>   // null: no blur, the plain surface colour
val canBlur: Boolean                                                   // API 31+
```

1. The screen provides the source once: `CompositionLocalProvider(LocalBackdropSource provides
   BackdropSource(views = { the SurfaceViews now attached }, window = activity.window))`. The
   theme knows nothing about panes: it gets views and asks each for its place on the screen.
   For the app that is the list of attached `PaneSurfaceView`s (`ui/TerminalViews.kt:27`,
   created in `PaneCard`, `ui/PaneArea.kt:94`), the one drawn on top last.
2. While an overlay is on screen, `rememberBackdrop` copies the rectangle under it: each view's
   part with `PixelCopy.request(SurfaceView, Rect, Bitmap, …)`, and, when the overlay is in a
   window of its own (`VeloMenu`, `VeloDialog`, `VeloPopover`, `VeloPalette` all are), the screen
   window's part with `PixelCopy.request(Window, …)`, so that chrome beside a pane is in the
   picture too. The bitmap has one pixel per 8 screen pixels (`BackdropScale`); `backdropPart`
   (pure, unit-tested) maps a view's place and buffer size to the source and destination
   rectangles. Views go under the window's picture where that picture is clear, over it where
   it is opaque.
3. The bitmap is drawn scaled up through one `RenderEffect`: a blur of `sizes.blur` (24 dp,
   about seven bitmap pixels at 360 dpi), then a colour matrix, `VeloColors.overlayMaterial()`. The
   matrix turns what is behind the overlay into the overlay's fill: the surface colour, moved by
   `overlayLight` (10 %) of the lightness and `overlayTint` (45 %) of the colour of what is
   behind it under the scrim. Over the ground the iOS sheet was measured on (`overlayAnchor`)
   the fill is exactly the measured surface colour. The two shares are the largest at which
   every text on an overlay keeps 4.5 over any colour a terminal can show; with a plain
   10 % mix of the picture, which was tried first, red, green and blue bars behind a dialog
   gave fills 6 % of a channel apart and a capture showed next to nothing.
4. The surface is clipped to its shape, so the blur is inside it and what is beside it stays
   sharp under the scrim (`colors.scrim`).
5. Before Android 12 (`canBlur` false; the app's `minSdk` is 30), without a source, and until
   the first picture arrives, the fill is the overlay's opaque surface colour. The first picture
   is asked for at the first frame after the surface is laid out and fades in over 150 ms.

**The refresh rate: every 100 ms** (`BackdropRefreshMillis`), ten pictures a second. Why:
the picture is blurred over 24 dp, so nothing finer than a block of colour moving can be seen
in it; the gallery's bars travel 120 dp a second, that is 12 dp, half the blur, between two
pictures. A copy is not free. Measured on the emulator (API 37, SwiftShader: the GPU is
emulated in software), a dialog, a menu and a dialog again open for 20 s each, one sample a
second, 57 samples: a copy takes 2.9 to 40.4 ms from request to bitmap, 10.6 ms at the median,
19.9 ms at the ninetieth percentile; the first copy of a process takes 170 to 380 ms. While an
overlay is open the frames of the process that miss their deadline go from 0.8 % to 21 % (the
ninetieth percentile of a frame from 12 to 32 ms, `dumpsys gfxinfo`): about one or two frames
of the pattern per picture wait for the blur, which this emulator renders on the CPU. At the
display's rate that would be every frame, for a difference nobody can see through 24 dp of
blur; once a second, and a scrolling terminal would jump under the glass. The loop waits
100 ms after a copy has arrived, so a slow device slows the refresh and never queues copies.
Nothing is copied while no overlay is open. The emulator timing is illustrative; actual
cost and first-frame latency depend on the device and its graphics backend.

## Components

Each reads the colours of the surface it is on. Every icon-only control takes a
`contentDescription`. "Replaces" names what the screens use today (paths under
`app/src/main/java/io/github/yhzion/velo/`).

### Controls and rows (`ui/theme/Components.kt`)

| component | signature | replaces |
|---|---|---|
| Round glass button | `GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false)` | `RailButton` (`ui/Rail.kt:61`), `ToolbarButton` (`ui/Sidebar.kt:375`) where it stands alone, the Material `IconButton` of the back arrows (`ui/SettingsScreen.kt:33,47`, `ui/PairingScreen.kt:71`) |
| Round glass switch | `GlassIconSwitch(icon: ImageVector, contentDescription: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier)` | nothing before it: the sidebar's pin (sidebar-dock), an on/off switch read with its state; the icon shows the state, the glass stays as `GlassIconButton`'s |
| Capsule of several icons | `GlassCapsule(modifier: Modifier = Modifier, vertical: Boolean = false, content: @Composable () -> Unit)` with `CapsuleIcon(icon, contentDescription: String, onClick, modifier = Modifier, enabled = true, selected = false, badge = false)` | the row of `ToolbarButton`s at the top of the sidebar (`ui/Sidebar.kt:163-167`); `selected` is the activity toggle's on state, `badge` the Duo's dot on the eye |
| List row | `ListRow(title: String, onClick: () -> Unit, modifier = Modifier, subtitle: String? = null, selected = false, enabled = true, dimmed = false, strong = false, onClickLabel: String? = null, onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null)` | the pane and tab rows (`ui/Sidebar.kt:240,249`), `HostHeader` (`:308`, `strong`, `dimmed` and `subtitle` for its note), `DeviceHeader` (`:284`), `FoundServerRow` (`:273`), `SidebarRow` (`:384`), the session line (`:198`) |
| Button inside a row | `RowIconButton(icon, contentDescription: String, onClick, modifier = Modifier, enabled = true)` | the 36 dp chevron and `…` boxes of `HostHeader` (`ui/Sidebar.kt:327,333`) |
| Section header | `SectionHeader(text: String, modifier = Modifier, onClick: (() -> Unit)? = null, onClickLabel: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null)` | the session label and "FOUND ON TAILNET" texts (`ui/Sidebar.kt:190,210`), `Section` (`ui/SettingsScreen.kt:94`) |
| Status mark | `StatusMark(status: Status, contentDescription: String, modifier = Modifier, icon: ImageVector? = null)`, `enum class Status { Ok, Warning, Danger, Active, Off }` | `ConnectionDot` (`ui/Sidebar.kt:354`), the node's dot in `DeviceHeader` (`:286-288`), the activity dot (`:366-369`), `LinkGlyph` (`ui/Rail.kt:50`, with `icon`). A dot or a glyph; it takes no tap |
| Key cap | `KeyCap(label: String, onClick, modifier = Modifier, armed = false, icon: ImageVector? = null, contentDescription: String? = null, enabled = true)` | the key `Box` of `KeyBar` (`ui/KeyBar.kt:69-73`); `armed` is Ctrl waiting for its letter; with `icon` the arrow is drawn and `label` is spoken; `enabled` false (the connection is down) draws it in `disabled`, unfilled, and takes no tap |
| Key bar strip | `KeyStrip(modifier = Modifier, content: @Composable RowScope.() -> Unit)` | the `BoxWithConstraints` of `KeyBar` (`ui/KeyBar.kt:60`); the scrolling and the fades stay with `KeyBar` |
| Button | `VeloButton(text: String, onClick, modifier = Modifier, kind: ButtonKind = ButtonKind.Secondary, enabled = true, small = false, icon: Painter? = null, iconAtEnd = false, row = false, danger = false, tag: String? = null)`, `enum class ButtonKind { Primary, Secondary, Destructive, Text }` | Controls board: Primary is the accent's fill (one per screen), Secondary glass with a rim, Destructive a red rim at 50 % with `dangerText`, Text the accent's words (44 high, 8 at each side; 12 with `row`). Not enabled: the `selected` fill with `disabled` words. `small` is the 40 high button of a list row; `row` the 44 high one at the end of a settings row (Edit order, Copy). `danger` sets a Text button's words in `dangerText` (Remove). Words and `row` buttons lay out at their height; the others at 48. Compose's hit testing gives every button a 48 dp touch target; the caller's modifier (a test tag, semantics) applies inside it, to the button drawn. `tag` names the drawn button |
| Update dot | `AttentionBadge(tint: Color, modifier = Modifier, ring: Color = VeloTheme.surface.surface)` | a flat dot in a 2 ring of what it sits on (Controls board, sample-update-dot); size it with `sizes.dot` |
| State chip | `StateChip(text: String, status: Status, modifier = Modifier)` | a state in words on a 12 % tint of its colour, with its dot (Controls board: Connected) |
| Badge | `TagBadge(text: String, modifier = Modifier)` | a short name on glass, such as a license's (Controls board) |
| Eyebrow | `Eyebrow(text: String, modifier = Modifier, color = surface.accent)` | the small heading in capitals: NEW VERSION, EXAMPLES; it is a heading for a screen reader |

### Settings controls (`ui/theme/Controls.kt`)

As the redesign's Controls board draws them, at Medium. A switch's knob is white when on and
`textOnRaised` when off, with a soft shade under it; a slider's knob is white.

| component | signature | what it is |
|---|---|---|
| Group | `GroupHeader(text, modifier, first = false)`, `GroupCard(modifier, content)`, `GroupDivider(modifier, full = false)`, `Modifier.groupBleed()`, `Modifier.groupInset()` | the heading is the caption at Medium weight, 20 above it (12 when `first`), held to its line (`lineBox`); the group has no fill, a `hairline` border of `divider` and the `group` radius, and its rows sit inside the border, 16 from its start and 12 from its end. A divider runs from the rows' start to the border, or from border to border with `full` (under a saved remote). A row that draws itself reaches the border with `groupBleed` and keeps the group's room with `groupInset`. On an overlay a group is a glass card with the `card` radius |
| Setting row | `SettingRow(label, modifier, tag = null, labelFills = true, control)` | a label with its control at the end, 60 high; a control too wide to sit beside the label goes under it. The label takes the room the control leaves (Appearance), or only its words with `labelFills` false (Keyboard). `tag` names `-row` and `-label` |
| Switch row | `SwitchRow(label, checked, onCheckedChange, modifier, subtitle = null, enabled = true, tag = null, paragraph = false)` | the whole row is the switch (`Role.Switch`), 60 high or 64 with a second line; a `paragraph` subtitle of more than a line is set at 1.4 em, 12 above and below. The label and subtitle are as wide as the wider of them. `tag` names `-row`, `-label`, `-caption`, `-switch` |
| Navigation row | `NavigationRow(title, onClick, modifier, value = null, subtitle = null, enabled = true, trailing = null)` | a row that opens a page: its value in `secondary`, then a chevron |
| Action row | `ActionRow(text, icon: ImageVector, onClick, modifier, enabled = true)` | words and an icon in the accent: Add Remote… |
| Switch | `VeloSwitch(checked, modifier, enabled = true)` | drawn only, the row takes the tap; on, the accent; off, `glassSelected`; not enabled, plain glass and a `disabled` knob |
| Slider | `VeloSlider(value, onValueChange, modifier, valueRange, steps, onValueChangeFinished)` | a tap, a drag along it, the left and right keys and a screen reader's own actions move it |
| Segmented control | `VeloSegmented(options, selected, label, modifier, optionTag = null, cellPadding = 12, stretch = false, onPick)` | a glass track with a rim; the chosen cell is `glassSelected` and Medium; `optionTag` tags each drawn cell, inside the track's rim. `cellPadding` is the room at each side of a cell's words (14 for Row and Grid). In the settings sheet the cells wrap instead of being cut, and share the control's width with `stretch` (Monospaced, All fonts) |
| Menu button | `MenuButton(text, onClick, modifier, enabled = true, contentDescription = null, tag = null, small = false)` | a 44 high glass pill with the current choice and a chevron; it opens a `SelectMenu`. `small` is the 40 high pill with 14 words of a row that holds a switch too (an alert's sound) |
| Key choice | `KeyChoice(key, selected, onClick, modifier)` | a key chip, 44 high: tinted with the accent and its rim when chosen, a `glassEdge` rim alone when not. A key named in words is set in JetBrains Mono at 14 (`monoAt`), an arrow keeps its icon (`IconKeys`); the rim adds to its width, as the board's border does |
| Close button | `CloseButton(onClick, modifier, contentDescription = "Close")` | the 48 glass disc of a sheet's header, with a light line along its top |

### Cards (`ui/theme/Cards.kt`)

| component | signature | what it is |
|---|---|---|
| Raised card | `RaisedCard(modifier, padding: Dp? = null, spacing: Dp? = null, content: ColumnScope.() -> Unit)` | `colors.raised`, a border of `divider`, the `card` radius, 18 inside and 14 between its parts unless given; its words keep the surface's colours, a paragraph is `textOnRaised` |
| App mark | `AppMark(modifier = Modifier)` | StartKit board, start-mark: the two panes, the left one in chrome's `accent` with its cursor, the right one in the sidebar's `glassSelected`; scaled to fit the modifier's size, centred. Decorative. The splash ends on the same picture (`ui/Splash.kt`) |
| Warning note | `WarningNote(text, modifier)` | the warning triangle and the words on a 10 % tint of `warning` |
| Category icon | `CategoryIcon(icon: Painter, tint: Color, modifier)` | a white line icon on a 32 tile of the category's tint (`CategoryTints`) |

### Tab tile (`ui/theme/Tile.kt`)

`TabTile(brand: TileBrand, modifier, panes = 1, size = sizes.tabTile, dot: TileDot? = null,
ring = surface.surface, tag: String? = null, contentDescription = tileLabel(brand, panes), stateDescription = null)`
draws the redesign's tab tile (Tile and TabTiles boards). The rules are pure functions of the
face's size at Medium and are unit-tested (`TabTileTest`):

| rule | function |
|---|---|
| One layer per pane, at most four (`MaxTileLayers`) | `tileLayers(panes)` |
| The pane count shows from five panes, at the front tile's lower right | `tilePaneCountShown(panes)`, `tileCountRect(size, digits)` |
| The state dot (`TileDot.Unread` accent, `Waiting` warning, `Failed` danger) keeps the upper right; the two never overlap | `tileDotRect(size)` |
| Each layer behind steps `max(2.5, size × 0.115)` up and right, 12 % smaller, 20 % fainter and darker; it is laid out where it draws, rim and all dimmed | `tileStep`, `tileBackLayers(panes, size)`, `tileLayerRect(layer, size)` |
| The box is the face and three steps, so a row's words never move | `tileBox(size)` |
| Corner `size × 0.27`, logo `size × 0.6`, letter `size × 0.56` (`>_` 0.4) | `tileCorner`, `tileGlyph`, `tileLetter` |
| What a screen reader says: "Claude Code, 3 panes" | `tileLabel(brand, panes)` |
| A program's brand from its name | `tileBrand(program)` |

`TileBrand` holds each brand's label, logo and colours. Claude and Codex fill their tile (the
orange gradient; white to `#E3E3E3` with a black C in Roboto Bold, as the Tile board sets it);
omp and pi, which the board does not show, keep the marks the app gave them before (omp's
gradient with its ring, pi's `#30556B` tile with a π). The tools draw a Simple Icons 16.38.1 logo
(`ic_brand_<slug>`, NOTICE) in its brand colour on dark glass; Azure and Heroku are letter tiles;
any other program is `>_`. A login shell (`-zsh`) has its shell's brand. The parts carry the
canvas's test tags under `tag`: `-face`, `-logo`, `-letter`, `-layer-2` to `-layer-4`, `-badge`,
`-pane-count`.

The sidebar's tab rows (`TabListRow`, `ui/theme/TabRow.kt`: Controls board sample-tab-row, Sidebar
board tab-row) draw it, the caption under the panes too (`PaneCaption`, 18). What a tab's tile
shows is a pure function of its report (`ui/TabTiles.kt`, `TabTilesTest`): the brand of the agent
the report names, else of the program (`tabTile`); the dot of a report not yet seen (`tileDot`:
a finish blue, a failure red, a program waiting for the user orange); and, at the row's end, what
the program does now (`rowMark`: three dots while it works, an orange dot while it waits).

### Fields (`ui/theme/Fields.kt`)

| component | signature | replaces |
|---|---|---|
| Search field | `SearchField(query: String, onQueryChange: (String) -> Unit, modifier = Modifier, placeholder: String = "Search", inputModifier = Modifier)` | the Sidebar board's filter: 52 high, the magnifier 12 before the text. Place it over the bottom of the list (`Modifier.align(Alignment.BottomCenter)` in a `Box` around the `LazyColumn`). In the sidebar it lies on a band of the surface colour with the board's 12 around it, so rows that scroll under it do not show around it; the list has a bottom content padding as tall as the band |
| Text field | `VeloTextField(value: String, onValueChange: (String) -> Unit, modifier = Modifier, placeholder = "", label: String? = null, error: String? = null, icon: Painter? = null, keyboardOptions = KeyboardOptions.Default, keyboardActions = KeyboardActions.Default, trailing: (@Composable () -> Unit)? = null, visualTransformation, help: String? = null, mono = false, tagPrefix: String? = null)` | a `glassControl` pill, 48 high (Controls and Overlays boards). While it has the keys its ring is `colors.chrome.accent`; an `error` turns the ring `danger` and takes the place of `help` in `dangerText`. `mono` sets an address or a path in the monospace face. `tagPrefix` tags `-field`, `-input`, `-help` and `-error` |

### Overlays (`ui/theme/Overlays.kt`)

Each opens in a window of its own over the screen's, with the scrim; a tap beside it or Back
closes it. The `…Surface` composables are the same surfaces without a window.

| component | signature | replaces |
|---|---|---|
| Menu | `VeloMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier = Modifier, anchor: (() -> IntRect?)? = null, content: @Composable ColumnScope.() -> Unit)`; `MenuSurface(modifier, content)` | `DropdownMenu` (`ui/PaneMenu.kt:25`, `ui/Sidebar.kt:336`, `ui/Workspace.kt:223`). Declared in the `Box` of its button it opens under it, as `DropdownMenu` does; `anchor` gives a rectangle of the screen instead (a long press on a pane). A menu does not dim the page under it (Overlays board, popup-menu; SetRemotes); a tap beside it still closes it |
| Menu item | `MenuItem(text: String, onClick, modifier = Modifier, icon: ImageVector? = null, enabled = true, destructive = false)`; `MenuDivider(modifier = Modifier)` | the row menu (Overlays board, popup-menu): a pill of `radii.pill`, items 48 high and 20 in, the icon in `secondary`; a destructive item in `dangerText` |
| Select menu | `SelectMenu(expanded, onDismissRequest, options: List<T>, selected: T, label: (T) -> String, onPick, modifier, anchor = null, optionTag = null)`; `SelectMenuItem(text, selected, onClick, modifier)` | the menu a `MenuButton` opens (Overlays board, select-menu): the current value has a check in the accent |
| Dialog | `VeloDialog(title: String, onDismissRequest, confirmText: String, onConfirm, modifier = Modifier, text: String? = null, cancelText = "Cancel", destructive = false, confirmEnabled = true, …, tagPrefix: String? = null, content: (@Composable ColumnScope.() -> Unit)? = null)`; `DialogSurface(…)` | Overlays board: radius 32, 22 above, 20 at the sides, 16 below; the `text` in the overlay's `secondary` at 15; Cancel as words and the confirming button filled with the accent (the sheet's red when `destructive`) at the lower right, wrapping under each other when they do not fit. `tagPrefix` tags `-title` (as wide as the dialog), `-body`, `-actions` (with the room above the buttons), `-cancel`, `-confirm`; `scrimTag` tags the scrim. 8 outside it at Medium (DlgAddRemote). The confirming button keeps 28 at each side in a dialog with fields, 24 in a question (DlgAddRemote and Overlays boards) |
| Toast | `ToastSurface(message, modifier, icon: ImageVector? = null, iconTint: Color? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null)`; `VeloToast(message: String?, onDismiss, …)` | Overlays board: a 48 high pill of the overlay at the bottom centre, a polite live region, tagged `toast`, `toast-message`, `toast-action`. `VeloToast` dismisses it after `ToastMillis` (3 s) or as long as the accessibility settings ask |
| Popover | `VeloPopover(expanded, onDismissRequest, modifier = Modifier, anchor: (() -> IntRect?)? = null, content: @Composable ColumnScope.() -> Unit)`; `PopoverSurface(modifier, content)` | nothing today; for the size popover and the remote's details the spec adds |
| Palette | `VeloPalette(query: String, onQueryChange, onGo: () -> Unit, onDismissRequest, modifier = Modifier, placeholder = "Command Palette", content: LazyListScope.() -> Unit)`; `PaletteSurface(query, onQueryChange, onGo, modifier, placeholder, focus = false, content)`; `PaletteRow(title: String, onClick, modifier = Modifier, enabled = true, note: String? = null)` | the `Dialog`, field and rows of `CommandPalette` (`ui/Palette.kt:39-58`); the list of commands and what Go runs stay there |
| Any other surface over the panes | `OverlaySurface(modifier = Modifier, shape: Shape = rounded(VeloTheme.radii.overlay), content: @Composable BoxScope.() -> Unit)` | the `Snackbar` of `ErrorNotice` (`ui/Dialogs.kt`) if it is to blur; otherwise Material's keeps working through the bridge |

In Compact style the **outside** gap is measured in physical pixels inside the system safe area:
`compactOuterPx` (4 px) on all four edges of a compact window, and `compactWideSidePx` (2 px)
on the left and right of a wider window. Comfortable keeps the Duo's `gap` on all four edges.

What has no component, because it is one token on an existing layout: the pane card's corner
(`radii.pane`, `radii.paneCompact`) and focus edge (`colors.paneFocus`, `sizes.paneFocus`) in
`PaneCard` (`ui/PaneArea.kt:89-91`); the gaps (`sizes.gap`, `sizes.gapCompact`); the backdrop
(`colors.backdrop.surface` for every `Color.Black` page background); the overview's tiles
(`backdrop.glass` for `Color(0xFF2C2C2E)`, `backdrop.accent` for the selected edge,
`ui/Overview.kt:36,54`); the rail's clock (`type.clock`, `ui/Rail.kt:37`); the screen titles
(`type.screenTitle`, `type.wordmark`).

Today's fifteen colour values, and the role each becomes:

| today | role |
|---|---|
| `Color.Black` page backgrounds | `backdrop.surface` |
| `Color.White` text and icons | the surface's `text` (`onGlass` inside a glass control) |
| `0xFF1C1C1E` `Surface`, `RailGrey` | `sidebar.surface`, `overlay.surface` (the palette), `chrome.glass` (rail discs); a key has no fill |
| `0xFF3A3A3C` `Selected` | `selected` for a row, `glassSelected` for a toggle |
| `0xFF8E8E93` and `0xFFAAAAAA` (`Dim`, `Caption`) | `secondary`; `disabled` where the thing takes no tap |
| `0xFF0A84FF` (`Blue`, `Accent`) | `accent`; `colors.paneFocus` for the pane edge |
| `0xFF2C2C2E` | `glass` (the row tile, the overview tile) |
| `0xFF30D158`, `0xFFFF9F0A` | `ok`, `warning` |
| `0xFFFF453A` and `0xFFFF6B6B` (`Warn`) | `danger` for the mark, the rim and the fill; `dangerText` for the words |
| `0x8C000000` over docked panes | `colors.scrim` |

## The gallery

`app/src/debug/` only: `GalleryActivity` is declared in `app/src/debug/AndroidManifest.xml`
and its code is in the debug source set, so a release APK holds neither.

    adb shell am start -n io.github.yhzion.velo/.ui.theme.GalleryActivity \
        [-e open menu|dialog|popover|palette] [-e scroll DP]

It starts no connection. The first page is a workspace built from the components (sidebar,
pane header, a pane, the key bar, the rail) around a real `SurfaceView` that draws a moving
pattern: terminal text that stands still, coloured bars that travel, and the time. Below it,
every overlay surface without its window, both dialogs, the buttons, fields and marks in every
state, and the settings controls on the overlay surface. The rail's three buttons and the header's `…`
open the overlays over the pattern. Last come the redesign's samples (`RedesignSample`,
`app/src/debug/.../RedesignGallery.kt`): the Controls and Overlays boards' rows, buttons,
controls, fields, marks, the confirm and Add Remote dialogs, the menus, the toast and every tab
tile, each under the test tag its board gives it (`sample-…`, `confirm-dialog`, `popup-menu`,
`select-menu`, `toast`, `brand-tile-…`). The gallery makes test tags resource ids
(`testTagsAsResourceId`), so `uiautomator dump` names them.

## What still differs from the Duo

These notes compare Velo's layout and surfaces with the visual references above.

| component | still differs |
|---|---|
| Icons and sounds | Velo uses its own Android glyphs for settings, alerts, connection status, pane actions, and agent indicators. Activity sounds use Velo's own presets. |
| Round glass button | The fill and rim match. The Duo's disc has a soft highlight inside its upper rim and a faint shadow under it (PK, M01); here the rim is one line and there is no inner highlight |
| Capsule of icons | In D12 the sidebar toggle is a separate disc to the right of the capsule; that is layout, which the sidebar screen decides. The capsule itself matches in height and radius. The capsule's order is Activity, command palette, Settings |
| Row with a tile | The sidebar draws the redesign's brand tile (`TabTile`) in every tab row. |
| Row with an icon (a remote, This Device) | Matches in size and position. The Duo's remote row has no state dot; the app's has one (it shows the connection) |
| Selected pill | Matches in height, radius and inset. In Light the Duo's pill reads `#131313` on black, a ratio of 1.13; here it is the same value |
| Section header | The Duo's "+" is a circled plus and its chevron is thinner; the text matches in size, weight and colour |
| Search field | Height, radius, fill and rim match; the magnifier is Material's (a heavier ring) at the Duo's 24 dp. On the Duo the list scrolls under the field and shows through its glass a little (L06: a row's text is faintly visible inside the capsule); here the field's fill is opaque |
| Pane header | Title and caption match in size. The Duo's caption has a small chevron after the session name; that is content for the header's screen |
| Tab capsule | The Duo's is 28 dp high with 2 dp around its selected cell (D12, L01); the gallery shows the tabs in the 52 dp capsule of the sidebar's toolbar. A token for the small capsule is not here; see Open |
| Key cap | Labels match in size and face (monospace). The Duo's arrows are thin SF Symbols; here Material's arrows. The Duo shows no armed key; the accent fill is derived |
| Key bar strip | Height and radius match. On the Duo the strip's glass discs at both ends have the rim highlight described above |
| Rail | The Duo's rail is 109 dp wide around the camera and its link mark is a ring with a Wi-Fi glyph; the app's rail is the Rail board's 60 dp at Medium and the mark is a glyph in the state's colour |
| Menu | Radius, width, text size and item height (48 against the Duo's 45.7) match. The Duo's menu is the system's: it has no dividers between groups and its icons are thin. Its material shows what is behind it more faintly than ours over a strongly coloured picture; over a terminal the two agree (`overlayAnchor`) |
| Dialog | The Duo shows sheets, not dialogs: a sheet is as wide as the screen less 8 dp and rises from the bottom, with a round close button in its corner. Ours is a centred dialog 386 dp wide (the sheet's width on the phone) with Cancel and a confirming button; fill, radius, padding, title size and the field's shape are the sheet's |
| Popover, palette | Not on the Duo in any image; derived from the sheet and the menu |
| Blur | iOS glass also refracts and lights its edge from what is behind it. Ours is a blur with a colour matrix and a plain rim |
| Type | SF Pro and SF Mono on iOS; Roboto and Droid Sans Mono on the emulator, the Samsung system font on the Fold8. Sizes are set to the measured ones |

## Open

- **A small capsule for the pane header's tabs** (28 dp high, D12 and L01). The tokens hold one
  capsule thickness, 52 dp. Whether the header shows tabs at all is decided by the screens; if it
  does, add `capsuleSmall` to `Metrics.kt` with the reading "tab capsule" of D12.
- **Performance.** Use the debug gallery to compare first-frame delay and blur cost on representative hardware; check the opaque fallback separately.
- **Not shown in any image**, so derived: Settings, the command palette, the contents of the
  "…" menus, a dialog with a text field, a disabled and a destructive menu item, a dark menu
  (AK's sheet stands in for it), every state colour, and the redesigned layout (gutters,
  overview) in Dark.
- **Text colours read from video** are short of the real colour, because a thin stroke is
  blended with its ground. Where a still shows the same role the still is used (MB2 for the
  dark sidebar, PK and M01 for the pane header). The light sidebar's text has no still: it
  is the lightest pixel of L06, `#D5DDE4`, and the real value may be lighter.
- **Typeface.** The interface uses Android's device fonts, so letter shapes differ from iOS even at comparable sizes. The terminal has a separate font setting.
