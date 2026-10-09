package io.github.headmaster218.recorder.core;

/** Evidence-driven transition contract. Adapters must obtain evidence and durably persist each step. */
public final class DeletionGate {
    public enum State { READY, UPLOADING, REMOTE_VERIFIED, REMOTE_COMMITTED,
        LOCAL_DELETE_ELIGIBLE, LOCAL_DELETED }
    public enum Verification { TRUSTED_SERVER_SHA256, AUTHENTICATED_FULL_READBACK }
    public static final class Receipt {
        public final String fragmentId, destination, payloadPath, metadataPath, commitPath;
        public final long payloadBytes;
        public final String payloadSha256, metadataSha256, commitSha256;
        public Receipt(String fragmentId, String destination, String payloadPath, String metadataPath,
                       String commitPath, long payloadBytes, String payloadSha256,
                       String metadataSha256, String commitSha256) {
            this.fragmentId = Checks.text(fragmentId, "fragmentId");
            this.destination = Checks.text(destination, "destination");
            this.payloadPath = Checks.text(payloadPath, "payloadPath");
            this.metadataPath = Checks.text(metadataPath, "metadataPath");
            this.commitPath = Checks.text(commitPath, "commitPath");
            if (payloadBytes <= 0 || payloadPath.equals(metadataPath) || payloadPath.equals(commitPath)
                || metadataPath.equals(commitPath)) throw new IllegalArgumentException();
            this.payloadBytes = payloadBytes;
            this.payloadSha256 = Checks.hash(payloadSha256);
            this.metadataSha256 = Checks.hash(metadataSha256);
            this.commitSha256 = Checks.hash(commitSha256);
        }
        private boolean matches(Receipt r) {
            return r != null && fragmentId.equals(r.fragmentId) && destination.equals(r.destination)
                && payloadPath.equals(r.payloadPath) && metadataPath.equals(r.metadataPath)
                && commitPath.equals(r.commitPath) && payloadBytes == r.payloadBytes
                && payloadSha256.equals(r.payloadSha256) && metadataSha256.equals(r.metadataSha256)
                && commitSha256.equals(r.commitSha256);
        }
    }
    private final Receipt expected;
    private final boolean deletionEnabled, strictDurability;
    private State state = State.READY;
    private boolean receiptPersisted, durabilityAcknowledged;
    public DeletionGate(Receipt expected, boolean deletionEnabled, boolean strictDurability) {
        if (expected == null) throw new IllegalArgumentException();
        this.expected = expected; this.deletionEnabled = deletionEnabled; this.strictDurability = strictDurability;
    }
    public State state() { return state; }
    public void beginUpload() { require(State.READY); state = State.UPLOADING; }
    public void uploadFailed() { require(State.UPLOADING); state = State.READY; }
    public void payloadVerified(long bytes, String sha256, Verification method) {
        require(State.UPLOADING);
        if (method == null || bytes != expected.payloadBytes || !expected.payloadSha256.equals(sha256))
            throw new IllegalArgumentException("remote payload does not match");
        state = State.REMOTE_VERIFIED;
    }
    /** Called only after conflict-safe publication, metadata verification and commit marker published last. */
    public void committed(Receipt observed, Verification metadataAndMarkerMethod,
                          boolean durabilityAcknowledged) {
        require(State.REMOTE_VERIFIED);
        if (!expected.matches(observed) || metadataAndMarkerMethod == null) throw new IllegalArgumentException();
        this.durabilityAcknowledged = durabilityAcknowledged; state = State.REMOTE_COMMITTED;
    }
    public void receiptPersisted(Receipt persisted) {
        require(State.REMOTE_COMMITTED);
        if (!expected.matches(persisted)) throw new IllegalArgumentException("wrong persisted receipt");
        receiptPersisted = true;
    }
    /** Recheck final payload, metadata and marker after receipt persistence. A failed recheck revokes eligibility. */
    public boolean finalObjectsRechecked(Receipt observed, Verification method) {
        if (state != State.REMOTE_COMMITTED && state != State.LOCAL_DELETE_ELIGIBLE)
            throw new IllegalStateException();
        state = State.REMOTE_COMMITTED;
        if (!expected.matches(observed) || method == null || !receiptPersisted
            || !deletionEnabled || (strictDurability && !durabilityAcknowledged)) return false;
        state = State.LOCAL_DELETE_ELIGIBLE; return true;
    }
    public void localDeletionAcknowledged() {
        require(State.LOCAL_DELETE_ELIGIBLE); state = State.LOCAL_DELETED;
    }
    private void require(State expectedState) {
        if (state != expectedState) throw new IllegalStateException("invalid transition from " + state);
    }
}
