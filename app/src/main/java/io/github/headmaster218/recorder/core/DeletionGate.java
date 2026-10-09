package io.github.headmaster218.recorder.core;

/** Evidence-driven transition contract. Adapters must obtain evidence and durably persist each step. */
public final class DeletionGate {
    public enum State { READY, UPLOADING, REMOTE_VERIFIED, REMOTE_COMMITTED,
        LOCAL_DELETE_ELIGIBLE, LOCAL_DELETED }
    public enum Verification { TRUSTED_SERVER_SHA256, AUTHENTICATED_FULL_READBACK }
    /** Stable IDs/versions established by adapters, never a pathname or timestamp alone. */
    public static final class ObjectBinding {
        public final String localObjectId, localVersion, payloadVersion, metadataVersion, commitVersion;
        public ObjectBinding(String localObjectId, String localVersion, String payloadVersion,
                             String metadataVersion, String commitVersion) {
            this.localObjectId = Checks.text(localObjectId, "localObjectId");
            this.localVersion = Checks.text(localVersion, "localVersion");
            this.payloadVersion = Checks.text(payloadVersion, "payloadVersion");
            this.metadataVersion = Checks.text(metadataVersion, "metadataVersion");
            this.commitVersion = Checks.text(commitVersion, "commitVersion");
        }
        private boolean matches(ObjectBinding b) {
            return b != null && localObjectId.equals(b.localObjectId) && localVersion.equals(b.localVersion)
                && payloadVersion.equals(b.payloadVersion) && metadataVersion.equals(b.metadataVersion)
                && commitVersion.equals(b.commitVersion);
        }
    }
    /** Evidence that the adapter holds one fenced check-to-delete operation; not a lock implementation. */
    public static final class Guard {
        public final Receipt receipt;
        public final String operationId;
        private boolean active = true;
        public Guard(Receipt receipt, String operationId) {
            if (receipt == null) throw new IllegalArgumentException();
            this.receipt = receipt; this.operationId = Checks.text(operationId, "operationId");
        }
        /** Call on lock/lease/version loss, cancellation, completion or any uncertain protection. */
        public void invalidate() { active = false; }
        public boolean isActive() { return active; }
    }
    public static final class Receipt {
        public final String fragmentId, destination, payloadPath, metadataPath, commitPath;
        public final long payloadBytes;
        public final ObjectBinding binding;
        public final String payloadSha256, metadataSha256, commitSha256;
        public Receipt(String fragmentId, String destination, String payloadPath, String metadataPath,
                       String commitPath, long payloadBytes, String payloadSha256,
                       String metadataSha256, String commitSha256, ObjectBinding binding) {
            this.fragmentId = Checks.text(fragmentId, "fragmentId");
            this.destination = Checks.text(destination, "destination");
            this.payloadPath = Checks.text(payloadPath, "payloadPath");
            this.metadataPath = Checks.text(metadataPath, "metadataPath");
            this.commitPath = Checks.text(commitPath, "commitPath");
            if (payloadBytes <= 0 || payloadPath.equals(metadataPath) || payloadPath.equals(commitPath)
                || metadataPath.equals(commitPath)) throw new IllegalArgumentException();
            if (binding == null) throw new IllegalArgumentException("object versions required");
            this.binding = binding;
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
                && commitSha256.equals(r.commitSha256) && binding.matches(r.binding);
        }
    }
    private final Receipt expected;
    private final boolean deletionEnabled, strictDurability;
    private State state = State.READY;
    private boolean receiptPersisted, durabilityAcknowledged;
    private Guard activeGuard;
    public DeletionGate(Receipt expected, boolean deletionEnabled, boolean strictDurability) {
        if (expected == null) throw new IllegalArgumentException();
        this.expected = expected; this.deletionEnabled = deletionEnabled; this.strictDurability = strictDurability;
    }
    public State state() {
        if (state == State.LOCAL_DELETE_ELIGIBLE && (activeGuard == null || !activeGuard.isActive()))
            state = State.REMOTE_COMMITTED;
        return state;
    }
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
    public boolean finalObjectsRechecked(Receipt observed, Verification method, Guard guard) {
        if (state != State.REMOTE_COMMITTED && state != State.LOCAL_DELETE_ELIGIBLE)
            throw new IllegalStateException();
        state = State.REMOTE_COMMITTED; activeGuard = null;
        if (guard == null || !guard.isActive() || !expected.matches(guard.receipt)
            || !expected.matches(observed) || method == null || !receiptPersisted
            || !deletionEnabled || (strictDurability && !durabilityAcknowledged)) return false;
        activeGuard = guard; state = State.LOCAL_DELETE_ELIGIBLE; return true;
    }
    public void localDeletionAcknowledged(Guard guard) {
        require(State.LOCAL_DELETE_ELIGIBLE);
        if (guard == null || guard != activeGuard || !guard.isActive())
            throw new IllegalStateException("same live guarded operation required");
        state = State.LOCAL_DELETED; guard.invalidate();
    }
    private void require(State expectedState) {
        if (state() != expectedState) throw new IllegalStateException("invalid transition from " + state);
    }
}
