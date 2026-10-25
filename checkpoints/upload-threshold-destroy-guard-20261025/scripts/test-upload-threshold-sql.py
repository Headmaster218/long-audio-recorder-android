#!/usr/bin/env python3
"""Production DDL on host SQLite; Java ContentValues/transaction glue is modeled, not executed."""
import re
import sqlite3
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/io/github/headmaster218/recorder/android/FtpsQueue.java').read_text()
statements = re.findall(r'\.execSQL\("([^"\n]*)"', source)
checks = 0


def check(value):
    global checks
    assert value
    checks += 1


def sql(prefix):
    matches = [value for value in statements if value.startswith(prefix)]
    assert len(matches) == 1, (prefix, matches)
    return matches[0]


def history(db):
    for prefix in ('CREATE TABLE retirement_meta', 'INSERT INTO retirement_meta',
                   'CREATE TABLE retired_ranges', 'CREATE TABLE sequence_floors',
                   'CREATE INDEX retirement_pending'):
        db.execute(sql(prefix))


def migrate(db, version, fail=False):
    # Models SQLiteOpenHelper's outer transaction. This is not Android execution.
    db.execute('BEGIN IMMEDIATE')
    try:
        for statement in statements:
            if not statement.startswith('ALTER TABLE'):
                continue
            if ('auto_revision' in statement or 'auto_notice' in statement) and version >= 2:
                continue
            if any(key in statement for key in ('cleanup_revision', 'cleanup_state', 'cleanup_binding')) and version >= 3:
                continue
            if ('retirement_generation' in statement or 'auto_capacity_at' in statement) and version >= 4:
                continue
            if 'upload_threshold_bytes' in statement:
                # Production calls createHistory before its last (v5) ALTER.
                if version < 4:
                    history(db)
                if version >= 5:
                    continue
            db.execute(statement)
        if fail:
            raise RuntimeError('Synthetic failure before upgrade commit')
        db.execute('COMMIT')
    except Exception:
        db.execute('ROLLBACK')
        raise


