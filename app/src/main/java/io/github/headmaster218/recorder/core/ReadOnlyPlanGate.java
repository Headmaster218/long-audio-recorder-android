package io.github.headmaster218.recorder.core;

/** UI-thread ticket: pausing/rotation invalidates old read-only work, never recording authority. */
public final class ReadOnlyPlanGate {
    private Object current;
    public boolean busy(){return current!=null;}
    public Object begin(){if(current!=null)throw new IllegalStateException("Plan already running");current=new Object();return current;}
    public boolean finish(Object ticket){if(ticket==null || current!=ticket)return false;current=null;return true;}
    public void cancel(){current=null;}
}
