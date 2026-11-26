# Settings design

Velo's settings have five categories: Appearance, Keyboard, Notifications, Remotes, and About. Changes apply immediately unless a dialog asks for confirmation. The measurements below describe Compose layout, not physical screen pixels. See [Design tokens](design-tokens.md) for colors, typography, and reusable components.

## Panel and navigation

Settings opens as a modal without replacing the current screen. The modal respects safe areas. When both window dimensions are at least 600dp (the unfolded Fold), it is a sheet: the categories beside the open page, Appearance selected initially, and separate scroll positions; tapping outside the sheet closes settings, but tapping empty space within it does not. In smaller windows (a phone, the folded Fold) settings fills the window: the categories come first, and a category or a page under it takes their place. Changing category leaves its subpage. Back goes up one level or closes settings at the top; Close Settings always closes the whole panel.

The measurements follow the redesign's settings boards at Medium (canvas page "4 설정"; the blocks SetNav and Set*).

- **Folded and phone.** The categories' screen has a 72dp header: "Settings" in the screen title style and a 48dp close button with a light rim along its top. Each category is a card 62dp high (60 and a 1dp border, 16dp radius, 8dp apart, 16dp from the window's sides) with its icon tile, its name, a line saying what it holds, About's update dot and a chevron. A page has a 64dp header with a divider under it: Back (48dp), the page's title (18sp, SemiBold) and a 44dp glass close button. The page starts 4dp under the header, 16dp from the sides.
- **Unfolded.** The sheet keeps 8dp from the window, has a 20dp radius and a ring of the divider, and grows to at most 820dp. Its 64dp header holds "Settings" and the 44dp close button over a divider. The category column is 236dp wide on the sidebar's fill with a divider at its end; its rows are 60dp high, 4dp apart, inside 8dp of padding, and the open one has the selected pill and a SemiBold name. The page beside it has 24dp of padding at its sides and starts with its title (22sp, SemiBold, 18dp above it). A page under a category (Terminal Font, Tailscale, SSH key, Updates, the licenses) has a 44dp row in the accent over its title, naming the page Back returns to.
- **Category icons.** A 32dp tile with a 9dp radius in the category's tint (`CategoryTints`), with a white line icon (`ic_category_*`).