column = ", upload_threshold_bytes INTEGER NOT NULL DEFAULT 9600000 CHECK(typeof(upload_threshold_bytes)='integer' AND upload_threshold_bytes>0)"
for version in (1, 2, 3, 4, 5):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'synthetic.db'
        db = sqlite3.connect(path, isolation_level=None)
        db.execute('PRAGMA foreign_keys=ON')
        db.execute(sql('CREATE TABLE profiles'))
        settings = sql('CREATE TABLE settings')
        queue = sql('CREATE TABLE queue')
        if version < 5:
            settings = settings.replace(column, '')
        if version < 4:
            settings = settings.replace(', auto_capacity_at INTEGER NOT NULL DEFAULT 0', '')
            queue = queue.replace(', retirement_generation INTEGER', '')
        if version < 3:
            settings = settings.replace(', cleanup_revision TEXT REFERENCES profiles(revision)', '')
            queue = queue.replace(", cleanup_state TEXT NOT NULL DEFAULT 'NONE', cleanup_binding BLOB", '')
        if version < 2:
            settings = settings.replace(", auto_revision TEXT REFERENCES profiles(revision), auto_notice TEXT NOT NULL DEFAULT 'OFF'", '')
        db.execute(settings)
        db.execute(sql('INSERT INTO settings'))
        db.execute(queue)
        if version >= 4:
            history(db)
        for revision in ('a', 'b'):
            db.execute('INSERT INTO profiles VALUES(?,?,?,?,?,?)', (revision, 'synthetic.invalid', 21, 'synthetic', '/' + revision, None))
        db.execute("UPDATE settings SET current_revision='b',paused=1")
        if version >= 2:
            db.execute("UPDATE settings SET auto_revision='a',auto_notice='ON'")
        if version >= 3:
            db.execute("UPDATE settings SET cleanup_revision='a'")
        # Two destination-bound copies of one WAV are two authorized pending row obligations.
        for n, (revision, state, size) in enumerate((('a', 'QUEUED', 20), ('b', 'RECONCILE', 22), ('a', 'BLOCKED', 999), ('b', 'NEEDS_CREDENTIALS', 999), ('b', 'VERIFIED_AT_TIME', 999))):
            db.execute('INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,next_at,detail) VALUES(?,?,?,?,?,?,?,?,?)',
                       ('same' if n < 2 else str(n), revision, 'c'*64, 'd'*64, size, 1, state, 300000, 'synthetic'))
        before = db.execute('SELECT source,revision,bytes,state,next_at,upload_now FROM queue ORDER BY id').fetchall()
        original_columns = db.execute('PRAGMA table_info(settings)').fetchall()
        original_tables = db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
        if version < 5:
            try:
                migrate(db, version, fail=True)
            except RuntimeError:
                check(True)
            else:
                raise AssertionError('Synthetic migration failure did not occur')
            check(db.execute('PRAGMA table_info(settings)').fetchall() == original_columns)
            check(db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall() == original_tables)
            check(db.execute('SELECT source,revision,bytes,state,next_at,upload_now FROM queue ORDER BY id').fetchall() == before)
            migrate(db, version)
        check(db.execute('SELECT upload_threshold_bytes FROM settings').fetchone() == (9600000,))
        check(db.execute('SELECT source,revision,bytes,state,next_at,upload_now FROM queue ORDER BY id').fetchall() == before)
        expected_consent = 'a' if version >= 2 else None
        expected_cleanup = 'a' if version >= 3 else None
        check(db.execute('SELECT current_revision,auto_revision,cleanup_revision,paused FROM settings').fetchone() == ('b', expected_consent, expected_cleanup, 1))
        # Model exactly the production ContentValues compare-and-set predicate and sole updated field.
        update = 'UPDATE settings SET upload_threshold_bytes=? WHERE id=1 AND upload_threshold_bytes=?'
        check(db.execute(update, (42, 9600000)).rowcount == 1)
        check(db.execute(update, (1, 9600000)).rowcount == 0)
        db.close()
        db = sqlite3.connect(path, isolation_level=None)
        db.execute('PRAGMA foreign_keys=ON')
        check(db.execute('SELECT upload_threshold_bytes FROM settings').fetchone() == (42,))
        check(db.execute('SELECT current_revision,auto_revision,cleanup_revision,paused FROM settings').fetchone() == ('b', expected_consent, expected_cleanup, 1))
        check(db.execute('SELECT source,revision,bytes,state,next_at,upload_now FROM queue ORDER BY id').fetchall() == before)
        check(db.execute("SELECT SUM(bytes) FROM queue WHERE state IN ('QUEUED','RECONCILE')").fetchone() == (42,))
        for invalid in (0, -1, None, 'bad', 1.5, float(2**63)):
            try:
                db.execute('UPDATE settings SET upload_threshold_bytes=?', (invalid,))
            except sqlite3.IntegrityError:
                check(True)
            else:
                raise AssertionError('Invalid threshold accepted: %r' % (invalid,))
        check(db.execute(update, (2**63-1, 42)).rowcount == 1)
        db.close()
        db = sqlite3.connect(path, isolation_level=None)
        check(db.execute('SELECT upload_threshold_bytes FROM settings').fetchone() == (2**63-1,))
        # Upload now touches only existing runnable rows, keeps destination/backoff/consent intact.
        db.execute(sql('UPDATE queue SET upload_now=1'))
        check(db.execute('SELECT upload_now FROM queue ORDER BY id').fetchall() == [(1,), (1,), (0,), (0,), (0,)])
        check(db.execute('SELECT source,revision,bytes,state,next_at FROM queue ORDER BY id').fetchall() == [row[:5] for row in before])
        db.execute("INSERT INTO queue(source,revision,wav_hash,metadata_hash,bytes,queued_at,state,detail) VALUES('later','a','c','d',1,1,'QUEUED','synthetic')")
        check(db.execute("SELECT upload_now FROM queue WHERE source='later'").fetchone() == (0,))
        check(db.execute('PRAGMA integrity_check').fetchone() == ('ok',))
        check(db.execute('PRAGMA foreign_key_check').fetchall() == [])
        db.close()

print('PASS: %d threshold schema/migration/restart/consent/destination host SQL assertions; Java writes modeled, Android durability untested' % checks)
