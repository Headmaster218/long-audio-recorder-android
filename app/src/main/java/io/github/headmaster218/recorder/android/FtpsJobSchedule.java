package io.github.headmaster218.recorder.android;

import android.app.job.JobInfo;
import android.content.ComponentName;

/** Charging-only wake, never a grant to transfer. The coordinator must first admit a bound Wi-Fi route. */
final class FtpsJobSchedule {
    static JobInfo build(int id, ComponentName service, long delay) {
        if (delay < 0) throw new IllegalArgumentException("Negative scheduled delay");
        // UNMETERED silently requires INTERNET/VALIDATED. A custom request can still match only
        // the UID default network on Android releases and strand a non-default local NAS Wi-Fi.
        // NONE is supported throughout API 29-37. Runtime Wi-Fi/charging guards remain mandatory.
        return new JobInfo.Builder(id,service).setRequiresCharging(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setPersisted(true).setMinimumLatency(Math.max(1000,delay))
            .setBackoffCriteria(60000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
    }
    private FtpsJobSchedule() { }
}
