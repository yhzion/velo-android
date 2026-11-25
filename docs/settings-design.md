# Settings design

Velo's settings have five categories: Appearance, Keyboard, Notifications, Remotes, and About. Changes apply immediately unless a dialog asks for confirmation. The measurements below describe Compose layout, not physical screen pixels. See [Design tokens](design-tokens.md) for colors, typography, and reusable components.

## Panel and navigation

Settings opens as a modal without replacing the current screen. The modal respects safe areas; tapping outside it closes settings, but tapping empty space within it does not. When both window dimensions are at least 600dp, categories and details appear side by side, with Appearance selected initially and separate scroll positions. In smaller windows, categories, details, and subpages appear one at a time. Changing category leaves its subpage. Back goes up one level or closes settings at the top; Close Settings always closes the whole panel.

At Large size the panel has an 8dp outside margin, a maximum width of 820dp, and a 236dp category column. It uses a 12dp corner radius and elevation, and 0.65dp borders and dividers. The header uses 16dp horizontal and 8dp vertical padding. The category list uses 8dp inside padding, 4dp between rows, and rows at least 60dp high, with a 28dp icon and a label. The icon is not a separate button. Details use 16dp horizontal padding.

The opaque category and detail surfaces are `sidebar` and `chrome`; menus and confirmations use `overlay`. The interface stays dark regardless of Android appearance. A glass border or small glass control does not make the entire sheet translucent. Use `onAccent` and `onDanger` for text on their respective fills.

## Shared controls

Small, Medium (default), and Large use scale factors of 0.72, 0.85, and 1.0. At those sizes a 20dp inset becomes 14.4, 17, and 20dp, and a 60dp row becomes 48, 51, and 60dp. Typesetting stays at least 11sp, line height follows the resulting text size, and Android's font scale still applies. The terminal font size is independent.

| Control | Layout and behavior |
| --- | --- |
| Section and group | Section headings expose heading semantics. An outlined group uses a 12dp radius at Large and dividers between its rows. Neither headings nor group backgrounds act as buttons. |
| Labeled row | A `SettingRow` places the label and control side by side with at least 14dp between them at Large; in a narrow window the control moves below. |
| Segmented choice | Radio choices have a minimum 48dp target. The selected cell uses `glassSelected` and `onGlass`. Choices wrap rather than truncating. |
| Switch | The entire row operates one switch. The track is 51×31dp at Large; position and color both indicate state. |
| Slider | A 48dp target with a 4dp track and 28dp thumb at Large. Supports tapping, horizontal dragging, arrow keys, and accessibility value actions. |
| Button | Text and icon buttons have a minimum 48dp target. Primary actions use an accent fill and destructive confirmations use a danger fill. Navigation chevrons follow reading direction; terminal keys represent physical directions. |
| Text field | `VeloTextField` keeps its label visible after typing and reports validation errors below the capsule-shaped field. |
| Menu | Opens below its button, or above when space is limited. Width is 200–285dp at Large; long menus scroll. Back and outside taps dismiss without choosing. |
| Confirmation | Centered, with at least 8dp outside margin and a maximum width of 386dp at Large. Content scrolls while action buttons stay below it. Cancel, Back, or an outside tap discards unconfirmed changes. |

## Appearance

- **Size** changes interface typography and spacing; **Pane style** selects Comfortable (default) or Compact.
- **Keep screen on while using Velo** is off by default, and changes the current activity's screen-on behavior.
- **Highlight color** shows the selected color and a two-pane preview. The presets are Accent (default), Blue, Green, Yellow, Cyan, Magenta, and Text. **Custom** remembers its last color, even after a preset is selected. The saturation and brightness area, hue slider, and hex field apply changes immediately. Hex accepts six digits with or without `#`; low contrast against black produces a warning rather than rejecting the color.
- **Font** shows the current family and opens **Terminal Font**. **Font size** accepts integers 8–24, with a default of 13. The sample uses the selected terminal font at the same pixel size as the terminal. The slider saves the value as it moves and sends the final size to the terminal at the end of the gesture.

