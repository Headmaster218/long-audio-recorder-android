#!/usr/bin/env python3
"""Offline synthetic Git fixtures for the source assembly command."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('assembly', Path(__file__).with_name('assemble-source.py'))
assembly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembly)


class AssemblyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Synthetic Test')
        self.git('config', 'user.email', 'synthetic@example.invalid')
        (self.repo / 'README.md').write_text('base\n')
        (self.repo / '.gitignore').write_text('private-data\n')
        for index, overlay in enumerate(assembly.OVERLAYS):
            path = self.repo / 'checkpoints' / overlay / 'app' / 'policy.java'
            path.parent.mkdir(parents=True)
            path.write_text('overlay-' + str(index))
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic source only')
        self.commit = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True)

    def test_exact_snapshot_and_order(self):
        (self.repo / 'README.md').write_text('dirty, must not copy\n')
        (self.repo / 'private-data').write_text('synthetic ignored data')
        (self.repo / 'untracked.txt').write_text('must not copy')
        out = self.root / 'assembled'
        receipt = assembly.assemble(self.repo, self.commit, out)
        self.assertEqual((out / 'README.md').read_text(), 'base\n')
        self.assertEqual((out / 'app/policy.java').read_text(), 'overlay-5')
        self.assertFalse((out / 'private-data').exists())
        self.assertFalse((out / 'untracked.txt').exists())
        self.assertFalse((out / '.git').exists())
        self.assertFalse((out / 'checkpoints').exists())
        self.assertEqual(receipt['source_commit'], self.commit)
        self.assertEqual(json.loads((out / assembly.RECEIPT).read_text()), receipt)
        for item in receipt['files']:
            data = (out / item['path']).read_bytes()
            self.assertEqual(item['sha256'], hashlib.sha256(data).hexdigest())
            self.assertEqual(item['bytes'], len(data))
        self.assertEqual((self.repo / 'README.md').read_text(), 'dirty, must not copy\n')

    def test_existing_destination_retained(self):
        out = self.root / 'existing'
        out.mkdir()
        (out / 'keep').write_text('preserved')
        with self.assertRaises(FileExistsError):
            assembly.assemble(self.repo, self.commit, out)
        self.assertEqual((out / 'keep').read_text(), 'preserved')
        self.assertFalse((out / assembly.RECEIPT).exists())

    def test_symlink_rejected(self):
        (self.repo / 'link').symlink_to('README.md')
        self.git('add', 'link')
        self.git('commit', '-qm', 'synthetic symlink')
        out = self.root / 'bad'
        with self.assertRaises(ValueError):
            assembly.assemble(self.repo, 'HEAD', out)
        self.assertFalse(out.exists())

    def test_missing_overlay_rejected(self):
        self.git('rm', '-qr', 'checkpoints/' + assembly.OVERLAYS[-1])
        self.git('commit', '-qm', 'synthetic incomplete input')
        out = self.root / 'bad'
        with self.assertRaises(ValueError):
            assembly.assemble(self.repo, 'HEAD', out)
        self.assertFalse(out.exists())

    def test_unsafe_paths_rejected(self):
        for path in ('../x', '/x', 'a/../x', '.git/config', 'a\\b', 'a//b'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                assembly.safe_path(path)


if __name__ == '__main__':
    unittest.main()
