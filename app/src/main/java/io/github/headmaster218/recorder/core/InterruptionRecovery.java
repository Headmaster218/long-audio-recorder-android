package io.github.headmaster218.recorder.core;

import java.util.ArrayDeque;

/**
 * Pure, process-local recovery policy for an ALREADY authorized surviving microphone FGS.
 * No Android calls, timers, recorder creation, persistence, audio or service-start authority.
 * An adapter must serialize physical operations, recheck OS authority and honor recorder leases.
 */
public final class InterruptionRecovery {
    public enum State { STOPPED, STARTING, RUNNING, BACKOFF, PAUSED }
    public enum Reason {
        MICROPHONE_CONTENTION, ROUTE_UNAVAILABLE, DEAD_RECORDER,
        STORAGE_FAILURE, PERMISSION_REVOKED, UNKNOWN_FAILURE, SERVICE_AUTHORITY_LOST
    }
    public enum End {
        RESUMED, USER_PAUSED, USER_STOPPED, OPTED_OUT, AUTHORITY_LOST,
        RETRIES_EXHAUSTED, NONRECOVERABLE, CLOCK_EXHAUSTED
    }
    public enum EventKind { OPENED, RECOVERED, TERMINATED }

    /** Identity capability for one physical create/use/release attempt; never reconstruct it. */
    public static final class Lease {
        public final long generation;
        public final int attempt; // zero is the explicit initial start
        private Lease(long generation, int attempt) {
            this.generation = generation; this.attempt = attempt;
        }
    }
    /** A scheduled callback must carry this exact object, not just a timestamp/generation. */
    public static final class Retry {
        public final long generation, dueMillis;
        private Retry(long generation, long dueMillis) {
            this.generation = generation; this.dueMillis = dueMillis;
        }
    }
    /**
     * Observed interruption interval, not an exact hardware-loss or call-recording claim.
     * Only RECOVERED has a known observed endMillis. TERMINATED ends recovery, NOT the gap:
     * its endMillis stays -1; observedAtMillis records cancellation/exhaustion instead.
     * Completion events contain all observed failure reason flags.
     * The adapter must persist before acknowledgeGap(); this outbox alone is NOT durable.
     */
    public static final class GapEvent {
        public final EventKind kind;
        public final long generation, gap, startMillis, observedAtMillis, endMillis;
        public final Reason firstReason, lastReason;
        public final int reasonMask, retriesUsed;
        public final End end;
        private GapEvent(EventKind kind, long generation, long gap, long start, long observedAt, long finish,
                         Reason first, Reason last, int mask, int retriesUsed, End end) {
            this.kind = kind; this.generation = generation; this.gap = gap;
            this.startMillis = start; this.observedAtMillis = observedAt; this.endMillis = finish;
            this.firstReason = first; this.lastReason = last; this.reasonMask = mask;
            this.retriesUsed = retriesUsed; this.end = end;
        }
        public boolean includes(Reason reason) {
            if (reason == null) throw new IllegalArgumentException("reason");
            return (reasonMask & (1 << reason.ordinal())) != 0;
        }
    }
    public static final class Snapshot {
        public final State state;
        public final long generation;
        public final boolean recoveryEnabled, releaseRequired;
        public final int retriesUsed, unacknowledgedGapEvents;
        public final Lease lease;
        public final Retry retry;
        public final End lastEnd;
        private Snapshot(InterruptionRecovery c) {
            state = c.state; generation = c.generation;
            recoveryEnabled = c.recoveryEnabled; releaseRequired = c.releaseRequired;
            retriesUsed = c.retriesUsed; unacknowledgedGapEvents = c.events.size();
            lease = c.lease; retry = c.retry; lastEnd = c.lastEnd;
        }
    }

    private final int maxRetries, eventCapacity;
    private final long initialDelayMillis, maximumDelayMillis;
    private final ArrayDeque<GapEvent> events = new ArrayDeque<GapEvent>();
    private State state = State.STOPPED;
    private long generation, sessionGeneration, lastMillis = -1, nextGap;
    private boolean recoveryEnabled, releaseRequired, generationExhausted;
    private int retriesUsed;
    private Lease lease;
    private Retry retry;
    private End lastEnd;
    private boolean gapOpen;
    private long gapId, gapStart;
    private Reason firstReason, lastReason;
    private int reasonMask;

