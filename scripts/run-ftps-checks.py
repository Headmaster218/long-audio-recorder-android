#!/usr/bin/env python3
"""Guarded source/host checks, CPU 6/nice 19. No dependency download, dex, APK or signing."""
import argparse, json, os, pathlib, signal, shutil, subprocess, time
parser = argparse.ArgumentParser()
parser.add_argument("--sdk", help="Existing official SDK, optional; never downloaded")
parser.add_argument("--resource-java", help="Existing aapt-generated R.java for unchanged app resources")
parser.add_argument("--checks", nargs="+", choices=["queue-sql","ftps","export","regressions","app-wiring","export-wiring","ftps-wiring","buffer-linkage","android-source-compile"], help="Optional focused rerun; omitted means all checks")
args = parser.parse_args()
if bool(args.sdk) != bool(args.resource_java): parser.error("Provide both --sdk and --resource-java, or neither")
root = pathlib.Path(__file__).resolve().parents[1]
limit = 12 * 1024**2
floor = 5 * 1024**3
rss_limit = 256 * 1024**2
out = root / 'app/build/ftps-checks'
out.mkdir(parents=True, exist_ok=True)
def size():
    total = root.stat().st_blocks * 512
    for path in root.rglob('*'):
        try: total += path.lstat().st_blocks * 512
        except FileNotFoundError: pass  # Synthetic fixtures can be atomically renamed while sampled.
    return total
def guard():
    if shutil.disk_usage(root).free < floor: raise RuntimeError('Main-project 5 GiB disk floor reached')
    if size() > limit: raise RuntimeError('12 MiB feature-worktree budget reached')
    if int(next(x for x in open('/proc/meminfo') if x.startswith('MemAvailable:')).split()[1]) < 2*1024**2:
        raise RuntimeError('Host available memory below 2 GiB')
def run(name, command):
    if args.checks and name not in args.checks: return
    guard(); peak = 0; start = time.monotonic()
    with open(out/(name+'.log'), 'w') as log:
        p = subprocess.Popen(['taskset','-c','6','nice','-n','19'] + command, cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            while p.poll() is None:
                guard(); parent, memory = {}, {}
                for path in pathlib.Path('/proc').iterdir():
                    if not path.name.isdigit(): continue
                    try:
                        st = (path/'stat').read_text().rsplit(')',1)[1].split()
                        parent[int(path.name)] = int(st[1]); memory[int(path.name)] = int(st[21])*os.sysconf('SC_PAGE_SIZE')
                    except (OSError,ValueError,IndexError): pass
                family = {p.pid}
                while True:
                    more = {pid for pid, pp in parent.items() if pp in family} - family
                    if not more: break
                    family |= more
                total = sum(memory.get(pid,0) for pid in family) + memory.get(os.getpid(),0)
                peak = max(peak,total)
                if total > rss_limit: raise RuntimeError('256 MiB sampled process-tree RSS cap exceeded')
                if time.monotonic()-start > 90: raise RuntimeError('90 second check ceiling reached')
                time.sleep(0.025)
        except BaseException:
            if p.poll() is None: os.killpg(p.pid, signal.SIGKILL)
            p.wait(); raise
    result = {'check':name,'command':command,'exit_code':p.returncode,'tree_rss_peak_bytes':peak,'seconds':round(time.monotonic()-start,3), 'worktree_disk_bytes':size(), 'free_disk_bytes':shutil.disk_usage(root).free}
    print(json.dumps(result),flush=True)
    (out/(name+'.json')).write_text(json.dumps(result,indent=2)+'\n')
    if p.returncode: raise SystemExit('Failed: '+name+'; inspect '+str(out/(name+'.log')))
run('ftps', ['sh','scripts/test-ftps.sh'])
run('queue-sql', ['python','scripts/test-ftps-queue-sql.py'])
run('export', ['sh','scripts/test-export.sh'])
run('regressions', ['sh','scripts/test-app-review.sh'])
run('app-wiring', ['python','scripts/check-app-review-wiring.py'])
run('export-wiring', ['python','scripts/check-export-wiring.py'])
run('ftps-wiring', ['python','scripts/check-ftps-wiring.py'])
run('buffer-linkage', ['python','scripts/check-java8-buffer-linkage.py','app/build/app-review/classes'])
if args.sdk and (not args.checks or "android-source-compile" in args.checks):
    android = pathlib.Path(args.sdk).resolve()/'platforms/android-37.0/android.jar'
    resource_java = pathlib.Path(args.resource_java).resolve()
    if not android.is_file() or not resource_java.is_file(): raise SystemExit('Missing SDK or generated R.java input')
    (out/'android-classes').mkdir(exist_ok=True)
    sources = sorted((root/'app/src/main/java').rglob('*.java')) + [resource_java]
    (out/'android-sources.txt').write_text('\n'.join(str(p) for p in sources)+'\n')
    run('android-source-compile', ['java','-Xmx48m','-XX:MaxMetaspaceSize=48m','-XX:ReservedCodeCacheSize=8m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-Xss256k',
        '-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-bootclasspath',str(android),
        '-Xlint:all,-options','-d',str(out/'android-classes'),'@'+str(out/'android-sources.txt')])
