package io.github.headmaster218.recorder.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** New host-only lifecycle interleaving; executes production coordinator, not Android. */
public final class DestroyDuringCleanPauseTest {
    private static int assertions;
    private static void require(boolean value,String message) {
        assertions++;
        if(!value)throw new AssertionError(message);
    }
    private static final class Write {
        final RecoverySession.JournalCompletion callback;
        boolean acknowledged;
        Write(RecoverySession.JournalCompletion callback){this.callback=callback;}
    }
    private static final class Host implements RecoverySession.Host,RecoverySession.Engines,RecoverySession.Journal {
        final ArrayDeque<Runnable> queue=new ArrayDeque<Runnable>();
        final List<Write> writes=new ArrayList<Write>();
        final RecoverySession session=new RecoverySession("capture-destroy-pause",0,false,this,this,this);
        RecoverySession.Events events;
        Attempt attempt;
        int created,finished,timers;
        boolean cleanPause,released;
        @Override public long nowMillis(){return 10;}
        @Override public boolean authorized(){return true;} // Destruction itself must fence authority.
        @Override public void post(Runnable work){queue.add(work);}
        @Override public void postDelayed(Runnable work,long delay){timers++;}
        @Override public void remove(Runnable work){timers--;}
        @Override public void status(String text){ }
        @Override public void finished(String text,boolean paused,long epoch,boolean admissionReleased){
            finished++;cleanPause=paused;released=admissionReleased;
        }
        @Override public RecoverySession.Attempt create(String capture,long epoch,boolean resumed,RecoverySession.Events callbacks){
            created++;events=callbacks;attempt=new Attempt(capture);return attempt;
        }
        @Override public void persist(String session,int ordinal,String record,RecoverySession.JournalCompletion callback){
            writes.add(new Write(callback));
        }
        void drain(){
            int bound=100;
            while(!queue.isEmpty()){require(--bound>0,"callback bound");queue.remove().run();}
        }
        void cleanResult(){
            CaptureEvidence evidence=new CaptureEvidence(attempt.captureId(),attempt.runId());
            evidence.handleObtained();evidence.releaseReturned();
            evidence.openingSpool();evidence.openingWriter();evidence.spoolClosed();evidence.writerClosed();
            PcmReadAccounting pcm=new PcmReadAccounting();pcm.readAccepted(8);pcm.appendConfirmed(8);
            events.finished(attempt,evidence.finish(pcm.snapshot(),true),"clean pause completed",true,1);
            drain();
        }
        void acknowledge(){
            int bound=100;
            while(true){
                drain();Write pending=null;
                for(Write write:writes)if(!write.acknowledged){pending=write;break;}
                if(pending==null)return;
                require(--bound>0,"journal bound");pending.acknowledged=true;pending.callback.complete(false,null);
            }
        }
    }
    private static final class Attempt implements RecoverySession.Attempt {
        final String capture;
        Attempt(String capture){this.capture=capture;}
        @Override public String captureId(){return capture;}
        @Override public String runId(){return "run-0";}
        @Override public void start(){ }
        @Override public void stop(RecoverySession.Stop reason){ }
    }
    private static Host pendingCleanPause(){
        Host host=new Host();require(host.session.start(true),"explicit start admitted");
        host.session.pause();host.cleanResult();
        require(host.finished==0,"must await durable journal acknowledgement");
        require(host.writes.size()==1,"clean result write outstanding");
        return host;
    }
    private static void cleanPauseControl(){
        Host host=pendingCleanPause();host.acknowledge();
        require(host.finished==1 && host.released && host.cleanPause,"ordinary clean pause remains supported");
    }
    private static void destroyAfterResult(boolean latePause){
        Host host=pendingCleanPause();Write first=host.writes.get(0);
        host.session.destroy();host.session.destroy();
        if(latePause)host.session.pause(); // Late UI callback cannot restore destroyed-session capability.
        require(host.finished==0,"destruction is not journal completion evidence");
        host.acknowledge();
        require(host.finished==1 && host.released,"known-clean resources may release admission");
        require(!host.cleanPause,"destroyed session must not grant clean-pause capability, latePause="+latePause);
        require(host.created==1 && host.timers==0,"destruction must not schedule a replacement");
        first.callback.complete(false,null);host.cleanResult();host.acknowledge();
        require(host.finished==1 && !host.cleanPause,"late duplicate callbacks cannot resurrect pause capability");
        require(!host.session.start(true),"destroyed coordinator cannot restart");
    }
    public static void main(String[] args){
        cleanPauseControl();destroyAfterResult(false);destroyAfterResult(true);
        System.out.println("PASS "+assertions+" destruction-during-clean-pause assertions; host ports only, no Android runtime");
    }
}