    /** Retry budget applies to the WHOLE explicit session; a brief success never resets it. */
    public InterruptionRecovery(int maxRetries, long initialDelayMillis, long maximumDelayMillis) {
        if (maxRetries < 0 || maxRetries > 8 || initialDelayMillis < 100
                || maximumDelayMillis < initialDelayMillis || maximumDelayMillis > 300000)
            throw new IllegalArgumentException("Use 0..8 retries and 100..300000 ms bounded backoff");
        this.maxRetries = maxRetries;
        this.initialDelayMillis = initialDelayMillis;
        this.maximumDelayMillis = maximumDelayMillis;
        // At most maxRetries successful recoveries plus one terminal interruption per session.
        eventCapacity = 2 * (maxRetries + 1);
    }

    /**
     * Only an explicit user action may call this, after a microphone FGS is already authorized.
     * False authority, outstanding release, pending evidence or a live session returns null.
     * A new controller starts STOPPED and never reconstructs intent from persisted preferences.
     */
    public synchronized Lease explicitStart(boolean optIn, boolean authorizedSurvivingFgs, long nowMillis) {
        if (!authorizedSurvivingFgs || (state != State.STOPPED && state != State.PAUSED)
                || lease != null || !events.isEmpty()) return null;
        checkTime(nowMillis);
        if (generationExhausted || generation == Long.MAX_VALUE)
            throw new IllegalStateException("Recovery generation exhausted; no new recorder authorized");
        generation++; sessionGeneration = generation; nextGap = 0;
        recoveryEnabled = optIn; retriesUsed = 0; retry = null;
        releaseRequired = false; lastEnd = null; lastMillis = nowMillis;
        state = State.STARTING; lease = new Lease(generation, 0); return lease;
    }

    /** Call only after first valid, attributable PCM has been accepted by the adapter. */
    public synchronized boolean captureConfirmed(Lease candidate, long nowMillis) {
        if (!current(candidate) || state != State.STARTING || releaseRequired) return false;
        checkTime(nowMillis); lastMillis = nowMillis;
        closeGap(End.RESUMED, nowMillis); state = State.RUNNING; return true;
    }

    /**
     * Report a failure once. The old lease remains held until physical release is acknowledged.
     * No callback can create a replacement recorder while that old lease is held.
     */
    public synchronized boolean interrupted(Lease candidate, Reason reason, long nowMillis) {
        if (!current(candidate) || (state != State.STARTING && state != State.RUNNING)
                || releaseRequired) return false;
        if (reason == null) throw new IllegalArgumentException("reason");
        checkTime(nowMillis); lastMillis = nowMillis;
        openOrUpdateGap(reason, nowMillis); releaseRequired = true;
        if (!recoveryEnabled) finishWait(End.OPTED_OUT, nowMillis);
        else if (!recoverable(reason)) finishWait(End.NONRECOVERABLE, nowMillis);
        else if (retriesUsed >= maxRetries) finishWait(End.RETRIES_EXHAUSTED, nowMillis);
        else {
            long delay = delayMillis();
            if (nowMillis > Long.MAX_VALUE - delay) finishWait(End.CLOCK_EXHAUSTED, nowMillis);
            else { state = State.BACKOFF; retry = new Retry(generation, nowMillis + delay); }
        }
        return true;
    }

    /**
     * Acknowledge the exact retired lease only AFTER release or confirmed create cancellation.
     * Generation may be stale after Stop/Pause, but the physical ownership identity must match.
     */
    public synchronized boolean released(Lease candidate) {
        if (candidate == null || candidate != lease || !releaseRequired) return false;
        lease = null; releaseRequired = false; return true;
    }

    /**
     * Admit one retry after its delay, physical release and a fresh OS/FGS authority check.
     * A duplicate, early or stale timer cannot consume a retry or start a second recorder.
     */
    public synchronized Lease takeRetry(Retry candidate, boolean authorizedSurvivingFgs, long nowMillis) {
        if (candidate == null || candidate != retry || candidate.generation != generation
                || state != State.BACKOFF) return null;
        checkTime(nowMillis);
        if (!authorizedSurvivingFgs) { authorityLost(nowMillis); return null; }
        if (lease != null || nowMillis < candidate.dueMillis) return null;
        lastMillis = nowMillis; retriesUsed++; retry = null;
        state = State.STARTING; lease = new Lease(generation, retriesUsed); return lease;
    }

