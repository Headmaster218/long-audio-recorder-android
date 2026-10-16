package io.github.headmaster218.recorder.core;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
public final class VerifiedLocalCleanupTest {
    private static int checks, serial;
    private interface Action { void run() throws Exception; }
    private static void yes(boolean b) { checks++; if(!b) throw new AssertionError("check "+checks); }
    private static void fails(Action a) throws Exception { checks++; try { a.run(); } catch(IOException e) { return; } throw new AssertionError("expected failure"); }
    private static final CachePolicy CACHE=new CachePolicy(1024*1024,0);
    private static final DirectorySpool.DirectoryProtocol NORMAL=new DirectorySpool.NioProtocol();
    private static final DirectorySpool.Faults NOFAULT=(op,path)->{};
    private static final class Fixture {
        final Path root, ready, quarantine, retired;
        final String capture, id;
        final CommittedSegments catalog;
        final CommittedSegments.Entry entry;
        final VerifiedLocalCleanup.Prepared prepared;
        Fixture() throws Exception {
            root=Files.createTempDirectory(Paths.get("app/build"),"cleanup-synthetic-").toAbsolutePath(); capture="synthetic-cleanup-"+serial++;
            try(DirectorySpool spool=new DirectorySpool(root,CACHE,NORMAL,NOFAULT)) {
                CaptureTimeline.Epoch epoch=new CaptureTimeline.Epoch(capture,"run",0,new PcmFormat(16000,1),"mic","mic",CaptureTimeline.Gap.START);
                PcmSegmentWriter writer=new PcmSegmentWriter(spool,epoch,8,0,p->{}); writer.append(new byte[16],0,16); writer.stop();
            }
            catalog=new CommittedSegments(root); entry=catalog.page(null).entries.get(0);id=entry.id;
            ready=root.resolve(id); quarantine=root.resolve(id.replace(".ready",".quarantine"));retired=root.resolve(id.replace(".ready",".retired"));
            prepared=VerifiedLocalCleanup.prepare(catalog,entry,()->false);
        }
        DirectorySpool open() throws Exception { return new DirectorySpool(root,CACHE,NORMAL,NOFAULT); }
    }
    public static void main(String[] args) throws Exception {
        Fixture f=new Fixture(); byte[] encoded=f.prepared.encode();
        yes(VerifiedLocalCleanup.decode(encoded).source.equals(f.id));
        fails(()->VerifiedLocalCleanup.decode(Arrays.copyOf(encoded,encoded.length-1)));
        fails(()->VerifiedLocalCleanup.decode(Arrays.copyOf(encoded,encoded.length+1)));
        fails(()->VerifiedLocalCleanup.decode(new byte[VerifiedLocalCleanup.MAX_BINDING_BYTES+1]));
        try(DirectorySpool spool=f.open()) {
            fails(()->spool.purgeQuarantine(f.prepared));yes(Files.exists(f.ready.resolve("audio.wav")));
            spool.quarantineVerified(f.prepared);yes(!Files.exists(f.ready));yes(Files.exists(f.quarantine.resolve("audio.wav")));
            yes(spool.nextSequence(f.capture)==1);fails(()->spool.quarantineVerified(f.prepared));
        }
        try(DirectorySpool spool=f.open()) {
            spool.restoreQuarantine(VerifiedLocalCleanup.decode(encoded));yes(Files.exists(f.ready.resolve("audio.wav")));
            spool.restoreQuarantine(f.prepared);yes(Files.exists(f.ready));
            spool.quarantineVerified(f.prepared);spool.purgeQuarantine(f.prepared);
            yes(!Files.exists(f.quarantine));yes(!Files.exists(f.ready));yes(Files.isDirectory(f.retired));
            yes(spool.nextSequence(f.capture)==1);spool.purgeQuarantine(f.prepared);
            yes(f.catalog.page(null).entries.isEmpty());
            final int[] recovery={0};spool.scan(x->recovery[0]++);yes(recovery[0]==0);
        }
        Fixture mutated=new Fixture();Path wav=mutated.ready.resolve("audio.wav");FileTime old=Files.getLastModifiedTime(wav);
        byte[] bytes=Files.readAllBytes(wav);bytes[bytes.length-1]^=1;Files.write(wav,bytes);Files.setLastModifiedTime(wav,old);
        try(DirectorySpool spool=mutated.open()) { fails(()->spool.quarantineVerified(mutated.prepared));yes(Files.exists(wav)); }
        Fixture replacement=new Fixture();Path original=replacement.ready.resolve("audio.wav");byte[] identical=Files.readAllBytes(original);
        Files.move(original,replacement.root.resolve("saved-original"));Files.write(original,identical);
        try(DirectorySpool spool=replacement.open()) { fails(()->spool.quarantineVerified(replacement.prepared));yes(Files.exists(original)); }
        Fixture unexpected=new Fixture();Files.write(unexpected.ready.resolve("extra"),new byte[]{1});
        try(DirectorySpool spool=unexpected.open()) { fails(()->spool.quarantineVerified(unexpected.prepared));yes(Files.exists(unexpected.ready.resolve("audio.wav"))); }
        Fixture conflict=new Fixture();Files.createDirectory(conflict.quarantine);
        fails(()->{try(DirectorySpool spool=conflict.open()) { spool.quarantineVerified(conflict.prepared); }});yes(Files.exists(conflict.ready));
        Fixture interrupted=new Fixture();final boolean[] fail={false};
        DirectorySpool.DirectoryProtocol fault=new DirectorySpool.DirectoryProtocol() {
            public void syncDirectory(Path p)throws IOException { NORMAL.syncDirectory(p);if(fail[0]&&p.equals(interrupted.quarantine)){fail[0]=false;throw new IOException("synthetic crash after unlink");} }
            public void atomicPublish(Path a,Path b)throws IOException {NORMAL.atomicPublish(a,b);}
        };
        try(DirectorySpool spool=new DirectorySpool(interrupted.root,CACHE,fault,NOFAULT)) {
            spool.quarantineVerified(interrupted.prepared);fail[0]=true;fails(()->spool.purgeQuarantine(interrupted.prepared));
            yes(!Files.exists(interrupted.quarantine.resolve("audio.wav")));yes(Files.exists(interrupted.quarantine.resolve("manifest.bin")));
        }
        try(DirectorySpool spool=interrupted.open()) {
            spool.purgeQuarantine(VerifiedLocalCleanup.decode(interrupted.prepared.encode()));yes(Files.isDirectory(interrupted.retired));yes(spool.nextSequence(interrupted.capture)==1);
        }
        Fixture partialMutation=new Fixture();
        try(DirectorySpool spool=partialMutation.open()) {
            spool.quarantineVerified(partialMutation.prepared);Files.delete(partialMutation.quarantine.resolve("audio.wav"));
            Path manifest=partialMutation.quarantine.resolve("manifest.bin");Files.write(manifest,new byte[]{1});
            fails(()->spool.purgeQuarantine(partialMutation.prepared));yes(Files.exists(manifest));yes(Files.exists(partialMutation.quarantine.resolve("intent.bin")));
        }
        for(int failAt=1;failAt<=4;failAt++) {
            Fixture crash=new Fixture();final int target=failAt;final int[] syncs={0};final boolean[] armed={false};
            DirectorySpool.DirectoryProtocol protocol=new DirectorySpool.DirectoryProtocol() {
                public void syncDirectory(Path path)throws IOException { NORMAL.syncDirectory(path);if(armed[0]&&++syncs[0]==target)throw new IOException("synthetic sync boundary"); }
                public void atomicPublish(Path from,Path to)throws IOException {NORMAL.atomicPublish(from,to);}
            };
            try(DirectorySpool spool=new DirectorySpool(crash.root,CACHE,protocol,NOFAULT)) {
                spool.quarantineVerified(crash.prepared);armed[0]=true;fails(()->spool.purgeQuarantine(crash.prepared));
            }
            try(DirectorySpool spool=crash.open()) {
                spool.purgeQuarantine(VerifiedLocalCleanup.decode(crash.prepared.encode()));yes(Files.isDirectory(crash.retired));yes(spool.nextSequence(crash.capture)==1);
            }
        }
        Fixture symlink=new Fixture();Path outside=symlink.root.resolve("outside-original");Files.write(outside,new byte[]{9});
        try(DirectorySpool spool=symlink.open()) {
            spool.quarantineVerified(symlink.prepared);Path member=symlink.quarantine.resolve("intent.bin");Files.delete(member);Files.createSymbolicLink(member,outside);
            fails(()->spool.purgeQuarantine(symlink.prepared));yes(Arrays.equals(Files.readAllBytes(outside),new byte[]{9}));yes(Files.exists(symlink.quarantine.resolve("audio.wav")));
        }
        Fixture hashRace=new Fixture(); final int[] calls={0};
        fails(()->VerifiedLocalCleanup.prepare(hashRace.catalog,hashRace.entry,()->{
            if(++calls[0]==3) try {
                Path path=hashRace.ready.resolve("audio.wav");FileTime time=Files.getLastModifiedTime(path);
                byte[] data=Files.readAllBytes(path);data[data.length-1]^=1;Files.write(path,data);Files.setLastModifiedTime(path,time);
            } catch(IOException e) { throw new RuntimeException(e); }
            return false;
        }));yes(Files.exists(hashRace.ready.resolve("audio.wav")));
        Fixture fallback=new Fixture();
        try(DirectorySpool spool=fallback.open()) {
            spool.quarantineVerified(fallback.prepared);
            // Model Android directory creationTime falling back to mutable mtime. Only directory
            // birthtime is altered in this synthetic binding; dev/inode and file bindings remain.
            java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(fallback.prepared.encode()));
            java.io.ByteArrayOutputStream bytesOut=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytesOut);
            out.writeInt(in.readInt());out.writeUTF(in.readUTF());out.writeUTF(in.readUTF());in.readUTF();out.writeUTF("fallback-mtime-changed");
            int value;while((value=in.read())!=-1)out.write(value);out.flush();
            spool.purgeQuarantine(VerifiedLocalCleanup.decode(bytesOut.toByteArray()));yes(Files.isDirectory(fallback.retired));
        }
        Fixture cancelled=new Fixture();fails(()->VerifiedLocalCleanup.prepare(cancelled.catalog,cancelled.entry,()->true));yes(Files.exists(cancelled.ready));
        System.out.println("PASS: "+checks+" synthetic verified-local-cleanup assertions; no user data or network");
    }
}
