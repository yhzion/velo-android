# Changelog

What changes for Velo users in each release. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases are listed newest first. Each release's items also appear in Velo before you update; [releasing](docs/releasing.md#write-the-changelog) explains how entries are added.

## Unreleased

### Fixed
- With a keyboard in use, the screen reader no longer jumps back to a control higher up when a list scrolls, so Notifications settings can be read through to the Work started alerts.

## 2.15.0 - 2026-11-28

### Added
- While a pane is full screen, the command palette offers Full Screen Pane for each other pane of the tab, so you can switch the full screen pane from a hardware keyboard.
- An image inserted from the keyboard, such as one from Gboard's clipboard strip, is now sent to the remote, and its path is typed into the pane, as Paste does for a copied file.

### Changed
- The command palette's Focus Pane and Full Screen Pane commands name each pane as the minimap's picker does, such as Pane 2 · zsh, so a pane without a title is no longer just Pane 2.
- A pane whose program Velo has not heard of yet is named Terminal in the minimap's picker and the command palette, instead of Tab or an ID the server gave it, such as Block 9ac42573.

### Fixed
- A floating pane made full screen from the menu now shows Full Screen and the minimap in the caption. The minimap draws floating panes over the tab's layout, so you can pick them or swipe to them too.
- With seven or more panes side by side, the full screen minimap's picker no longer cuts off the last ones. It scrolls instead, keeps each pane big enough to tap, and opens on the full screen pane.
- In the full screen minimap's picker, a pane that a floating pane covers, or leaves too narrow to tap, now also shows in a row under the map, where you can tap it.
- The full screen minimap shows which pane is full screen even when a floating pane lies over it.
- The full screen minimap's picker no longer covers the caption on a phone held sideways. It fits the room above the caption, or opens under it where there is more room, and scrolls.
- A pane no longer stays blank, and Velo no longer stops responding, after you come back to it from another app while the keyboard was up.
- The front of a tab's tile in the sidebar shows the program of the pane you have selected, such as zsh beside a Claude Code pane. A report from another pane still puts its dot on the tile.
- A screen reader says each menu button in settings once, such as Sound: Attention, instead of also saying the choice shown. Rows such as Channel no longer say their name again before the button.
- A screen reader also says the alert rows in Notifications settings, the keys in Edit order, Compose's attachment and the SSH key's buttons once, without the words shown.
- A screen reader says each part of a sidebar tab row once: the program and panes, the tab's name, its state and folder. SSH details' Forget buttons are also said once.
- A screen reader says the release found on About's Updates row once, instead of also saying it as the row's state.
- A pane whose drawing gets stuck no longer makes Velo stop responding when you leave the app or the pane closes, and the pane draws again when you come back.
- Upload and error notices now show a whole line in landscape with the keyboard up, and sit under the sidebar's Filter field instead of over it and the rail.
- A screen reader says Compose's Draft field once, instead of saying its name twice.
- In landscape with the keyboard up, two or three notices at once now sit side by side above the key bar, so at the default text size none covers it or runs under the keyboard.
- Notices in the sidebar no longer cover its Filter field with the keyboard up, and a screen reader reads a notice once when the sidebar opens, closes or the keyboard moves it. Error notices say so.

## 2.14.0 - 2026-11-27

### Added
- Spread two fingers fast on a pane to make it full screen, and pinch in fast to split the tab again. An outline shows where the pane goes before you lift; slow pinches still change the text size.
- While a pane is full screen, a minimap at the end of the caption shows the tab's panes and which have news. Tap it to pick another pane, or swipe across it for the next one.

### Changed
- The key bar's keys are now rounded keys of one width that share the bar's whole width, as on a touch bar; a key turns a step lighter while you hold it.

### Fixed
- A screen reader names the keys on the key bar and the Keyboard settings chips in full words: Control, Escape, Page Up, Page Down, Minus, Pipe, Slash and Tilde.
- The This Device dot in the sidebar tells a screen reader the Tailscale settings page's word for the node's state, such as Connected, instead of Node Running.
- The This Device dot also turns orange and says Paused while Android holds the network back, and is blue, not orange, while the node starts, as on the Tailscale settings page.
- The tap gesture menus in Keyboard settings now also say Escape to a screen reader, as the key bar does.
- The Tailscale dot in Remotes settings has the Tailscale settings page's colour in every state, such as blue while the node starts, as the sidebar's dot does.
- While Android holds the network back, the line under This Device in the sidebar says Paused, as its dot does, instead of Running.
- A screen reader says the state of This Device in the sidebar once, in its dot's word, such as Connected, instead of twice.

## 2.13.0 - 2026-11-27

### Added
- Paste now sends an image or file copied in another app, such as a browser, the gallery or a file manager, to the remote and types its path into the pane, as Compose's attachment does.

### Changed
- Swiping through the tab overview is smoother on Android 14 and later: while the cards move, each shows a picture of its panes, and the phone puts the screen together with much less work. The panes are live again as soon as the cards stop.

### Fixed
- A tab running OpenCode is named after its conversation, without the "OC | " that OpenCode puts before the title.

## 2.12.5 - 2026-11-27

### Added
- A tab running OpenCode now shows OpenCode's tile in the sidebar, under the panes, in the overview and on the rail's wheel.

### Changed
- Velo's own code is now compiled ahead of time with the app's profile, as the libraries' code already was. Once Android has compiled the app (usually overnight while charging), screen updates take a few percent less of the main thread's time, and rebuilding the screen after a change takes about a fifth less.

