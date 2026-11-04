package android.os; public class SystemClock {
 public static long elapsedRealtimeNanos(){return System.nanoTime();} public static long elapsedRealtime(){return System.nanoTime()/1000000;} public static long uptimeMillis(){return elapsedRealtime();}}
