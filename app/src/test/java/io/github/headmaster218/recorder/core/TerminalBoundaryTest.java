package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Terminal-aware publication is finite host evidence, never physical capture/EOS inference. */
public final class TerminalBoundaryTest {
    private static int checks, cases;
    private static final Path BASE = Paths.get("app/build/terminal-boundary-tests/fixtures-"+System.nanoTime());
    private interface Action { void run() throws Exception; }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check "+checks); }
    private static void fails(Action action) throws Exception {
        checks++; try { action.run(); } catch (IOException | IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("expected rejection "+checks);
    }
    private static CaptureTimeline.Epoch epoch(long number) {
        return new CaptureTimeline.Epoch("terminal-fixture","run",number,PcmFormat.DEFAULT,"synthetic","synthetic",
            number==0 ? CaptureTimeline.Gap.START : CaptureTimeline.Gap.ROUTE_CHANGE_UNCERTAIN);
    }
    private static Path root() throws IOException { return Files.createDirectories(BASE.resolve("case-"+cases++)); }
    private static DirectorySpool spool(Path root) throws IOException {
        return new DirectorySpool(root,new CachePolicy(2000000,0),new DirectorySpool.NioProtocol(),(op,p)->{});
    }
    private static void geometry() throws Exception {
        for (int frames : new int[]{0,1,4,5,8,9,12}) for (int stride : new int[]{1,2,3,8,32}) {
            Path root=root(); List<SegmentStore.Published> published=new ArrayList<SegmentStore.Published>();
            byte[] pcm=new byte[frames*2]; for(int i=0;i<pcm.length;i++)pcm[i]=(byte)(i*17);
            try (DirectorySpool store=spool(root)) {
                PcmSegmentWriter writer=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,published::add);
                for(int at=0;at<pcm.length;at+=stride)writer.append(pcm,at,Math.min(stride,pcm.length-at));
                yes(published.size()==(frames==0 ? 0 : (frames-1)/4));
                writer.append(new byte[0],0,0); yes(published.size()==(frames==0 ? 0 : (frames-1)/4));
                writer.stop(); yes(published.size()==(frames+3)/4); fails(writer::stop); fails(()->writer.append(new byte[2],0,2));
            }
            CommittedSegments catalog=new CommittedSegments(root); CommittedSegments.Page page=catalog.page(null);
            yes(page.rejected==0);yes(page.entries.size()==published.size());
            int offset=0;
            for(int i=0;i<page.entries.size();i++) {
                CommittedSegments.Entry entry=page.entries.get(i);catalog.verify(entry,()->false);
                SegmentMetadata m=entry.metadata;yes(m.intent.sequence==i);yes(m.intent.frameStart==i*4);
                yes(m.closeReason==(i==page.entries.size()-1 ? CaptureTimeline.Close.STOP : CaptureTimeline.Close.ROTATION));
                yes(m.trustedPreviousSeam==(i>0));yes(m.frameCount==Math.min(4,frames-i*4));
                byte[] wav=Files.readAllBytes(root.resolve(entry.id).resolve("audio.wav"));
                yes(Arrays.equals(Arrays.copyOfRange(wav,44,wav.length),Arrays.copyOfRange(pcm,offset,offset+wav.length-44)));
                offset+=wav.length-44;
                yes(Arrays.equals(m.encode(),SegmentMetadata.decode(m.encode()).encode()));
            }
            yes(offset==pcm.length);
        }
    }
    private static void boundaries() throws Exception {
        Path root=root();List<SegmentStore.Published> published=new ArrayList<SegmentStore.Published>();
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,published::add);
            w.append(new byte[8],0,8);yes(published.isEmpty());w.interrupt();
            yes(published.size()==1);yes(published.get(0).metadata.closeReason==CaptureTimeline.Close.INTERRUPTION);
            fails(w::stop);
        }
        root=root();published.clear();
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,published::add);
            w.append(new byte[8],0,8);w.beginEpoch(epoch(1));
            yes(published.size()==1);yes(published.get(0).metadata.closeReason==CaptureTimeline.Close.INTERRUPTION);
            w.append(new byte[8],0,8);w.stop();yes(published.size()==2);
            SegmentMetadata m=published.get(1).metadata;yes(m.closeReason==CaptureTimeline.Close.STOP);
            yes(m.intent.epoch.number==1);yes(m.intent.sequence==1);yes(m.intent.frameStart==0);yes(!m.trustedPreviousSeam);
        }
        root=root();published.clear();
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter legacy=new PcmSegmentWriter(store,epoch(0),4,0,published::add);
            legacy.append(new byte[8],0,8);yes(published.size()==1);
            legacy.stop();yes(published.size()==1);yes(published.get(0).metadata.closeReason==CaptureTimeline.Close.ROTATION);
        }
    }
    private static void uncertainty() throws Exception {
        Path root=root();List<SegmentStore.Published> published=new ArrayList<SegmentStore.Published>();
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,published::add);
            w.append(new byte[8],0,8);yes(published.isEmpty()); // No stop: emulate owner loss, not power loss.
        }
        yes(new CommittedSegments(root).page(null).entries.isEmpty());
        try(DirectorySpool store=spool(root)) {
            List<DirectorySpool.Recovery> reports=new ArrayList<DirectorySpool.Recovery>();store.scan(reports::add);
            yes(reports.size()==1);yes(reports.get(0).state==DirectorySpool.RecoveryState.PRESERVED_PARTIAL);
            fails(()->store.confirmReady(reports.get(0).localObjectId));yes(store.nextSequence("terminal-fixture")==1);
        }
        root=root();published.clear();
        try(DirectorySpool store=new DirectorySpool(root,new CachePolicy(2000000,0),new DirectorySpool.NioProtocol(),
                (op,p)->{if(op==DirectorySpool.Operation.WRITE_MANIFEST)throw new IOException("synthetic manifest failure");})) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,published::add);
            w.append(new byte[8],0,8);fails(w::stop);yes(published.isEmpty());fails(w::stop);
        }
        yes(new CommittedSegments(root).page(null).entries.isEmpty());
        root=root();published.clear();
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,published::add);
            w.append(new byte[1],0,1);fails(w::stop);yes(published.isEmpty());
        }
    }
    private static void observedStereoAndAdmission() throws Exception {
        Path root=root();List<SegmentStore.Published> published=new ArrayList<SegmentStore.Published>();
        CaptureTimeline.Epoch stereo=new CaptureTimeline.Epoch("terminal-fixture","run",0,new PcmFormat(16000,2),
            "synthetic","synthetic",CaptureTimeline.Gap.START);
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,stereo,4,0,published::add);
            CaptureAnchors.Read read=new CaptureAnchors.Read("00000000-0000-0000-0000-000000000005","run",
                0,0,20,1,2,3,4,5,6,0,0,CaptureAnchors.Timestamp.notPolled(),CaptureAnchors.RUN_START);
            w.append(new byte[40],0,40,read);yes(published.size()==2);w.stop();yes(published.size()==3);
            for(int i=0;i<3;i++) {
                SegmentMetadata m=published.get(i).metadata;
                yes(m.captureAnchors.runSampleStart==i*8);yes(m.captureAnchors.runSampleEnd==Math.min((i+1)*8,20));
                yes(m.captureAnchors.firstRead.ordinal==0 && m.captureAnchors.lastRead.ordinal==0);
                yes(m.closeReason==(i==2 ? CaptureTimeline.Close.STOP : CaptureTimeline.Close.ROTATION));
                yes(Arrays.equals(m.encode(),SegmentMetadata.decode(m.encode()).encode()));
            }
        }
        root=root();published.clear();
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,stereo,4,0,published::add);
            for(int i=0;i<2;i++) {
                CaptureAnchors.Read read=new CaptureAnchors.Read("00000000-0000-0000-0000-000000000005","run",
                    i,i*8,(i+1)*8,1+i*10,2+i*10,3+i*10,4,5+i*10,6,0,0,CaptureAnchors.Timestamp.notPolled(),i==0 ? CaptureAnchors.RUN_START : 0);
                w.append(new byte[16],0,16,read);yes(published.size()==i);
            }
            w.stop();yes(published.size()==2);yes(published.get(1).metadata.closeReason==CaptureTimeline.Close.STOP);
            yes(published.get(0).metadata.captureAnchors.runSampleEnd==published.get(1).metadata.captureAnchors.runSampleStart);
            yes(published.get(1).metadata.captureAnchors.firstRead.ordinal==1);
        }
        root=root();published.clear();
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,stereo,4,0,published::add);
            CaptureAnchors.Read read=new CaptureAnchors.Read("00000000-0000-0000-0000-000000000005","run",
                0,0,8,1,2,3,4,5,6,0,0,CaptureAnchors.Timestamp.notPolled(),CaptureAnchors.RUN_START);
            w.append(new byte[16],0,16,read);
            w.beginEpoch(new CaptureTimeline.Epoch("terminal-fixture","new-run",1,new PcmFormat(16000,2),
                "synthetic","synthetic",CaptureTimeline.Gap.RESTART));w.stop();
            yes(published.size()==1);yes(published.get(0).metadata.closeReason==CaptureTimeline.Close.INTERRUPTION);
        }
        root=root();published.clear();
        try(DirectorySpool store=spool(root)) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,p->{throw new IOException("synthetic listener failure");});
            w.append(new byte[8],0,8);fails(()->w.append(new byte[2],0,2));fails(w::stop);
        }
        CommittedSegments.Page afterListener=new CommittedSegments(root).page(null);
        yes(afterListener.entries.size()==1);yes(afterListener.entries.get(0).metadata.closeReason==CaptureTimeline.Close.ROTATION);
        root=root();published.clear();
        SegmentMetadata.Intent intent=new SegmentMetadata.Intent(epoch(0),0,0,4);
        long limit=SegmentMetadata.encodeIntent(intent).length+44+DirectorySpool.FINALIZATION_RESERVE+8;
        try(DirectorySpool store=new DirectorySpool(root,new CachePolicy(limit,0),new DirectorySpool.NioProtocol(),(op,p)->{})) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,published::add);
            w.append(new byte[8],0,8);yes(published.isEmpty());fails(()->w.append(new byte[2],0,2));
            yes(published.size()==1);yes(published.get(0).metadata.closeReason==CaptureTimeline.Close.ROTATION);fails(w::stop);
        }
        root=root();published.clear();
        try(DirectorySpool store=new DirectorySpool(root,new CachePolicy(2000000,0),new DirectorySpool.NioProtocol(),
                (op,p)->{if(op==DirectorySpool.Operation.WRITE_MANIFEST)throw new IOException("synthetic rotation failure");})) {
            PcmSegmentWriter w=PcmSegmentWriter.terminalAware(store,epoch(0),4,0,published::add);
            w.append(new byte[8],0,8);fails(()->w.append(new byte[2],0,2));yes(published.isEmpty());fails(w::stop);
        }
        yes(new CommittedSegments(root).page(null).entries.isEmpty());
    }
    public static void main(String[] args) throws Exception {
        geometry();boundaries();uncertainty();observedStereoAndAdmission();
        System.out.println("PASS terminal boundary: "+checks+" assertions; "+cases+" finite storage scenarios; no device/power-loss claims");
    }
}
