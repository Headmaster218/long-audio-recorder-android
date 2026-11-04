package android.os;
/** Deterministic host boundary: records main-thread work without Android execution. */
public final class Handler {
 public Handler() {} public Handler(Looper looper) {}
 public final java.util.List<Runnable> pending=new java.util.ArrayList<Runnable>();
 public boolean post(Runnable r){pending.add(r);return true;}
 public boolean postDelayed(Runnable r,long delay){pending.add(r);return true;}
}
