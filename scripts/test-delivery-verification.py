#!/usr/bin/env python3
"""Synthetic delivery-source validation regressions, without SDK/network/device."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('verification', Path(__file__).with_name('verify-assembled-source.py'))
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)


class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.files = ['build.gradle', 'app/build.gradle', 'app/src/main/AndroidManifest.xml', 'scripts/build-android-unsigned.py', 'source-overlays.json']
        for name in self.files:
            p = self.root/name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('{}')
            p.chmod(0o644)
        (self.root/'source-overlays.json').write_text(json.dumps({'schema_version': 1, 'overlays': ['fixture']}))
        self.receipt = {'schema_version': 1, 'source_commit': 'a'*40, 'overlays': ['fixture'], 'files': [
            {'path': name, 'bytes': (self.root/name).stat().st_size, 'mode': '100644', 'sha256': hashlib.sha256((self.root/name).read_bytes()).hexdigest()} for name in self.files]}
        self.save()

    def save(self):
        (self.root/'ASSEMBLED_SOURCE.json').write_text(json.dumps(self.receipt))

    def test_clean_and_generated_outputs(self):
        (self.root/'app/build').mkdir()
        (self.root/'app/build/generated.class').write_text('generated')
        self.assertEqual(verification.verify(self.root), 'a'*40)

    def test_old_root(self):
        (self.root/'checkpoints').mkdir()
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_changed_source(self):
        (self.root/'build.gradle').write_text('modified')
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_missing_source(self):
        (self.root/'app/build.gradle').unlink()
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_added_source(self):
        (self.root/'app/src/Injected.java').write_text('extra source')
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_java_hidden_in_pycache_rejected(self):
        target = self.root/'app/src/main/java/__pycache__/Injected.java'
        target.parent.mkdir(parents=True)
        target.write_text('extra source')
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_python_bytecode_allowed_but_symlink_rejected(self):
        target = self.root/'scripts/__pycache__/helper.pyc'
        target.parent.mkdir()
        target.write_bytes(b'generated Python bytecode')
        self.assertEqual(verification.verify(self.root), 'a'*40)
        target.unlink()
        target.symlink_to('../build-android-unsigned.py')
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_symlink_source(self):
        target = self.root/'build.gradle'
        target.unlink()
        target.symlink_to('app/build.gradle')
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_overlay_mismatch(self):
        self.receipt['overlays'] = ['wrong']
        self.save()
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_duplicate_path(self):
        self.receipt['files'].append(self.receipt['files'][0])
        self.save()
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_unsafe_path(self):
        self.receipt['files'][0]['path'] = '../escape'
        self.save()
        with self.assertRaises(ValueError): verification.verify(self.root)

    def test_removed_required_entry(self):
        self.receipt['files'] = [x for x in self.receipt['files'] if x['path'] != 'build.gradle']
        self.save()
        with self.assertRaises(ValueError): verification.verify(self.root)


if __name__ == '__main__':
    unittest.main()
