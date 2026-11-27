# Velo: native Android client

Velo is an independent Android client for Rex, with layouts for phones and foldables.
It connects directly to the Rex server, without a bridge process.

## Split of responsibilities

| Layer | Language | Owns |
|---|---|---|
| `native/` → `libvelocore.so` | Zig 0.17.1 (+ C ABI of libghostty-vt 1.0.1, statically linked) | TLS (std.crypto.tls, Android system CAs), WebSocket client, Rex control/data protocol, session/window/layout/block state, events, reconnect/resume, terminal emulation, Kitty images, OpenGL ES 3 rendering, key encoding, selection/copy text |
| `app/` | Kotlin + Jetpack Compose | Activity, Compose screens (start screen, add by QR scan, sidebar, right rail, overview, menus, settings), SurfaceView hosting, forwarding touch/keyboard/IME events, persisting settings and the remotes saved on the phone (with each one's reconnect token and last session), one core per saved remote, the app's Tailscale node, glyph rasterization (Android fonts) on request |

Kotlin renders the state the core publishes and sends user intents back. Rex protocol I/O and
parsing run on core-owned Zig threads; Android-specific services such as the app's release checker
run separately in Kotlin.

## Connection (implemented in Zig)

- Address: the server's built-in Tailscale HTTPS URL, e.g. `https://rex.example.ts.net`.
  `rex server tailscale pair` shows it as a QR code holding only the URL. The server admits
  callers by Tailscale identity. The app carries its own tailnet node (libtailscale, see
  "The phone and its remotes"), so it needs no VPN app; while that node runs, every core
  dials through it, otherwise through the system network. At app start the node is
  started first and a connection made while it starts dials only when the node runs
  (`redialOn` in `Tailnet.kt`), or when it stops starting without running, or after 10 s:
  on the system network a tailnet name may not resolve, and the row would read
  "Connection failed" for the node's first seconds. Until then the row reads "Connecting…".
- TLS: `net/TlsClient.zig`, a patched copy of `std.crypto.tls.Client` (every certificate
  that signs another must be a CA within its path length, not counting self-issued
  intermediates; the nameConstraints of every CA in the path, the trusted one included, hold
  for dNSName, iPAddress and directoryName; the server's certificate must allow TLS server
  use in extendedKeyUsage, and no presented certificate may carry an unknown critical
  extension; IP hosts get no SNI and match iPAddress names only; every length a server
  sends, in a certificate or a handshake message, is compared with the bytes that are
  there, because the release core is built ReleaseFast, without the safety checks
  upstream's parser relies on). The roots are every CA of
  `AndroidCAStore`, system and user, which the app copies as PEM into `no_backup/user-cas`
  on every start (`TrustedCas.kt`): the store leaves out the system CAs the user turned off
  and reads the updatable store of Android 14 and later, the app may not read
  `/data/misc/user/0/cacerts-added`, and Android writes DER there. When the store cannot be
  read or holds no system CA, `/system/etc/security/cacerts` stands in: the link
  `no_backup/system-cas` then points at it. The core gets both paths at once and reads them
  when it opens a connection; the copy runs on a thread of its own, and the app's first
  connect waits until it ends, so no connection reads a directory being written. A store that
  does not answer in 5 s gives way: the system directory alone stands in until the store
  answers, and its CAs then replace the stand-in for later connections. SNI and hostname
  verification on.
- WebSocket (RFC 6455) client in Zig: HTTP/1.1 upgrade, masked client frames,
  text/binary/ping/pong/close, fragmentation, no compression.
- Control: `wss://rex.example.ts.net/api/control`, subprotocol `rex.control.v1`, one JSON object per
  text message. Hello (< 512 bytes): `{"type":"hello","api_revision":18,"client":{"kind":
  "app","platform":"other","name":"Velo","version":"…"}}` plus `reconnect_token` when
  resuming. First request `session.bootstrap` with `event_scope:"session"` and
  `data_tickets:[{creator_name:"com.superlogical.terminal",data_ref_id:"terminal",
  subprotocol:"rex.data.terminal.v1"}]`.
- Data: `wss://rex.example.ts.net/api/data`, subprotocol `rex.data.terminal.v1`. HELLO `0x01`+16 ticket
  bytes (base64url, no padding). WELCOME 32 bytes: `0x81`, 16-byte token, 3 zero bytes,
  u32 LE resume ms, u64 LE nonzero nonce. REJECT `0x82`+reason. RESUME `0x02`+token+u64 LE
  consumed byte count.
- Stream: Ghostty snapshot, then program output with Rex APCs
  `ESC _ rex1;<nonce>;<json> ESC \` (`resize`, `theme`, `child_exited`). APCs with another
  nonce are program output.
- Full protocol reference: `GET https://rex.example.ts.net/llms.txt` (API revision 18).

## JNI contract

One Kotlin class `io.github.yhzion.velo.core.VeloCore` with `@JvmStatic external` methods.
Handles are opaque `Long`s. Every call returns quickly; work happens on core threads.

### Kotlin → core

| Method | Meaning |
|---|---|
| `start(listener: CoreListener, caDirs: Array<String>, fontScaleDp: Float): Long` | Create the core. |
| `connect(core, url: String, reconnectToken: String?)` | Pair/connect to a server URL. |
| `disconnect(core)` / `destroy(core)` | |
| `selectSession(core, sessionId: String)` | Attach to a session. Called before the first `connect`, it names the session the connection attaches, so the server does not pick one (the app does this with the remote's saved session: a shown terminal is taken and sized). |
| `command(core, json: String)` | User intent as JSON, e.g. `{"op":"split","block":"…","dir":"right"}`, `{"op":"float",…}`, `{"op":"zoom",…}`, `{"op":"close",…}`, `{"op":"focus",…}`, `{"op":"newWindow"}`, `{"op":"selectWindow","window":"…"}`, `{"op":"sendText","block":"…","text":"…"}`. The core maps intents to Rex methods. Two ops report what happened to a pane: `{"op":"fit","block":"…"}` (its surface size has settled, or a pinch on it ended: the terminal is fitted to the pane again) and `{"op":"touch","block":"…"}` (a finger went down on it: the terminal follows this device). Ops that name a session act on any session of the server: `{"op":"newWindow","session":"…"}` and `{"op":"selectWindow","session":"…","window":"…"}` select that session first when it is not the selected one; `{"op":"closeWindow","session":"…","window":"…"}`, `{"op":"closeSession","session":"…"}`, `{"op":"renameSession","session":"…","label":"…"}` and `{"op":"renameWindow","session":"…","window":"…","label":"…"}` select nothing (an empty label restores the server's name). A close whose target is already gone reports no error. `{"op":"detach"}` parks the core: no session selected, no panes, no tabs, until `selectSession` or an op that selects one. `{"op":"fence","prefix":"…"}` (debug builds, before `connect`; it replaces the prefix, and no prefix or `""` is no fence) leaves the core only the sessions whose label starts with the prefix and the ones it created itself: no other session is in the state, nothing attaches or follows one, `selectSession` and ops that name one do nothing, and no request names one, also not a session made later or renamed out of the prefix. With a fence the session named by `selectSession` before `connect` is attached once the session list has arrived, if it is one of them. |
| `key(core, block: String, keyCode: Int, metaState: Int, unicode: Int, action: Int)` | Android key event; the core encodes it (libghostty-vt key encoder) and writes to the terminal. |
| `paneSurface(core, block: String, surface: Surface?, widthPx: Int, heightPx: Int)` | Attach/detach the render surface for a pane. `null` detaches. The first surface of a pane takes its terminal for this device and fits the terminal to the pane; a later one only replaces the surface (send `fit` once the size has settled). |
| `paneScale(core, block: String, scale: Float, live: Boolean)` | Pinch zoom. Local while the pinch lasts; `fit` after it ends resizes the terminal. `live` while the pinch still changes the scale: the pane keeps the glyphs it has and stretches them to the cells. A scale that is not live (the pinch's end sends its last scale again so, and a text size command is one) draws them again at that size, once. |
| `panePan(core, block: String, dxPx: Float, dyPx: Float, xPx: Float, yPx: Float)` | A finger drag pans an oversized grid, then scrolls history. A live TUI receives wheel events at the touched cell if it tracks the mouse, or cursor keys if it enables alternate scroll. |
| `panePreview(core, block: String, surface: Surface?, widthPx: Int, heightPx: Int)` | `paneSurface` for a preview (the tab overview): the surface draws the pane exactly as a shown pane's surface does, but the pane is not shown. Nothing is taken and no size is asked for, also not by `fit`, `touch`, a setting or typed input for that block (typed input is still written to the terminal). `null` detaches, as `paneSurface` does. A pane has one surface: `paneSurface` or `panePreview` with the `Surface` it already draws to switches between shown and preview in place (the renderer and its EGL surface stay). `paneSurface` on a preview shows the pane as a first surface does: it takes the terminal unless this device has it and asks for the fit unless it asked for that size last. |
| `paneResume(core, block: String, surface: Surface?, widthPx: Int, heightPx: Int)` | `paneSurface` for a pane that is back where it was shown before: its preview ended on the tab the overview was opened from (cancelled, reversed, or that tab's own card tapped), or its surface is back after `paneSuspend` (the app is on the screen again, or what covered the panes is gone). That is no use of the terminal: nothing is taken, also not from a client that took it meanwhile, and a size is asked for only when the pane's fit changed. A pane that was not shown before is shown as `paneSurface` shows it. |
| `paneSuspend(core, block: String)` | Detach the pane's surface because the app left the screen (the screen went off, another app came to the front). `PaneSurfaceView.surfaceDestroyed` calls it instead of `paneSurface(null)` when the activity is stopped or its window is hidden, and the next surface is attached with `paneResume`: unlocking the phone takes no terminal from the desktop. A shown pane's view calls it too when it goes away because the app's own sidebar or screen covers the workspace (`PaneCover` in `ui/PaneCover.kt`), and the pane's next view attaches with `paneResume`: opening and closing the compact sidebar or Settings takes no terminal either. |
| `paneTouch(core, block: String, action: Int, x: Float, y: Float)` | Selection gestures. |
| `paneCopy(core, block: String): String` | Selected text (or screen text). |
| `paneBracketedPaste(core, block: String): Boolean` | Whether a paste into the pane would be framed as a bracketed paste: its terminal has the mode on. The pane menu's Paste asks before it pastes a text with a line break or a control character into a pane without it (`pasteQuestion`). The `{"op":"paste",…}` command reads the mode again when it runs and reports `paste_needs_confirm` for an unconfirmed text that needs the question. False when the pane is unavailable, so such a paste is asked about, never framed unseen. |
| `paneHasSelection(core, block: String): Boolean` | Whether the pane has a selection, which `paneCopy` then copies. A long press on such a pane opens the pane menu without the touch-down that would replace the selection; a move after the press sends it and starts a new selection. A tap on such a pane clears the selection (`paneClearSelection`); a tap on a pane without one sends nothing. |
| `paneSwaps(core, block: String): Long` | How many frames the pane's surfaces have taken: the render thread counts each swap of a frame that drew something new (`Pane.swaps`). The same number again is the same picture on the surface, so the overview draws a preview's last still again instead of copying the surface (`ui/PaneStill.kt`). 0 for no such pane. |
| `paneClearSelection(core, block: String)` | Ends the pane's selection: `paneCopy` copies the screen again. |
| `forwardOpen(core, block: String, host: String, port: Int): String` | Blocking, on an app thread: forwards `host`:`port` of the remote, which a link in the terminal `block` names, to the phone (see "Port forwards"). Answers `{"status":"ok","port":N}` for the phone's `127.0.0.1:N`, or `{"status":"error","code","message"?}` (`too_old` for a server without tunnel blocks). A forward of the same host and port is used again. |
| `forwardClose(core, block: String)` | Blocking: ends the forward of the tunnel block `block`, its connections, and the block. |
| `forwardSweep(core): Boolean` | Blocking: closes the tunnel blocks in the sessions whose `last_owner` is this client and that are no forward of this core: the forwards of an app that was stopped. False when the core could not look. |

### Core → Kotlin (`CoreListener`, called on a core thread; Kotlin posts to main)

| Callback | Meaning |
|---|---|
| `onState(json: String)` | Whole UI model after each change: connection status, server label and account, sessions, selected session, windows (label, icon hint, layout tree with block ids, zoom, floating layers), focused block, and per block its title, foreground program, working directory and reported activity. Every session carries its `tabs`, also the ones that are not selected: `[{id, label, icon?, panes, active, minimized, block, title, program, cwd, activity, attention}]` in the session's window order, where `block` is the tab's lead block (focused, else first; left out for a tab without panes) and the fields after it describe that block. `panes` does not count minimized panes; `minimized` (also in each entry of `windows`) is true when the tab has at least one, so a tab is one shell and nothing else only when `panes` is 1 and `minimized` is false. `tabs` is left out while the session's windows are not known (before the first answer, after a failed read, and for every session while none is selected); `[]` means the session has no tabs. `session`, `windows`, `window`, `focused` and `blocks` describe the selected session only and agree with its `tabs`. `forwards` lists the port forwards, `[{block, host, port, localPort, connections}]`, with the other scalars (`status`, `session`, …): a forward that changes sends that group again. When the selected session is destroyed the core selects the next one in the list, else the previous, else none. Kotlin renders it with Compose. |
| `onReconnectToken(token: String)` | Persist for next launch. |
| `onError(code: String, message: String, fix: String)` | User-visible error. `fix` is a one-line command that fixes it, for the user to copy (from the SSH pool), or "". |
| `rasterizeGlyph(utf8: ByteArray, bold: Boolean, italic: Boolean, cellW: Int, cellH: Int, columns: Int): ByteArray?` | RGBA or A8 bitmap for the atlas; null if the font has no glyph. Called only on atlas misses. |

The `icon` field can be absent from `session.view` and `com.superlogical.terminal.process`.
For example, a window labeled `Alpha` might report only `foreground.name: "bash"` and
`child.name: "bash"`.
The core copies a nonblank window `icon` when present. Otherwise it takes the
basename of the lead block's reported process name (`foreground.name`, or the
child process name when the foreground process is absent). Both `tabs` and
`windows` publish the same hint for a tab. The sidebar, overview, and workspace
caption use one glyph mapping. An unrecognized hint displays `▸` and an omitted
hint displays `>_`. The process-based result reflects the reported executable
name, which may identify an interpreter shared by several tools.

Box-drawing and block elements (U+2500–U+259F) are drawn by the core as geometry,
edge to edge, never from fonts.

## The phone and its remotes

- **This Device** is the phone: the app's own Tailscale node (`Tailnet.kt`,
  `native/src/sys/tailnet.zig`), one per process, with its login kept in the app's files.
  The sidebar shows “This Device” above the remote list, with its address and state.
  Tap it for the node name, on/off, login and logout controls.
- **Remotes** are the computers that run Rex. The list (`{id, label, url}`) lives on the
  phone only (`HostStore`, private SharedPreferences), with each remote's reconnect token
  and last session stored by URL. No server's `hosts.list` is read or written, and
  nothing a server does adds or removes a row.
- **Found on tailnet**: while the node runs it probes its online peers (`GET /llms.txt`
  names Rex) and lists the servers that are not saved; one tap saves one.
- **One core per saved remote** (`RemoteConnections`, `HostConnection`), all connected at
  once. A remote that is off stays as a dimmed row and connects when it comes back: the
  core retries by itself. One remote is active and drawn, even if it goes offline.
  The four most recently active connected remotes keep their sessions. Other connected
  remotes detach their session (`detach`) and keep the control link. A core without
  a stream slot starts or retries with a control-only bootstrap. On promotion the
  app sends `resumeSession` with the last saved session, if any. The core checks its current
  session list and attaches the saved session if present, otherwise the first one.
- **One client per server.** The cores belong to the process (`VeloApplication`,
  `PerProcess`), not to the activity: starting the activity again or recreating it never
  makes a second connection. Removing the task ends them.
- **Session selection:** a connection requests the session saved for its remote. A debug
  connection with a test prefix starts without attaching any session; it checks the server's
  list and attaches only a session matching its prefix or one it created itself.

Settings → Appearance changes interface sizing and pane presentation, not the terminal palette.
Velo's interface uses a dark palette independently of the system's appearance.

**Pane menu → Compose…** pins a draft to the original host, session and focused pane. Send
types text without adding Enter. It accepts one document or image up to 8 MiB from Android's
document picker. With an attachment the core writes any draft text and a space first, then
uses `com.superlogical.terminal.upload_begin` and the `rex.data.bytes.v1` stream. The server
pastes the resulting file path into the pane. These operations are not atomic: after an error,
check the pane before opening another draft. No write or upload is automatically retried.
An error that the core or the app reports before the first write leaves the draft editable,
to send again. After any other error the draft stays read-only. A draft whose pane changed
or disconnected can be copied to the clipboard.
`Attach…` opens the image or file picker and lets the user remove the current attachment.
Only a confirmed send replaces the Send button with a check mark. The dialog then closes.
Closing a draft that holds text or an attachment asks first, with `Keep` and `Discard`.
The key bar groups the saved keys into Modifiers, Editing and Navigation without changing
the stored key names.

**Paste** in the long-press toolbar and in the pane menu sends a clipboard file instead of
text when the clip's first item is a `content:` URI with no text. Any such clip is sent as a
file, even one whose type is `text/*`. The file goes through the
same upload as Compose's attachment (`com.superlogical.terminal.upload_begin`,
`rex.data.bytes.v1`), up to 8 MiB, and the server types its path into the pane without Enter.
It uses the provider's display name when usable, or `clipboard-yyyyMMdd-HHmmss.<ext>`
(omitting the extension when the MIME type maps to none). Only the clip's first item is sent.
The file goes to the focused pane: a Paste from another pane's menu focuses that pane first,
and gives up when the core has not shown it focused within 2 seconds. A focus, tab or
connection change during the read refuses the send, and a read that takes over 60 seconds
ends with an error. A toast shows while the upload runs, and failures appear in the
connection's error notice. An image that the keyboard inserts (#160), for example from Gboard's
clipboard strip, goes the same way: the editor declares `image/*` and common image types in
`EditorInfo.contentMimeTypes`, takes `commitContent`, and holds the read grant until the read ends.

## Port forwards

A tap on a link to the remote's own loopback (`localhost`, `127.0.0.1`, `[::1]`, `0.0.0.0`,
`*.localhost`; `ui/LocalForwards.kt`) opens it in the phone's browser through the remote's Rex connection:

1. The core makes a `com.superlogical.tunnel.dial` block in the selected session
   (`session.new_block`, `{host, port, client_address}`) and listens on the phone's
   `127.0.0.1:<the same port>`, or on a free port when that one is taken or below 1024.
   The same port keeps the page's origin, and the cookies and OAuth callbacks tied to it.
2. The app opens the link with its port changed to the one the phone listens on.
3. Each connection the phone accepts gets its own stream: `data.connect` with
   `data_ref_id` `conn:<random>` and `rex.data.bytes.v1`, then `native/src/rex/Tunnel.zig`
   relays it both ways. An empty message is a half-close; a stream closed before both
   halves ended, with any code, resets the other side.

Only the block's owner may open its streams (the server closes another client's with 4006),
and `data.connect` needs the block's session attached (`session.attach`; `attach_all` is
not enough). A forward takes its block back (`session.own_block`, after `session.attach`
when its session is not the selected one) before its next stream when the link was lost,
its session was detached, or a stream was refused as not the owner's.

