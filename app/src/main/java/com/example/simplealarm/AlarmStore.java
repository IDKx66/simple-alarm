package com.example.simplealarm;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public final class AlarmStore {
    private static final String PREFS = "alarms";
    private static final String KEY = "items";
    private static final String BACKUP_KEY = "items_backup";

    public static List<Alarm> load(Context context) {
        List<Alarm> result = new ArrayList<>();
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String previous = preferences.getString(KEY, "[]");
        SharedPreferences.Editor edit=preferences.edit();
        if(parse(previous,new ArrayList<Alarm>()))edit.putString(BACKUP_KEY,previous);
        edit.putString(KEY,array.toString()).apply();
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
        int next=1000;
        for (Alarm alarm : alarms) {
            if (alarm.hour < 0 || alarm.hour > 23 || alarm.minute < 0 || alarm.minute > 59 || alarm.id<0 || alarm.id>500000 || !ids.add(alarm.id)) return false;
            next=Math.max(next,alarm.id+1);
        }
        save(context, alarms);
        context.getSharedPreferences(PREFS,0).edit().putInt("next_id",next).apply();
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
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int id = p.getInt("next_id", 1000);
        p.edit().putInt("next_id", id + 1).apply();
        return id;
    }
}