### Terminal Font

Search matches font names by case-insensitive substring; **Monospaced** and **All fonts** filter without changing the selected font. The sample, **Import font…** action, and results scroll together, even in a short window with the keyboard open or enlarged Android text. Select a TTF, OTF, or TTC file in the Android picker. A successful import clears the search, chooses the appropriate filter, and applies the first imported font. A failure shows an error without changing the selection. The list combines built-in choices, device fonts, and imports; the selected row has a background and check. Loading disables selection and import, and the empty state changes from **Loading fonts…** to **No matching fonts** once loading finishes.

## Keyboard

- **Key bar** groups independently selectable keys under Modifiers, Editing, and Navigation. With two or more keys selected, **Edit order** replaces the chips with a list whose arrow controls move one row at a time and save immediately. **Done** exits editing. The initial order is `/`, Ctrl, Esc, Tab, Left, Down, Up, Right, Backspace. Re-adding `/` puts it first; other keys are added before a trailing Backspace or at the end.
- **Keep keyboard open** defaults to off and **Haptic feedback** to on. These switches are independent.
- **Use tap gestures** defaults to off. When on, Double tap defaults to Tab and Triple tap to Enter. Each can send None, Tab, Enter, Esc, Left, Right, Up, or Down whether or not the key is in the key bar. Assigning Enter shows a warning about sending current terminal input. Double and triple taps preserve the IME's prior open or closed state; a single tap that opens the IME first waits for a possible second tap.

## Notifications

- **Show background notifications** is on by default. If Android blocks an enabled alert type, Velo offers the permission prompt or a link to the appropriate app or channel setting. It rechecks when that flow ends.
- **Play activity sounds** is on by default. Volume ranges from 0% to 200%, with a 100% default. Turning off all activity sounds hides the slider but preserves per-kind choices. Preview and playback are subject to system sound mode, Do Not Disturb, and channel settings.
- **Stay connected in the background** is initially on, subject to the first-connection choice. When enabled, it explains the quiet running notification and shows the battery optimization action or exemption status. Allowing background connections does not grant notification permission.

Each alert kind has separate **Background notification** and **Activity sound** switches. The per-kind picker appears when that sound is enabled and offers Attention, Warning, Error, Success, Ready, and Loading.

| Alert kind | Notification / sound defaults | Sound preset |
| --- | --- | --- |
| Needs your response | On / on | Attention |
| Work completed | On / on | Success |
| Work failed | On / on | Error |
| Work started | Off / off | Loading |

## Remotes

- **This Device → Tailscale** controls Velo's optional embedded node, which defaults to off. The page reports its state and, when connected, its addresses. Login opens a browser; if none can open the URL it can be copied. Renaming the node or logging out requires confirmation because these actions interrupt connections. Other devices are managed in the [Tailscale admin console](https://console.tailscale.com/admin/machines).
- **Saved** lists each remote's name and address in saved order. The menu is bound to that remote's identity, not the active connection. **Rename…** trims the name and leaves the original intact if canceled. **Remove Remote…** confirms removal from this phone without deleting server sessions.
- **Add Remote…** accepts a label and an address typed as a hostname, URL, SSH login, or local socket path. Examples are `https://rex.example.ts.net` and `ssh://user@rex.example.ts.net`. QR codes must hold valid HTTPS URLs; scanning fills the address but does not save the remote until Add is confirmed. Invalid addresses, rejected codes, and duplicates use separate messages. If camera permission is denied, enter the address manually or open Android settings.
- **SSH key** opens a separate subpage for the phone's SSH identity.

## About

The app name, `versionName`, and `longVersionCode` come from Android's `PackageManager`. **Updates** opens a subpage for channel selection, automatic checks, background update notifications, and manual checks. Updates compare Android version codes, not tag strings. After an APK download passes verification, installation is a separate action with Android's own confirmation.

