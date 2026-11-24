#!/usr/bin/env python3
"""Verify the notice and font assets of the APK that will be published."""

import hashlib
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile


# Check the release inventory independently of the source-to-APK byte match:
# a matching source and APK must not reintroduce the removed composite.
FONT_FILES = {
    f'{family}-{style}.ttf'
    for family, styles in {
        'JetBrainsMono': ('Regular', 'Bold', 'Italic', 'BoldItalic'),
        'CascadiaCode': ('Regular', 'Bold', 'Italic', 'BoldItalic'),
        'D2CodingLigature': ('Regular', 'Bold'),
        'FiraCode': ('Regular', 'Bold'),
    }.items()
    for style in styles
} | {'Noto-COLRv1.ttf'}
FONT_NOTICES = {
    'JetBrainsMono-OFL.txt', 'CascadiaCode-OFL.txt', 'D2Coding-OFL.txt', 'Nanum-OFL.txt',
    'FiraCode-OFL.txt', 'Noto-LICENSE.txt', 'Powerline-MIT.txt',
}


def verify_font_inventory(expected):
    for prefix, allowed in (
        ('assets/fonts/', FONT_FILES | FONT_NOTICES | {'PROVENANCE.txt'}),
        ('assets/notices/third-party/fonts/', FONT_NOTICES),
    ):
        actual = {name[len(prefix):] for name in expected if name.startswith(prefix)}
        unexpected = sorted(actual - allowed)
        missing = sorted(allowed - actual)
        if unexpected or missing:
            sys.exit(f'Unexpected release font inventory under {prefix}: missing={missing}, extra={unexpected}')


def blob_id(stream, size, algorithm):
    digest = hashlib.new(algorithm)
    digest.update(f'blob {size}\0'.encode('ascii'))
    for chunk in iter(lambda: stream.read(65536), b''):
        digest.update(chunk)
    return digest.hexdigest()


def main(apk_path, revision):
    root = Path(__file__).resolve().parents[2]
    git = ['git', '--no-replace-objects', '-C', str(root)]
    commit = subprocess.check_output(
        git + ['rev-parse', '--verify', '--end-of-options', f'{revision}^{{commit}}'], text=True,
    ).strip()
    algorithm = subprocess.check_output(git + ['rev-parse', '--show-object-format'], text=True).strip()
    folders = {'licenses/': 'assets/notices/third-party/', 'app/src/main/assets/fonts/': 'assets/fonts/'}
    required = {'LICENSE', 'NOTICE'}
    tree = subprocess.check_output(git + ['ls-tree', '-rz', commit, '--', *sorted(required), *folders])
    expected = {}
    seen_folders = set()
    for record in tree.split(b'\0')[:-1]:
        metadata, raw_path = record.split(b'\t', 1)
        mode, kind, oid = metadata.decode('ascii').split()
        path = raw_path.decode('utf-8')
        if kind != 'blob' or mode not in ('100644', '100755'):
            sys.exit(f'Not a regular file in release source: {path}')
        if path in required:
            name = 'assets/notices/' + path
            required.remove(path)
        else:
            folder = next(prefix for prefix in folders if path.startswith(prefix))
            seen_folders.add(folder)
            name = folders[folder] + path[len(folder):]
        expected[name] = oid
    if required or seen_folders != folders.keys():
        sys.exit('The release commit is missing required notice files or asset directories.')
    verify_font_inventory(expected)

    with ZipFile(apk_path) as apk:
        seen = set()
        for entry in apk.infolist():
            name = entry.filename
            if entry.is_dir() or not name.startswith(('assets/notices/', 'assets/fonts/')):
                continue
            if name in seen or name not in expected:
                sys.exit(f'Unexpected or duplicate APK asset: {name}')
            seen.add(name)
            with apk.open(entry) as actual:
                if blob_id(actual, entry.file_size, algorithm) != expected[name]:
                    sys.exit(f'APK asset does not match source: {name}')
        missing = sorted(expected.keys() - seen)
        if missing:
            sys.exit(f'Missing APK assets: {", ".join(missing)}')
    print(f'Verified {len(expected)} APK notice and font assets against source.')


if __name__ == '__main__':
    if len(sys.argv) not in (2, 3):
        sys.exit(f'Usage: {sys.argv[0]} APK [COMMIT]')
    main(sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else 'HEAD')
