package io.github.headmaster218.recorder.core;

/** Includes payload, staging, journal and finalization bytes in the caller's estimates. */
public final class CachePolicy {
    public enum Decision { CONTINUE, STOP_AND_ALERT }
    public final long maximumBytes, minimumFreeBytes;
    public CachePolicy(long maximumBytes, long minimumFreeBytes) {
        if (maximumBytes <= 0 || minimumFreeBytes < 0) throw new IllegalArgumentException();
        this.maximumBytes = maximumBytes; this.minimumFreeBytes = minimumFreeBytes;
    }
    public Decision beforeWrite(long occupiedBytes, long availableBytes, long nextWriteBytes) {
        if (occupiedBytes < 0 || availableBytes < 0 || nextWriteBytes < 0) throw new IllegalArgumentException();
        if (occupiedBytes > maximumBytes || nextWriteBytes > maximumBytes - occupiedBytes
            || availableBytes < minimumFreeBytes || nextWriteBytes > availableBytes - minimumFreeBytes)
            return Decision.STOP_AND_ALERT;
        return Decision.CONTINUE;
    }
    // Deliberately no eviction operation: unverified files are never expendable.
}