### Updates

- **Check for updates automatically** is on by default. When Velo comes to the foreground, it checks the selected channel if the last successful check is at least 12 hours old. The check runs in the background and never blocks the interface. A failure is silent and waits 15 minutes before the next foreground attempt.
- **Notify me about updates** is off by default and independent of automatic checks. It schedules a periodic background check that posts an Android notification for a new release.
- Every successful check, whether automatic, background, or manual, remembers the newest eligible release it found. A check that started earlier never replaces the result of one that started later. Changing the channel forgets what the previous channel found and saw.
- While that release is newer than the installed build, About's **Updates** row shows "X.Y.Z available" until it is installed. The raised dot (`AttentionBadge`) appears on that row, on the sidebar's Settings gear, and on the start screen's **Settings** link until the Updates page has shown the release.
- Opening the page checks GitHub Releases. When a newer release exists, the page starts with "Velo X.Y.Z", "You have A.B.C", and **What's new**, followed by **Download update** or **Install update** and **Full release notes on GitHub**. Below that come the installed version, the channel and switches, and the check status with **Check for updates**.
- **What's new** lists every eligible release newer than the installed build, newest first, with a small version heading when there are several. Each release shows its New (Added), Changed, Fixed, Removed, and Security sections. The items come from the `<!-- velo-changes -->` … `<!-- /velo-changes -->` block of the release body, as plain text: links keep only their text, at most 12 items per release, each cut to 200 characters. A release without the block adds nothing; with none, the list is absent.

## Android boundaries

Velo owns its settings, previews, and links to system flows. Android owns permissions, font file selection, notification channels and presentation, battery exemptions, and APK install confirmation. The browser owns Tailscale authentication and administration, and the active input method owns the keyboard. None of those surfaces inherit the settings sheet's tokens.

## Source files

- [Settings navigation and categories](../app/src/main/java/io/github/yhzion/velo/ui/SettingsScreen.kt), [terminal font picker](../app/src/main/java/io/github/yhzion/velo/ui/TerminalFontPicker.kt), [background settings](../app/src/main/java/io/github/yhzion/velo/ui/BackgroundSettings.kt), [updates](../app/src/main/java/io/github/yhzion/velo/ui/AppUpdateSettings.kt)
- [Preferences](../app/src/main/java/io/github/yhzion/velo/AppController.kt), [key bar](../app/src/main/java/io/github/yhzion/velo/ui/KeyBar.kt), [key icons](../app/src/main/java/io/github/yhzion/velo/ui/KeyIcons.kt), [tap gestures](../app/src/main/java/io/github/yhzion/velo/ui/TapGestures.kt)
- [Remote dialogs](../app/src/main/java/io/github/yhzion/velo/ui/HostDialogs.kt), [dialog components](../app/src/main/java/io/github/yhzion/velo/ui/Dialogs.kt), [address input](../app/src/main/java/io/github/yhzion/velo/ui/RemoteAddressInput.kt)
- [Alert preferences](../app/src/main/java/io/github/yhzion/velo/PaneAlertStore.kt), [Android notifications](../app/src/main/java/io/github/yhzion/velo/PaneNotifications.kt), [background service](../app/src/main/java/io/github/yhzion/velo/BackgroundConnection.kt)
- [Metrics](../app/src/main/java/io/github/yhzion/velo/ui/theme/Metrics.kt), [palette](../app/src/main/java/io/github/yhzion/velo/ui/theme/Tokens.kt), [controls](../app/src/main/java/io/github/yhzion/velo/ui/theme/Controls.kt), [rows and buttons](../app/src/main/java/io/github/yhzion/velo/ui/theme/Components.kt), [fields](../app/src/main/java/io/github/yhzion/velo/ui/theme/Fields.kt), [overlays](../app/src/main/java/io/github/yhzion/velo/ui/theme/Overlays.kt)
