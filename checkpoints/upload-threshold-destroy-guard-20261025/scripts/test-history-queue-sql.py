#!/usr/bin/env python3
"""Production DDL/queries on host SQLite; transaction/ContentValues flow modeled, not Android Java execution."""
import re,sqlite3,tempfile
from pathlib import Path
source=(Path(__file__).resolve().parents[1]/'app/src/main/java/io/github/headmaster218/recorder/android/FtpsQueue.java').read_text()
statements=re.findall(r'\.execSQL\("([^"\n]*)"',source)
queries=re.findall(r'rawQuery\("([^"\n]*)"',source)
def sql(prefix):
 result=list(dict.fromkeys(s for s in statements+queries if s.startswith(prefix)));assert len(result)==1,(prefix,result);return result[0]
checks=0
def check(x):
 global checks
 assert x;checks+=1
P='a'*64;Q='b'*64;H='c'*64

def create(db,version=4):
 db.execute('PRAGMA foreign_keys=ON')
 db.execute(sql('CREATE TABLE profiles'))
 settings=sql('CREATE TABLE settings').replace(", upload_threshold_bytes INTEGER NOT NULL DEFAULT 9600000 CHECK(typeof(upload_threshold_bytes)='integer' AND upload_threshold_bytes>0)",'');queue=sql('CREATE TABLE queue')
 if version<4:
  queue=queue.replace(', retirement_generation INTEGER','');settings=settings.replace(', auto_capacity_at INTEGER NOT NULL DEFAULT 0','')
 if version<3:
  settings=settings.replace(', cleanup_revision TEXT REFERENCES profiles(revision)','');queue=queue.replace(", cleanup_state TEXT NOT NULL DEFAULT 'NONE', cleanup_binding BLOB",'')
 if version==1:settings=settings.replace(", auto_revision TEXT REFERENCES profiles(revision), auto_notice TEXT NOT NULL DEFAULT 'OFF'",'')
 db.execute(settings);db.execute(sql('INSERT INTO settings'));db.execute(queue);db.execute(sql('CREATE INDEX runnable'))
 if version==4:history(db)
def history(db):
 for prefix in ['CREATE TABLE retirement_meta','INSERT INTO retirement_meta','CREATE TABLE retired_ranges','CREATE TABLE sequence_floors','CREATE INDEX retirement_pending']:db.execute(sql(prefix))
def seed(db):
 for revision in [P,Q]:db.execute('INSERT INTO profiles VALUES(?,?,?,?,?,?)',(revision,'synthetic.invalid',21,'synthetic','/synthetic',b'\x01\x02'))
 db.execute("UPDATE settings SET current_revision=?,auto_revision=?,cleanup_revision=?,auto_notice='ON'",(P,P,P))
 db.execute("UPDATE retirement_meta SET store_id='00000000-0000-0000-0000-000000000001',ready=1")
def item(db,n=0,revision=P,cleanup='REMOVED',state='VERIFIED_AT_TIME',capture=H):
 identity=f'segment-{capture}-{n}.ready'
 db.execute('INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,attempt,detail,result_metadata_hash,marker_hash,verified_at,cleanup_state,cleanup_binding) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)',(identity,revision,'d'*64,'e'*64,60,1,state,'synthetic-attempt','synthetic','f'*64,'1'*64,2,cleanup,b'proof' if cleanup=='REMOVED' else None));return identity

def contains(db,identity,revision):
 m=re.fullmatch(r'segment-([0-9a-f]{64})-(0|[1-9][0-9]*)\.ready',identity)
 if not m or int(m[2])>=2**63-1:return False
 return db.execute(sql('SELECT lo FROM retired_ranges WHERE revision=?'),(revision,m[1],int(m[2]),int(m[2]))).fetchone() is not None

