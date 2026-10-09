package io.github.headmaster218.recorder.core;

import java.util.HashSet;
import java.util.Set;

/** Single-writer app budget, not carrier accounting. Persist reservations before any network I/O. */
public final class QuotaLedger {
    public static final class Reservation {
        private long remaining;
        private Reservation(long bytes) { remaining = bytes; }
        public long remainingBytes() { return remaining; }
    }
    public static final class Snapshot {
        public final long day, month, dailyCharged, monthlyCharged;
        private Snapshot(long day, long month, long dailyCharged, long monthlyCharged) {
            this.day = day; this.month = month;
            this.dailyCharged = dailyCharged; this.monthlyCharged = monthlyCharged;
        }
    }
    private final long dailyLimit, monthlyLimit;
    private long day, month, dailySpent, monthlySpent, reserved;
    private final Set<Reservation> active = new HashSet<Reservation>();
    public QuotaLedger(long dailyLimit, long monthlyLimit, long day, long month,
                       long dailyCharged, long monthlyCharged) {
        if (dailyLimit < 0 || monthlyLimit < 0 || day < 0 || month < 0
            || dailyCharged < 0 || monthlyCharged < 0) throw new IllegalArgumentException();
        this.dailyLimit = dailyLimit; this.monthlyLimit = monthlyLimit;
        this.day = day; this.month = month;
        this.dailySpent = dailyCharged; this.monthlySpent = monthlyCharged;
    }
    public long availableBytes() {
        long daily = dailySpent >= dailyLimit ? 0 : dailyLimit - dailySpent;
        long monthly = monthlySpent >= monthlyLimit ? 0 : monthlyLimit - monthlySpent;
        long remaining = Math.min(daily, monthly);
        return remaining <= reserved ? 0 : remaining - reserved;
    }
    public Reservation reserve(long upperBoundBytes) {
        if (upperBoundBytes <= 0) throw new IllegalArgumentException();
        if (upperBoundBytes > availableBytes()) return null;
        Reservation r = new Reservation(upperBoundBytes);
        reserved = Checks.add(reserved, upperBoundBytes); active.add(r); return r;
    }
    /** Count every attempt, retry, metadata byte and verification read in both directions. */
    public boolean recordBytes(Reservation r, long actualBytes) {
        requireActive(r);
        if (actualBytes < 0) throw new IllegalArgumentException();
        long nextDaily = saturatingAdd(dailySpent, actualBytes);
        long nextMonthly = saturatingAdd(monthlySpent, actualBytes);
        boolean withinReservation = actualBytes <= r.remaining;
        long consumed = Math.min(actualBytes, r.remaining);
        r.remaining -= consumed; reserved -= consumed;
        dailySpent = nextDaily; monthlySpent = nextMonthly;
        return withinReservation && dailySpent <= dailyLimit && monthlySpent <= monthlyLimit;
    }
    /** Failed transfers still keep actual bytes charged; only unused reserved bytes are released. */
    public void finish(Reservation r) {
        requireActive(r); reserved -= r.remaining; r.remaining = 0; active.remove(r);
    }
    // Observed traffic cannot be rejected without accounting for it. Saturation is persistably exhausted.
    private static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
    private void requireActive(Reservation r) {
        if (r == null || !active.contains(r)) throw new IllegalStateException("foreign or finished reservation");
    }
    /** Crash recovery charges all outstanding reservations conservatively; never refunds unknown traffic. */
    public Snapshot snapshot() {
        return new Snapshot(day, month, saturatingAdd(dailySpent, reserved), saturatingAdd(monthlySpent, reserved));
    }
    /** Caller resolves configured timezone/calendar. Reject backward clocks and in-flight resets. */
    public void advanceWindow(long nextDay, long nextMonth) {
        if (nextDay < day || nextMonth < month || (nextMonth > month && nextDay == day))
            throw new IllegalArgumentException("backward or inconsistent window");
        if (nextDay == day && nextMonth == month) return;
        if (!active.isEmpty()) throw new IllegalStateException("reconcile transfers before reset");
        if (nextDay > day) dailySpent = 0;
        if (nextMonth > month) monthlySpent = 0;
        day = nextDay; month = nextMonth;
    }
}
