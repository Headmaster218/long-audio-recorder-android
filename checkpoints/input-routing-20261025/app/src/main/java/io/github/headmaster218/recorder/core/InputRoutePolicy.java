package io.github.headmaster218.recorder.core;

import java.util.List;

/** Exact observed-input admission. Preferences are requests, never evidence of routing success. */
public final class InputRoutePolicy {
    public static final int PHONE = 1, WIRED = 2, USB = 3, UNSUPPORTED = 0;
    public static final class Device {
        public final int id, kind;
        public Device(int id,int kind) { this.id=id; this.kind=kind; }
    }
    public static final class Request {
        public final int kind, id;
        public final boolean phoneFallback;
        public Request(int kind,int id,boolean phoneFallback) {
            if ((kind != PHONE && kind != WIRED && kind != USB) || (kind != PHONE && id <= 0))
                throw new IllegalArgumentException("Select a supported phone, wired or USB input.");
            this.kind=kind; this.id=kind == PHONE ? 0 : id;
            this.phoneFallback=kind != PHONE && phoneFallback;
        }
        public static Request phone() { return new Request(PHONE,0,false); }
        public String description() {
            return "input-kind=" + kind + ";selected-device-id=" + id + ";policy="
                + (phoneFallback ? "explicit-phone-fallback" : "selected-only") + ";device-id-scope=current-connection";
        }
    }
    public interface Port {
        List<Device> inputs();
        boolean prefer(Device device);
        Device actual();
    }
    public static final class Denied extends Exception {
        private static final long serialVersionUID = 1L;
        public Denied(String message) { super(message); }
    }
    private final Request request;
    private Device preferred;
    private boolean fallback;
    private long revision;
    public InputRoutePolicy(Request request) {
        if (request == null) throw new IllegalArgumentException("Input request required");
        this.request=request;
    }
    /** Resolve only enumerated input devices. Fallback is sticky for this AudioRecord run. */
    public void prepare(Port port) throws Denied {
        List<Device> devices=port.inputs();
        Device selected=null, phone=null;
        if (devices == null) throw new Denied("Input enumeration unavailable; capture stopped.");
        for (Device device : devices) {
            if (device == null) continue;
            if (device.kind == PHONE && phone == null) phone=device;
            if (device.kind == request.kind && (request.kind == PHONE || device.id == request.id)) selected=device;
        }
        Device target=fallback ? phone : selected;
        if (target == null && request.phoneFallback) { fallback=true; target=phone; }
        if (target == null) throw new Denied("Selected input unavailable; capture stopped. Explicit Start required.");
        if (!same(preferred,target)) {
            if (!port.prefer(target)) throw new Denied("Android rejected input preference; actual route is not assumed.");
            preferred=target; revision++;
        }
    }
    public Device verify(Port port) throws Denied {
        Device actual=port.actual();
        if (preferred == null || actual == null || !same(preferred,actual))
            throw new Denied("Actual input is unknown or differs from the selected input; capture stopped.");
        return actual;
    }
    public long revision() { return revision; }
    public boolean usingPhoneFallback() { return fallback; }
    /** Previous values belong to the last admitted positive read, or pre-start for the first read. */
    public static boolean uncertainBoundary(long previousRevision,long revision,long previousEvents,long before,long after) {
        return previousRevision != revision || before != after || previousEvents != after;
    }
    /** Native buffered PCM cannot be re-attributed cleanly just because a later route query is stable. */
    public static final class Attribution {
        private boolean uncertain;
        public boolean observe(boolean boundaryUncertain) { uncertain |= boundaryUncertain; return uncertain; }
    }
    public static boolean same(Device a,Device b) { return a != null && b != null && a.id == b.id && a.kind == b.kind; }
}