The opaque surface is `sheet` (the redesign's settings: chrome's fill with the sidebar's controls), and the category column takes the `sidebar` fill; menus and confirmations use `overlay`. A card that stands out of a page is `RaisedCard` (`colors.raised`). The interface stays dark regardless of Android appearance. A glass border or small glass control does not make the entire sheet translucent. Use `onAccent` and `onDanger` for text on their respective fills.

## Shared controls

Small, Medium (default), and Large use scale factors of 0.72, 0.85, and 1.0. The redesign gives its sizes at Medium: a settings row is 60dp at Medium, so 50.8 at Small and 70.6 at Large; a 20dp inset at Large becomes 14.4, 17, and 20dp. Typesetting stays at least 11sp, line height follows the resulting text size, and Android's font scale still applies. The terminal font size is independent.

| Control | Layout and behavior |
| --- | --- |
| Section and group | Section headings expose heading semantics. A group has no fill, a 1dp border, a 12dp radius at Medium and dividers between its rows from 16dp in; its rows reach the border. A page that leads with a summary (Tailscale, SSH key, About, Updates) puts it on a raised card. Neither headings nor group backgrounds act as buttons. |
| Labeled row | A `SettingRow` places the label and control side by side with at least 14dp between them at Medium; in a narrow window the control moves below. `SwitchRow`, `NavigationRow` and `ActionRow` are the other rows of a group. |
| Segmented choice | Radio choices have a minimum 48dp target. The selected cell uses `glassSelected` and `onGlass`. Choices wrap rather than truncating. |
| Switch | The entire row operates one switch (`SwitchRow`). The track is 51×31dp at Medium; position and color both indicate state. |
| Slider | A 48dp target with a 4dp track and 28dp thumb at Medium. Supports tapping, horizontal dragging, arrow keys, and accessibility value actions. |
| Button | Buttons have a minimum 48dp target; words alone and a row's 44dp button lay out at their drawn height and keep the target through hit testing. Primary actions use an accent fill; a destructive button on a page is a red outline, a removal in a row red words, and a destructive confirmation in a dialog a red fill. Navigation chevrons follow reading direction; terminal keys represent physical directions. |
| Text field | `VeloTextField` keeps its label visible after typing and reports validation errors below the capsule-shaped field. |
| Menu | Opens below its button, or above when space is limited, without dimming the page; a row whose menu is open stays marked. Width is 200–285dp at Large; long menus scroll. Back and outside taps dismiss without choosing. |
| Confirmation | Centered over a scrim, with 8dp outside margin and a maximum width of 386dp at Medium. Content scrolls while Cancel and the confirming button stay at its lower right. Cancel, Back, or an outside tap discards unconfirmed changes. |

## Appearance

- **Size** changes interface typography and spacing; **Pane style** selects Comfortable (default) or Compact.
- **Keep screen on while using Velo** is off by default, and changes the current activity's screen-on behavior.
- **Interface** holds Size, Pane style, Overview (Row or Grid), where minimized windows sit, and keeping the screen on.
- **Highlight color** shows the selected color and a picture of two panes, the left one with the edge. The swatches sit four to a row. The presets are Accent (default), Blue, Green, Yellow, Cyan, Magenta, and Text. **Custom** remembers its last color, even after a preset is selected. The saturation and brightness area, hue slider, and hex field apply changes immediately. Hex accepts six digits with or without `#`; low contrast against black produces a warning rather than rejecting the color.
- **Font** shows the current family and opens **Terminal Font**. **Font size** accepts integers 8–24, with a default of 13. The sample, a few prompt lines on the terminal's black, uses the selected terminal font at the same pixel size as the terminal. The slider saves the value as it moves and sends the final size to the terminal at the end of the gesture.

### Terminal Font

Search matches font names by case-insensitive substring; **Monospaced** and **All fonts** filter without changing the selected font. The sample, **Import font…** action, and results scroll together, even in a short window with the keyboard open or enlarged Android text. Select a TTF, OTF, or TTC file in the Android picker. A successful import clears the search, chooses the appropriate filter, and applies the first imported font. A failure shows an error without changing the selection. A sample of the chosen font comes before **Import font…**. The list groups fonts as **Built in**, **On this device**, and **Imported**, each name in its own font; a note under it says when it draws Korean or was imported. The selected row has a background and check. Loading disables selection and import, and the empty state changes from **Loading fonts…** to **No matching fonts** once loading finishes.

## Keyboard

- **Key bar** shows the bar as the terminal draws it, then **Order** and the keys, independently selectable under Modifiers, Editing, and Navigation. With two or more keys selected, **Edit order** replaces the chips with a list whose arrow controls move one row at a time and save immediately. **Done** exits editing. The initial order is `/`, Ctrl, Esc, Tab, Left, Down, Up, Right, Backspace. Re-adding `/` puts it first; other keys are added before a trailing Backspace or at the end.
- **Keep keyboard open** defaults to off and **Haptic feedback** to on. These switches are independent.
- **Use tap gestures** defaults to off. When on, Double tap defaults to Tab and Triple tap to Enter. Each can send None, Tab, Enter, Esc, Left, Right, Up, or Down whether or not the key is in the key bar. Assigning Enter shows a warning about sending current terminal input. Double and triple taps preserve the IME's prior open or closed state; a single tap that opens the IME first waits for a possible second tap.

## Notifications

- **Show background notifications** is on by default. If Android blocks an enabled alert type, Velo offers the permission prompt or a link to the appropriate app or channel setting. It rechecks when that flow ends.
- **Play activity sounds** is on by default. Volume ranges from 0% to 200%, with a 100% default. Turning off all activity sounds hides the slider but preserves per-kind choices. Preview and playback are subject to system sound mode, Do Not Disturb, and channel settings.
- **Stay connected in the background** is initially on, subject to the first-connection choice. When enabled, it explains the quiet running notification and shows the battery optimization action or exemption status. Allowing background connections does not grant notification permission.

Each alert kind is a group headed by its name and colour, with separate **Background notification** and **Activity sound** switches. The per-kind picker, a small menu beside the sound's switch, appears when that sound is enabled and offers Attention, Warning, Error, Success, Ready, and Loading; a pick plays it. **Background** shows the battery as **Unrestricted** with a check, or a row that asks Android to allow background running.

| Alert kind | Notification / sound defaults | Sound preset |
| --- | --- | --- |
| Needs your response | On / on | Attention |
| Work completed | On / on | Success |
| Work failed | On / on | Error |
| Work started | Off / off | Loading |

## Remotes

- **This Device → Tailscale** controls Velo's optional embedded node, which defaults to off. Its row shows the node's state with a coloured dot. The page's card shows the state on a chip and the node's switch, whose line says what turning it off does, or why the node is paused or failed. **This node** shows the name, with **Rename**, and, when connected, the addresses, each with a copy button. Login opens a browser; if none can open the URL it can be copied. Renaming the node or logging out requires confirmation because these actions interrupt connections. Other devices are managed in the [Tailscale admin console](https://console.tailscale.com/admin/machines).
- **Saved** lists each remote's name and address in saved order, with **Add Remote…** as the group's last row. The menu is bound to that remote's identity, not the active connection. **Rename…** trims the name and leaves the original intact if canceled. **Remove Remote…** confirms removal from this phone without deleting server sessions.
- **Add Remote…** accepts a label and an address typed as a hostname, URL, SSH login, or local socket path; the address takes the keys first, and a box under the fields shows the examples `https://rex.example.ts.net` and `ssh://user@rex.example.ts.net`. The QR button sits in the address field. QR codes must hold valid HTTPS URLs; scanning fills the address but does not save the remote until Add is confirmed. Invalid addresses, rejected codes, and duplicates use separate messages. If camera permission is denied, enter the address manually or open Android settings.
- **SSH key**, whose row shows the key's type and fingerprint, opens a separate subpage for the phone's SSH identity. Its card shows the type, fingerprint and line, **Copy install command**, **Copy**, and **Share**. Each imported key shows **Remove**; a tap on it copies its line or copies or shares its install command. **Import a key…** is the imported group's last row and says why an import or removal failed. **Replace key…** says under it what it costs.

## About

About opens with Velo's card: its mark, name, version, and what it is. The app name, `versionName`, and `longVersionCode` come from Android's `PackageManager`. **Updates** opens a subpage for channel selection, automatic checks, background update notifications, and manual checks. Updates compare Android version codes, not tag strings. After an APK download passes verification, installation is a separate action with Android's own confirmation.

### Updates

- **Check for updates automatically** is on by default. When Velo comes to the foreground, it checks the selected channel if the last successful check is at least 12 hours old. The check runs in the background and never blocks the interface. A failure is silent and waits 15 minutes before the next foreground attempt.
- **Notify me about updates** is off by default and independent of automatic checks. It schedules a periodic background check that posts an Android notification for a new release.
- Every successful check, whether automatic, background, or manual, remembers the newest eligible release it found. A check that started earlier never replaces the result of one that started later. Changing the channel forgets what the previous channel found and saw.
- While that release is newer than the installed build, About's **Updates** row shows "X.Y.Z available" until it is installed. The raised dot (`AttentionBadge`) appears on that row, on the sidebar's Settings gear, and on the start screen's **Settings** link until the Updates page has shown the release.
- Opening the page checks GitHub Releases. When a newer release exists, the page starts with "Velo X.Y.Z", "You have A.B.C", and **What's new**, followed by **Download update** or **Install update** and **Full release notes on GitHub**. Below that come the installed version, the channel and switches, and the check status with **Check for updates**.
- **What's new** lists every eligible release newer than the installed build, newest first, with a small version heading when there are several. Each release shows its New (Added), Changed, Fixed, Removed, and Security sections. The items come from the `<!-- velo-changes -->` … `<!-- /velo-changes -->` block of the release body, as plain text: links keep only their text, at most 12 items per release, each cut to 200 characters. A release without the block adds nothing; with none, the list is absent.

**Open source licenses**, under Updates, lists Velo's own MIT license, the full NOTICE, and the third-party software the APK ships, grouped as Velo, Terminal core, Networking, Android, Fonts, and Artwork (the program logos and the Tailscale logo from Simple Icons, whose page shows the NOTICE's section on them). Each row shows the version under the name and the license as a badge. A row opens a page with its badges, a **Project home** link, and the bundled license and notice texts in JetBrains Mono, in a frame, selectable. The texts are the files `copyNotices` packages under `assets/notices/`, so the page works offline. Long notices such as the NDK's load a block at a time. **Go modules** lists each linked module; a module opens its own page, and Back returns to the module list. The list is the manifest in `OpenSourceLicenses.kt`; its unit test checks that every listed text exists under `licenses/` and that every file there is listed or is a byte-identical copy of a listed one.

## Android boundaries

Velo owns its settings, previews, and links to system flows. Android owns permissions, font file selection, notification channels and presentation, battery exemptions, and APK install confirmation. The browser owns Tailscale authentication and administration, and the active input method owns the keyboard. None of those surfaces inherit the settings sheet's tokens.

## Source files

- [Settings navigation and categories](../app/src/main/java/io/github/yhzion/velo/ui/SettingsScreen.kt), [what the pages read and change](../app/src/main/java/io/github/yhzion/velo/ui/SettingsHost.kt), [Appearance](../app/src/main/java/io/github/yhzion/velo/ui/SettingsAppearance.kt), [Keyboard](../app/src/main/java/io/github/yhzion/velo/ui/SettingsKeyboard.kt), [Notifications](../app/src/main/java/io/github/yhzion/velo/ui/SettingsNotifications.kt), [Remotes](../app/src/main/java/io/github/yhzion/velo/ui/SettingsRemotes.kt), [Tailscale](../app/src/main/java/io/github/yhzion/velo/ui/SettingsTailscale.kt), [SSH key](../app/src/main/java/io/github/yhzion/velo/ui/SshKeySettings.kt), [About](../app/src/main/java/io/github/yhzion/velo/ui/SettingsAbout.kt), [terminal font picker](../app/src/main/java/io/github/yhzion/velo/ui/TerminalFontPicker.kt), [background settings](../app/src/main/java/io/github/yhzion/velo/ui/BackgroundSettings.kt), [updates](../app/src/main/java/io/github/yhzion/velo/ui/AppUpdateSettings.kt), [open source licenses](../app/src/main/java/io/github/yhzion/velo/ui/LicenseSettings.kt) and [their manifest](../app/src/main/java/io/github/yhzion/velo/OpenSourceLicenses.kt)
- [Preferences](../app/src/main/java/io/github/yhzion/velo/AppController.kt), [key bar](../app/src/main/java/io/github/yhzion/velo/ui/KeyBar.kt), [key icons](../app/src/main/java/io/github/yhzion/velo/ui/KeyIcons.kt), [tap gestures](../app/src/main/java/io/github/yhzion/velo/ui/TapGestures.kt)
- [Remote dialogs](../app/src/main/java/io/github/yhzion/velo/ui/HostDialogs.kt), [dialog components](../app/src/main/java/io/github/yhzion/velo/ui/Dialogs.kt), [address input](../app/src/main/java/io/github/yhzion/velo/ui/RemoteAddressInput.kt)
- [Alert preferences](../app/src/main/java/io/github/yhzion/velo/PaneAlertStore.kt), [Android notifications](../app/src/main/java/io/github/yhzion/velo/PaneNotifications.kt), [background service](../app/src/main/java/io/github/yhzion/velo/BackgroundConnection.kt)
- [Metrics](../app/src/main/java/io/github/yhzion/velo/ui/theme/Metrics.kt), [palette](../app/src/main/java/io/github/yhzion/velo/ui/theme/Tokens.kt), [controls](../app/src/main/java/io/github/yhzion/velo/ui/theme/Controls.kt), [rows and buttons](../app/src/main/java/io/github/yhzion/velo/ui/theme/Components.kt), [fields](../app/src/main/java/io/github/yhzion/velo/ui/theme/Fields.kt), [overlays](../app/src/main/java/io/github/yhzion/velo/ui/theme/Overlays.kt)
