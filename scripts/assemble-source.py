#!/usr/bin/env python3
"""Assemble a committed source snapshot plus ordered overlays into a NEW directory.

No network, checkout mutation, build, credential copying, or deletion. Uncommitted
files (including local fixes) are intentionally not inputs. Failure leaves the
partial destination for inspection, without a completed receipt.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess

OVERLAYS = (
    'upload-threshold-destroy-guard-20261025',
    'input-routing-20261025',
    'transfer-budget-recovery-20261030',
    'recorder-reliability-recovery-20261031',
    'repeated-start-lifecycle-audit-20261101',
    'sdk-wire-readiness-20261101',
    'terminal-boundary-stop-20261103',
)
LEGACY_OVERLAYS = OVERLAYS[:-1]
RECEIPT = 'ASSEMBLED_SOURCE.json'
OVERLAY_MANIFEST = 'source-overlays.json'


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def safe_path(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or any(p in ('', '.', '..', '.git') for p in name.split('/')) or '\\' in name:
        raise ValueError('Unsafe source path')
    return path


def assemble(repo, ref, destination):
    commit = git(repo, 'rev-parse', '--verify', '--end-of-options', ref + '^{commit}').decode().strip()
    records = {}
    for row in git(repo, 'ls-tree', '-rz', '--full-tree', commit).split(b'\0'):
        if not row:
            continue
        header, raw_path = row.split(b'\t', 1)
        mode, kind, sha = header.decode('ascii').split()
        name = raw_path.decode('utf-8')
        safe_path(name)
        if kind != 'blob' or mode not in ('100644', '100755'):
            raise ValueError('Only regular tracked source files are supported: ' + name)
        records[name] = (mode, sha)
    overlays = LEGACY_OVERLAYS
    if OVERLAY_MANIFEST in records:
        config_bytes = git(repo, 'cat-file', 'blob', records[OVERLAY_MANIFEST][1])
        if len(config_bytes) > 4096:
            raise ValueError('Oversized overlay manifest')
        config = json.loads(config_bytes)
        if config != {'schema_version': 1, 'overlays': list(OVERLAYS)}:
            raise ValueError('Unsupported overlay manifest')
        overlays = OVERLAYS
    selected = {name: name for name in records if not name.startswith('checkpoints/')}
    for overlay in overlays:
        prefix = 'checkpoints/' + overlay + '/'
        candidates = {name[len(prefix):]: name for name in records
                      if name.startswith(prefix) and name[len(prefix):].split('/', 1)[0] in ('app', 'scripts', 'docs')}
        if not candidates:
            raise ValueError('Required source overlay is absent: ' + overlay)
        selected.update(candidates)
    if RECEIPT in selected:
        raise ValueError('Source collides with reserved assembly receipt')
    # Preflight all bytes before creating output; Git object IDs and full content
    # hashes are recorded, so an assembly can be independently reproduced.
    content = {}
    for target, source in sorted(selected.items()):
        safe_path(target)
        mode, sha = records[source]
        data = git(repo, 'cat-file', 'blob', sha)
        digest = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if digest != sha:
            raise ValueError('Git blob mismatch: ' + source)
        content[target] = (source, mode, sha, data)
    # mkdir without exist_ok rejects existing files/directories and symlinks.
    destination = Path(destination)
    destination.mkdir()
    receipt = {'schema_version': 1, 'source_commit': commit, 'overlays': list(overlays), 'files': []}
    for target, (source, mode, sha, data) in content.items():
        output = destination / target
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('xb') as stream:
            stream.write(data)
        output.chmod(0o755 if mode == '100755' else 0o644)
        if output.read_bytes() != data:
            raise IOError('Assembled source readback mismatch: ' + target)
        receipt['files'].append({'path': target, 'source_path': source, 'git_blob': sha,
                                 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'mode': mode})
    with (destination / RECEIPT).open('x', encoding='utf-8') as stream:
        json.dump(receipt, stream, indent=2, sort_keys=True)
        stream.write('\n')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ref', default='HEAD', help='Committed ref only; working-copy edits are excluded')
    parser.add_argument('--output', required=True, type=Path, help='Fresh nonexistent directory; parent must exist')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    receipt = assemble(repo, args.ref, args.output)
    print(json.dumps({'source_commit': receipt['source_commit'], 'files': len(receipt['files']),
                      'output': str(args.output), 'built': False, 'device_tested': False}))


if __name__ == '__main__':
    main()
