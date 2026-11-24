#!/usr/bin/env python3
"""Exercise the release verifier with real Git trees and ZIP entries, without Android tools or secrets."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile


class VerifyApkNoticesTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.script = self.root / '.github/scripts/verify-apk-notices.py'
        self.script.parent.mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name(self.script.name), self.script)
        self.git('init', '-q')
        self.assets = {
            'LICENSE': 'assets/notices/LICENSE',
            'NOTICE': 'assets/notices/NOTICE',
        }
        for family, styles in {
            'JetBrainsMono': ('Regular', 'Bold', 'Italic', 'BoldItalic'),
            'CascadiaCode': ('Regular', 'Bold', 'Italic', 'BoldItalic'),
            'D2CodingLigature': ('Regular', 'Bold'),
            'FiraCode': ('Regular', 'Bold'),
        }.items():
            for style in styles:
                self.add_font(f'{family}-{style}.ttf')
        self.add_font('Noto-COLRv1.ttf')
        self.add_font('PROVENANCE.txt')
        for notice in ('JetBrainsMono-OFL.txt', 'CascadiaCode-OFL.txt', 'D2Coding-OFL.txt', 'Nanum-OFL.txt',
                       'FiraCode-OFL.txt', 'Noto-LICENSE.txt', 'Powerline-MIT.txt'):
            self.add_font(notice)
            self.add_notice(notice)
        self.assets['licenses/android/example.txt'] = 'assets/notices/third-party/android/example.txt'

    def git(self, *args):
        subprocess.run(['git', '-C', self.root, '-c', 'core.hooksPath=/dev/null', *args], check=True)

    def add_font(self, name):
        self.assets[f'app/src/main/assets/fonts/{name}'] = f'assets/fonts/{name}'

    def add_notice(self, name):
        self.assets[f'licenses/fonts/{name}'] = f'assets/notices/third-party/fonts/{name}'

    def verify(self, *, extra=None, omit=None, replace=None):
        for source in self.assets:
            file = self.root / source
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(source.encode())
        self.git('add', '.')
        self.git('-c', 'user.name=Font test', '-c', 'user.email=font-test@example.invalid',
                 '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Font fixture')
        apk = self.root / 'fixture.apk'
        with ZipFile(apk, 'w') as archive:
            for source, dest in self.assets.items():
                if dest != omit:
                    archive.writestr(dest, b'wrong bytes' if dest == replace else source.encode())
            if extra:
                archive.writestr(extra, b'unexpected')
        return subprocess.run([sys.executable, self.script, apk, 'HEAD'], capture_output=True, text=True)

    def assert_rejected(self, result, detail):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(detail, result.stderr)

    def test_accepts_matching_stock_assets(self):
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_composite_even_if_source_and_apk_match(self):
        self.add_font('JetBrainsMonoNerdFontMono-Regular.ttf')
        self.assert_rejected(self.verify(), 'JetBrainsMonoNerdFontMono-Regular.ttf')

    def test_rejects_composite_notice_even_if_source_and_apk_match(self):
        self.add_notice('nerd-fonts/license-audit.md')
        self.assert_rejected(self.verify(), 'nerd-fonts/license-audit.md')

    def test_rejects_missing_powerline_notice_in_both_source_and_apk(self):
        del self.assets['licenses/fonts/Powerline-MIT.txt']
        self.assert_rejected(self.verify(), 'Powerline-MIT.txt')

    def test_rejects_missing_stock_style_in_both_source_and_apk(self):
        del self.assets['app/src/main/assets/fonts/JetBrainsMono-BoldItalic.ttf']
        self.assert_rejected(self.verify(), 'JetBrainsMono-BoldItalic.ttf')

    def test_rejects_unlisted_apk_font(self):
        self.assert_rejected(self.verify(extra='assets/fonts/JetBrainsMonoNerdFontMono-Bold.ttf'),
                             'Unexpected or duplicate APK asset')

    def test_rejects_missing_apk_font(self):
        self.assert_rejected(self.verify(omit='assets/fonts/JetBrainsMono-Bold.ttf'), 'Missing APK assets')

    def test_rejects_apk_font_bytes_that_differ_from_source(self):
        self.assert_rejected(self.verify(replace='assets/fonts/JetBrainsMono-Regular.ttf'),
                             'APK asset does not match source')


if __name__ == '__main__':
    unittest.main()
