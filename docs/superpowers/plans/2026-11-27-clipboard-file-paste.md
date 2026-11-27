# Clipboard File Paste Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android's Paste (long-press toolbar and pane menu) attaches a file that another app put on the clipboard, as the composer's attachment does.

**Architecture:** A clip whose first item is a `content:` URI without text is a file. The app reads it (8 MiB at most) on a worker thread and sends it with `HostConnection.sendComposer` and no text; the server types the uploaded path into the pane. A toast shows while it runs; failures go to the connection's error notice.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4, Robolectric 4.18.1. Build from the worktree with `./gradlew`.

**Spec:** `docs/superpowers/specs/2026-11-27-clipboard-file-paste-design.md`

## Global Constraints

- No change under `native/` and no server change. The limit stays 8 MiB (`readComposerBytes`).
- Only the clip's first item is used.
- A text clip pastes exactly as before (`clipboardText`, `pasteQuestion`, `pasteTextIntoPane`).
- User-facing copy, verbatim: `Uploading <name>…`, `Uploading file…`, `Another send is still in progress.`, `Could not read the clipboard file.`, `Velo may not read this clipboard file.`, `The pane or connection changed. Paste again.`, `Not enough memory to read the file.`
- Generated name: `clipboard-yyyyMMdd-HHmmss` (local time), then `.<ext>` when the MIME type maps to one.
- Commits use the noreply address (see docs/releasing.md) and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A clip with both text and a URI (a browser's copied link, rich text): pastes the text, never uploads. Task 1 test `textWinsOverUri`.
2. A provider that gives no `DISPLAY_NAME` or `SIZE`: a generated name, a bounded read. Task 1 test `namelessProviderGetsGeneratedName`.
3. A provider that refuses the read (`SecurityException`, `FileNotFoundException`): an error notice, no crash. Task 1 test `refusedReadBecomesIOException`.
4. A second Paste while one upload runs: refused with `Another send is still in progress.`, the first goes on. Task 4 device check.
5. The pane or connection changes while the file is read: the send is refused by its epoch and says so; nothing is typed elsewhere. Task 4 device check.

---

### Task 1: Clipboard file helpers (leaf A)

**Files:**
- Create: `app/src/main/java/io/github/yhzion/velo/ui/ClipboardFile.kt`
- Test: `app/src/test/java/io/github/yhzion/velo/ui/ClipboardFileTest.kt` (Robolectric runner, as other Robolectric tests in `app/src/test`)

**Interfaces:**
- Consumes: `ComposerAttachment(name, bytes)`, `usableAttachmentName(name)`, `readComposerBytes(input, reportedSize)` from `ComposerDraft.kt`.
- Produces:
  - `internal class ClipboardFile(val uri: Uri, val mime: String?)`
  - `internal fun clipItemIsFile(text: CharSequence?, uriScheme: String?): Boolean` — true only when `uriScheme == "content"` and `text.isNullOrEmpty()`.
  - `internal fun clipboardFileName(displayName: String?, extension: String?, now: LocalDateTime): String`
  - `internal fun clipboardFile(context: Context): ClipboardFile?` — the primary clip's first item when `clipItemIsFile`; `mime` = `contentResolver.getType(uri)` or the clip description's first MIME type.
  - `internal fun ContentResolver.loadClipboardFile(file: ClipboardFile, now: LocalDateTime): ComposerAttachment` — blocking; throws `IOException` with the user-facing message.

- [ ] **Step 1: Write the failing tests**

```kotlin
private val t = LocalDateTime.of(2026, 11, 27, 15, 30, 12)

@Test fun textWinsOverUri() {
    assertTrue(clipItemIsFile(null, "content"))
    assertTrue(clipItemIsFile("", "content"))
    assertFalse(clipItemIsFile("https://x.example", "content"))
    assertFalse(clipItemIsFile(null, "file"))
    assertFalse(clipItemIsFile(null, null))
    assertFalse(clipItemIsFile("hi", null))
}

@Test fun providerNameIsKeptWhenUsable() {
    assertEquals("사진.png", clipboardFileName("사진.png", "jpg", t))
    assertEquals("clipboard-20261127-153012.png", clipboardFileName(null, "png", t))
    assertEquals("clipboard-20261127-153012.pdf", clipboardFileName("", "pdf", t))
    assertEquals("clipboard-20261127-153012.png", clipboardFileName("../x", "png", t))
    assertEquals("clipboard-20261127-153012", clipboardFileName(null, null, t))
}

@Test fun namelessProviderGetsGeneratedName() {
    // Robolectric: query() of an unregistered URI gives no cursor; registerInputStream gives the bytes.
    // If MimeTypeMap has no png, add it with shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("png", "image/png").
    val uri = Uri.parse("content://test.clip/1")
    shadowOf(resolver).registerInputStream(uri, byteArrayOf(1, 2, 3).inputStream())
    val got = resolver.loadClipboardFile(ClipboardFile(uri, "image/png"), t)
    assertEquals("clipboard-20261127-153012.png", got.name)
    assertArrayEquals(byteArrayOf(1, 2, 3), got.bytes)
}

@Test fun refusedReadBecomesIOException() {
    // A stream whose read() throws SecurityException stands for a provider that withdrew the grant.
    val e = assertThrows(IOException::class.java) { resolver.loadClipboardFile(ClipboardFile(refusingUri, null), t) }
    assertEquals("Velo may not read this clipboard file.", e.message)
}
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'io.github.yhzion.velo.ui.ClipboardFileTest'`
Expected: compilation fails, the functions do not exist.

- [ ] **Step 3: Implement `ClipboardFile.kt`**

`loadClipboardFile`: query `DISPLAY_NAME` and `SIZE` as `ComposerDialog.loadDocument` does, but a missing cursor or column is not an error. The extension comes from `MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)`. `openInputStream` returning null → `IOException("Could not read the clipboard file.")`; `SecurityException` and `FileNotFoundException` → `IOException("Velo may not read this clipboard file.")`; the 8 MiB `IOException` of `readComposerBytes` passes through as it is.

- [ ] **Step 4: Run the tests and see them pass**

Run: the command of Step 2. Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit** `feat(paste): read a file that another app put on the clipboard (#107)`

### Task 2: Docs and changelog (leaf B, parallel with Task 1)

**Files:**
- Modify: `docs/native-architecture.md` — a paragraph right after the **Pane menu → Compose…** paragraph.
- Modify: `CHANGELOG.md` — under `## Unreleased`, an `### Added` section.

**Interfaces:** none (text only).

- [ ] **Step 1: Write the paragraph.** It says: Paste in the long-press toolbar and in the pane menu sends a clipboard file instead of text when the clip's first item is a `content:` URI with no text; the file goes through the same upload as Compose's attachment (`upload_begin`, `rex.data.bytes.v1`), 8 MiB at most, without Enter; the name is the provider's or `clipboard-yyyyMMdd-HHmmss.<ext>`; only the first item is sent; a toast shows while it runs and failures show in the error notice; the keyboard's own clipboard is not covered.
- [ ] **Step 2: Write the changelog entry**, one user-facing sentence in the file's style: an image or file copied in another app (a browser, the gallery, a file manager) now goes to the pane with Paste, which types its path on the remote, as Compose's attachment does.
- [ ] **Step 3: Commit** `docs(paste): describe pasting a clipboard file (#108)`

### Task 3: Upload flow, toast and entry points (leaf C, after Task 1)

**Files:**
- Create: `app/src/main/java/io/github/yhzion/velo/ui/ClipboardUpload.kt`
- Modify: `app/src/main/java/io/github/yhzion/velo/HostConnection.kt` (one state property)
- Modify: `app/src/main/java/io/github/yhzion/velo/ui/Workspace.kt` (the notice column with `UndoNotice` and `ErrorNotice`)
- Modify: `app/src/main/java/io/github/yhzion/velo/ui/PaneArea.kt` (`v.onPasteSelection`)
- Modify: `app/src/main/java/io/github/yhzion/velo/ui/PaneMenu.kt` (`PaneAction.Paste`)

**Interfaces:**
- Consumes: Task 1's `ClipboardFile`, `clipboardFile(context)`, `loadClipboardFile(file, now)`; `HostConnection.composerTarget`, `sendComposer`, `composerRunning`, `error`.
- Produces:
  - `HostConnection.uploading: String?` (Compose state, main thread): the toast's text, null hides it.
  - `internal fun pasteClipboardFile(context: Context, conn: HostConnection, block: String, file: ClipboardFile)` (main thread).

- [ ] **Step 1: Add `var uploading: String? by mutableStateOf(null)` to `HostConnection`.**
- [ ] **Step 2: Implement `pasteClipboardFile`** in this order:
  1. `conn.uploading != null || conn.composerRunning` → `conn.error = "Another send is still in progress."`, return.
  2. `epoch` = `conn.composerTarget(session, block)` with `session = conn.state.session`; no session or `0L` → `conn.error = "The pane or connection changed. Paste again."`, return.
  3. `conn.uploading = "Uploading file…"`; start `Thread(…, "clipboard-file")` that calls `context.contentResolver.loadClipboardFile(file, LocalDateTime.now())` and posts the result to the main looper. `OutOfMemoryError` on start → `uploading = null`, `error = "Not enough memory to read the file."`.
  4. On main: a read failure → `uploading = null`, `error = e.message ?: "Could not read the clipboard file."`. Success → `uploading = "Uploading ${name}…"`, then `conn.sendComposer(session, block, "", name, bytes, epoch) { f -> conn.uploading = null; if (f != null) conn.error = f.message }`.
- [ ] **Step 3: Show the toast.** In `Workspace.kt`'s notice column, before `ErrorNotice(conn, …)`: `conn.uploading?.let { ToastSurface(it, icon = Icons.Default.Upload) }`. No timer: it stays until the send ends.
- [ ] **Step 4: Wire `PaneArea.kt`.** In `v.onPasteSelection`, inside the existing connected check: `clipboardFile(context)?.let { if (!input.finishCompositionBeforePaste()) pasteClipboardFile(context, conn, block, it); return@… }`, else the text path unchanged.
- [ ] **Step 5: Wire `PaneMenu.kt`.** In `PaneAction.Paste`, after `beforePaste()`: a non-null `clipboardFile(context)` → `act { pasteClipboardFile(context, conn, block, file) }`; else the text path unchanged.
- [ ] **Step 6: Build and run all unit tests.**
Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, no failing test (`HostConnection` needs the native core, so this flow has no unit test; Task 4 checks it on the device).
- [ ] **Step 7: Commit** `feat(paste): send a clipboard file to the pane with Paste (#109)`

### Task 4: Device check on the Velvet (leaf D, after Task 3)

Take `~/yhzion/velo-wt/.velvet-lock` (write the session name) before using the Velvet, and remove it at the end. Install the debug build, connect to this workstation's Rex server, and record each result with a screenshot or the pane's text:

- [ ] An image copied in Chrome on the Velvet, pasted from the long-press toolbar into a shell pane: a path is typed, no Enter; `sha256sum` of that path on the workstation equals the image's.
- [ ] The same from the pane menu's Paste.
- [ ] A file over 8 MiB (copy from a file manager): the notice says `Choose a file of 8 MiB or less.`; nothing is typed.
- [ ] A text clip still pastes text, and a two-line text still asks in a pane without bracketed paste.
- [ ] Paste twice quickly: the second says `Another send is still in progress.`
- [ ] Switch to another tab while a large file is read: nothing is typed in the other tab, and the notice says why.

Report: `docs`-free; write the results to the report file the coordinator names.
