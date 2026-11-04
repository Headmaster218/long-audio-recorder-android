#!/usr/bin/env python3
"""Run supported offline host suites on verified assembled source; no device/network."""
import datetime
import json
from pathlib import Path
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    subprocess.run([sys.executable, str(root/'scripts/verify-assembled-source.py')], check=True)
    source = json.loads((root/'ASSEMBLED_SOURCE.json').read_text())['source_commit']
    out = root/'app/build'/('delivery-checks-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    out.mkdir(parents=True)
    # SDK-backed shell fixtures need separately compiled Android classes.
    sdk_suites = {'test-enrollment-consent.sh', 'test-ftps-admission.sh'}
    # The real FTPS wire fixture is deliberately
    # separate because it needs prepared dependencies and loopback networking.
    suites = sorted(p for p in (root/'scripts').glob('test-*.sh') if p.name not in sdk_suites)
    suites += sorted((root/'scripts').glob('test-*-sql.py'))
    suites += [root/'scripts/test-ftps-wire-readiness.py', root/'scripts/test-source-assembly.py', root/'scripts/test-delivery-verification.py']
    results = []
    report = {'source_commit': source, 'scope': 'offline_host_only', 'passed': False, 'suites': results,
              'not_run': ['SDK-backed admission and enrollment-consent fixtures', 'official SDK compile/APK packaging (unless separately requested)', 'real FTPS/TLS wire', 'Android runtime/device/OEM', 'physical server', 'all-day recording']}
    for script in suites:
        command = ['sh' if script.suffix == '.sh' else sys.executable, str(script)]
        with (out/(script.name + '.log')).open('w') as log:
            result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        results.append({'suite': script.name, 'exit_code': result.returncode})
        (out/'results.json').write_text(json.dumps(report, indent=2) + '\n')
        print(('PASS ' if result.returncode == 0 else 'FAIL ') + script.name, flush=True)
    # Ensure none of the tests modified the source being qualified.
    subprocess.run([sys.executable, str(root/'scripts/verify-assembled-source.py')], check=True)
    report['passed'] = all(item['exit_code'] == 0 for item in results)
    (out/'results.json').write_text(json.dumps(report, indent=2) + '\n')
    print('HOST_REPORT ' + str(out/'results.json'))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
