package io.github.headmaster218.recorder.core;

/** Byte trigger only. Does not grant enrollment, select destinations, or relax any hard gate. */
public final class UploadThreshold {
    public static final long DEFAULT_BYTES = 9600000L;
    private UploadThreshold() { }

    public static long validate(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("Upload threshold must be 1 through 9223372036854775807 bytes.");
        return bytes;
    }

    /** Decimal bytes, not rounded MB/MiB. Reject signs, fractions, exponent notation and overflow. */
    public static long parse(String text) {
        if (text == null || !text.matches("[0-9]{1,19}"))
            throw new IllegalArgumentException("Enter 1–19 decimal digits for the upload threshold in bytes.");
        try { return validate(Long.parseLong(text)); }
        catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("Upload threshold exceeds 9223372036854775807 bytes.",overflow);
        }
    }

    public static TransferPolicy policy(long bytes) {
        return new TransferPolicy(true,true,false,false,false,validate(bytes),-1);
    }

    /** Same queue-wide accounting as before: runnable-state WAV rows, even if deferred. */
    public static long addPending(long total, String state, long wavBytes) {
        if (total < 0 || wavBytes < 0 || state == null) throw new IllegalArgumentException("Invalid pending-byte record");
        if (!state.equals("QUEUED") && !state.equals("RECONCILE")) return total;
        return wavBytes > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + wavBytes;
    }
}
