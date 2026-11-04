#!/usr/bin/env python3
"""Assemble committed source, run offline host checks, optionally build an unsigned APK.

Never downloads, signs, installs, uploads or uses a phone/server. Existing output
is never replaced. Uncommitted edits are deliberately excluded by assembly.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ref', default='HEAD')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--sdk', type=Path, help='Already installed official SDK; omit for host checks only')
    parser.add_argument('--deadline-utc', help='Required with --sdk; explicit timezone, e.g. 2026-11-05T12:00:00+00:00')
    args = parser.parse_args()
    if bool(args.sdk) != bool(args.deadline_utc):
        parser.error('--sdk and --deadline-utc must be supplied together')
    root = Path(__file__).resolve().parents[1]
    output = args.output.absolute()
    subprocess.run([sys.executable, str(root/'scripts/assemble-source.py'), '--ref', args.ref, '--output', str(output)], check=True)
    subprocess.run([sys.executable, str(output/'scripts/run-delivery-checks.py')], check=True)
    if args.sdk:
        subprocess.run([sys.executable, str(output/'scripts/build-android-unsigned.py'), '--sdk', str(args.sdk.absolute()), '--deadline-utc', args.deadline_utc], check=True)
    print(json.dumps({'source': str(output), 'host_checks': 'passed', 'unsigned_build': 'passed' if args.sdk else 'not_run', 'device_tested': False}))


if __name__ == '__main__':
    main()
