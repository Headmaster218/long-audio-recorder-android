package io.github.headmaster218.recorder.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import io.github.headmaster218.recorder.core.CommittedSegments;
import io.github.headmaster218.recorder.core.FtpsTransfer;

/** Accessed only by FtpsCoordinator's single worker. No spool/source write or delete API. */
final class FtpsQueue extends SQLiteOpenHelper {
    static final int MAX_ROWS = 500, MAX_PROFILES = 32, MAX_RECONCILIATIONS = 3;
    static final class Profile {
        final String revision;
        final FtpsTransfer.Profile destination;
        final byte[] credential;
        Profile(String revision, FtpsTransfer.Profile destination, byte[] credential) {
            this.revision = revision; this.destination = destination; this.credential = credential;
        }
        String label() { return destination.user + " @ " + destination.host + ":" + destination.port + destination.directory
            + " [" + revision.substring(0, 12) + "]"; }
    }
    static final class Item {
        final long key, bytes, queuedAt, nextAt, verifiedAt;
        final String source, revision, wavHash, metadataHash, state, attempt, detail, resultMetadataHash, markerHash;
        final int reconciliations;
        final boolean uploadNow;
        Item(Cursor c) {
            key = c.getLong(0); source = c.getString(1); revision = c.getString(2); wavHash = c.getString(3);
            metadataHash = c.getString(4); bytes = c.getLong(5); queuedAt = c.getLong(6); state = c.getString(7);
            attempt = c.isNull(8) ? null : c.getString(8); reconciliations = c.getInt(9); nextAt = c.getLong(10);
            uploadNow = c.getInt(11) != 0; detail = c.getString(12);
            resultMetadataHash = c.isNull(13) ? null : c.getString(13); markerHash = c.isNull(14) ? null : c.getString(14);
            verifiedAt = c.isNull(15) ? 0 : c.getLong(15);
        }
    }
    FtpsQueue(Context c) {
        // Queue, profiles and encrypted credentials are excluded from backup independently of manifest settings.
        super(c, new File(c.getNoBackupFilesDir(), "ftps-queue.db").getAbsolutePath(), null, 1);
    }
    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        db.execSQL("PRAGMA synchronous=FULL");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE profiles(revision TEXT PRIMARY KEY, host TEXT NOT NULL, port INTEGER NOT NULL, user TEXT NOT NULL, directory TEXT NOT NULL, credential BLOB)");
        db.execSQL("CREATE TABLE settings(id INTEGER PRIMARY KEY CHECK(id=1), current_revision TEXT REFERENCES profiles(revision), paused INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO settings(id,paused) VALUES(1,0)");
        db.execSQL("CREATE TABLE queue(id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, revision TEXT NOT NULL REFERENCES profiles(revision), wav_hash TEXT NOT NULL, metadata_hash TEXT NOT NULL, bytes INTEGER NOT NULL, queued_at INTEGER NOT NULL, state TEXT NOT NULL, attempt TEXT, reconciliations INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, upload_now INTEGER NOT NULL DEFAULT 0, detail TEXT NOT NULL, result_metadata_hash TEXT, marker_hash TEXT, verified_at INTEGER, UNIQUE(source,revision))");
        db.execSQL("CREATE INDEX runnable ON queue(state,next_at,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("FTPS queue migration requires explicit implementation");
    }
    static String digest(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder(64);
            for (byte value : digest) b.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return b.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String revision(FtpsTransfer.Profile p) {
        return digest((p.host + "\n" + p.port + "\n" + p.user + "\n" + p.directory).getBytes(StandardCharsets.UTF_8));
    }
    Profile current() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT current_revision FROM settings WHERE id=1", null)) {
            return c.moveToFirst() && !c.isNull(0) ? profile(c.getString(0)) : null;
        }
    }
    Profile profile(String revision) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT host,port,user,directory,credential FROM profiles WHERE revision=?", new String[]{revision})) {
            return c.moveToFirst() ? new Profile(revision, new FtpsTransfer.Profile(c.getString(0),c.getInt(1),c.getString(2),c.getString(3)), c.isNull(4) ? null : c.getBlob(4)) : null;
        }
    }
    Profile save(FtpsTransfer.Profile destination, byte[] credential) throws IOException {
        String revision = revision(destination); SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Profile old = profile(revision);
            if (old == null) {
                if (count("profiles") >= MAX_PROFILES) throw new IOException("Profile limit reached; no profile was removed");
                ContentValues v = new ContentValues(); v.put("revision",revision); v.put("host",destination.host);
                v.put("port",destination.port); v.put("user",destination.user); v.put("directory",destination.directory);
                v.put("credential",credential); db.insertOrThrow("profiles",null,v);
            } else {
                ContentValues v = new ContentValues(); v.put("credential",credential);
                db.update("profiles",v,"revision=?",new String[]{revision});
            }
            ContentValues current = new ContentValues(); current.put("current_revision",revision);
            db.update("settings",current,"id=1",null); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return profile(revision);
    }
    private long count(String table) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM " + table,null)) { c.moveToFirst(); return c.getLong(0); }
    }
    boolean enqueue(CommittedSegments.Entry entry, String revision) throws IOException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT id FROM queue WHERE source=? AND revision=?", new String[]{entry.id,revision})) {
                if (c.moveToFirst()) { db.setTransactionSuccessful(); return false; }
            }
            if (count("queue") >= MAX_ROWS) throw new IOException("Queue history limit reached; all files and receipts retained");
            ContentValues v = new ContentValues(); v.put("source",entry.id); v.put("revision",revision);
            v.put("wav_hash",entry.metadata.wavSha256); v.put("metadata_hash",digest(entry.metadata.encode()));
            v.put("bytes",entry.metadata.wavBytes); v.put("queued_at",System.currentTimeMillis());
            v.put("state","QUEUED"); v.put("detail","Explicitly selected; waiting for transfer trigger and safety gates");
            db.insertOrThrow("queue",null,v); db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    List<Item> items(boolean runnableOnly) {
        List<Item> result = new ArrayList<Item>();
        String where = runnableOnly ? " WHERE state IN ('QUEUED','RECONCILE')" : "";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,source,revision,wav_hash,metadata_hash,bytes,queued_at,state,attempt,reconciliations,next_at,upload_now,detail,result_metadata_hash,marker_hash,verified_at FROM queue" + where + " ORDER BY id",null)) {
            while (c.moveToNext()) result.add(new Item(c));
        }
        return result;
    }
    void recover() {
        // The intent is durable before DNS/connect. Any surviving attempt is readback-only forever.
        getWritableDatabase().execSQL("UPDATE queue SET state='RECONCILE', detail='Interrupted attempt: exact remote attempt will be read back only', next_at=0 WHERE state='RUNNING'");
    }
    boolean paused() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT paused FROM settings WHERE id=1",null)) { c.moveToFirst(); return c.getInt(0) != 0; }
    }
    void pause(boolean value) {
        ContentValues v = new ContentValues(); v.put("paused",value ? 1 : 0); getWritableDatabase().update("settings",v,"id=1",null);
    }
    void credentialsReady(String revision) {
        getWritableDatabase().execSQL("UPDATE queue SET state=CASE WHEN attempt IS NULL THEN 'QUEUED' ELSE 'RECONCILE' END, detail='Credentials supplied; safety gates still apply' WHERE revision=? AND state='NEEDS_CREDENTIALS'",new Object[]{revision});
    }
    void uploadNow() {
        getWritableDatabase().execSQL("UPDATE queue SET upload_now=1 WHERE state IN ('QUEUED','RECONCILE')");
        pause(false);
    }
    void begin(Item item, String attempt) throws IOException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v = new ContentValues(); v.put("state","RUNNING"); v.put("attempt",attempt);
            v.put("reconciliations",item.reconciliations + (item.attempt == null ? 0 : 1));
            v.put("detail",item.attempt == null ? "Attempt journal committed before network I/O" : "Read-only reconciliation; no remote overwrite");
            int changed = db.update("queue",v,"id=? AND state=? AND " + (item.attempt == null ? "attempt IS NULL" : "attempt=?"),
                item.attempt == null ? new String[]{String.valueOf(item.key),item.state} : new String[]{String.valueOf(item.key),item.state,item.attempt});
            if (changed != 1) throw new IOException("Attempt ownership changed");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void state(Item item, String state, String detail, long nextAt) {
        ContentValues v = new ContentValues(); v.put("state",state); v.put("detail",RecorderState.bounded(detail)); v.put("next_at",nextAt);
        if (getWritableDatabase().update("queue",v,"id=?",new String[]{String.valueOf(item.key)}) != 1) throw new IllegalStateException("Queue item disappeared");
    }
    void verified(Item item, FtpsTransfer.Result result) {
        if (!result.verifiedAtTime || result.bytes != item.bytes || !item.wavHash.equals(result.wavSha256))
            throw new IllegalStateException("Invalid verification result");
        ContentValues v = new ContentValues(); v.put("state","VERIFIED_AT_TIME");
        v.put("detail","Readback verified at this time only; originals retained, no deletion eligibility");
        v.put("result_metadata_hash",result.metadataSha256); v.put("marker_hash",result.markerSha256);
        v.put("verified_at",System.currentTimeMillis());
        if (getWritableDatabase().update("queue",v,"id=?",new String[]{String.valueOf(item.key)}) != 1) throw new IllegalStateException("Queue item disappeared");
    }
}
