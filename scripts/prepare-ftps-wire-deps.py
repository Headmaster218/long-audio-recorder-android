#!/usr/bin/env python3
"""Explicit opt-in, hash-pinned official PyPI fixture dependencies. No system upgrade."""
import argparse,hashlib,json,pathlib,subprocess,sys,urllib.request
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--download',action='store_true')
args=parser.parse_args()
if not args.download:raise SystemExit('Dependency download/install requires explicit --download; default is no action')
manifest=json.loads((root/'app/src/testFixtures/ftps-wire/dependencies.json').read_text())
work=root/'app/build/ftps-wire-dependencies';archives=work/'archives';target=work/'installed'
archives.mkdir(parents=True,exist_ok=True);target.mkdir(exist_ok=True)
if any(target.iterdir()):raise SystemExit('Existing dependency target retained; inspect rather than overwrite')
total=0
for item in manifest['packages']:
 if not item['url'].startswith('https://files.pythonhosted.org/'):raise SystemExit('Unapproved package host')
 with urllib.request.urlopen(item['url'],timeout=20) as response:
  data=response.read(item['size']+1)
 total+=len(data)
 if len(data)!=item['size'] or total>manifest['download_limit_bytes'] or hashlib.sha256(data).hexdigest()!=item['sha256']:raise SystemExit('Package bound/hash mismatch')
 (archives/item['filename']).write_bytes(data)
subprocess.run([sys.executable,'-m','pip','--disable-pip-version-check','install','--no-index','--no-deps','--no-build-isolation','--no-cache-dir','--target',str(target)]+[str(archives/i['filename'])for i in manifest['packages']],check=True,timeout=120)
size=sum(p.stat().st_size for p in target.rglob('*')if p.is_file())
if size>manifest['install_limit_bytes']:raise SystemExit('Fixture install bound exceeded; do not execute')
print(json.dumps({'download_bytes':total,'installed_bytes':size,'target':str(target)}))
