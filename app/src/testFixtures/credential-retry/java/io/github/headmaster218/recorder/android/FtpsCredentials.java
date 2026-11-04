package io.github.headmaster218.recorder.android;
import java.util.*;
/** Explicit host keystore boundary: no key creation, actual Android Keystore not exercised. */
final class FtpsCredentials {
 static boolean locked=true;static int unlockCalls;static Runnable beforeReturn;
 static final Map<String,Integer> calls=new HashMap<String,Integer>();static final List<char[]> returned=new ArrayList<char[]>();
 static char[] unlock(String revision,byte[] encrypted)throws java.security.GeneralSecurityException {
  unlockCalls++;calls.put(revision,calls.containsKey(revision)?calls.get(revision)+1:1);
  if(locked)throw new java.security.GeneralSecurityException("Synthetic locked key");
  if(encrypted==null||encrypted.length!=1||encrypted[0]!=1)throw new java.security.GeneralSecurityException("Synthetic unavailable or invalid key");
  char[] probe="synthetic".toCharArray();returned.add(probe);if(beforeReturn!=null)beforeReturn.run();return probe;
 }
 static byte[] remember(String revision,char[] password,boolean consent){throw new AssertionError("Recovery must never create/save a key or credential");}
}
