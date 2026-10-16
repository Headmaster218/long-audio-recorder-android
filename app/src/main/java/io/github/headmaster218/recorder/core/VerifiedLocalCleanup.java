package io.github.headmaster218.recorder.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/** Local cleanup after separately authorized readback-at-time evidence. Not DeletionGate evidence. */
public final class VerifiedLocalCleanup {
    public static final int MAX_BINDING_BYTES = 8192;
    public interface ChangeIdentity { String read(Path path) throws IOException; }
    private static final ChangeIdentity HOST_IDENTITY=new ChangeIdentity() { @Override public String read(Path path) throws IOException {
        try {
            Object value=Files.getAttribute(path,"unix:ctime",LinkOption.NOFOLLOW_LINKS);
            if(!(value instanceof java.nio.file.attribute.FileTime)) throw new IOException("Strong local change identity unavailable");
            return value.toString();
        } catch(UnsupportedOperationException | IllegalArgumentException e) { throw new IOException("Strong local change identity unavailable",e); }
    } };
    private static final String[] NAMES = {"audio.wav","intent.bin","manifest.bin"};
    private static final String ID = "segment-[0-9a-f]{64}-(0|[1-9][0-9]*)\\.ready";
    private static final class Stamp {
        final String key, created, modified, changed;
        final long size;
        Stamp(Path path, boolean directory, ChangeIdentity identity) throws IOException {
            BasicFileAttributes a = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (directory ? !a.isDirectory() : !a.isRegularFile()) throw new IOException("Unsafe cleanup object type");
            if (a.fileKey() == null) throw new IOException("Stable local identity unavailable");
            changed=identity.read(path);
            key = a.fileKey().toString(); created = a.creationTime().toString(); modified = a.lastModifiedTime().toString(); size = a.size();
            if (key.length() > 256 || created.length() > 128 || modified.length() > 128 || changed.length() > 128 || size < 0) throw new IOException("Unsupported cleanup identity");
        }
        Stamp(DataInputStream in) throws IOException {
            key=in.readUTF(); created=in.readUTF(); modified=in.readUTF(); changed=in.readUTF(); size=in.readLong();
            if (key.isEmpty() || key.length()>256 || created.length()>128 || modified.length()>128 || changed.length()>128 || size<0) throw new IOException("Invalid cleanup identity");
        }
        void write(DataOutputStream out) throws IOException { out.writeUTF(key); out.writeUTF(created); out.writeUTF(modified); out.writeUTF(changed); out.writeLong(size); }
        void check(Path path, boolean directory, boolean partiallyPurged, ChangeIdentity identity) throws IOException {
            Stamp now = new Stamp(path,directory,identity);
            if (!key.equals(now.key) || (!(directory && partiallyPurged) && !created.equals(now.created)) || (!directory && !changed.equals(now.changed))
                || (!partiallyPurged && (!modified.equals(now.modified) || size != now.size))) throw new IOException("Cleanup identity changed");
        }
    }
    public static final class Prepared {
        public final String source;
        private final Stamp directory;
        private final Stamp[] files;
        private final ChangeIdentity identity;
        private Prepared(String source, Stamp directory, Stamp[] files, ChangeIdentity identity) { this.source=source; this.directory=directory; this.files=files; this.identity=identity; }
        public byte[] encode() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeUTF(source); directory.write(out); for (Stamp stamp : files) stamp.write(out); out.flush();
            if (bytes.size()>MAX_BINDING_BYTES) throw new IOException("Oversized cleanup binding"); return bytes.toByteArray();
        }
    }
    private VerifiedLocalCleanup() { }
    public static Prepared decode(byte[] bytes) throws IOException { return decode(bytes,HOST_IDENTITY); }
    public static Prepared decode(byte[] bytes, ChangeIdentity identity) throws IOException {
        if(identity==null) throw new IOException("Missing identity provider");
        if (bytes==null || bytes.length>MAX_BINDING_BYTES) throw new IOException("Invalid cleanup binding");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt()!=1) throw new IOException("Unsupported cleanup binding");
        String source=in.readUTF(); if (!source.matches(ID)) throw new IOException("Invalid cleanup source");
        Stamp directory=new Stamp(in); Stamp[] files=new Stamp[3]; for(int i=0;i<3;i++) files[i]=new Stamp(in);
        if(in.read()!=-1) throw new IOException("Trailing cleanup binding"); return new Prepared(source,directory,files,identity);
    }
    /** Full audio hashing happens on the transfer worker; production mutation requires idle recording. */
    public static Prepared prepare(CommittedSegments catalog, CommittedSegments.Entry entry, VerifiedExport.Cancellation cancellation) throws IOException { return prepare(catalog,entry,cancellation,HOST_IDENTITY); }
    public static Prepared prepare(CommittedSegments catalog, CommittedSegments.Entry entry, VerifiedExport.Cancellation cancellation, ChangeIdentity identity) throws IOException {
        if(identity==null) throw new IOException("Missing identity provider");
        catalog.unchanged(entry); Path directory=catalog.rootPath().resolve(entry.id);
        Stamp d=new Stamp(directory,true,identity); Stamp[] files=new Stamp[3];
        for(int i=0;i<3;i++) files[i]=new Stamp(directory.resolve(NAMES[i]),false,identity);
        catalog.verify(entry,cancellation);
        Prepared result=new Prepared(entry.id,d,files,identity); inspect(directory,result,false);
        catalog.unchanged(entry); result.encode(); return result;
    }
    private static Path ready(Path root, Prepared p) throws IOException {
        if (p==null || !p.source.matches(ID)) throw new IOException("Invalid cleanup request");
        return root.resolve(p.source);
    }
    private static Path quarantine(Path root, Prepared p) throws IOException {
        ready(root,p); return root.resolve(p.source.substring(0,p.source.length()-6)+".quarantine");
    }
    private static boolean exists(Path path) { return Files.exists(path,LinkOption.NOFOLLOW_LINKS); }
    private static void inspect(Path directory, Prepared p, boolean partial) throws IOException {
        p.directory.check(directory,true,partial,p.identity); int count=0;
        try (DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries) {
                int index=Arrays.asList(NAMES).indexOf(entry.getFileName().toString());
                if(index<0) throw new IOException("Unexpected quarantine object");
                p.files[index].check(entry,false,false,p.identity); count++;
            }
        }
        if(!partial && count!=3) throw new IOException("Incomplete cleanup source");
    }
    /** Caller owns the writer lock and has durably committed cleanup intent before calling. */
    static void quarantine(Path root, DirectorySpool.DirectoryProtocol protocol, Prepared p) throws IOException {
        Path source=ready(root,p), target=quarantine(root,p);
        if(exists(target)) throw new IOException("Quarantine already exists");
        inspect(source,p,false); protocol.atomicPublish(source,target); protocol.syncDirectory(root); inspect(target,p,false);
    }
    /** Before purge intent, restart/disable restores the complete original instead of assuming freshness. */
    static void restore(Path root, DirectorySpool.DirectoryProtocol protocol, Prepared p) throws IOException {
        Path source=ready(root,p), target=quarantine(root,p);
        if(exists(source)) { if(exists(target)) throw new IOException("Both cleanup namespaces exist"); inspect(source,p,false); return; }
        inspect(target,p,false); protocol.atomicPublish(target,source); protocol.syncDirectory(root); inspect(source,p,false);
    }
    /** Called only after durable purge intent. Replays missing members but never a replacement object. */
    static void purge(Path root, DirectorySpool.DirectoryProtocol protocol, Prepared p) throws IOException {
        Path source=ready(root,p), target=quarantine(root,p);
        if(exists(source)) throw new IOException("Live source exists during purge");
        Path retired=root.resolve(p.source.substring(0,p.source.length()-6)+".retired");
        if(!exists(target)) {
            p.directory.check(retired,true,true,p.identity);
            try(DirectoryStream<Path> remaining=Files.newDirectoryStream(retired)) { if(remaining.iterator().hasNext()) throw new IOException("Retired marker not empty"); }
            protocol.syncDirectory(root); return;
        }
        if(exists(retired)) throw new IOException("Retirement identity conflict");
        inspect(target,p,true);
        for(int i=0;i<3;i++) {
            Path file=target.resolve(NAMES[i]); if(!exists(file)) continue;
            p.directory.check(target,true,true,p.identity); p.files[i].check(file,false,false,p.identity);
            Files.delete(file); protocol.syncDirectory(target);
        }
        p.directory.check(target,true,true,p.identity);
        try(DirectoryStream<Path> remaining=Files.newDirectoryStream(target)) { if(remaining.iterator().hasNext()) throw new IOException("Quarantine not empty"); }
        // Keep the original directory inode as an empty ordinal reservation. Never reuse a deleted ID.
        protocol.atomicPublish(target,retired); protocol.syncDirectory(root);
    }
    /** Empty ordinal marker only. The caller separately proves committed retirement intent. */
    public static boolean verifyRetired(Path root,Prepared p,boolean allowAbsent)throws IOException {
        Path source=ready(root,p),target=quarantine(root,p);
        Path retired=root.resolve(p.source.substring(0,p.source.length()-6)+".retired");
        Path partial=root.resolve(p.source.substring(0,p.source.length()-6)+".part");
        if(exists(source)||exists(target)||exists(partial))throw new IOException("live/partial source blocks retirement");
        if(!exists(retired)){if(allowAbsent)return false;throw new IOException("missing retired marker");}
        p.directory.check(retired,true,true,p.identity);
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(retired)){if(entries.iterator().hasNext())throw new IOException("retired marker is not empty");}
        return true;
    }
    static void pruneRetired(Path root,DirectorySpool.DirectoryProtocol protocol,Prepared p,boolean allowAbsent)throws IOException {
        if(verifyRetired(root,p,allowAbsent)) Files.delete(root.resolve(p.source.substring(0,p.source.length()-6)+".retired"));
        protocol.syncDirectory(root);
    }

}
