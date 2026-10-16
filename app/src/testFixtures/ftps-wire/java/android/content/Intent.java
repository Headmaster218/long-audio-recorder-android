package android.content;
import java.util.HashMap;
import java.util.Map;
public final class Intent {
    public static final String ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED";
    public static final String ACTION_POWER_DISCONNECTED = "android.intent.action.ACTION_POWER_DISCONNECTED";
    private final String action;
    private final Map<String,Integer> extras = new HashMap<String,Integer>();
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public Intent putExtra(String name, int value) { extras.put(name, Integer.valueOf(value)); return this; }
    public int getIntExtra(String name, int fallback) {
        Integer value = extras.get(name); return value == null ? fallback : value.intValue();
    }
}
