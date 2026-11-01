"""Loopback-fixture readiness publication; no production networking code."""
import json


def publish_ready(work, host, port):
    target = work / 'ready.json'
    temporary = work / 'ready.json.tmp'
    try:
        temporary.write_text(json.dumps({'host': host, 'port': port}))
        temporary.replace(target)  # Same-directory atomic visibility, not a durability claim.
    finally:
        temporary.unlink(missing_ok=True)
