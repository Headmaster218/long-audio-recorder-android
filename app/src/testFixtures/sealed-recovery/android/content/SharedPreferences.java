package android.content;
/** Minimal host contract, not an Android implementation. */
public interface SharedPreferences {
    boolean getBoolean(String key, boolean fallback);
    Editor edit();
    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        boolean commit();
    }
}
