package io.github.headmaster218.recorder.core;

final class Checks {
    private Checks() { }
    static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name);
        return value;
    }
    static String hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("SHA-256 must be 64 lowercase hex digits");
        return value;
    }
    static long add(long a, long b) {
        if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) throw new IllegalArgumentException("overflow");
        return a + b;
    }
    static long multiply(long a, long b) {
        if (a < 0 || b < 0 || (b != 0 && a > Long.MAX_VALUE / b))
            throw new IllegalArgumentException("overflow");
        return a * b;
    }
}
