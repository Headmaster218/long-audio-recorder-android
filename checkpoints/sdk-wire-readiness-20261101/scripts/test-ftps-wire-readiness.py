#!/usr/bin/env python3
"""Deterministic fixture file-visibility regression; no sockets, keys or third-party dependencies."""
import importlib.util
import json
import sys
from pathlib import Path
import tempfile
from unittest.mock import patch

sys.dont_write_bytecode = True
root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('fixture_readiness', root / 'app/src/testFixtures/ftps-wire/readiness.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
checks = 0


def check(value):
    global checks
    checks += 1
    assert value


with tempfile.TemporaryDirectory(prefix='recorder-ready-') as directory:
    work = Path(directory)
    final = work / 'ready.json'
    original = Path.write_text
    original_replace = Path.replace
    observed = []

    def interrupted_write(path, data, *args, **kwargs):
        # Force the exact scheduling window: creation/truncation precedes write completion.
        original(path, '')
        check(not final.exists())
        observed.append(True)
        return original(path, data, *args, **kwargs)

    def observed_replace(path, destination):
        check(path.parent == destination.parent == work)
        check(json.loads(path.read_text()) == {'host': '127.0.0.1', 'port': 43210})
        return original_replace(path, destination)

    with patch.object(Path, 'write_text', interrupted_write), patch.object(Path, 'replace', observed_replace):
        module.publish_ready(work, '127.0.0.1', 43210)
    check(len(observed) == 1)
    check(json.loads(final.read_text()) == {'host': '127.0.0.1', 'port': 43210})
    check(not (work / 'ready.json.tmp').exists())
    original_bytes = final.read_bytes()

    def failed_write(path, data, *args, **kwargs):
        original(path, '{')
        raise OSError('injected fixture write failure')

    try:
        with patch.object(Path, 'write_text', failed_write):
            module.publish_ready(work, '127.0.0.1', 43211)
        raise AssertionError('write failure was swallowed')
    except OSError:
        check(final.read_bytes() == original_bytes)
        check(not (work / 'ready.json.tmp').exists())
    try:
        with patch.object(Path, 'replace', side_effect=OSError('injected replace failure')):
            module.publish_ready(work, '127.0.0.1', 43211)
        raise AssertionError('replace failure was swallowed')
    except OSError:
        check(final.read_bytes() == original_bytes)
        check(not (work / 'ready.json.tmp').exists())
    # Retry atomically replaces the previous complete announcement, never a partial document.
    module.publish_ready(work, '127.0.0.1', 43212)
    check(json.loads(final.read_text())['port'] == 43212)
    check(not (work / 'ready.json.tmp').exists())
# Narrow source-order contract: publication follows real bind and TLS configuration.
server = (root / 'app/src/testFixtures/ftps-wire/server.py').read_text()
check(server.index("server=FTPServer(('127.0.0.1',0),Handler)") < server.index('server.handler.ssl_context.set_min_proto_version'))
check(server.index('port=server.socket.getsockname()[1]') < server.index("publish_ready(work, '127.0.0.1', port)"))
print('PASS', checks, 'fixture readiness assertions; deterministic interrupted write, no network')
