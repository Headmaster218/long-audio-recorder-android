#!/usr/bin/env python3
"""Production schema/SQL executed on synthetic host SQLite; Android ContentValues writes modeled."""
import re,sqlite3,tempfile
from pathlib import Path
r=Path(__file__).resolve().parents[1]
s=(r/'app/src/main/java/io/github/headmaster218/recorder/android/FtpsQueue.java').read_text()
sqls=re.findall(r'\.execSQL\("([^"\n]*)"',s)
def sql(prefix):
 x=[q for q in sqls if q.startswith(prefix)];assert len(x)==1;return x[0]
n=0
def check(value):
 global n
 assert value;n+=1
for version in (1,2):
 with tempfile.TemporaryDirectory() as tmp:
  path=Path(tmp)/'synthetic.db';db=sqlite3.connect(path,isolation_level=None);db.execute('PRAGMA foreign_keys=ON')
  db.execute(sql('CREATE TABLE profiles'))
  settings=sql('CREATE TABLE settings').replace(', auto_capacity_at INTEGER NOT NULL DEFAULT 0','').replace(', cleanup_revision TEXT REFERENCES profiles(revision)','')
  if version==1:settings=settings.replace(", auto_revision TEXT REFERENCES profiles(revision), auto_notice TEXT NOT NULL DEFAULT 'OFF'",'')
  db.execute(settings);db.execute(sql('INSERT INTO settings'))
  db.execute(sql('CREATE TABLE queue').replace(", retirement_generation INTEGER","").replace(", cleanup_state TEXT NOT NULL DEFAULT 'NONE', cleanup_binding BLOB",''))
  db.execute("INSERT INTO profiles VALUES('a','synthetic.invalid',21,'synthetic','/synthetic',X'0102')")
  db.execute("UPDATE settings SET current_revision='a',paused=1")
  if version==2:db.execute("UPDATE settings SET auto_revision='a',auto_notice='ON'")
  for i,state in enumerate(['QUEUED','RUNNING','RECONCILE','BLOCKED','VERIFIED_AT_TIME']):
   db.execute('INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,attempt,next_at,verified_at,detail) VALUES(?,?,?,?,?,?,?,?,?,?,?)',(str(i),'a','c'*64,'d'*64,60,1,state,'attempt',123,456,'synthetic'))
  before=db.execute('SELECT id,source,state,attempt,next_at,verified_at FROM queue ORDER BY id').fetchall()
  for statement in sqls:
   if statement.startswith('ALTER TABLE') and (version==1 or 'auto_revision' not in statement and 'auto_notice' not in statement):db.execute(statement)
  check(db.execute('SELECT id,source,state,attempt,next_at,verified_at FROM queue ORDER BY id').fetchall()==before)
  check(db.execute('SELECT cleanup_revision,paused,current_revision FROM settings').fetchone()==(None,1,'a'))
  check(db.execute('SELECT DISTINCT cleanup_state,cleanup_binding FROM queue').fetchall()==[('NONE',None)])
  check(db.execute('SELECT credential FROM profiles').fetchone()==(b'\x01\x02',))
  db.execute("UPDATE settings SET cleanup_revision='a'")
  for state in ('INTENT','QUARANTINED','PURGING','REMOVED'):
   db.execute("UPDATE queue SET cleanup_state=?,cleanup_binding=X'010203' WHERE id=5",(state,));db.close();db=sqlite3.connect(path,isolation_level=None)
   check(db.execute('SELECT cleanup_state,cleanup_binding FROM queue WHERE id=5').fetchone()==(state,b'\x01\x02\x03'))
  for i,state in enumerate(['DEFERRED','RECHECKING','WAIT_CREDENTIALS','PURGING','REMOVED'],1):db.execute('UPDATE queue SET cleanup_state=? WHERE id=?',(state,i))
  db.execute(sql("UPDATE queue SET cleanup_state='RETAINED'"))
  check(db.execute('SELECT cleanup_state FROM queue ORDER BY id').fetchall()==[('RETAINED',),('RETAINED',),('RETAINED',),('PURGING',),('REMOVED',)])
  db.execute("UPDATE queue SET cleanup_state='INTENT' WHERE id=1")
  db.execute("UPDATE queue SET cleanup_state='QUARANTINED' WHERE id=2")
  db.execute(sql("UPDATE queue SET cleanup_state='RESTORE_ONLY'"))
  db.execute("UPDATE settings SET cleanup_revision='a'")
  check(db.execute('SELECT cleanup_state FROM queue WHERE id IN (1,2)').fetchall()==[('RESTORE_ONLY',),('RESTORE_ONLY',)])
  check(db.execute('SELECT count(*) FROM queue').fetchone()==(5,))
  guard=next(x for x in re.findall(r'rawQuery\("([^"\n]*)"',s) if x.startswith('SELECT id FROM queue WHERE source=? AND id<>?'))
  db.execute('BEGIN')
  db.execute("INSERT INTO profiles VALUES('b','synthetic.invalid',21,'synthetic','/other',NULL)")
  db.execute("INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,detail) VALUES('4','b','hash','metadata',60,1,'QUEUED','synthetic')")
  check(db.execute(guard,('4','5')).fetchone() is not None)
  db.execute("UPDATE queue SET state='VERIFIED_AT_TIME' WHERE revision='b'")
  check(db.execute(guard,('4','5')).fetchone() is None)
  db.execute("UPDATE queue SET state='BLOCKED' WHERE revision='b'")
  check(db.execute(guard,('4','5')).fetchone() is not None)
  db.execute('ROLLBACK')
  check(db.execute('PRAGMA integrity_check').fetchone()==('ok',))
print('PASS: %d cleanup migration/restart/revocation host SQL assertions; not Android SQLite durability' % n)
