package io.github.headmaster218.recorder.core;

import java.util.UUID;

/** Process-local single-flight state; Activity recreation observes it but never restarts work. */
public final class ExportSession {
    public enum Phase { IDLE, LISTING, CHOOSING, COPYING, VERIFIED, UNVERIFIED, FAILED, CANCELLED }
    public static final class Snapshot {
        public final Phase phase;
        public final String token, detail;
        public final CommittedSegments.Page page;
        public final CommittedSegments.Entry entry;
        public final VerifiedExport.Result result;
        private Snapshot(Phase phase, String token, String detail, CommittedSegments.Page page,
                CommittedSegments.Entry entry, VerifiedExport.Result result) {
            this.phase = phase; this.token = token; this.detail = detail; this.page = page; this.entry = entry; this.result = result;
        }
        public boolean busy() { return phase == Phase.LISTING || phase == Phase.CHOOSING || phase == Phase.COPYING; }
    }
    private Snapshot state = new Snapshot(Phase.IDLE, "", "Choose a completed segment. All originals are kept.", null, null, null);
    private boolean cancelled;
    public synchronized Snapshot snapshot() { return state; }
    public synchronized String list() {
        if (state.busy()) return null;
        String token = UUID.randomUUID().toString();
        state = new Snapshot(Phase.LISTING, token, "Reading completed-segment metadata…", state.page, null, null); return token;
    }
    public synchronized void listed(String token, CommittedSegments.Page page, String error) {
        if (!matches(token, Phase.LISTING)) return;
        state = new Snapshot(error == null ? Phase.IDLE : Phase.FAILED, token,
            error == null ? "Choose a completed segment. Audio is validated before export." : error, page, null, null);
    }
    public synchronized String choose(int index) {
        if (state.busy() || state.page == null || index < 0 || index >= state.page.entries.size()) return null;
        String token = UUID.randomUUID().toString(); cancelled = false;
        state = new Snapshot(Phase.CHOOSING, token, "Choose where to save in Android’s file picker.", state.page, state.page.entries.get(index), null); return token;
    }
    public synchronized CommittedSegments.Entry destination(String token) {
        if (!matches(token, Phase.CHOOSING)) return null;
        state = new Snapshot(Phase.COPYING, token, "Validating source, copying, then reading target back… Keep the app open.", state.page, state.entry, null); return state.entry;
    }
    public synchronized void pickerCancelled(String token, String detail) {
        if (matches(token, Phase.CHOOSING)) state = new Snapshot(Phase.CANCELLED, token, detail, state.page, state.entry, null);
    }
    public synchronized void cancelCopy() { if (state.phase == Phase.COPYING) cancelled = true; }
    public synchronized boolean cancellationRequested() { return cancelled; }
    public synchronized boolean cancelled(String token) { return !matches(token, Phase.COPYING) || cancelled; }
    public synchronized void finished(String token, VerifiedExport.Result result) {
        if (!matches(token, Phase.COPYING)) return;
        state = new Snapshot(Phase.valueOf(result.status.name()), token, result.detail, state.page, state.entry, result);
    }
    private boolean matches(String token, Phase phase) { return token != null && token.equals(state.token) && state.phase == phase; }
}
