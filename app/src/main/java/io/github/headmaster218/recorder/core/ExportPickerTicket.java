package io.github.headmaster218.recorder.core;

/** Per-Activity monotonically increasing request code, saved across recreation alongside its token. */
public final class ExportPickerTicket {
    private static final int FIRST = 10000, LAST = 65535;
    private int code;
    private String token;
    public ExportPickerTicket(int savedCode, String savedToken) {
        if (savedCode < FIRST || savedCode > LAST) throw new IllegalArgumentException("invalid saved picker code");
        code = savedCode; token = savedToken;
    }
    public ExportPickerTicket() { code = FIRST; }
    public int issue(String nextToken) {
        if (nextToken == null || nextToken.isEmpty() || code == LAST) return -1;
        code++; token = nextToken; return code;
    }
    public String consume(int resultCode) {
        if (code != resultCode) return null;
        String result = token; token = null; return result;
    }
    public void forgetToken() { token = null; }
    public int code() { return code; }
    public String token() { return token; }
}
