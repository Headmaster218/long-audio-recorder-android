package io.github.headmaster218.recorder.android;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Native scheduler entrypoint, separate from the microphone foreground service. */
public final class FtpsJobService extends JobService {
    private static final class Invocation {
        final JobParameters parameters;
        FtpsCoordinator.Job job;
        boolean stopped;
        Invocation(JobParameters parameters) { this.parameters = parameters; }
    }
    private Invocation current;
    @Override public boolean onStartJob(final JobParameters params) {
        if (current != null) return false;
        final FtpsCoordinator coordinator = FtpsCoordinator.get(this);
        final Invocation call = new Invocation(params); current = call;
        call.job = coordinator.startJob(new FtpsCoordinator.Completion() {
            @Override public void finished() {
                if (current == call) current = null;
                if (!call.stopped) jobFinished(call.parameters,false);
                // Re-read current queue state on its owner, so a late completion cannot undo a newer pause/resume.
                coordinator.reschedule();
            }
        });
        if (call.job == null) { current = null; return false; }
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Invocation call = current;
        if (call != null) { call.stopped = true; if (call.job != null) call.job.cancel(); }
        // Queue intent survives. A started attempt is recoverable only with exact readback.
        return true;
    }
    @Override public void onDestroy() {
        Invocation call = current;
        if (call != null) { call.stopped = true; if (call.job != null) call.job.cancel(); }
        super.onDestroy();
    }
}
