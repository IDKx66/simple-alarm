package com.example.simplealarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.util.Log;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public final class AlarmStore {
    private static final String PREFS = "alarms";
    private static final String KEY = "items";
    private static final String LEGACY_BACKUP_KEY = "items_backup";

    private static synchronized SharedPreferences preferences(Context context) {
        Context device = context.createDeviceProtectedStorageContext();
        SharedPreferences preferences = device.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        discardLegacyBackup(preferences);
        UserManager users = context.getSystemService(UserManager.class);
        // Older releases kept alarms in credential-encrypted storage. Migrate the
        // current alarms and ID counter once accessible, discarding old backups.
        // Existing device storage is authoritative and must never be overwritten.
        if (users != null && users.isUserUnlocked()) {
            Context credential = context.isDeviceProtectedStorage()
                    ? context.getApplicationContext() : context;
            if (credential == null || credential.isDeviceProtectedStorage()) return preferences;
            SharedPreferences previous = credential.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            discardLegacyBackup(previous);
            if (!preferences.contains(KEY)) {
                if (!device.moveSharedPreferencesFrom(credential, PREFS)) {
                    Log.e(AlarmScheduler.TIMING_TAG, "alarm storage migration failed; will retry after unlock");
                    return previous;
                }
                preferences = device.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        }
        return preferences;
    }

    private static void discardLegacyBackup(SharedPreferences preferences) {
        if (preferences.contains(LEGACY_BACKUP_KEY)
                && !preferences.edit().remove(LEGACY_BACKUP_KEY).commit()) {
            Log.e(AlarmScheduler.TIMING_TAG, "failed to remove legacy alarm backup");
        }
    }

    public static List<Alarm> load(Context context) {
        List<Alarm> result = new ArrayList<>();
        SharedPreferences preferences = preferences(context);
        String raw = preferences.getString(KEY, "[]");
        if (!parse(raw, result)) result.clear();
        return result;
    }

    public static void save(Context context, List<Alarm> alarms) {
        JSONArray array = new JSONArray();
        for (Alarm alarm : alarms) {
            try { array.put(alarm.toJson()); } catch (Exception ignored) { }
        }
        SharedPreferences preferences = preferences(context);
        // A reboot immediately after snoozing must not lose an asynchronous write.
        if (!preferences.edit().putString(KEY,array.toString()).commit()) {
            Log.e(AlarmScheduler.TIMING_TAG, "failed to persist alarms");
        }
    }

    private static boolean parse(String raw, List<Alarm> destination) {
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) destination.add(Alarm.fromJson(array.getJSONObject(i)));
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static Alarm find(Context context, int id) {
        for (Alarm alarm : load(context)) if (alarm.id == id) return alarm;
        return null;
    }

    public static int nextId(Context context) {
        SharedPreferences p = preferences(context);
        int id = p.getInt("next_id", 1000);
        p.edit().putInt("next_id", id + 1).commit();
        return id;
    }
}
