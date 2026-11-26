# Changelog

What changes for Velo users in each release. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases are listed newest first. Each release's items also appear in Velo before you update; [releasing](docs/releasing.md#write-the-changelog) explains how entries are added.

## Unreleased

### Changed
- Settings shows the Tailscale logo, as the start screen does.

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
