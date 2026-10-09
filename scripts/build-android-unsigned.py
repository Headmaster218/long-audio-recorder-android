#!/usr/bin/env python3
"""Linux-first, offline unsigned APK build from an already installed official SDK. Never signs/installs."""
import argparse, datetime, hashlib, os, pathlib, shutil, signal, subprocess, sys, time, zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', required=True)
parser.add_argument('--deadline-utc', required=True, help='Pause deadline, e.g. 2026-10-09T17:10:00+00:00')
args = parser.parse_args()
if sys.platform != 'linux':
    raise SystemExit('This guarded direct build is Linux-first; Windows execution remains unvalidated.')
root = pathlib.Path(__file__).resolve().parents[1]
sdk = pathlib.Path(args.sdk).resolve()
android = sdk / 'platforms/android-37.0/android.jar'
build_tools = sdk / 'build-tools/37.0.0'
for required in (android, build_tools/'aapt2', build_tools/'zipalign', build_tools/'lib/d8.jar'):
    if not required.is_file(): raise SystemExit('Missing installed SDK input: ' + str(required))
deadline = datetime.datetime.fromisoformat(args.deadline_utc)
if deadline.tzinfo is None: raise SystemExit('Deadline needs an explicit timezone')
GIB = 1024**3
FLOOR = 5 * GIB + 9 * GIB // 8  # main 5 GiB floor plus 1.125 GiB external reservation
LIMIT = 24 * 1024**2
RSS = 256 * 1024**2
build_root = root/'app/build'

def used_bytes():
    if not build_root.exists(): return 0
    return sum(p.stat().st_blocks * 512 for p in build_root.rglob('*') if p.is_file())

def guard(before_write=False):
    if datetime.datetime.now(datetime.timezone.utc) >= deadline: raise RuntimeError('Main-project pause deadline reached')
    if shutil.disk_usage(root).free < FLOOR + (LIMIT if before_write else 0): raise RuntimeError('Main disk floor/reservation unavailable')
    if used_bytes() > LIMIT: raise RuntimeError('24 MiB generated-build budget exceeded')
    available = int(next(x for x in open('/proc/meminfo') if x.startswith('MemAvailable:')).split()[1]) * 1024
    if available < 2 * GIB: raise RuntimeError('Host memory headroom below 2 GiB')

guard(True)
out = build_root/('direct-' + str(time.time_ns()))
for name in ('generated','classes','dex'): (out/name).mkdir(parents=True,exist_ok=True)
peak = 0

def run(command, log=None):
    global peak
    guard(True)
    print('RUN ' + ' '.join(str(x) for x in command), flush=True)
    output = open(log,'w') if log else None
    p = subprocess.Popen([str(x) for x in command], cwd=root, stdout=output, stderr=subprocess.STDOUT if output else None, start_new_session=True)
    started = time.monotonic()
    try:
        while p.poll() is None:
            guard()
            parent, memory = {}, {}
            for entry in pathlib.Path('/proc').iterdir():
                if not entry.name.isdigit(): continue
                try:
                    stat = (entry/'stat').read_text().rsplit(')',1)[1].split()
                    parent[int(entry.name)] = int(stat[1]); memory[int(entry.name)] = int(stat[21]) * os.sysconf('SC_PAGE_SIZE')
                except (OSError,ValueError,IndexError): pass
            family = {p.pid}
            while True:
                more = {pid for pid,ppid in parent.items() if ppid in family} - family
                if not more: break
                family |= more
            total = sum(memory.get(pid,0) for pid in family) + memory.get(os.getpid(),0)
            peak = max(peak,total)
            if total > RSS: raise RuntimeError('256 MiB sampled process-tree cap exceeded')
            if time.monotonic() - started > 90: raise RuntimeError('90-second command ceiling exceeded')
            time.sleep(0.025)
        if p.returncode != 0: raise RuntimeError('Command failed with exit ' + str(p.returncode))
    except BaseException:
        if p.poll() is None: os.killpg(p.pid,signal.SIGKILL)
        p.wait(); raise
    finally:
        if output: output.close()

java = ['java','-Xmx64m','-XX:MaxMetaspaceSize=64m','-XX:ReservedCodeCacheSize=16m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-Xss256k']
aapt = build_tools/'aapt2'
run([aapt,'compile','--dir',root/'app/src/main/res','-o',out/'resources.zip'])
run([aapt,'link','-I',android,'--manifest',root/'app/src/main/AndroidManifest.xml','--java',out/'generated','--min-sdk-version','29','--target-sdk-version','37','-o',out/'resources.apk',out/'resources.zip'])
sources = sorted((root/'app/src/main/java').rglob('*.java')) + sorted((out/'generated').rglob('*.java'))
source_list = out/'sources.txt'; source_list.write_text('\n'.join('"'+str(x)+'"' for x in sources)+'\n')
run(java + ['-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-bootclasspath',android,'-Xlint:all,-options','-d',out/'classes','@'+str(source_list)])
classes = out/'classfiles.txt'; classes.write_text('\n'.join(str(x) for x in sorted((out/'classes').rglob('*.class')))+'\n')
run(java + ['-cp',build_tools/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','29','--lib',android,'--output',out/'dex','@'+str(classes)])
guard(True)
combined = out/'combined-unsigned.apk'; shutil.copyfile(out/'resources.apk',combined)
with zipfile.ZipFile(combined,'a') as apk:
    for dex in sorted((out/'dex').glob('*.dex')): apk.write(dex,dex.name,compress_type=zipfile.ZIP_DEFLATED)
final = out/'recorder-unsigned.apk'
run([build_tools/'zipalign','-f','4',combined,final])
run([build_tools/'zipalign','-c','-v','4',final],out/'alignment.txt')
run([aapt,'dump','badging',final],out/'badging.txt')
run([aapt,'dump','xmltree',final,'--file','AndroidManifest.xml'],out/'manifest-tree.txt')
with zipfile.ZipFile(final) as apk:
    for needed in ('AndroidManifest.xml','classes.dex','resources.arsc'):
        if needed not in apk.namelist(): raise RuntimeError('Missing APK member: '+needed)
    if any(name.startswith('META-INF/') for name in apk.namelist()): raise RuntimeError('Unexpected signing metadata')
hash_value = hashlib.sha256(final.read_bytes()).hexdigest()
(out/'receipt.txt').write_text('UNSIGNED: not installable until authorized signing.\nSHA256 '+hash_value+'\nSampled observer+children peak MiB '+str(round(peak/1024**2,2))+'\n')
print('UNSIGNED_APK '+str(final)+'\nSHA256 '+hash_value+'\nSAMPLED_TOTAL_RSS_MIB '+str(round(peak/1024**2,2))+'\nGENERATED_DISK_BYTES '+str(used_bytes()))