    public synchronized void pause(long nowMillis) { cancel(State.PAUSED, End.USER_PAUSED, nowMillis); }
    public synchronized void stop(long nowMillis) { cancel(State.STOPPED, End.USER_STOPPED, nowMillis); }
    public synchronized void authorityLost(long nowMillis) {
        checkTime(nowMillis);
        if (gapOpen || state == State.STARTING || state == State.RUNNING)
            openOrUpdateGap(Reason.SERVICE_AUTHORITY_LOST, nowMillis);
        cancel(State.PAUSED, End.AUTHORITY_LOST, nowMillis);
    }

    /** Disabling recovery never stops a healthy recorder or the initial explicit attempt. */
    public synchronized void disableRecovery(long nowMillis) {
        checkTime(nowMillis);
        if (state == State.BACKOFF || (state == State.STARTING && retriesUsed > 0))
            cancel(State.PAUSED, End.OPTED_OUT, nowMillis);
        else { recoveryEnabled = false; lastMillis = nowMillis; }
    }

    public synchronized Snapshot snapshot() { return new Snapshot(this); }
    /** Non-destructive read; failed persistence must leave this event unacknowledged. */
    public synchronized GapEvent peekGap() { return events.peekFirst(); }
    /** Exact FIFO object identity prevents duplicate/stale acknowledgements from dropping another event. */
    public synchronized boolean acknowledgeGap(GapEvent event) {
        if (event == null || event != events.peekFirst()) return false;
        events.removeFirst(); return true;
    }

    private boolean current(Lease candidate) {
        return candidate != null && candidate == lease && candidate.generation == generation;
    }
    private void checkTime(long nowMillis) {
        if (nowMillis < 0 || nowMillis < lastMillis)
            throw new IllegalArgumentException("Nondecreasing elapsed-realtime milliseconds required");
    }
    private static boolean recoverable(Reason reason) {
        return reason == Reason.MICROPHONE_CONTENTION || reason == Reason.ROUTE_UNAVAILABLE
            || reason == Reason.DEAD_RECORDER;
    }
    private long delayMillis() {
        long delay = initialDelayMillis;
        for (int n = 0; n < retriesUsed; n++) delay = Math.min(maximumDelayMillis, delay * 2);
        return delay;
    }
    private void cancel(State target, End end, long nowMillis) {
        checkTime(nowMillis); lastMillis = nowMillis;
        if (generation == Long.MAX_VALUE) generationExhausted = true; else generation++;
        recoveryEnabled = false; retry = null; releaseRequired = lease != null;
        closeGap(end, nowMillis); lastEnd = end; state = target;
    }
    private void finishWait(End end, long nowMillis) {
        retry = null; recoveryEnabled = false; closeGap(end, nowMillis);
        lastEnd = end; state = State.PAUSED;
    }
    private void openOrUpdateGap(Reason reason, long nowMillis) {
        if (!gapOpen) {
            gapOpen = true; gapId = nextGap++; gapStart = nowMillis;
            firstReason = reason; lastReason = reason; reasonMask = 1 << reason.ordinal();
            addEvent(new GapEvent(EventKind.OPENED, sessionGeneration, gapId, gapStart, nowMillis, -1,
                firstReason, lastReason, reasonMask, retriesUsed, null));
        } else { lastReason = reason; reasonMask |= 1 << reason.ordinal(); }
    }
    private void closeGap(End end, long nowMillis) {
        if (!gapOpen) return;
        addEvent(new GapEvent(end == End.RESUMED ? EventKind.RECOVERED : EventKind.TERMINATED,
            sessionGeneration, gapId, gapStart, nowMillis, end == End.RESUMED ? nowMillis : -1,
            firstReason, lastReason, reasonMask, retriesUsed, end));
        gapOpen = false; lastEnd = end;
    }
    private void addEvent(GapEvent event) {
        // Cannot be reached with valid transitions; no silent eviction if the invariant is broken.
        if (events.size() >= eventCapacity) throw new IllegalStateException("Gap evidence capacity exhausted");
        events.addLast(event);
    }
}
