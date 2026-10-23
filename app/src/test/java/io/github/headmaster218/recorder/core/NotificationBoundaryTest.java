package io.github.headmaster218.recorder.core;

/** Finite production-port tests. Does not run Android NotificationManager or Service. */
public final class NotificationBoundaryTest {
    private static int assertions;
    private static void check(boolean ok) { assertions++;if(!ok)throw new AssertionError("assertion "+assertions); }
    private static final class Publication implements NotificationBoundary.Publication {
        boolean visible=true,queryThrows,publishThrows;
        int queries,publications;
        @Override public boolean visible() { queries++;if(queryThrows)throw new SecurityException("notification query");return visible; }
        @Override public void publish() { publications++;if(publishThrows)throw new SecurityException("notification publish"); }
    }
    private static void cases() {
        Publication p=new Publication();check(NotificationBoundary.publish(p));check(p.queries==1 && p.publications==1);
        p=new Publication();p.visible=false;check(!NotificationBoundary.publish(p));check(p.publications==0);
        p=new Publication();p.queryThrows=true;
        for(int i=0;i<3;i++)check(!NotificationBoundary.publish(p));
        check(p.queries==3 && p.publications==0);
        p=new Publication();p.publishThrows=true;check(!NotificationBoundary.publish(p));check(p.publications==1);
        for(int failure=0;failure<3;failure++) {
            final StringBuilder order=new StringBuilder();Publication result=new Publication();
            result.queryThrows=failure==0;result.publishThrows=failure==1;result.visible=failure!=2;
            NotificationBoundary.finish(new Runnable(){@Override public void run(){order.append("cleanup;");}},result,
                new Runnable(){@Override public void run(){order.append("stop;");}});
            check(order.toString().equals("cleanup;stop;"));
        }
        final StringBuilder order=new StringBuilder();Publication result=new Publication();
        try {
            NotificationBoundary.finish(new Runnable(){@Override public void run(){order.append("cleanup;");throw new IllegalStateException("cleanup unresolved");}},result,
                new Runnable(){@Override public void run(){order.append("stop;");}});
            check(false);
        } catch(IllegalStateException expected) { check(expected.getMessage().equals("cleanup unresolved")); }
        check(order.toString().equals("cleanup;stop;") && result.queries==0 && result.publications==0);
    }
    public static void main(String[] args) {
        cases();System.out.println("PASS "+assertions+" finite notification-boundary assertions; no Android runtime");
    }
}
