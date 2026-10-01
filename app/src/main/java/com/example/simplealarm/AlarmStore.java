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
    private static final String BACKUP_KEY = "items_backup";

    private static synchronized SharedPreferences preferences(Context context) {
        Context device = context.createDeviceProtectedStorageContext();
        SharedPreferences preferences = device.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        UserManager users = context.getSystemService(UserManager.class);
        // Older releases kept alarms in credential-encrypted storage. Migrate the
        // entire file once accessible, retaining its backup and ID counter too.
        // Existing device storage is authoritative and must never be overwritten.
        if (users != null && users.isUserUnlocked() && !preferences.contains(KEY)) {
            Context credential = context.isDeviceProtectedStorage()
                    ? context.getApplicationContext() : context;
            if (credential == null || credential.isDeviceProtectedStorage()) return preferences;
            if (!device.moveSharedPreferencesFrom(credential, PREFS)) {
                Log.e(AlarmScheduler.TIMING_TAG, "alarm storage migration failed; will retry after unlock");
                return credential.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
            preferences = device.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return preferences;
    }

    public static List<Alarm> load(Context context) {
        List<Alarm> result = new ArrayList<>();
        SharedPreferences preferences = preferences(context);
        String raw = preferences.getString(KEY, "[]");
        if (!parse(raw, result)) {
            result.clear();
            parse(preferences.getString(BACKUP_KEY, "[]"), result);
        }
        return result;
    }

    public static void save(Context context, List<Alarm> alarms) {
        JSONArray array = new JSONArray();
        for (Alarm alarm : alarms) {
            try { array.put(alarm.toJson()); } catch (Exception ignored) { }
        }
        SharedPreferences preferences = preferences(context);
        String previous = preferences.getString(KEY, "[]");
        SharedPreferences.Editor edit=preferences.edit();
        if(parse(previous,new ArrayList<Alarm>()))edit.putString(BACKUP_KEY,previous);
        // A reboot immediately after snoozing must not lose an asynchronous write.
        if (!edit.putString(KEY,array.toString()).commit()) {
            Log.e(AlarmScheduler.TIMING_TAG, "failed to persist alarms");
        }
    }

    public static String exportJson(Context context) {
        JSONArray array = new JSONArray();
        for (Alarm alarm : load(context)) try { array.put(alarm.toJson()); } catch (Exception ignored) { }
        return array.toString();
    }

    public static boolean importJson(Context context, String raw) {
        List<Alarm> alarms = new ArrayList<>();
        if (!parse(raw, alarms)) return false;
        java.util.Set<Integer> ids=new java.util.HashSet<>();
        java.util.Set<Integer> times=new java.util.HashSet<>();
        int next=1000;
        for (Alarm alarm : alarms) {
            if (alarm.hour < 0 || alarm.hour > 23 || alarm.minute < 0 || alarm.minute > 59 || alarm.id<0 || alarm.id>500000 || !ids.add(alarm.id) || !times.add(alarm.hour*60+alarm.minute)) return false;
            alarm.pendingSnoozeAt=0L;
            next=Math.max(next,alarm.id+1);
        }
        save(context, alarms);
        preferences(context).edit().putInt("next_id",next).commit();
        return true;
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
