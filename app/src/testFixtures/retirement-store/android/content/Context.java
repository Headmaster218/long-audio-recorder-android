package android.content;

/** Host-only application-context port. It does not model Android storage or lifecycle. */
public final class Context {
    public final Object queueModel;
    public Context(Object queueModel) { this.queueModel = queueModel; }
    public Context getApplicationContext() { return this; }
}
