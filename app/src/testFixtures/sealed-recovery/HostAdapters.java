package io.github.headmaster218.recorder.android;
import android.content.Context;
import io.github.headmaster218.recorder.core.DirectorySpool;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
/** These replace Android adapters only; production DirectorySpool does all filesystem work. */
final class RecordingSettings { static final long FREE_RESERVE=0; private RecordingSettings(){ } }
final class ExportCoordinator { static volatile boolean busy; static boolean cleanupBlocked(){return busy;} }
final class RetirementStore implements DirectorySpool.Initializer {
    RetirementStore(Context ignored){ }
    @Override public Map<String,Long> initialize(Path root,DirectorySpool.DirectoryProtocol protocol){return Collections.emptyMap();}
}
final class AndroidDirectoryProtocol implements DirectorySpool.DirectoryProtocol {
    static volatile CountDownLatch entered,release;
    static volatile boolean failAfterPublish;
    static volatile int publications;
    private final DirectorySpool.NioProtocol nio=new DirectorySpool.NioProtocol();
    private boolean published;
    static void reset(){entered=null;release=null;failAfterPublish=false;publications=0;}
    @Override public void syncDirectory(Path directory)throws IOException {
        if(directory.getFileName().toString().endsWith(".part")&&entered!=null){
            entered.countDown();
            try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("host test gate timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        }
        if(published&&failAfterPublish)throw new IOException("injected uncertain root force");
        nio.syncDirectory(directory);
    }
    @Override public void atomicPublish(Path from,Path to)throws IOException {
        nio.atomicPublish(from,to);published=true;publications++;
    }
}
