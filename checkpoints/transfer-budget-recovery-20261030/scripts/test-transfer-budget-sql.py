#!/usr/bin/env python3
"""Production DDL/CAS on host SQLite. Android ContentValues and callbacks are modeled, not executed."""
import json,re,sqlite3,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/io/github/headmaster218/recorder/android/FtpsQueue.java').read_text()
statements=re.findall(r'\.execSQL\("([^"\n]*)"',s)
checks=0
def check(value):
 global checks
 assert value
 checks+=1
def sql(prefix):
 matches=[x for x in statements if x.startswith(prefix)];assert len(matches)==1,(prefix,matches);return matches[0]
def budget(db):
 db.execute(sql('CREATE TABLE transfer_budget'));db.execute(sql('INSERT INTO transfer_budget'))
def history(db):
 for prefix in ('CREATE TABLE retirement_meta','INSERT INTO retirement_meta','CREATE TABLE retired_ranges','CREATE TABLE sequence_floors','CREATE INDEX retirement_pending'):db.execute(sql(prefix))
def old_schema(db,v):
 db.execute(sql('CREATE TABLE profiles'))
 settings=sql('CREATE TABLE settings');queue=sql('CREATE TABLE queue')
 removals={2:[", auto_revision TEXT REFERENCES profiles(revision)",", auto_notice TEXT NOT NULL DEFAULT 'OFF'"],3:[", cleanup_revision TEXT REFERENCES profiles(revision)"],4:[", auto_capacity_at INTEGER NOT NULL DEFAULT 0"],5:[", upload_threshold_bytes INTEGER NOT NULL DEFAULT 9600000 CHECK(typeof(upload_threshold_bytes)='integer' AND upload_threshold_bytes>0)"]}
 for added,columns in removals.items():
  if v<added:
   for col in columns:assert col in settings;settings=settings.replace(col,'')
 if v<3:
  queue=queue.replace(", cleanup_state TEXT NOT NULL DEFAULT 'NONE'",'').replace(', cleanup_binding BLOB','')
 if v<4:queue=queue.replace(', retirement_generation INTEGER','')
 db.execute(settings);db.execute(sql('INSERT INTO settings'));db.execute(queue);db.execute(sql('CREATE INDEX runnable'))
 if v>=4:history(db)
 db.execute("INSERT INTO profiles VALUES(?,?,?,?,?,?)",('a'*64,'invalid.test',21,'synthetic','/test',None))
 db.execute("UPDATE settings SET current_revision=?,paused=1 WHERE id=1",('a'*64,))
 db.execute("INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,detail,next_at) VALUES(?,?,?,?,?,?,?,?,?)",('kept.ready','a'*64,'b'*64,'c'*64,100,123,'RECONCILE','kept',999))
def upgrade(db,v,fail=False):
 db.execute('BEGIN IMMEDIATE')
 try:
  for stmt in statements:
   if not stmt.startswith('ALTER TABLE'):continue
   if ('auto_revision' in stmt or 'auto_notice' in stmt) and v>=2:continue
   if any(x in stmt for x in ('cleanup_revision','cleanup_state','cleanup_binding')) and v>=3:continue
   if ('retirement_generation' in stmt or 'auto_capacity_at' in stmt) and v>=4:continue
   if 'upload_threshold_bytes' in stmt:
    if v<4:history(db)
    if v>=5:continue
   db.execute(stmt)
  budget(db)
  if fail:raise RuntimeError('synthetic rollback')
  db.execute('COMMIT')
 except Exception:db.execute('ROLLBACK');raise
with tempfile.TemporaryDirectory(prefix='budget-sql-') as folder:
 for v in range(1,6):
  db=sqlite3.connect(str(Path(folder)/('v'+str(v)+'.db')),isolation_level=None);db.execute('PRAGMA foreign_keys=ON');old_schema(db,v)
  prior=list(db.execute("SELECT sql FROM sqlite_master WHERE type='table' ORDER BY name"))
  try:upgrade(db,v,True)
  except RuntimeError:pass
  else:raise AssertionError('rollback did not fire')
  check(list(db.execute("SELECT sql FROM sqlite_master WHERE type='table' ORDER BY name"))==prior)
  upgrade(db,v)
  check(db.execute('SELECT * FROM transfer_budget').fetchall()==[(1,0,0,0,0)])
  check(db.execute('SELECT paused,current_revision,upload_threshold_bytes FROM settings').fetchone()==(1,'a'*64,9600000))
  check(db.execute('SELECT state,detail,next_at FROM queue').fetchone()==('RECONCILE','kept',999))
  check(db.execute('PRAGMA foreign_key_check').fetchall()==[])
  db.close()
 dbpath=Path(folder)/'fresh.db';db=sqlite3.connect(str(dbpath),isolation_level=None);budget(db)
 for values in [(2,0,0,0),(1,0,0,0),(0,-1,0,0),(0,0,-1,0),(0,0,0,-1),(0,0.5,0,0),(0,0,0,'bad')]:
  try:db.execute('UPDATE transfer_budget SET enabled=?,limit_bytes=?,charged_bytes=?,revision=? WHERE id=1',values)
  except sqlite3.IntegrityError:check(True)
  else:raise AssertionError(('invalid budget admitted',values))
 def cas(enabled,limit,charged,revision,expected):
  return db.execute('UPDATE transfer_budget SET enabled=?,limit_bytes=?,charged_bytes=?,revision=? WHERE id=1 AND revision=?',(enabled,limit,charged,revision,expected)).rowcount
 check(cas(1,1000,0,1,0)==1);check(cas(0,0,0,1,0)==0)
 db.execute('BEGIN IMMEDIATE');check(cas(1,1000,600,2,1)==1);db.execute('ROLLBACK')
 check(db.execute('SELECT charged_bytes,revision FROM transfer_budget').fetchone()==(0,1))
 # Commit full reservation BEFORE a possible network start. A process failure keeps all charge.
 db.execute('BEGIN IMMEDIATE');check(cas(1,1000,600,2,1)==1);db.execute('COMMIT');db.close()
 db=sqlite3.connect(str(dbpath),isolation_level=None);check(db.execute('SELECT charged_bytes,revision FROM transfer_budget').fetchone()==(600,2))
 # Insufficient attempt never writes; repeat startup does not clear or refund.
 before=db.execute('SELECT * FROM transfer_budget').fetchone();check(600>1000-600);check(db.execute('SELECT * FROM transfer_budget').fetchone()==before)
 check(cas(0,1000,600,3,2)==1);check(db.execute('SELECT charged_bytes FROM transfer_budget').fetchone()==(600,))
 check(cas(1,1000,600,4,3)==1);check(cas(1,1000,0,5,3)==0);check(cas(1,1000,0,5,4)==1)
 db.close()
print(json.dumps({'checks':checks,'scope':'production DDL and modeled CAS/transaction/restart; no Android execution or traffic'}))
