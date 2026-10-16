package io.github.headmaster218.recorder.android;
/** Executes the actual compiled nested production gate; no Android objects are constructed. */
public final class EnrollmentConsentTest {
    static int checks;
    static void yes(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[]args)throws Exception{
        FtpsCoordinator.EnrollmentConsent gate=new FtpsCoordinator.EnrollmentConsent();
        yes(gate.ticket("source","A")==null);
        yes(gate.enable("A",gate.epoch()));
        FtpsCoordinator.EnrollmentConsent.Ticket beforeHash=gate.ticket("source","A");
        yes(!gate.cancelled(beforeHash));gate.disable();yes(gate.cancelled(beforeHash));yes(!gate.admit(beforeHash));
        yes(gate.enable("A",gate.epoch()));
        FtpsCoordinator.EnrollmentConsent.Ticket beforeAdmission=gate.ticket("source","A");
        gate.disable();yes(!gate.admit(beforeAdmission));
        yes(gate.enable("A",gate.epoch()));
        FtpsCoordinator.EnrollmentConsent.Ticket admitted=gate.ticket("source","A");
        yes(gate.admit(admitted));gate.disable();
        // The one already-admitted operation may finish; the ticket can never admit a second row.
        yes(admitted.source.equals("source")&&admitted.revision.equals("A"));yes(!gate.admit(admitted));
        long superseded=gate.epoch();gate.disable();yes(!gate.enable("A",superseded));
        yes(gate.enable("B",gate.epoch()));yes(gate.ticket("source","A")==null);
        yes(!gate.admit(beforeAdmission));yes(!gate.admit(admitted));
        FtpsCoordinator.EnrollmentConsent.Ticket b=gate.ticket("source","B");yes(gate.admit(b));yes(!gate.admit(b));
        FtpsCoordinator.EnrollmentConsent other=new FtpsCoordinator.EnrollmentConsent();yes(other.enable("B",other.epoch()));yes(!other.admit(b));
        // Re-enabling the same revision is not an ABA authorization for stale work.
        FtpsCoordinator.EnrollmentConsent.Ticket old=gate.ticket("source2","B");
        gate.disable();yes(gate.enable("B",gate.epoch()));yes(gate.cancelled(old));yes(!gate.admit(old));
        // Checked exhaustion retains disabled state rather than wrapping the revocation epoch.
        java.lang.reflect.Field epoch=gate.getClass().getDeclaredField("epoch");epoch.setAccessible(true);epoch.setLong(gate,Long.MAX_VALUE);
        gate.disable();yes(gate.epoch()==Long.MAX_VALUE);yes(!gate.enable("B",gate.epoch()));
        yes(gate.ticket("source","B")==null);
        System.out.println("PASS: "+checks+" production enrollment-consent assertions; no Android runtime");
    }
}
