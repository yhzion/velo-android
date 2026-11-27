# Clipboard file paste: short design

Approved by the owner on 2026-11-27.

## Intent

People copy images and files to Android's clipboard from many apps (the browser, the
gallery, file managers). A long press on a pane shows Android's text toolbar, and its Paste
should attach such a file to the pane as it pastes text: one tap, no extra screen.

## Goals

- Paste in the long-press toolbar (`PaneSelection`) and the pane menu's Paste attach a
  clipboard file: any type, not only images.
- The file goes through the existing upload (`HostConnection.sendComposer` with no text):
  the server types the file's path into the pane, without Enter.
- A text clip pastes as it does today.

## Non-goals

- Several files at once: only the clip's first item is used, as a draft holds one attachment.
- The keyboard's own clipboard (Gboard image insert, `commitContent`): a later change.
- A preview or a confirmation dialog.
- Changes to the native core or the server. The 8 MiB limit stays (`native/src/rex/Upload.zig`).

## Rules

| Item | Rule |
|---|---|
| A file clip | The first item has a `content:` URI and no text. Anything else is a text clip. |
| Size | 8 MiB at most, read with `readComposerBytes`; over it, nothing is sent. |
| Name | The provider's `DISPLAY_NAME` when `usableAttachmentName` accepts it; otherwise `clipboard-yyyyMMdd-HHmmss` plus `.ext` from the MIME type when one is known. |
| While it runs | A toast says `Uploading <name>…` until the send ends. |
| A failure | Nothing more is tried. The connection's error notice shows the message: the composer's own text where it has one (`Choose a file of 8 MiB or less.`). |
| A second paste while one runs | Refused with `Another send is still in progress.` |

## Approaches

| Approach | Trade-off |
|---|---|
| A. Upload at once (chosen) | One tap, as the owner asked. A mistaken tap only types a path, which runs nothing. |
| B. Open Compose with the file attached | Safest, but one more tap. |
| C. A confirmation with a preview | Shows what goes, but a new screen and one more tap. |

The owner chose A.

## Calls made by Claude (open to change)

- The name rule when the provider gives none.
- The pane menu's Paste gets the same behaviour as the toolbar's.
- The upload toast.