### Fixed
- The command Velo gives to compare a host's SSH key fingerprint now also works in Termux, which keeps its host keys in its own folder instead of /etc/ssh.

## 2.12.4 - 2026-11-27

### Changed
- When Velo finds no Rex server on the tailnet, the start screen now also says to run rex server tailscale on and to turn on the tailnet's MagicDNS and HTTPS certificates.
- When a Rex server lets in only its owner, Velo now says the owner must allow tailnet members, on the start screen and when the server refuses the connection.
- A terminal whose program only changes its title, rings the bell or switches a mode no longer redraws its screen, so the phone does less work while an agent updates its title many times a second.
- A new title no longer rebuilds the whole screen: only the tab's name is drawn again where it is shown. While an agent updates its title, the app's main thread does about a quarter less work.
- Drawing new characters, such as a screen of Korean text or the text at a new size, no longer holds up the terminal: output keeps arriving and the screen keeps answering a finger while the characters are drawn.
- Switching tabs shows the text sooner: characters one tab has drawn are not drawn again for another, or for the same tab when you come back to it. On a slow phone, coming back to a tab drew its text in about 100 ms, and now takes a few.
- After Android closed the app in the background, tabs that were not on screen fill in within a few seconds of the server letting go of the old connection, instead of up to 15 seconds later. Until then they and their overview cards still show an empty terminal, for about two minutes; that wait needs a change on the server.

### Fixed
- A tab whose report names an agent keeps that agent's tile after you have seen the report. Only its dot goes away, so a Claude tab no longer turns back into node once you have looked at it.
- A tab running Gemini CLI now shows Gemini's tile instead of node's, and a search for Gemini in the sidebar finds it.

## 2.12.3 - 2026-11-27

### Fixed
- In the caption under the panes, the tab's tile sits on the title's line, with the usual small gap before the title.
- The tab's name under the panes now follows the UI size you choose in Settings. Pinching a pane no longer makes it larger or smaller.
- The overview's cards and the rail's tab wheel show each program with the same tile as the sidebar, and a tab that waits for you shows its orange dot in the overview too.
- omp and pi tabs show their programs' own marks on their tiles instead of letters.

## 2.12.2 - 2026-11-26

### Security
- A notification you can answer marks its text as the program's report and shows the last lines on the pane's screen. An answer is not sent once another program is in front in the pane.

## 2.12.1 - 2026-11-26

### Security
- A server can no longer close Velo, or make it read memory it should not, by sending a damaged certificate or handshake message when you connect.
- A saved SSH password is sent only to the host whose key accepted it. Forgetting a host key in SSH details also forgets the passwords saved for that host.
- A link that a program in the terminal hides under other text, or under blank space, now shows where it goes and asks before it opens.
- While Velo waits for you to log in to Tailscale, the login address no longer appears in the device log, where someone reading the log could open it before you and log in to their own tailnet.
- A file you tap in the terminal can no longer fill your phone: Velo stops the download once the file is larger than the server said it would be, or larger than 256 MB, and tells you so.
- A tap on a link to the remote's own localhost asks before it forwards a new port to this phone, and a forwarded port serves at most 64 connections at a time.

## 2.12.0 - 2026-11-26

### Added
- On a wide enough screen, such as the unfolded Fold8 held upright, a pin button at the top of the sidebar docks it beside the panes or undocks it.

### Changed
- Settings shows the Tailscale logo, as the start screen does.

### Fixed
- Korean, Chinese and Japanese characters and emoji under the block cursor show in full, not cut in half.

## 2.11.1 - 2026-11-26

### Fixed
- While Velo waits for you to log in in the browser, Cancel takes you back to the login step.
- English typed on the on-screen keyboard reaches the terminal letter by letter, so completion in programs such as Claude Code works while you type.

## 2.11.0 - 2026-11-26

### Added
- A short splash plays when Velo starts. With animations turned off, it shows its last picture without moving.
- When a computer you picked takes a while to open, the start screen shows that it is opening, with Cancel.
- On an unfolded Fold the sidebar can stay beside the panes: choose Dock Sidebar Beside Panes in the command palette.
- The rail shows which tab of the session you are on, such as 2 / 4.

### Changed
- Velo has a refreshed look: new colours, cards, controls and spacing on every screen. Menus no longer dim the screen.
- Tabs in the sidebar show their program's logo, stacked for a tab split into panes, with a dot for a result you have not seen yet.
- Every key of the key bar fits on a phone's screen.
- The start screen shows its three steps with the current one's card, side by side on the unfolded Fold.
- On an unfolded Fold, Settings keeps its categories beside the open page.
- Updates shows what a new version brings and when Velo last checked. Open source licenses lists the program logos.
- Add Remote… is the last row of the saved remotes, and Add Remote scans a QR code from a button in the address field.

### Fixed
- License texts in Open source licenses flow to the width of the screen instead of breaking lines in the middle.

## 2.10.1 - 2026-11-25

### Changed
- Codex tabs show a C on their white tile instead of an X.

## 2.10.0 - 2026-11-25

### Added
- A dot on Settings tells you when an update is ready.
- Velo shows what changed in a new version before you update.
- Settings, About, Open source licenses lists the software Velo is built with and shows each license, offline.

### Changed
- Velo checks for updates automatically. You can turn this off in Settings, About, Updates.

## 2.9.19 - 2026-11-25

### Fixed
- Cells keep the proportions of their font, so Hangul in D2Coding and in imported fonts with narrow letters no longer looks flat.

### Changed
- Releases are now built and published by GitHub Actions.

## 2.9.18 - 2026-11-25

### Added
- The first public build of Velo.
