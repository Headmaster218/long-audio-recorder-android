package android.content;

import java.util.HashMap;
import java.util.Map;

/** Host fixture only. Never package this class in the Android application. */
public final class Intent {
    private final Map<String, Object> values = new HashMap<String, Object>();
    public Intent putExtra(String key, int value) { values.put(key,value); return this; }
    public Intent putExtra(String key, long value) { values.put(key,value); return this; }
    public int getIntExtra(String key, int fallback) { Object value = values.get(key); return value instanceof Integer ? (Integer) value : fallback; }
    public long getLongExtra(String key, long fallback) { Object value = values.get(key); return value instanceof Long ? (Long) value : fallback; }
}
