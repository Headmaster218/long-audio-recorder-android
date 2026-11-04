#!/usr/bin/env python3
"""Verify the exact assembled source bytes before tests/builds; not a signature."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat


def verify(root):
    root = Path(root).resolve()
    if (root / 'checkpoints').exists():
        raise ValueError('Old root baseline cannot be built. Run scripts/prepare-delivery.py from the Git checkout.')
    receipt = json.loads((root / 'ASSEMBLED_SOURCE.json').read_text())
    if receipt.get('schema_version') != 1 or not re.fullmatch('[0-9a-f]{40}', receipt.get('source_commit', '')):
        raise ValueError('Invalid assembly provenance')
    manifest = json.loads((root / 'source-overlays.json').read_text())
    if manifest.get('schema_version') != 1 or receipt.get('overlays') != manifest.get('overlays'):
        raise ValueError('Assembly overlay order does not match source manifest')
    expected = set()
    for entry in receipt['files']:
        name = entry['path']
        if not isinstance(name, str) or not name or PurePosixPath(name).is_absolute() or '\\' in name or any(p in ('', '.', '..', '.git') for p in name.split('/')):
            raise ValueError('Unsafe assembly receipt path')
        if name in expected:
            raise ValueError('Duplicate assembly receipt path: ' + name)
        expected.add(name)
        path = root / name
        if any(p.is_symlink() for p in (path, *path.parents)) or not path.is_file():
            raise ValueError('Missing or nonregular assembled file: ' + name)
        data = path.read_bytes()
        mode = stat.S_IMODE(path.stat().st_mode)
        if len(data) != entry['bytes'] or hashlib.sha256(data).hexdigest() != entry['sha256'] or mode != int(entry['mode'][-3:], 8):
            raise ValueError('Assembled source changed: ' + name)
    # Added Java/resources/scripts could alter a build without changing any listed file.
    # Build output is excluded. Python caches under scripts are NOT excluded:
    # an unchecked-hash cache can execute instead of the recorded source.
    for directory in ('app/src', 'scripts'):
        for path in (root / directory).rglob('*'):
            relative = path.relative_to(root)
            if path.is_symlink():
                raise ValueError('Symlink in source inputs: ' + str(relative))
            if path.is_file() and relative.as_posix() not in expected:
                raise ValueError('Unrecorded source file: ' + str(path.relative_to(root)))
    for required in ('build.gradle', 'app/build.gradle', 'app/src/main/AndroidManifest.xml', 'source-overlays.json', 'scripts/build-android-unsigned.py'):
        if required not in expected:
            raise ValueError('Missing required receipt entry: ' + required)
    return receipt['source_commit']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        print('VERIFIED_ASSEMBLED_SOURCE ' + verify(args.root))
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, 'Source verification failed: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