def begin(db,identity):
 # Models ContentValues/transaction glue; interval algorithm itself has production Java tests.
 db.execute('BEGIN IMMEDIATE')
 try:
  rows=db.execute('SELECT * FROM queue WHERE source=? ORDER BY id',(identity,)).fetchall()
  proof=next((r for r in rows if r['cleanup_state']=='REMOVED' and r['cleanup_binding']),None)
  allowed={'REMOVED','NONE','RETAINED','RESTORED'}
  if not proof or any(r['state']!='VERIFIED_AT_TIME' or r['verified_at']<=0 or not r['attempt'] or not r['result_metadata_hash'] or not r['marker_hash'] or r['wav_hash']!=proof['wav_hash'] or r['metadata_hash']!=proof['metadata_hash'] or r['bytes']!=proof['bytes'] or r['cleanup_state'] not in allowed for r in rows):raise ValueError('retain source')
  if db.execute('SELECT count(DISTINCT source) FROM queue WHERE retirement_generation IS NOT NULL').fetchone()[0]>=8:raise ValueError('pending cap')
  m=re.fullmatch(r'segment-([0-9a-f]{64})-(0|[1-9][0-9]*)\.ready',identity)
  if not m or int(m[2])>=2**63-1:raise ValueError('ordinal')
  capture,n=m[1],int(m[2]);generation=db.execute('SELECT generation FROM retirement_meta').fetchone()[0]
  if generation==2**63-1:raise ValueError('generation exhausted')
  for row in rows:
   ranges=[tuple(r) for r in db.execute(sql('SELECT lo,hi FROM retired_ranges WHERE revision=?'),(row['revision'],capture))]
   ranges.append((n,n));ranges.sort();merged=[]
   for lo,hi in ranges:
    if merged and lo<=merged[-1][1]+1:merged[-1]=(merged[-1][0],max(merged[-1][1],hi))
    else:merged.append((lo,hi))
   db.execute('DELETE FROM retired_ranges WHERE revision=? AND capture=?',(row['revision'],capture))
   db.executemany('INSERT INTO retired_ranges VALUES(?,?,?,?)',[(row['revision'],capture,lo,hi)for lo,hi in merged])
  old=db.execute(sql('SELECT next_sequence FROM sequence_floors WHERE capture=?'),(capture,)).fetchone()
  db.execute('INSERT OR REPLACE INTO sequence_floors VALUES(?,?)',(capture,max(n+1,old[0] if old else 0)))
  if db.execute('SELECT count(*) FROM retired_ranges').fetchone()[0]>2048 or db.execute('SELECT count(*) FROM sequence_floors').fetchone()[0]>256 or db.execute(sql('SELECT count(*) FROM (SELECT revision,capture')).fetchone()[0]>512:raise ValueError('history cap')
  db.execute('UPDATE queue SET retirement_generation=? WHERE source=?',(generation+1,identity));db.execute('UPDATE retirement_meta SET generation=?',(generation+1,));db.execute('COMMIT');return generation+1
 except ValueError:db.execute('ROLLBACK');return None

def dbnew(path=':memory:'):
 db=sqlite3.connect(path,isolation_level=None);db.row_factory=sqlite3.Row;create(db);seed(db);return db
for version in (1,2,3):
 db=sqlite3.connect(':memory:',isolation_level=None);create(db,version)
 db.execute('INSERT INTO profiles VALUES(?,?,?,?,?,?)',(P,'synthetic.invalid',21,'synthetic','/synthetic',b'\x01\x02'))
 for i,state in enumerate(['QUEUED','RUNNING','RECONCILE','BLOCKED','VERIFIED_AT_TIME']):db.execute('INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,detail,attempt,verified_at) VALUES(?,?,?,?,?,?,?,?,?,?)',(str(i),P,'d'*64,'e'*64,60,1,state,'retain','attempt',2))
 before=db.execute('SELECT id,source,state,attempt,verified_at FROM queue').fetchall()
 for s in statements:
  if not s.startswith('ALTER TABLE'):continue
  if ('auto_revision' in s or 'auto_notice' in s) and version!=1:continue
  if ('cleanup_revision' in s or 'cleanup_state' in s or 'cleanup_binding' in s) and version>=3:continue
  db.execute(s)
 history(db)
 check(db.execute('SELECT auto_capacity_at FROM settings').fetchone()==(0,))
 check(before==db.execute('SELECT id,source,state,attempt,verified_at FROM queue').fetchall());check(db.execute('SELECT credential FROM profiles').fetchone()==(b'\x01\x02',));check(db.execute('SELECT DISTINCT retirement_generation FROM queue').fetchall()==[(None,)]);check(db.execute('PRAGMA integrity_check').fetchone()==('ok',));check(db.execute('PRAGMA foreign_key_check').fetchall()==[])
 db.close()
with tempfile.TemporaryDirectory() as directory:
 path=Path(directory)/'synthetic.db';db=dbnew(path)
 identity=item(db,cleanup='WAIT_SOURCE');item(db,revision=Q,cleanup='NONE',state='QUEUED');check(begin(db,identity)is None)
 db.execute("UPDATE queue SET state='VERIFIED_AT_TIME' WHERE revision=?",(Q,));check(begin(db,identity)is None)
 db.execute("UPDATE queue SET cleanup_state='REMOVED',cleanup_binding=X'70726f6f66' WHERE revision=?",(P,))
 generation=begin(db,identity);check(generation==1);check(db.execute('SELECT count(*) FROM queue').fetchone()[0]==2)
 db.close();db=sqlite3.connect(path,isolation_level=None);db.row_factory=sqlite3.Row
 check(contains(db,identity,P));check(contains(db,identity,Q));check(not contains(db,identity,'2'*64));check(db.execute('SELECT next_sequence FROM sequence_floors').fetchone()[0]==1)
 # After separate real filesystem proof tests, model second transaction deleting retained intents.
 db.execute('DELETE FROM queue WHERE source=? AND retirement_generation=?',(identity,generation));check(db.execute('SELECT count(*) FROM queue').fetchone()[0]==0)
 check(contains(db,identity,P));check(db.execute('SELECT auto_revision FROM settings').fetchone()[0]==P)
 for n in [2,1]:
  target=item(db,n);g=begin(db,target);check(g is not None);db.execute('DELETE FROM queue WHERE source=? AND retirement_generation=?',(target,g))
 check(tuple(db.execute('SELECT lo,hi FROM retired_ranges WHERE revision=?',(P,)).fetchone())==(0,2));check(not contains(db,f'segment-{H}-3.ready',P))
 # Explicitly cleared enrollment is never resurrected by compaction.
 db.execute('UPDATE settings SET auto_revision=NULL');target=item(db,4);check(begin(db,target)is not None);check(db.execute('SELECT auto_revision FROM settings').fetchone()[0]is None)
 db.close()
