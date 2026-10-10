package android.content;
import android.net.ConnectivityManager;
public class Context {
 public final ConnectivityManager connectivity=new ConnectivityManager(); public boolean powered=true,permission=true;
 public Object getSystemService(String name) { return connectivity; }
 public int checkSelfPermission(String permissionName) { return permission?0:-1; }
 public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter) { Intent i=new Intent();i.powered=powered;return i; }
}
