#!/usr/bin/env python3
"""Explicit bounded real-wire acceptance. Requires reviewed fixture and separately prepared pinned dependencies."""
import argparse,datetime,hashlib,json,os,pathlib,shutil,signal,socket,subprocess,sys,time
root=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--production',required=True);p.add_argument('--android',required=True);p.add_argument('--execute-reviewed-fixture',action='store_true')
a=p.parse_args()
if not a.execute_reviewed_fixture:raise SystemExit('No execution without --execute-reviewed-fixture')
if sys.platform!='linux':raise SystemExit('This bounded process-tree runner is Linux-only')
if os.sched_getaffinity(0)!={3}:raise SystemExit('Run with taskset -c3 nice -n19')
if os.getpriority(os.PRIO_PROCESS,0)<19:raise SystemExit('Run at nice19')
production=pathlib.Path(a.production).resolve();android=pathlib.Path(a.android).resolve()
fixture=root/'app/src/testFixtures/ftps-wire';deps=root/'app/build/ftps-wire-dependencies/installed'
if not deps.is_dir() or not android.is_file():raise SystemExit('Existing dependencies and official SDK are required')
sys.path.insert(0,str(deps))
import importlib.metadata
manifest=json.loads((fixture/'dependencies.json').read_text())
for item in manifest['packages']:
 if importlib.metadata.version(item['name'])!=item['version']:raise SystemExit('Dependency version mismatch')
 archive=deps.parent/'archives'/item['filename']
 if not archive.is_file() or archive.stat().st_size!=item['size'] or hashlib.sha256(archive.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('Dependency archive integrity mismatch')
if sum(f.stat().st_size for f in deps.rglob('*')if f.is_file())>16*1024*1024:raise SystemExit('Dependency size bound')
work=root/'app/build'/('ftps-wire-'+str(time.time_ns()));work.mkdir(parents=True)
keys=work/'ephemeral-keys';keys.mkdir(mode=0o700)
started=time.monotonic();peak=0;owned=[];results=[]

def guard():
 global peak
 if time.monotonic()-started>600:raise TimeoutError('Ten-minute suite deadline')
 if shutil.disk_usage(root).free<5*1024**3:raise RuntimeError('Disk floor')
 parents={};rss={}
 for f in pathlib.Path('/proc').iterdir():
  if not f.name.isdigit():continue
  try:
   fields=(f/'stat').read_text().rsplit(')',1)[1].split();parents[int(f.name)]=int(fields[1]);rss[int(f.name)]=int(fields[21])*os.sysconf('SC_PAGE_SIZE')
  except (OSError,ValueError,IndexError):pass
 family={os.getpid()}
 while True:
  more={pid for pid,parent in parents.items()if parent in family}-family
  if not more:break
  family|=more
 memory=sum(rss.get(pid,0)for pid in family);peak=max(peak,memory)
 if memory>384*1024**2:raise RuntimeError('384MiB process-tree guard')
 if sum(f.stat().st_size for f in work.rglob('*')if f.is_file())>8*1024**2:raise RuntimeError('8MiB fixture output bound')

def spawn(cmd,out,env=None):
 guard();stream=open(out,'w');proc=subprocess.Popen([str(x)for x in cmd],cwd=root,stdout=stream,stderr=subprocess.STDOUT,env=env,start_new_session=True)
 owned.append((proc,stream));return proc

def finish(proc,timeout):
 until=time.monotonic()+timeout
 while proc.poll()is None:
  guard()
  if time.monotonic()>until:raise TimeoutError('Finite subprocess deadline')
  time.sleep(.025)
 if proc.returncode:raise RuntimeError('Fixture process failed: '+str(proc.returncode))

def invoke(cmd,out,timeout=30):
 proc=spawn(cmd,out);finish(proc,timeout)

try:
 # Only ephemeral test identities, never OS/JDK trust configuration.
 from cryptography import x509
 from cryptography.x509.oid import NameOID
 from cryptography.hazmat.primitives import hashes,serialization
 from cryptography.hazmat.primitives.asymmetric import rsa
 import ipaddress
 now=datetime.datetime.now(datetime.timezone.utc)
 ca_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
 leaf_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
 other_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
 def cert(subject,issuer,key,public,ca=False,ip=None):
  b=x509.CertificateBuilder().subject_name(subject).issuer_name(issuer).public_key(public).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.BasicConstraints(ca=ca,path_length=0 if ca else None),critical=True)
  if ip:b=b.add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(ip))]),critical=False)
  return b.sign(key,hashes.SHA256())
 name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Synthetic recorder fixture CA')])
 other=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Untrusted synthetic fixture CA')])
 ca=cert(name,name,ca_key,ca_key.public_key(),True)
 leaf=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Synthetic loopback server')])
 objects={'ca':ca,'positive':cert(leaf,name,ca_key,leaf_key.public_key(),ip='127.0.0.1'),'wrong':cert(leaf,name,ca_key,leaf_key.public_key(),ip='127.0.0.2'),'untrusted':cert(leaf,other,other_key,leaf_key.public_key(),ip='127.0.0.1')}
 for label,value in objects.items():(keys/(label+'.pem')).write_bytes(value.public_bytes(serialization.Encoding.PEM))
 private=keys/'server.key';private.write_bytes(leaf_key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));private.chmod(0o600)
 fingerprints={k:v.fingerprint(hashes.SHA256()).hex()for k,v in objects.items()}
 trust=keys/'trust.p12'
 invoke(['keytool','-importcert','-noprompt','-storetype','PKCS12','-keystore',trust,'-storepass','synthetic','-alias','fixture-ca','-file',keys/'ca.pem'],work/'trust-setup.log')
 classes=work/'classes';classes.mkdir();sources=sorted((fixture/'java').rglob('*.java'))
 java=['java','-Xmx64m','-XX:MaxMetaspaceSize=48m','-XX:ReservedCodeCacheSize=8m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-Xss256k']
 source_list=work/'sources.txt';source_list.write_text('\n'.join(str(f)for f in sources)+'\n')
 invoke(java+['-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-cp',str(production)+':'+str(android),'-d',classes,'@'+str(source_list)],work/'compile.log',60)
 scenarios=['success','untrusted_control','wrong_host_control','wrong_data_cert','corrupt_readback','truncated_readback','partial_disconnect','lost_final_reply','stalled_handshake']
 for scenario in scenarios:
  case=work/scenario;case.mkdir();case_started=time.monotonic()
  certname='untrusted'if scenario=='untrusted_control'else'wrong'if scenario=='wrong_host_control'else'positive'
  env=dict(os.environ,PYTHONPATH=str(deps),PYTHONDONTWRITEBYTECODE='1')
  server=spawn([sys.executable,fixture/'server.py',scenario,case,keys/(certname+'.pem'),private,keys/'wrong.pem'],case/'server.log',env)
  while not(case/'ready.json').exists():
   guard()
   if server.poll()is not None or time.monotonic()-case_started>5:raise RuntimeError('Server did not become ready')
   time.sleep(.025)
  address=json.loads((case/'ready.json').read_text());assert address['host']=='127.0.0.1'
  client=spawn(java+['-Djavax.net.ssl.trustStore='+str(trust),'-Djavax.net.ssl.trustStorePassword=synthetic','-Djavax.net.ssl.trustStoreType=PKCS12','-cp',str(classes)+':'+str(production)+':'+str(android),'io.github.headmaster218.recorder.android.WireClient',scenario,case,str(address['port'])],case/'client.log')
  finish(client,25);(case/'stop').touch();finish(server,3)
  if time.monotonic()-case_started>30:raise TimeoutError('Thirty-second case bound')
  receipt=json.loads((case/'client.log').read_text().splitlines()[-1]);facts=json.loads((case/'server-result.json').read_text())
  assert receipt['scenario']==scenario and receipt['local_unchanged'] and not receipt['cleanup_invoked']
  assert receipt['source_hash_before']==receipt['source_hash_after'] and receipt['source_bytes']<=128*1024
  assert receipt['all_raw_sockets_closed'] and receipt['raw_connections_made']>0
  assert receipt['callback_registrations']==receipt['callback_unregistrations']
  expected='verified'if scenario=='success'else'reconciled'if scenario=='lost_final_reply'else'expected_failure'
  assert receipt['status']==expected and receipt['first_failed']==(scenario!='success')
  if receipt['result'] is not None:
   result=receipt['result'];assert result['verified_at_time'] and result['bytes']==receipt['source_bytes']
   prefix=receipt['remote_directory'].lstrip('/')+'/'
   for name,key in [('audio.wav','wav_sha256'),('segment.v1.bin','metadata_sha256'),('delivery.v1.txt','marker_sha256')]:
    assert facts['remote_files'][prefix+name]['sha256']==result[key]
   assert facts['remote_files'][prefix+'audio.wav']['size']==result['bytes']
  else:assert scenario not in('success','lost_final_reply')
  if scenario=='stalled_handshake':assert receipt['cancel_fired'] and receipt['closed_at_cancel'] and 0<=receipt['cancel_return_ms']<=3000
  assert facts['all_closed'] and not facts['errors'] and facts['data_bytes']<=2*1024*1024
  for port in facts['listeners']:
   with socket.socket()as probe:
    probe.settimeout(.2);assert probe.connect_ex(('127.0.0.1',port))!=0,'listener survived case cleanup'
  assert all(c['tls']for c in facts['commands']if c['cmd']in('USER','PASS'))
  if scenario in('untrusted_control','wrong_host_control','stalled_handshake'):
   assert not any(c['cmd']in('USER','PASS','STOR')for c in facts['commands'])
  if scenario=='wrong_data_cert':assert facts['data_bytes']==0
  if scenario in('partial_disconnect','lost_final_reply'):
   replay=[c for c in facts['commands']if c['session']==2]
   assert replay and all(c['cmd']not in('MKD','STOR','REST','RNFR','RNTO','DELE','RMD')for c in replay)
  if scenario in('corrupt_readback','truncated_readback','partial_disconnect','lost_final_reply'):assert facts['fault']
  assert all(c['cmd']not in('DELE','RMD')for c in facts['commands'])
  results.append({'scenario':scenario,'seconds':round(time.monotonic()-case_started,3),'client':receipt,'server':facts})
  if sum(r['server']['data_bytes']for r in results)>8*1024**2:raise RuntimeError('Aggregate application wire-byte bound')
  print('PASS real-wire case '+scenario,flush=True)
 report={'base_commit':'8edff98955655350e5af6b2d8b4ccdf4cb89fd9b','scenarios':results,'certificate_sha256':fingerprints,'peak_process_tree_rss':peak,'seconds':round(time.monotonic()-started,3),'dependencies':manifest,'limitations':'Real JVM sockets/TLS and production FTPS classes; Android routing/trust provider/lifecycle and automatic cleanup coordinator are not executed.'}
 (work/'report.json').write_text(json.dumps(report,indent=2)+'\n')
 print('REPORT '+str(work/'report.json'))
finally:
 try:
  for proc,stream in owned:
   try:
    if proc.poll()is None:
     try:os.killpg(proc.pid,signal.SIGTERM)
     except ProcessLookupError:pass
     try:proc.wait(timeout=1)
     except subprocess.TimeoutExpired:
      try:os.killpg(proc.pid,signal.SIGKILL)
      except ProcessLookupError:pass
      proc.wait(timeout=2)
   finally:stream.close()
 finally:
  # Only this freshly created fixture key directory; never source or user data.
  shutil.rmtree(keys)
  print('EPHEMERAL_KEYS_REMOVED '+str(not keys.exists()),flush=True)