db=dbnew();identity=item(db,cleanup='WAIT_SOURCE');item(db,revision=Q);db.execute(sql("UPDATE queue SET cleanup_state='RETAINED'"));check(begin(db,identity)==1)
for cleanup in ['INTENT','QUARANTINED','PURGING','RESTORE_ONLY','BLOCKED','DEFERRED']:
 db=dbnew();identity=item(db);item(db,revision=Q,cleanup=cleanup);check(begin(db,identity)is None);check(db.execute('SELECT count(*) FROM retired_ranges').fetchone()[0]==0)
for cap in ['range','floor','group','pending','generation']:
 db=dbnew();identity=item(db,5000,capture='9'*64)
 if cap=='range':
  db.execute('INSERT INTO sequence_floors VALUES(?,?)',(H,5000));db.executemany('INSERT INTO retired_ranges VALUES(?,?,?,?)',[(P,H,i*2,i*2)for i in range(2048)])
 elif cap=='floor':db.executemany('INSERT INTO sequence_floors VALUES(?,1)',[(f'{i:064x}',)for i in range(256)])
 elif cap=='group':
  for revision in [P,Q]+[f'{i:064x}'for i in range(30)]:
   db.execute('INSERT OR IGNORE INTO profiles VALUES(?,?,?,?,?,NULL)',(revision,'synthetic.invalid',21,'synthetic','/synthetic'))
   for j in range(16):
    capture=f'{j:064x}';db.execute('INSERT OR IGNORE INTO sequence_floors VALUES(?,1)',(capture,));db.execute('INSERT INTO retired_ranges VALUES(?,?,0,0)',(revision,capture))
 elif cap=='pending':
  for n in range(8):other=item(db,n);check(begin(db,other)is not None)
 else:db.execute('UPDATE retirement_meta SET generation=?',(2**63-1,))
 before=[tuple(r)for r in db.execute('SELECT * FROM retired_ranges ORDER BY revision,capture,lo')];generation=db.execute('SELECT generation FROM retirement_meta').fetchone()[0]
 check(begin(db,identity)is None);check(before==[tuple(r)for r in db.execute('SELECT * FROM retired_ranges ORDER BY revision,capture,lo')]);check(db.execute('SELECT generation FROM retirement_meta').fetchone()[0]==generation);check(db.execute('SELECT retirement_generation FROM queue WHERE source=?',(identity,)).fetchone()[0]is None)
# Capacity pressure preserves the pin across restart; explicit Disable clears both.
with tempfile.TemporaryDirectory() as directory:
 path=Path(directory)/'capacity.db';db=dbnew(path)
 db.execute("UPDATE settings SET auto_capacity_at=300001,auto_notice='waiting' WHERE id=1 AND auto_revision IS NOT NULL")
 db.close();db=sqlite3.connect(path,isolation_level=None)
 check(db.execute('SELECT auto_revision,auto_capacity_at FROM settings').fetchone()==(P,300001))
 db.execute('UPDATE settings SET current_revision=?',(Q,))
 check(db.execute('SELECT auto_revision FROM settings').fetchone()==(P,))
 db.execute("UPDATE settings SET auto_capacity_at=0 WHERE id=1 AND auto_revision IS NOT NULL")
 check(db.execute('SELECT auto_revision,auto_capacity_at FROM settings').fetchone()==(P,0))
 db.execute('UPDATE settings SET auto_capacity_at=600001')
 db.execute("UPDATE settings SET auto_revision=NULL,auto_capacity_at=0,auto_notice='OFF'")
 db.close();db=sqlite3.connect(path,isolation_level=None)
 check(db.execute('SELECT auto_revision,auto_capacity_at FROM settings').fetchone()==(None,0))
 db.execute("UPDATE settings SET auto_capacity_at=900001 WHERE id=1 AND auto_revision IS NOT NULL")
 db.execute("UPDATE settings SET auto_capacity_at=0 WHERE id=1 AND auto_revision IS NOT NULL")
 check(db.execute('SELECT auto_revision,auto_capacity_at FROM settings').fetchone()==(None,0));db.close()
print(f'PASS: {checks} migration/exact-history/transaction host SQLite assertions; Java ContentValues flow modeled, not Android runtime')
