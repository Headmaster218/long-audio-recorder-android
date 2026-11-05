package io.github.headmaster218.recorder.android;

/** A zero-length list is not proof of an empty queue when the journal is unavailable. */
final class TransferProgressDisplay {
    private TransferProgressDisplay() { }
    static String describe(long loadedThreshold,int unverified,int verified) {
        if(loadedThreshold<=0)return "Transfer record counts unavailable: queue is loading or its journal could not be read. Do not assume there is no backlog.";
        return "Detailed transfer records: "+unverified+" not remotely verified; "+verified+" verified by full readback at the recorded time."
            +"\nQueued, running, retrying and blocked are NOT verified copies. Compacted receipts are listed separately below. Counts are per destination record, not unique recordings; unenrolled local files are not included. Remote persistence is not guaranteed.";
    }
}
