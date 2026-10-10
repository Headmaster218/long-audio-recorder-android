#!/usr/bin/env python3
"""Host SQLite executes production schema/recovery statements; does NOT execute Java/Android queue code.
Fixture writes are tiny and synthetic. No network, credential, power-loss or Android lifecycle test.
"""
import re, sqlite3, tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/io/github/headmaster218/recorder/android/FtpsQueue.java').read_text()
statements=re.findall(r'\.execSQL\("([^"\n]*)"',source)
def statement(prefix):
    found=[s for s in statements if s.startswith(prefix)]
    assert len(found)==1,(prefix,len(found))
    return found[0]
count=0
def check(condition):
    global count
    assert condition
    count+=1
def rejected(sql,args):
    try: db.execute(sql,args)
    except sqlite3.IntegrityError: check(True)
    else: raise AssertionError('Constraint accepted invalid queue data')
out=root/'app/build/ftps-checks';out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='queue-sql-',dir=str(out)) as fixture:
    path=Path(fixture)/'synthetic.db'
    db=sqlite3.connect(path,isolation_level=None)
    db.execute('PRAGMA foreign_keys=ON')
    db.execute(statement('PRAGMA synchronous='))
    check(db.execute('PRAGMA synchronous').fetchone()[0]==2)
    for prefix in ['CREATE TABLE profiles','CREATE TABLE settings','INSERT INTO settings','CREATE TABLE queue','CREATE INDEX runnable']:
        db.execute(statement(prefix))
    profile=('a'*64,'invalid.test',21,'synthetic','/synthetic')
    db.execute('INSERT INTO profiles(revision,host,port,user,directory) VALUES(?,?,?,?,?)',profile)
    db.execute('INSERT INTO profiles(revision,host,port,user,directory) VALUES(?,?,?,?,?)',('b'*64,)+profile[1:])
    check(db.execute('SELECT paused FROM settings').fetchone()==(0,))
    rejected('INSERT INTO settings(id,paused) VALUES(?,?)',(2,0))
    sql="INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,detail) VALUES(?,?,?,?,?,?,?,?)"
    row=('synthetic.ready','a'*64,'c'*64,'d'*64,46,1,'QUEUED','synthetic only')
    db.execute(sql,row)
    rejected(sql,row) # Repeated click cannot add the same source/revision twice.
    rejected(sql,(row[0],'e'*64)+row[2:]) # Destination must exist.
    db.execute(sql,(row[0],'b'*64)+row[2:]) # Explicit different destination is distinct.
    check(db.execute('SELECT count(*) FROM queue').fetchone()==(2,))
    # Fixture update models the Java ContentValues begin transaction. Not a Java method execution.
    db.execute('BEGIN IMMEDIATE')
    db.execute("UPDATE queue SET state='RUNNING',attempt='synthetic-attempt' WHERE id=1 AND state='QUEUED' AND attempt IS NULL")
    db.execute('ROLLBACK')
    check(db.execute('SELECT state,attempt FROM queue WHERE id=1').fetchone()==('QUEUED',None))
    db.execute('BEGIN IMMEDIATE')
    db.execute("UPDATE queue SET state='RUNNING',attempt='synthetic-attempt' WHERE id=1 AND state='QUEUED' AND attempt IS NULL")
    db.execute('COMMIT');db.close()
    db=sqlite3.connect(path,isolation_level=None)
    check(db.execute('SELECT state,attempt FROM queue WHERE id=1').fetchone()==('RUNNING','synthetic-attempt'))
    recovery=statement("UPDATE queue SET state='RECONCILE'")
    db.execute(recovery);db.execute(recovery) # Exact production restart SQL, repeatable.
    check(db.execute('SELECT state,attempt,next_at FROM queue WHERE id=1').fetchone()==('RECONCILE','synthetic-attempt',0))
    check(db.execute('SELECT state,attempt FROM queue WHERE id=2').fetchone()==('QUEUED',None))
    db.execute("UPDATE queue SET state='NEEDS_CREDENTIALS'")
    db.execute(statement('UPDATE queue SET state=CASE'),('a'*64,))
    check(db.execute('SELECT state,attempt FROM queue WHERE id=1').fetchone()==('RECONCILE','synthetic-attempt'))
    check(db.execute('SELECT state FROM queue WHERE id=2').fetchone()==('NEEDS_CREDENTIALS',))
    db.execute(statement('UPDATE queue SET state=CASE'),('b'*64,))
    check(db.execute('SELECT state,attempt FROM queue WHERE id=2').fetchone()==('QUEUED',None))
    db.execute(statement('UPDATE queue SET upload_now=1'))
    check(db.execute('SELECT sum(upload_now) FROM queue').fetchone()==(2,))
    db.execute("UPDATE queue SET state='BLOCKED' WHERE id=1")
    db.execute("UPDATE queue SET state='VERIFIED_AT_TIME',verified_at=123,result_metadata_hash='test',marker_hash='test' WHERE id=2")
    db.execute(recovery);db.execute(statement('UPDATE queue SET state=CASE'),('a'*64,))
    check(db.execute('SELECT state FROM queue ORDER BY id').fetchall()==[('BLOCKED',),('VERIFIED_AT_TIME',)])
    db.execute('UPDATE settings SET paused=1 WHERE id=1');db.close()
    db=sqlite3.connect(path)
    check(db.execute('SELECT paused FROM settings').fetchone()==(1,))
    check(db.execute('SELECT attempt FROM queue WHERE id=1').fetchone()==('synthetic-attempt',))
    check(db.execute('SELECT verified_at FROM queue WHERE id=2').fetchone()==(123,))
    check(db.execute('PRAGMA integrity_check').fetchone()==('ok',))
    db.close()
print('PASS: %d host SQLite schema/recovery assertions, using production SQL; Java queue methods and Android durability/lifecycle untested' % count)