A forward stays until it is stopped in Settings > Remotes, where each saved remote lists
its forwards. One whose block or session is gone on the remote (a stream answered
`not_found`) is made again by the next tap on its link, on the same port: the phone's
listener sets `SO_REUSEADDR`, not `SO_REUSEPORT`, so connections in TIME_WAIT do not keep
the port, while another listener still does. While one runs, the background service keeps the process (and its relays)
alive with the browser in front. A core that goes closes its forwards' blocks first: a
control link closed normally ends the client on the server. A process that is killed
leaves them, and `forwardSweep`, which the app runs on each connection's first link,
closes them when the client comes back with its reconnect token.

## Terminal size

A terminal has one size for every client. The device that connected or was used last
decides it: the core takes a terminal (`session.own_block`, mode `take`) when its pane is
first shown on the phone and when the user touches the pane or types into it, and asks
for the columns and rows that fit the pane at its text size
(`com.superlogical.terminal.resize`). The app only reports: a pane's surface, a size that
has settled or a pinch that ended (`fit`), a finger down (`touch`). The rules are in
`native/src/rex/SizeRules.zig` and `docs/native-core.md`. While another client has the
terminal, the phone draws that client's grid and the next touch takes it back. A pane
attached as a preview (the tab overview) takes and asks nothing. Looking is not using: a
pane that comes back as it was, after the overview, after the app left the screen, or
after the app's own sidebar or screen covered the workspace, takes nothing and asks for a
size only when its fit changed.

