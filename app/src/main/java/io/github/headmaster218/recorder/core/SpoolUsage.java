package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Current owner-generation logical-byte ledger. No filesystem I/O under this monitor. */
final class SpoolUsage {
    static final int MAX_OBJECTS=4096;
    private final Map<String,Long> charges=new HashMap<String,Long>();
    private long total;
    static String key(String name) {
        if(name.matches("segment-[0-9a-f]{64}-[0-9]+\\.(part|ready|quarantine|retired)"))
            return name.substring(0,name.lastIndexOf('.'));
        return name;
    }
    synchronized void inventory(String name,long bytes) throws IOException {
        String key=key(name);
        if(charges.containsKey(key)) throw new IOException("duplicate spool reservation");
        reserve(key,bytes);
    }
    private void reserve(String key,long bytes) throws IOException {
        if(bytes<0 || total>Long.MAX_VALUE-bytes) throw new IOException("spool usage overflow");
        if(!charges.containsKey(key) && charges.size()>=MAX_OBJECTS) throw new IOException("spool object bound exceeded");
        long old=charges.containsKey(key) ? charges.get(key) : 0;
        if(old>Long.MAX_VALUE-bytes) throw new IOException("object usage overflow");
        charges.put(key,old+bytes); total+=bytes;
    }
    synchronized void charge(String name,long bytes) throws IOException { reserve(key(name),bytes); }
    synchronized long total() { return total; }
    synchronized boolean contains(String name) { return charges.containsKey(key(name)); }
    /** Caller has completed durable purge. Zero retains the ordinal and makes replay credit-once. */
    synchronized void purged(String name) throws IOException {
        String key=key(name); Long charge=charges.get(key);
        if(charge==null) throw new IOException("unknown owner-generation charge");
        total-=charge; charges.put(key,0L);
    }
    synchronized void retireZero(String name,boolean allowAbsent)throws IOException {
        String key=key(name);Long charge=charges.get(key);
        if(charge==null){if(allowAbsent)return;throw new IOException("unknown retirement charge");}
        if(charge!=0)throw new IOException("retirement cannot discard charged bytes");charges.remove(key);
    }
    synchronized int objects(){return charges.size();}

}
