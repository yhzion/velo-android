# Changelog

What changes for Velo users in each release. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases are listed newest first. Each release's items also appear in Velo before you update; [releasing](docs/releasing.md#write-the-changelog) explains how entries are added.

## Unreleased

### Added
- A short splash plays when Velo starts. With animations turned off, it shows its last picture without moving.
- When a computer you picked takes a while to open, the start screen shows that it is opening, with Cancel.
- On an unfolded Fold the sidebar can stay beside the panes: choose Dock Sidebar Beside Panes in the command palette. Velo remembers the choice.
- The rail shows which tab of the session you are on, such as 2 / 4.
- Open source licenses lists the program logos on tab tiles (Simple Icons) with their licenses.

### Changed
- Settings and dialogs use refreshed colours, cards and controls.
- The start screen shows its three steps with the current one's card, side by side on the unfolded Fold.
- Tabs in the sidebar show their program's logo, stacked for a tab split into panes, with a dot for a result you have not seen yet.
- The sidebar, rail, key bar and the line under the panes use refreshed colours and spacing, and every key of the key bar fits on a phone's screen.
- About shows Velo's card, Updates and the licenses; Updates shows what a new version brings above its settings and when Velo last checked.
- Appearance shows the focused pane's edge on two small panes above its colours, and a sample of the terminal's font under its size.
- Terminal Font sorts fonts into built in, on this device and imported, shows the chosen font in a sample, and says which fonts draw Korean or were imported.
- On a phone and a folded Fold, Settings fills the screen and lists its categories with what each holds; on an unfolded Fold the categories stay beside the open page, and a page under a category has a row back to it.

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
