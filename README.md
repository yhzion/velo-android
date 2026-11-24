Velo: an unofficial Android client for Rex (terminal blocks only).

![The large black Velo wordmark on the left; a rainbow-coloured baby velociraptor in black sunglasses leans in from the right, one hand at the sunglasses temple, a green terminal prompt glowing on the left lens.](docs/brand/velo-hero.webp)

[![Stable releases](https://img.shields.io/badge/releases-stable-387344)](https://github.com/yhzion/velo-android/releases)
[![APK downloads](https://img.shields.io/badge/download-APK-60418C)](https://github.com/yhzion/velo-android/releases/latest)
[![Requires Android 11 or later](https://img.shields.io/badge/Android-11%2B-287455)](#open-your-first-session)
[![Release architecture: ARM64](https://img.shields.io/badge/architecture-ARM64-326789)](#open-your-first-session)

I built Velo because I wanted to use Rex away from my desk. The name is the velociraptor: a small, fast dinosaur — a terminal that fits in your pocket. Look for the velociraptor in sunglasses on your home screen. Velo is unofficial, noncommercial, and not affiliated with Superlogical. Rex is only the product Velo connects to; Superlogical does not make, support, or endorse this independent Android client.

## What you can do

- Switch between sessions and tabs across multiple Rex servers from the sidebar.
- Unfold your phone to see terminal panes side by side. Drag the divider to resize them.
- Use the row above the on-screen keyboard to enter special keys.
- Choose which panes notify you when a task finishes, needs a reply or fails. Tap a notification to return to that pane.

## Open your first session

You need a 64-bit ARM device running Android 11 or later and a computer running a Rex server. The phone connects to the server; it does not run one.

1. Once a stable APK is published, download `velo-<tag>-arm64-v8a.apk` from [GitHub Releases](https://github.com/yhzion/velo-android/releases/latest) and install it on your phone. Until then, use the source build instructions below.
2. On the computer running Rex, run `rex server tailscale pair` to display a QR code.
3. Connect your phone to the same Tailscale network. In **Velo**, choose **Add by QR code or address** and scan the code. You can also enter an address directly, such as `https://rex.example.ts.net`, `ssh://user@rex.example.ts.net`, or a local socket path.
4. Open the server and select the session and tab you want to continue.

## Update the app

If you have Velo v2.9.17 or an earlier build tied to the old private repository, download the first public APK from [Velo's releases](https://github.com/yhzion/velo-android/releases) and install it as an update once. **Do not uninstall the existing app.** An in-place upgrade requires the same package name and signing certificate and a higher version code. If Android rejects the update, stop rather than deleting saved servers, settings, or SSH keys. The public build checks the new repository for subsequent updates.

Once releases are available, open **Settings → About → Updates** to check for new versions or enable update notifications. Stable (the default) offers stable Velo releases only. Preview also includes preview builds; Edge adds experimental builds on top of both. Switching channels never downgrades the installed app.

Background checks run roughly every 12 hours when connected. They never download an APK. To update Velo, choose **Download update**, then **Install update**. Android may ask whether this app can install updates. Velo checks the APK digest, package, version and signing certificate before opening Android's confirmation screen.

## Terminal fonts

Velo defaults to stock JetBrains Mono and uses D2Coding for Hangul. Basic Powerline symbols still work, but Nerd Font icons are no longer bundled. On upgrade, a saved JetBrains Mono choice switches to the stock font; a saved Jetendard choice uses D2Coding.

To restore Nerd icons in the terminal, import your own font once on each device:

1. Obtain a Nerd Font Mono from its publisher. Extract the download and save the Regular `.ttf` file on your device (for example, `JetBrainsMonoNerdFontMono-Regular.ttf` in Downloads).
2. In Velo, open **Settings → Appearance → Font**, then tap **Import font…**.
3. In the Android file picker, open **Downloads** (or the folder where you saved the file) and tap the font file, not the ZIP. Velo copies and selects the font. To switch back to it later, open **Font** and select the imported row.

The picker takes one TTF, OTF, or TTC file at a time. A Regular TTF supplies one face; Android handles bold and italic when those designs are missing. If you import the separate Bold, Italic, and Bold Italic TTFs, each becomes its own choice and the last import is selected. Velo cannot combine them. If you already have a TTC that holds all four faces under the same embedded family name, pick that single TTC instead to use all four designs. Imported glyphs are available when the imported font is selected; they are not a fallback for other font choices.

## QR scanning and privacy

ZXing reads pairing QR codes on your device. The scanner does not upload camera frames, decoded results, or scanner usage metrics. Velo uses a valid HTTPS address from the code to save and connect to that server. You can enter a server address instead of granting camera access.

## Build from source

On x86-64 Linux, use JDK 25, Gradle 9.6.0, Zig 0.17.1, and the [Go version pinned by the project](native/tailscale/go.mod). Install the Android SDK and NDK versions listed in the [release workflow](.github/workflows/release.yml), then set `ANDROID_HOME` to your SDK directory.

```sh
gradle :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

[Native architecture](docs/native-architecture.md) · [Signing and releases](docs/releasing.md) · [Issue tracker](https://github.com/yhzion/velo-android/issues)

## License

Velo's original code and assets are licensed under the [MIT License](LICENSE). Third-party components in the source tree and APK retain their own licenses and terms; see [NOTICE](NOTICE).

## Acknowledgements

Velo exists because of [Rex](https://www.superlogical.com/) and
[Ghostty](https://ghostty.org/). Thank you to Mitchell Hashimoto and the
Rex team for building them, and for kindly allowing this unofficial client
to be shared.

Velo is not affiliated with or endorsed by the Rex authors.
