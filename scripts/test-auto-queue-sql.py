#!/usr/bin/env python3
"""Executes production DDL. ContentValues writes modeled, not Android SQLite/JVM execution."""
import re, sqlite3, tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/io/github/headmaster218/recorder/android/FtpsQueue.java').read_text()
statements=re.findall(r'\.execSQL\("([^"\n]*)"',source)
def sql(prefix):
    result=[x for x in statements if x.startswith(prefix)]
    assert len(result)==1
    return result[0]
checks=0
def check(value):
    global checks
    assert value
    checks+=1
with tempfile.TemporaryDirectory() as directory:
    path=Path(directory)/'synthetic.db'
    db=sqlite3.connect(path,isolation_level=None)
    db.execute('PRAGMA foreign_keys=ON')
    db.execute(sql('CREATE TABLE profiles'))
    # Production v1 settings schema, followed by actual v2 migration statements.
    db.execute('CREATE TABLE settings(id INTEGER PRIMARY KEY CHECK(id=1), current_revision TEXT REFERENCES profiles(revision), paused INTEGER NOT NULL DEFAULT 0)')
    db.execute(sql('INSERT INTO settings'))
    db.execute(sql('CREATE TABLE queue')); db.execute(sql('CREATE INDEX runnable'))
    db.execute('UPDATE settings SET paused=1')
    for statement in statements:
        if statement.startswith('ALTER TABLE settings'): db.execute(statement)
    check(db.execute('SELECT paused,auto_revision,auto_notice FROM settings').fetchone()==(1,None,'OFF'))
    for rev in ('a','b'):
        db.execute('INSERT INTO profiles(revision,host,port,user,directory) VALUES(?,?,?,?,?)',(rev,'synthetic.invalid',21,'synthetic','/synthetic'))
    db.execute("UPDATE settings SET current_revision='a',auto_revision='a',auto_notice='ON'")
    db.execute("UPDATE settings SET current_revision='b'")
    check(db.execute('SELECT current_revision,auto_revision FROM settings').fetchone()==('b','a'))
    db.close();db=sqlite3.connect(path,isolation_level=None)
    check(db.execute('SELECT auto_revision FROM settings').fetchone()==('a',))
    db.execute('PRAGMA foreign_keys=ON')
    try: db.execute("UPDATE settings SET auto_revision='absent'")
    except sqlite3.IntegrityError: check(True)
    else: raise AssertionError('unpinned destination accepted')
    row_sql="INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,detail) VALUES(?,?,?,?,?,?,?,?)"
    for i in range(500):
        db.execute(row_sql,(str(i),'a','c'*64,'d'*64,46,1,'VERIFIED_AT_TIME' if i%2 else 'QUEUED','synthetic'))
    check(db.execute('SELECT count(*) FROM queue').fetchone()==(500,))
    try: db.execute(row_sql,('0','a','c'*64,'d'*64,46,1,'QUEUED','duplicate'))
    except sqlite3.IntegrityError: check(True)
    else: raise AssertionError('duplicate accepted')
    # Models the auto-disable ContentValues write at cap; receipts and sources remain.
    db.execute("UPDATE settings SET auto_revision=NULL,auto_notice='limit'")
    check(db.execute('SELECT count(*) FROM queue').fetchone()==(500,))
    check(db.execute("SELECT count(*) FROM queue WHERE state='VERIFIED_AT_TIME'").fetchone()==(250,))
    db.close();db=sqlite3.connect(path)
    check(db.execute('SELECT auto_revision,auto_notice FROM settings').fetchone()==(None,'limit'))
    check(db.execute('PRAGMA integrity_check').fetchone()==('ok',))
    check('if (count("queue") >= MAX_ROWS)' in source) # Source contract only, not execution.
    check(source.index('SELECT id FROM queue WHERE source=? AND revision=?',source.index('boolean enqueue(')) < source.index('if (count("queue") >= MAX_ROWS)'))
print('PASS: %d auto migration/pinning/idempotence host SQL and source assertions; Android behavior untested' % checks)
