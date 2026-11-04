#!/usr/bin/env python3
"""Synthetic 40-source actual coordinator/SQLite/FTPS integration; no device or external server."""
import argparse,datetime,hashlib,ipaddress,json,os,pathlib,shutil,socket,subprocess,sys,time
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
p=argparse.ArgumentParser();p.add_argument('--production',required=True,type=pathlib.Path);p.add_argument('--android',required=True,type=pathlib.Path);p.add_argument('--dependencies',required=True,type=pathlib.Path);p.add_argument('--wait-capacity',action='store_true');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
fixture=root/'app/src/testFixtures/catchup-wire'
queue_fixture=root/'app/src/testFixtures/queue-host'
wire_fixture=root/'app/src/testFixtures/ftps-wire'
for key in ['production','android','dependencies']:setattr(a,key,getattr(a,key).resolve())
import importlib.metadata
manifest=json.loads((wire_fixture/'dependencies.json').read_text())
installed={d.metadata['Name'].lower().replace('-','_'):d.version for d in importlib.metadata.distributions(path=[str(a.dependencies)])}
for item in manifest['packages']:
 archive=a.dependencies.parent/'archives'/item['filename']
 if installed.get(item['name'].lower().replace('-','_'))!=item['version']:raise SystemExit('Pinned fixture dependency version mismatch')
 if not archive.is_file() or archive.stat().st_size!=item['size'] or hashlib.sha256(archive.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('Pinned fixture archive mismatch')
if sum(p.stat().st_size for p in a.dependencies.rglob('*') if p.is_file())>manifest['install_limit_bytes']:raise SystemExit('Fixture dependency size bound')

work=root/'app/build'/('catchup-wire-'+str(time.time_ns()));work.mkdir(parents=True);keys=work/'ephemeral-keys';keys.mkdir(mode=0o700);owned=[];started=time.monotonic();peak=0

def guard():
 global peak
 if time.monotonic()-started>600:raise TimeoutError('Ten-minute integration limit')
 if shutil.disk_usage(root).free<5*1024**3:raise RuntimeError('Five-GiB free-space floor')
 if sum(p.stat().st_size for p in work.rglob('*') if p.is_file())>16*1024**2:raise RuntimeError('16-MiB test-output bound')
 parents={};memory={}
 for path in pathlib.Path('/proc').iterdir():
  if not path.name.isdigit():continue
  try:
   fields=(path/'stat').read_text().rsplit(')',1)[1].split();parents[int(path.name)]=int(fields[1]);memory[int(path.name)]=int(fields[21])*os.sysconf('SC_PAGE_SIZE')
  except (OSError,ValueError,IndexError):pass
 family={os.getpid()}
 while True:
  more={pid for pid,parent in parents.items() if parent in family}-family
  if not more:break
  family|=more
 rss=sum(memory.get(pid,0) for pid in family);peak=max(peak,rss)
 if rss>384*1024**2:raise RuntimeError('384-MiB sampled test-process-tree bound')

def run(cmd,label,timeout=30,expected=0):
 with (work/(label+'.log')).open('w') as log:
  proc=subprocess.Popen([str(x)for x in cmd],stdout=log,stderr=subprocess.STDOUT,start_new_session=True);owned.append(proc)
  until=time.monotonic()+timeout
  try:
   while proc.poll() is None:
    guard()
    if time.monotonic()>until:raise TimeoutError(label+' deadline')
    time.sleep(.05)
   code=proc.returncode
  except BaseException:
   if proc.poll() is None:os.killpg(proc.pid,9)
   proc.wait();raise
 if code!=expected:raise RuntimeError(label+' failed: '+str(code)+' '+(work/(label+'.log')).read_text()[-7000:])
 print('PASS '+label,flush=True)

try:
 ca_key=rsa.generate_private_key(public_exponent=65537,key_size=2048);key=rsa.generate_private_key(public_exponent=65537,key_size=2048);now=datetime.datetime.now(datetime.timezone.utc)
 name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Synthetic catch-up fixture CA')]);leaf=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Synthetic loopback')])
 def cert(subject,public,is_ca):
  b=x509.CertificateBuilder().subject_name(subject).issuer_name(name).public_key(public).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.BasicConstraints(ca=is_ca,path_length=0 if is_ca else None),True)
  if not is_ca:b=b.add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]),False)
  return b.sign(ca_key,hashes.SHA256())
 for label,obj in [('ca',cert(name,ca_key.public_key(),True)),('server',cert(leaf,key.public_key(),False))]:(keys/(label+'.pem')).write_bytes(obj.public_bytes(serialization.Encoding.PEM))
 private=keys/'server.key';private.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));private.chmod(0o600)
 trust=keys/'trust.p12';run(['keytool','-importcert','-noprompt','-storetype','PKCS12','-keystore',trust,'-storepass','synthetic','-alias','fixture','-file',keys/'ca.pem'],'trust')
 classes=work/'classes';classes.mkdir();sources=sorted(p for p in (wire_fixture/'java').rglob('*.java') if p.name not in ('Context.java','WireClient.java'))
 sources+=sorted((fixture/'java').rglob('*.java'))
 sources+=sorted(p for p in (queue_fixture/'adapter').rglob('*.java') if p.name not in ('Context.java','RecorderState.java'))
 sources+=[root/'app/src/testFixtures/finalize-overflow/android/os/Handler.java',root/'app/src/testFixtures/catchup-queue/java/io/github/headmaster218/recorder/android/RealCoordinatorHarness.java']
 (work/'sources.txt').write_text('\n'.join(str(x)for x in sources)+'\n')
 java=['java','-Xmx64m','-XX:MaxMetaspaceSize=64m','-XX:ReservedCodeCacheSize=16m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-Xss256k']
 run(java+['-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-cp',str(a.production)+':'+str(a.android),'-d',classes,'@'+str(work/'sources.txt')],'compile')
 case=work/'server';case.mkdir();serverlog=(work/'server.log').open('w');env=dict(os.environ,PYTHONPATH=str(a.dependencies),PYTHONDONTWRITEBYTECODE='1')
 server=subprocess.Popen([sys.executable,str(fixture/'server.py'),'success',str(case),str(keys/'server.pem'),str(private),str(keys/'server.pem')],stdout=serverlog,stderr=subprocess.STDOUT,env=env,start_new_session=True);owned.append(server)
 until=time.monotonic()+10
 while not(case/'ready.json').exists():
  guard()
  if server.poll()is not None or time.monotonic()>until:raise RuntimeError('server startup '+(work/'server.log').read_text())
  time.sleep(.05)
 address=json.loads((case/'ready.json').read_text());assert address['host']=='127.0.0.1';port=address['port'];app=work/'app'
 client=java+['-Dqueue.host.bridge='+str(queue_fixture/'sqlite_bridge.py'),'-Djavax.net.ssl.trustStore='+str(trust),'-Djavax.net.ssl.trustStorePassword=synthetic','-Djavax.net.ssl.trustStoreType=PKCS12','-cp',str(classes)+':'+str(a.production)+':'+str(a.android),'io.github.headmaster218.recorder.android.CatchupWireClient']
 run(client+['setup',app,str(port)],'setup',60)
 if a.wait_capacity:
  due=int((app/'due.txt').read_text());delay=max(0,due/1000-time.time());print('WAIT real persisted capacity deadline '+str(round(delay,3))+' seconds',flush=True)
  while time.time()<due/1000:
   guard()
   if server.poll()is not None:raise RuntimeError('server exited during capacity wait')
   time.sleep(min(1,max(.001,due/1000-time.time())))
 run(client+['first',app]+([] if a.wait_capacity else ['force-due']),'first-hard-exit',120,23)
 run(client+['finish',app],'finish-after-restart',60)
 (case/'stop').touch();server.wait(timeout=5);serverlog.close();assert server.returncode==0
 facts=json.loads((case/'server-result.json').read_text());assert facts['all_closed'] and not facts['errors'];assert facts['sessions']==40 and facts['control_tls']==40 and facts['data_tls']==360
 assert sum(x['cmd']=='STOR' for x in facts['commands'])==120
 assert sum(x['cmd']=='RETR' for x in facts['commands'])==240
 assert facts['data_bytes']<2*1024*1024
 records=[line.split('\t')for line in (app/'final.tsv').read_text().splitlines()];assert len(records)==40
 for source,state,attempt,wav,meta,marker,verified in records:
  assert state=='VERIFIED_AT_TIME' and int(verified)>0
  prefix='fixture/'+attempt+'/'
  for name,digest in [('audio.wav',wav),('segment.v1.bin',meta),('delivery.v1.txt',marker)]:assert facts['remote_files'][prefix+name]['sha256']==digest,(prefix,name)
 assert len(facts['remote_files'])==120
 assert all(x['cmd']not in ('DELE','RMD','RNFR','RNTO')for x in facts['commands'])
 for port in facts['listeners']:
  with socket.socket() as probe:probe.settimeout(.2);assert probe.connect_ex(('127.0.0.1',port))!=0
 report={'passed':True,'segments':40,'initial_callback_rows':32,'recovered_missing':8,'verified_before_hard_exit':36,'verified_after_restart':40,'server_sessions':facts['sessions'],'remote_files':len(facts['remote_files']),'wire_bytes':facts['data_bytes'],'actual_capacity_wait':a.wait_capacity,'seconds':round(time.monotonic()-started,3),'sampled_process_tree_peak_bytes':peak,'limits':'Actual production coordinator, queue SQL, FTPS and host filesystem; Android APIs/scheduler are fixtures. Host SQLite via Python is not Android runtime or power-loss proof.'}
 (work/'report.json').write_text(json.dumps(report,indent=2)+'\n');print('REPORT '+str(work/'report.json'),flush=True)
finally:
 for proc in owned:
  if proc.poll()is None:
   os.killpg(proc.pid,15)
   try:proc.wait(timeout=3)
   except subprocess.TimeoutExpired:os.killpg(proc.pid,9);proc.wait()
 shutil.rmtree(keys)
 print('EPHEMERAL_KEYS_REMOVED '+str(not keys.exists()),flush=True)
