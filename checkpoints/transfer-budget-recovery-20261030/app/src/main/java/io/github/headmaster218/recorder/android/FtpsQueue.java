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
import io.github.headmaster218.recorder.core.TransferBudget;
import io.github.headmaster218.recorder.core.SegmentIdentity;
import io.github.headmaster218.recorder.core.RetirementRanges;
import io.github.headmaster218.recorder.core.UploadThreshold;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Queue mutations use the transfer worker; startup reconciles history under physical spool ownership. No filesystem API. */
final class FtpsQueue extends SQLiteOpenHelper {
    static final int MAX_ROWS = 500, MAX_PROFILES = 32, MAX_RECONCILIATIONS = 3;
    static final int MAX_RANGES=2048, MAX_CAPTURES=256, MAX_GROUPS=512, MAX_RETIRING=8;
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
        final long key, bytes, queuedAt, nextAt, verifiedAt, retirementGeneration;
        final String source, revision, wavHash, metadataHash, state, attempt, detail, resultMetadataHash, markerHash;
        final int reconciliations;
        final boolean uploadNow;
        final String cleanupState;
        final byte[] cleanupBinding;
        Item(Cursor c) {
            key = c.getLong(0); source = c.getString(1); revision = c.getString(2); wavHash = c.getString(3);
            metadataHash = c.getString(4); bytes = c.getLong(5); queuedAt = c.getLong(6); state = c.getString(7);
            attempt = c.isNull(8) ? null : c.getString(8); reconciliations = c.getInt(9); nextAt = c.getLong(10);
            uploadNow = c.getInt(11) != 0; detail = c.getString(12);
            resultMetadataHash = c.isNull(13) ? null : c.getString(13); markerHash = c.isNull(14) ? null : c.getString(14);
            verifiedAt = c.isNull(15) ? 0 : c.getLong(15);
            cleanupState = c.getString(16); cleanupBinding = c.isNull(17) ? null : c.getBlob(17); retirementGeneration=c.isNull(18) ? -1 : c.getLong(18);
        }
    }
    FtpsQueue(Context c) {
        // Queue, profiles and encrypted credentials are excluded from backup independently of manifest settings.
        super(c, new File(c.getNoBackupFilesDir(), "ftps-queue.db").getAbsolutePath(), null, 6);
    }
    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        db.execSQL("PRAGMA synchronous=FULL");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE profiles(revision TEXT PRIMARY KEY, host TEXT NOT NULL, port INTEGER NOT NULL, user TEXT NOT NULL, directory TEXT NOT NULL, credential BLOB)");
        db.execSQL("CREATE TABLE settings(id INTEGER PRIMARY KEY CHECK(id=1), current_revision TEXT REFERENCES profiles(revision), paused INTEGER NOT NULL DEFAULT 0, auto_revision TEXT REFERENCES profiles(revision), auto_notice TEXT NOT NULL DEFAULT 'OFF', cleanup_revision TEXT REFERENCES profiles(revision), auto_capacity_at INTEGER NOT NULL DEFAULT 0, upload_threshold_bytes INTEGER NOT NULL DEFAULT 9600000 CHECK(typeof(upload_threshold_bytes)='integer' AND upload_threshold_bytes>0))");
        db.execSQL("INSERT INTO settings(id,paused) VALUES(1,0)");
        db.execSQL("CREATE TABLE queue(id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, revision TEXT NOT NULL REFERENCES profiles(revision), wav_hash TEXT NOT NULL, metadata_hash TEXT NOT NULL, bytes INTEGER NOT NULL, queued_at INTEGER NOT NULL, state TEXT NOT NULL, attempt TEXT, reconciliations INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, upload_now INTEGER NOT NULL DEFAULT 0, detail TEXT NOT NULL, result_metadata_hash TEXT, marker_hash TEXT, verified_at INTEGER, cleanup_state TEXT NOT NULL DEFAULT 'NONE', cleanup_binding BLOB, retirement_generation INTEGER, UNIQUE(source,revision))");
        db.execSQL("CREATE INDEX runnable ON queue(state,next_at,id)");
        createHistory(db);
        createBudget(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion > 5 || newVersion != 6) throw new IllegalStateException("Unsupported FTPS queue migration");
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE settings ADD COLUMN auto_revision TEXT REFERENCES profiles(revision)");
            db.execSQL("ALTER TABLE settings ADD COLUMN auto_notice TEXT NOT NULL DEFAULT 'OFF'");
        }
        if(oldVersion<3) {
        db.execSQL("ALTER TABLE settings ADD COLUMN cleanup_revision TEXT REFERENCES profiles(revision)");
        db.execSQL("ALTER TABLE queue ADD COLUMN cleanup_state TEXT NOT NULL DEFAULT 'NONE'");
        db.execSQL("ALTER TABLE queue ADD COLUMN cleanup_binding BLOB");
        }
        if (oldVersion < 4) {
        db.execSQL("ALTER TABLE queue ADD COLUMN retirement_generation INTEGER");
        db.execSQL("ALTER TABLE settings ADD COLUMN auto_capacity_at INTEGER NOT NULL DEFAULT 0");
        createHistory(db);
        }
        if (oldVersion < 5) db.execSQL("ALTER TABLE settings ADD COLUMN upload_threshold_bytes INTEGER NOT NULL DEFAULT 9600000 CHECK(typeof(upload_threshold_bytes)='integer' AND upload_threshold_bytes>0)");
        createBudget(db);
    }
    private static void createBudget(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transfer_budget(id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL DEFAULT 0 CHECK(typeof(enabled)='integer' AND enabled IN (0,1)), limit_bytes INTEGER NOT NULL DEFAULT 0 CHECK(typeof(limit_bytes)='integer' AND limit_bytes>=0), charged_bytes INTEGER NOT NULL DEFAULT 0 CHECK(typeof(charged_bytes)='integer' AND charged_bytes>=0), revision INTEGER NOT NULL DEFAULT 0 CHECK(typeof(revision)='integer' AND revision>=0), CHECK(enabled=0 OR limit_bytes>0))");
        db.execSQL("INSERT INTO transfer_budget(id) VALUES(1)");
    }
    TransferBudget.State transferBudget() { return readBudget(getReadableDatabase()); }
    private static TransferBudget.State readBudget(SQLiteDatabase db) {
        try (Cursor c=db.rawQuery("SELECT enabled,limit_bytes,charged_bytes,revision FROM transfer_budget WHERE id=1",null)) {
            if (!c.moveToFirst()) throw new IllegalStateException("Missing transfer budget");
            for (int i=0;i<4;i++) if(c.getType(i)!=Cursor.FIELD_TYPE_INTEGER) throw new IllegalStateException("Invalid transfer budget type");
            long enabled=c.getLong(0);
            if(enabled!=0 && enabled!=1) throw new IllegalStateException("Invalid transfer budget flag");
            return new TransferBudget.State(enabled==1,c.getLong(1),c.getLong(2),c.getLong(3));
        }
    }
    private static void writeBudget(SQLiteDatabase db,TransferBudget.State before,TransferBudget.State after) throws IOException {
        ContentValues v=new ContentValues(); v.put("enabled",after.enabled?1:0); v.put("limit_bytes",after.limitBytes);
        v.put("charged_bytes",after.chargedBytes); v.put("revision",after.revision);
        if(db.update("transfer_budget",v,"id=1 AND revision=?",new String[]{Long.toString(before.revision)})!=1)
            throw new IOException("Transfer budget changed; review it again");
    }
    void configureBudget(boolean enabled,long limit,long expectedRevision,boolean reset,boolean inFlight) throws IOException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            TransferBudget.State before=readBudget(db);
            TransferBudget.State after=reset ? TransferBudget.reset(before,expectedRevision,inFlight)
                : TransferBudget.configure(before,enabled,limit,expectedRevision,inFlight);
            writeBudget(db,before,after);db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** Full application-file-data charge is committed before either network entrance. Never refunded. */
    boolean reserveBudget(long wavBytes,long metadataBytes) throws IOException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            TransferBudget.State before=readBudget(db);
            if(!before.enabled) { db.setTransactionSuccessful(); return true; }
            long charge=TransferBudget.estimate(wavBytes,metadataBytes);
            TransferBudget.State after=TransferBudget.reserve(before,charge,before.revision);
            if(after==null) { db.setTransactionSuccessful(); return false; }
            writeBudget(db,before,after);db.setTransactionSuccessful();return true;
        } finally { db.endTransaction(); }
    }
    private static void createHistory(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE retirement_meta(id INTEGER PRIMARY KEY CHECK(id=1),store_id TEXT,generation INTEGER NOT NULL DEFAULT 0,ready INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO retirement_meta(id,generation,ready) VALUES(1,0,0)");
        db.execSQL("CREATE TABLE retired_ranges(revision TEXT NOT NULL REFERENCES profiles(revision),capture TEXT NOT NULL,lo INTEGER NOT NULL CHECK(lo>=0),hi INTEGER NOT NULL CHECK(hi>=lo),PRIMARY KEY(revision,capture,lo))");
        db.execSQL("CREATE TABLE sequence_floors(capture TEXT PRIMARY KEY,next_sequence INTEGER NOT NULL CHECK(next_sequence>0))");
        db.execSQL("CREATE INDEX retirement_pending ON queue(retirement_generation,source)");
    }
    static final class HistoryMeta {
        final String store;final long generation;final boolean ready;
        HistoryMeta(String store,long generation,boolean ready){this.store=store;this.generation=generation;this.ready=ready;}
    }
    HistoryMeta historyMeta(boolean initialize)throws IOException {
        SQLiteDatabase db=getWritableDatabase();
        if(initialize) {
            db.beginTransaction();try {
                ContentValues value=new ContentValues();value.put("store_id",UUID.randomUUID().toString());
                db.update("retirement_meta",value,"id=1 AND store_id IS NULL",null);db.setTransactionSuccessful();
            } finally {db.endTransaction();}
        }
        try(Cursor c=db.rawQuery("SELECT store_id,generation,ready FROM retirement_meta WHERE id=1",null)) {
            if(!c.moveToFirst())throw new IOException("missing retirement metadata");
            String store=c.isNull(0)?null:c.getString(0);long gen=c.getLong(1);
            if(c.isNull(2)||(c.getInt(2)!=0&&c.getInt(2)!=1)||gen<0 || (store!=null&&!store.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))throw new IOException("invalid retirement metadata");
            return new HistoryMeta(store,gen,c.getInt(2)==1);
        }
    }
    void historyReady() {ContentValues v=new ContentValues();v.put("ready",1);getWritableDatabase().update("retirement_meta",v,"id=1",null);}
    boolean emptyRetirement(){return count("retired_ranges")==0&&count("sequence_floors")==0&&retiring().isEmpty();}
    Map<String,Long> floors()throws IOException {
        Map<String,Long> result=new HashMap<String,Long>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT capture,next_sequence FROM sequence_floors",null)) {
            while(c.moveToNext()) {String key=c.getString(0);long next=c.getLong(1);if(!key.matches("[0-9a-f]{64}")||next<=0||result.size()>=MAX_CAPTURES)throw new IOException("invalid sequence floor");result.put(key,next);}
        }return result;
    }
    boolean retiredContains(String source,String revision) {
        final SegmentIdentity id;try{id=SegmentIdentity.parse(source);}catch(IOException e){return false;}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT lo FROM retired_ranges WHERE revision=? AND capture=? AND lo<=? AND hi>=? LIMIT 1",new String[]{revision,id.capture,String.valueOf(id.sequence),String.valueOf(id.sequence)})){return c.moveToFirst();}
    }
    String retiredCount() { return retiredCount(null); }
    String retiredCount(String revision) {
        java.math.BigInteger total=java.math.BigInteger.ZERO;
        try(Cursor c=getReadableDatabase().rawQuery(revision==null?"SELECT lo,hi FROM retired_ranges":"SELECT lo,hi FROM retired_ranges WHERE revision=?",revision==null?null:new String[]{revision})) {while(c.moveToNext())total=total.add(java.math.BigInteger.valueOf(c.getLong(1)).subtract(java.math.BigInteger.valueOf(c.getLong(0))).add(java.math.BigInteger.ONE));}
        return total.toString();
    }
    List<Item> retiring() {
        List<Item> result=new ArrayList<Item>();for(Item item:items(false))if(item.retirementGeneration>=0)result.add(item);return result;
    }
    List<Item> retirementCandidates() {
        List<Item> result=new ArrayList<Item>();for(Item item:items(false))if(item.cleanupState.equals("REMOVED")&&item.retirementGeneration<0)result.add(item);return result;
    }
    private List<Item> sourceRows(String source) {List<Item> result=new ArrayList<Item>();for(Item item:items(false))if(item.source.equals(source))result.add(item);return result;}
    private boolean verifiedRows(List<Item> rows,Item proof) {
        if(rows.isEmpty()||!proof.cleanupState.equals("REMOVED")||proof.cleanupBinding==null)return false;
        for(Item row:rows)if(!row.state.equals("VERIFIED_AT_TIME")||row.verifiedAt<=0||row.attempt==null||row.resultMetadataHash==null||row.markerHash==null||row.bytes<=0||!row.revision.matches("[0-9a-f]{64}")||!row.wavHash.matches("[0-9a-f]{64}")||!row.metadataHash.matches("[0-9a-f]{64}")||!row.markerHash.matches("[0-9a-f]{64}")||!row.resultMetadataHash.matches("[0-9a-f]{64}")||!row.wavHash.equals(proof.wavHash)||!row.metadataHash.equals(proof.metadataHash)||row.bytes!=proof.bytes
            || !(row.cleanupState.equals("REMOVED")||row.cleanupState.equals("NONE")||row.cleanupState.equals("RETAINED")||row.cleanupState.equals("RESTORED")))return false;
        return true;
    }
    void validateHistoryBounds()throws IOException {
        if(count("retired_ranges")>MAX_RANGES||count("sequence_floors")>MAX_CAPTURES)throw new IOException("history bounds exceeded");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM (SELECT revision,capture FROM retired_ranges GROUP BY revision,capture)",null)){c.moveToFirst();if(c.getLong(0)>MAX_GROUPS)throw new IOException("history group cap exceeded");}
        Map<String,Long> floors=floors();String group=null;long previous=-2;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT revision,capture,lo,hi FROM retired_ranges ORDER BY revision,capture,lo",null)) {
            while(c.moveToNext()) {
                String revision=c.getString(0),capture=c.getString(1);long lo=c.getLong(2),hi=c.getLong(3);
                new RetirementRanges.Range(lo,hi);
                if(!revision.matches("[0-9a-f]{64}")||!capture.matches("[0-9a-f]{64}")||!floors.containsKey(capture)||floors.get(capture)<=hi)throw new IOException("interval floor mismatch");
                String next=revision+capture;if(next.equals(group)&&lo<=previous+1)throw new IOException("noncanonical interval history");group=next;previous=hi;
            }
        }
    }
    void validateRetirement(Item proof)throws IOException {
        List<Item> rows=sourceRows(proof.source);HistoryMeta meta=historyMeta(false);SegmentIdentity id=SegmentIdentity.parse(proof.source);
        if(!verifiedRows(rows,proof)||proof.retirementGeneration<=0||proof.retirementGeneration>meta.generation)throw new IOException("invalid retained retirement proof");
        Map<String,Long> floors=floors();if(!floors.containsKey(id.capture)||floors.get(id.capture)<=id.sequence)throw new IOException("missing committed floor");
        for(Item row:rows)if(row.retirementGeneration!=proof.retirementGeneration||!retiredContains(row.source,row.revision))throw new IOException("partial retirement transaction");
    }
    /** Caller holds exact empty-marker maintenance proof. Retained rows are the bounded prune intent. */
    long beginRetirement(Item proof)throws IOException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            List<Item> rows=sourceRows(proof.source);if(!verifiedRows(rows,proof))return -1;
            for(Item row:rows)if(row.retirementGeneration>=0)return row.retirementGeneration;
            Map<String,Boolean> pending=new HashMap<String,Boolean>();for(Item row:retiring())pending.put(row.source,true);
            if(pending.size()>=MAX_RETIRING)throw new IOException("retirement pending cap");
            HistoryMeta meta=historyMeta(false);if(!meta.ready||meta.store==null||meta.generation==Long.MAX_VALUE)throw new IOException("retirement store unavailable/exhausted");
            SegmentIdentity id=SegmentIdentity.parse(proof.source);long gen=meta.generation+1;
            for(Item row:rows) {
                List<RetirementRanges.Range> old=new ArrayList<RetirementRanges.Range>();
                try(Cursor c=db.rawQuery("SELECT lo,hi FROM retired_ranges WHERE revision=? AND capture=? ORDER BY lo",new String[]{row.revision,id.capture})) {while(c.moveToNext())old.add(new RetirementRanges.Range(c.getLong(0),c.getLong(1)));}
                List<RetirementRanges.Range> merged=RetirementRanges.add(old,id.sequence);
                db.delete("retired_ranges","revision=? AND capture=?",new String[]{row.revision,id.capture});
                for(RetirementRanges.Range range:merged){ContentValues v=new ContentValues();v.put("revision",row.revision);v.put("capture",id.capture);v.put("lo",range.low);v.put("hi",range.high);db.insertOrThrow("retired_ranges",null,v);}
            }
            long floor=id.sequence+1;try(Cursor c=db.rawQuery("SELECT next_sequence FROM sequence_floors WHERE capture=?",new String[]{id.capture})){if(c.moveToFirst())floor=Math.max(floor,c.getLong(0));}
            ContentValues f=new ContentValues();f.put("capture",id.capture);f.put("next_sequence",floor);db.insertWithOnConflict("sequence_floors",null,f,SQLiteDatabase.CONFLICT_REPLACE);
            if(count("retired_ranges")>MAX_RANGES||count("sequence_floors")>MAX_CAPTURES)throw new IOException("retired history cap; detailed receipts retained");
            try(Cursor c=db.rawQuery("SELECT count(*) FROM (SELECT revision,capture FROM retired_ranges GROUP BY revision,capture)",null)){c.moveToFirst();if(c.getLong(0)>MAX_GROUPS)throw new IOException("retired group cap");}
            ContentValues marker=new ContentValues();marker.put("retirement_generation",gen);db.update("queue",marker,"source=?",new String[]{proof.source});
            ContentValues generation=new ContentValues();generation.put("generation",gen);db.update("retirement_meta",generation,"id=1",null);
            db.setTransactionSuccessful();return gen;
        } finally {db.endTransaction();}
    }
    void finishRetirement(String source,long generation)throws IOException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try {
            List<Item> rows=sourceRows(source);if(rows.isEmpty())throw new IOException("missing retirement intent");
            Item proof=null;for(Item row:rows)if(row.cleanupState.equals("REMOVED")&&row.cleanupBinding!=null)proof=row;
            if(proof==null||!verifiedRows(rows,proof))throw new IOException("retirement rows changed");
            for(Item row:rows)if(row.retirementGeneration!=generation||!retiredContains(source,row.revision))throw new IOException("retirement interval mismatch");
            db.delete("queue","source=? AND retirement_generation=?",new String[]{source,String.valueOf(generation)});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    String cleanupRevision() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT cleanup_revision FROM settings WHERE id=1",null)) { c.moveToFirst(); return c.isNull(0) ? null : c.getString(0); }
    }
    void cleanupRevision(String revision) {
        ContentValues v=new ContentValues(); v.put("cleanup_revision",revision); getWritableDatabase().update("settings",v,"id=1",null);
    }
    void revokeCleanup() {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            cleanupRevision(null);
            db.execSQL("UPDATE queue SET cleanup_state='RETAINED' WHERE cleanup_state IN ('DEFERRED','RECHECKING','WAIT_CREDENTIALS','WAIT_SOURCE')");
            db.execSQL("UPDATE queue SET cleanup_state='RESTORE_ONLY' WHERE cleanup_state IN ('INTENT','QUARANTINED')");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    boolean otherNeedsSource(Item item) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM queue WHERE source=? AND id<>? AND state<>'VERIFIED_AT_TIME' LIMIT 1",new String[]{item.source,String.valueOf(item.key)})) { return c.moveToFirst(); }
    }
    void cleanup(Item item, String state, byte[] binding) throws IOException {
        ContentValues v=new ContentValues(); v.put("cleanup_state",state); v.put("cleanup_binding",binding);
        if(getWritableDatabase().update("queue",v,"id=? AND state='VERIFIED_AT_TIME' AND attempt IS NOT NULL AND verified_at IS NOT NULL",new String[]{String.valueOf(item.key)})!=1)
            throw new IOException("Cleanup receipt ownership unavailable");
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
    String automaticRevision() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT auto_revision FROM settings WHERE id=1",null)) {
            c.moveToFirst(); return c.isNull(0) ? null : c.getString(0);
        }
    }
    String automaticNotice() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT auto_notice FROM settings WHERE id=1",null)) { c.moveToFirst(); return c.getString(0); }
    }
    void automatic(String revision, String notice) {
        ContentValues v = new ContentValues(); v.put("auto_revision",revision); v.put("auto_notice",notice); v.put("auto_capacity_at",0);
        getWritableDatabase().update("settings",v,"id=1",null);
    }
    long automaticCapacityAt() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT auto_capacity_at FROM settings WHERE id=1",null)){c.moveToFirst();return c.getLong(0);}
    }
    void automaticCapacityWait(long nextAt) {
        ContentValues v=new ContentValues();v.put("auto_capacity_at",nextAt);
        v.put("auto_notice","ON: waiting for queue capacity; destination consent retained. Historical catch-up uses bounded finalized-source leases.");
        getWritableDatabase().update("settings",v,"id=1 AND auto_revision IS NOT NULL",null);
    }
    void automaticCapacityScanned() {
        ContentValues v=new ContentValues();v.put("auto_capacity_at",0);v.put("auto_notice","ON: capacity catch-up discovered; historical confirmation uses finalized-source leases.");
        getWritableDatabase().update("settings",v,"id=1 AND auto_revision IS NOT NULL",null);
    }
    boolean contains(String source, String revision) {
        if(retiredContains(source,revision))return true;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM queue WHERE source=? AND revision=?",new String[]{source,revision})) { return c.moveToFirst(); }
    }
    boolean full() { return count("queue") >= MAX_ROWS; }
    boolean enqueue(CommittedSegments.Entry entry, String revision) throws IOException { return enqueue(entry,revision,false); }
    boolean enqueue(CommittedSegments.Entry entry, String revision, boolean automatic) throws IOException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if(retiredContains(entry.id,revision)) { db.setTransactionSuccessful(); return false; }
            try (Cursor c = db.rawQuery("SELECT id FROM queue WHERE source=? AND revision=?", new String[]{entry.id,revision})) {
                if (c.moveToFirst()) { db.setTransactionSuccessful(); return false; }
            }
            if (count("queue") >= MAX_ROWS) throw new IOException("Queue history limit reached; all files and receipts retained");
            ContentValues v = new ContentValues(); v.put("source",entry.id); v.put("revision",revision);
            v.put("wav_hash",entry.metadata.wavSha256); v.put("metadata_hash",digest(entry.metadata.encode()));
            v.put("bytes",entry.metadata.wavBytes); v.put("queued_at",System.currentTimeMillis());
            v.put("state","QUEUED"); v.put("detail",automatic ? "Automatically enrolled with pinned destination consent; waiting for transfer trigger and safety gates" : "Explicitly selected; waiting for transfer trigger and safety gates");
            db.insertOrThrow("queue",null,v); db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    List<Item> items(boolean runnableOnly) {
        List<Item> result = new ArrayList<Item>();
        String where = runnableOnly ? " WHERE state IN ('QUEUED','RECONCILE')" : "";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,source,revision,wav_hash,metadata_hash,bytes,queued_at,state,attempt,reconciliations,next_at,upload_now,detail,result_metadata_hash,marker_hash,verified_at,cleanup_state,cleanup_binding,retirement_generation FROM queue" + where + " ORDER BY id",null)) {
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
        getWritableDatabase().execSQL("UPDATE queue SET cleanup_state='DEFERRED' WHERE revision=? AND state='VERIFIED_AT_TIME' AND cleanup_state='WAIT_CREDENTIALS'",new Object[]{revision});
        getWritableDatabase().execSQL("UPDATE queue SET state=CASE WHEN attempt IS NULL THEN 'QUEUED' ELSE 'RECONCILE' END, detail='Credentials supplied; safety gates still apply' WHERE revision=? AND state='NEEDS_CREDENTIALS'",new Object[]{revision});
    }
    long uploadThresholdBytes() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT upload_threshold_bytes FROM settings WHERE id=1",null)) {
            if (!c.moveToFirst() || c.getType(0) != Cursor.FIELD_TYPE_INTEGER)
                throw new IllegalStateException("Invalid upload threshold record");
            return UploadThreshold.validate(c.getLong(0));
        }
    }
    void uploadThresholdBytes(long bytes, long expected) throws IOException {
        UploadThreshold.validate(bytes); UploadThreshold.validate(expected);
        ContentValues value = new ContentValues(); value.put("upload_threshold_bytes",bytes);
        // Compare-and-set rejects stale confirmation, touching no consent, destination, pause or retry fields.
        if (getWritableDatabase().update("settings",value,"id=1 AND upload_threshold_bytes=?",
                new String[]{Long.toString(expected)}) != 1)
            throw new IOException("Upload threshold changed; review the saved value again");
    }
    void uploadNow() {
        getWritableDatabase().execSQL("UPDATE queue SET upload_now=1 WHERE state IN ('QUEUED','RECONCILE')");
        pause(false);
    }
    void deferForSafety(long notBefore) {
        // Persist one queue-wide floor. Upload now does not reset it; later retry times are retained.
        getWritableDatabase().execSQL("UPDATE queue SET next_at=MAX(next_at,?), detail='Waiting for charging, eligible unmetered Wi-Fi and local-network permission; originals retained' WHERE state IN ('QUEUED','RECONCILE')",new Object[]{notBefore});
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
        v.put("detail","Readback verified at this time only; local cleanup is recorded separately; remote future persistence unproven");
        v.put("result_metadata_hash",result.metadataSha256); v.put("marker_hash",result.markerSha256);
        v.put("verified_at",System.currentTimeMillis());
        if (getWritableDatabase().update("queue",v,"id=?",new String[]{String.valueOf(item.key)}) != 1) throw new IllegalStateException("Queue item disappeared");
    }
}
