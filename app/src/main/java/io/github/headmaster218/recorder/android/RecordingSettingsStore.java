package io.github.headmaster218.recorder.android;

import java.util.HashMap;
import java.util.Map;

/** Only four nonsecret GUI defaults; no recording, device, or resume authority. */
final class RecordingSettingsStore {
    static final String PREFS = "recording-settings";
    static final int VERSION = 1;
    interface Storage {
        Map<String, ?> read();
        boolean write(Map<String, Object> values);
    }
    static final class Loaded {
        final RecordingSettings settings;
        final String warning;
        Loaded(RecordingSettings settings, String warning) { this.settings = settings; this.warning = warning; }
    }
    private final Storage storage;
    RecordingSettingsStore(Storage storage) { this.storage = storage; }
    private static boolean unsupported(Map<String, ?> values) {
        return values.containsKey("schema_version") && !Integer.valueOf(VERSION).equals(values.get("schema_version"));
    }
    Loaded load() {
        try {
            Map<String, ?> values = storage.read();
            if (values.isEmpty()) return new Loaded(RecordingSettings.defaults(),null);
            if (unsupported(values)) return new Loaded(RecordingSettings.defaults(),
                "Saved settings use an unsupported version. They are preserved; Save is unavailable. Displayed defaults may still be used with Start.");
            if (!Integer.valueOf(VERSION).equals(values.get("schema_version"))
                || !(values.get("rate") instanceof Integer) || !(values.get("channels") instanceof Integer)
                || !(values.get("seconds") instanceof Integer) || !(values.get("cache_mib") instanceof Long))
                throw new IllegalArgumentException("Invalid saved settings record");
            return new Loaded(new RecordingSettings((Integer) values.get("rate"),(Integer) values.get("channels"),
                (Integer) values.get("seconds"),(Long) values.get("cache_mib")),null);
        } catch (RuntimeException failure) {
            return new Loaded(RecordingSettings.defaults(),
                "Saved settings could not be loaded. Displaying defaults; existing data is unchanged. A successful explicit Save replaces invalid settings.");
        }
    }
    /** Returns null only after the persistence adapter reports success. */
    synchronized String save(RecordingSettings settings) {
        try {
            if (unsupported(storage.read())) return "Settings were not saved: unsupported saved version is preserved.";
            Map<String, Object> values = new HashMap<String, Object>();
            values.put("schema_version",VERSION); values.put("rate",settings.rate);
            values.put("channels",settings.channels); values.put("seconds",settings.seconds);
            values.put("cache_mib",settings.cacheBytes / RecordingSettings.MIB);
            return storage.write(values) ? null : "Settings could not be saved. Durable storage was not confirmed; try again.";
        } catch (RuntimeException failure) {
            return "Settings could not be saved. Durable storage was not confirmed; try again.";
        }
    }
}
