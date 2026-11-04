package io.github.headmaster218.recorder.android;
import android.content.Context;import java.nio.file.Path;import java.io.IOException;import java.util.concurrent.CountDownLatch;import java.util.concurrent.TimeUnit;import io.github.headmaster218.recorder.core.*;
final class RecordingService {static boolean permissionsReady(Context c){return true;}}
final class RecorderState {static volatile String status,route;static volatile long writtenFrames;static String bounded(String s){return s;}}
final class ExportCoordinator {static boolean cleanupBlocked(){return false;}}
final class FtpsCoordinator {private static final FtpsCoordinator INSTANCE=new FtpsCoordinator();static FtpsCoordinator get(Context c){return INSTANCE;}void published(SegmentStore.Published p){}void captureClosed(){}}
final class RetirementStore implements DirectorySpool.Initializer {RetirementStore(Context c){}public java.util.Map<String,Long> initialize(Path p,DirectorySpool.DirectoryProtocol protocol){return java.util.Collections.emptyMap();}}
final class AndroidDirectoryProtocol implements DirectorySpool.DirectoryProtocol {
 static volatile CountDownLatch entered,proceed;private final DirectorySpool.NioProtocol nio=new DirectorySpool.NioProtocol();
 public void syncDirectory(Path p)throws IOException{if(p.toString().endsWith(".part") && entered!=null){CountDownLatch signal=entered;entered=null;signal.countDown();try{if(!proceed.await(5,TimeUnit.SECONDS))throw new IOException("fixture drain gate timeout");}catch(InterruptedException e){throw new IOException(e);}}nio.syncDirectory(p);}
 public void atomicPublish(Path a,Path b)throws IOException{nio.atomicPublish(a,b);}}
