package io.github.headmaster218.recorder.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic host-port tests. Does not prove Android/hardware routing. */
public final class InputRoutePolicyTest {
    private static final InputRoutePolicy.Device PHONE=new InputRoutePolicy.Device(1,InputRoutePolicy.PHONE);
    private static final InputRoutePolicy.Device WIRED=new InputRoutePolicy.Device(2,InputRoutePolicy.WIRED);
    private static final InputRoutePolicy.Device USB=new InputRoutePolicy.Device(3,InputRoutePolicy.USB);
    private static final class Fake implements InputRoutePolicy.Port {
        List<InputRoutePolicy.Device> devices=new ArrayList<InputRoutePolicy.Device>();
        InputRoutePolicy.Device actual, preferred;
        boolean accepted=true;
        int preferences;
        @Override public List<InputRoutePolicy.Device> inputs() { return devices; }
        @Override public boolean prefer(InputRoutePolicy.Device target) { preferences++; preferred=target; return accepted; }
        @Override public InputRoutePolicy.Device actual() { return actual; }
        void devices(InputRoutePolicy.Device... values) { devices=Arrays.asList(values); }
    }
    private interface Operation { void run() throws InputRoutePolicy.Denied; }
    private static void denied(Operation action) throws Exception {
        try { action.run(); throw new AssertionError("Expected denied input"); }
        catch (InputRoutePolicy.Denied expected) { if (expected.getMessage().isEmpty()) throw new AssertionError(); }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Fake port=new Fake(); port.devices(PHONE,WIRED,USB);
        InputRoutePolicy phone=new InputRoutePolicy(InputRoutePolicy.Request.phone());
        phone.prepare(port);
        check(port.preferred == PHONE && port.preferences == 1);
        denied(() -> phone.verify(port)); // Accepted preference is not actual route evidence.
        port.actual=USB; denied(() -> phone.verify(port));
        port.actual=PHONE; check(phone.verify(port) == PHONE);
        phone.prepare(port); check(port.preferences == 1 && phone.revision() == 1);

        InputRoutePolicy selected=new InputRoutePolicy(new InputRoutePolicy.Request(InputRoutePolicy.WIRED,2,false));
        selected.prepare(port); port.actual=WIRED; selected.verify(port);
        port.devices(PHONE,USB); port.actual=PHONE;
        denied(() -> selected.prepare(port)); denied(() -> selected.verify(port));
        check(!selected.usingPhoneFallback());
        // A different headset or an ID reused for another kind is never the original selection.
        port.devices(PHONE,new InputRoutePolicy.Device(22,InputRoutePolicy.WIRED));
        denied(() -> selected.prepare(port));
        port.devices(PHONE,new InputRoutePolicy.Device(2,InputRoutePolicy.USB));
        denied(() -> selected.prepare(port));

        InputRoutePolicy fallback=new InputRoutePolicy(new InputRoutePolicy.Request(InputRoutePolicy.USB,3,true));
        port.devices(PHONE,USB); port.actual=USB; fallback.prepare(port); fallback.verify(port);
        port.devices(PHONE); port.actual=PHONE; long before=fallback.revision();
        fallback.prepare(port); fallback.verify(port);
        check(fallback.usingPhoneFallback() && fallback.revision() > before && port.preferred == PHONE);
        port.devices(PHONE,USB); fallback.prepare(port); check(port.preferred == PHONE);
        port.actual=USB; denied(() -> fallback.verify(port)); // No automatic return to headset.
        port.devices(USB); denied(() -> fallback.prepare(port));

        InputRoutePolicy absent=new InputRoutePolicy(new InputRoutePolicy.Request(InputRoutePolicy.USB,3,true));
        port.devices(PHONE); port.actual=PHONE; absent.prepare(port); absent.verify(port);
        check(absent.usingPhoneFallback());
        InputRoutePolicy rejected=new InputRoutePolicy(InputRoutePolicy.Request.phone());
        port.accepted=false; denied(() -> rejected.prepare(port));
        check(rejected.revision() == 0); denied(() -> rejected.verify(port));
        port.accepted=true; port.devices(new InputRoutePolicy.Device(4,InputRoutePolicy.UNSUPPORTED));
        denied(() -> rejected.prepare(port));
        port.devices=null; denied(() -> rejected.prepare(port));
        try { new InputRoutePolicy.Request(InputRoutePolicy.UNSUPPORTED,4,true); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        try { new InputRoutePolicy.Request(InputRoutePolicy.USB,0,true); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        check(!new InputRoutePolicy.Request(InputRoutePolicy.PHONE,9,true).phoneFallback);
        check(new InputRoutePolicy.Request(InputRoutePolicy.USB,3,true).description().contains("explicit-phone-fallback"));
        check(!InputRoutePolicy.uncertainBoundary(1,1,0,0,0)); // Default phone positive control.
        check(InputRoutePolicy.uncertainBoundary(1,2,0,0,0)); // Pre-first-read fallback, even without callback.
        check(InputRoutePolicy.uncertainBoundary(1,1,0,0,1)); // Callback during first read.
        check(InputRoutePolicy.uncertainBoundary(1,1,0,1,1)); // Callback already delivered before read.
        check(InputRoutePolicy.uncertainBoundary(1,2,0,2,2)); // Zero reads must not consume baseline.
        check(InputRoutePolicy.uncertainBoundary(2,2,2,2,3)); // Stale callback is conservative uncertainty.
        InputRoutePolicy.Attribution attribution=new InputRoutePolicy.Attribution();
        check(!attribution.observe(false));
        check(attribution.observe(true));
        for (int buffered=0; buffered<16; buffered++) check(attribution.observe(false));
        check(!new InputRoutePolicy.Attribution().observe(false)); // New run, not an in-run reset.
        // Keep the new failure ordinal appended: historic masks retain their meaning.
        check(CaptureEvidence.Failure.UNKNOWN.ordinal() == 17);
        check(CaptureEvidence.Failure.INPUT_POLICY_DENIED.ordinal() == 18);
        for (boolean read : new boolean[]{false,true}) {
            CaptureEvidence evidence=new CaptureEvidence("capture","run");
            evidence.handleObtained(); evidence.releaseReturned();
            evidence.failed(CaptureEvidence.Failure.INPUT_POLICY_DENIED);
            evidence.failed(CaptureEvidence.Failure.ROUTE_UNAVAILABLE);
            evidence.failed(CaptureEvidence.Failure.POLICY_SILENCED);
            PcmReadAccounting accounting=new PcmReadAccounting();
            if (read) accounting.readAccepted(8); // Rejected route buffer is not credited as saved.
            CaptureEvidence.Result result=evidence.finish(accounting.snapshot(),true);
            check(RecoveryGate.retryReason(result) == null);
            check(!RecoveryGate.cleanPauseEligible(result,true));
            check(result.allReadSamplesConfirmed() != read);
        }
        System.out.println("InputRoutePolicyTest passed (host policy only; no hardware proof)");
    }
}