When the overview is closed, a confirmed tab switch updates both tabs' surface roles
from the same selection in one composition. A local pick awaiting confirmation remains
a preview: moving a card into view alone does not grant it ownership of the terminal.

## Design

The workspace has a black background, rounded pane cards, and a right rail. Per-pane actions
are available from the “…” button and from the pane's long-press menu. See [Design tokens](design-tokens.md).

The tab overview (`ui/Overview.kt`, `ui/OverviewMotion.kt`) is not a second rendering. The
pane area holds every tab of the session, each in one container; a pane keeps its own
full-size `SurfaceView` and the container is moved and scaled as a whole (a Compose
`graphicsLayer`, which the compositor applies to the surfaces under it). One number,
progress, runs from 0 (the selected tab fills the pane area) to 1 (the tabs stand in a row
of cards); three fingers moved up or down, a pinch that goes on below the smallest text
size, the rail button and the palette all change only that number. Only the tab that fills
the pane area has shown panes; every other pane, and every pane while the overview is open
at all, is attached with `panePreview`, on the same `Surface`, so nothing is taken, sized
or drawn anew. Back on the tab the overview was opened from, the panes that were shown
there are shown again with `paneResume` (`ReturnMark` in `ui/OverviewMotion.kt` decides, per
connection, session, tab and block, once): a terminal another client holds stays that
client's until the pane is touched. The selected tab and two tabs on each side of the one in view keep surfaces;
a tab farther away is a plain card until it comes near. While the row moves (a finger or a
throw changes its scroll), every preview pane is a still (`ui/PaneStill.kt`): its surface is
copied with `PixelCopy` into a picture that the window draws, one pane per frame, and the
surface's own layer is hidden. A pane that has taken no frame since its last still
(`paneSwaps`) is drawn from that still again, without a copy. The surface stays, attached to the view and not to its
visibility (API 34 and later), and so does the core's renderer: at rest the layer shows again
with the core's last frame. SurfaceFlinger then moves and composes the window alone; with more
than two cards' layers moving it composed most frames on the GPU (see [Measuring](perf.md)).
Before API 34 the cards move live. The pane area takes every gesture of
two or more fingers before a pane sees it, and every finger while the overview is open.

The left sidebar (`ui/Sidebar.kt`) follows Rex for Mac: a toolbar (hide sidebar, Activity,
command palette, Settings, and where the window can dock it, the unfolded Fold upright, a pin
that docks it beside the panes or undocks it, as the palette's Dock and Undock commands do), New Session, the host as `account@host`, then the session's
tabs with a row per pane showing its program and directory. Activity keeps only panes whose
program reported a state other than idle. The palette filters as you type and Enter runs
the first match.
