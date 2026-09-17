package com.example.simplealarm;

import org.json.JSONException;
import org.json.JSONObject;

public class Alarm {
    public int id;
    public int hour;
    public int minute;
    public String label;
    public boolean enabled;
    public int daysMask;
    public String ringtoneUri;
    public boolean vibrate;
    public boolean gradualVolume;
    public int ringDurationMinutes;
    public int snoozeMinutes;
    public int maxSnoozes;
    public int snoozeCount;
    public long skippedOccurrence;

    public Alarm(int id, int hour, int minute, String label, boolean enabled, int daysMask) {
        this.id = id; this.hour = hour; this.minute = minute;
        this.label = label; this.enabled = enabled; this.daysMask = daysMask;
        ringtoneUri = ""; vibrate = true; gradualVolume = true;
        ringDurationMinutes = 10; snoozeMinutes = 5; maxSnoozes = 3;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("hour", hour); o.put("minute", minute);
        o.put("label", label); o.put("enabled", enabled); o.put("daysMask", daysMask);
        o.put("ringtoneUri", ringtoneUri); o.put("vibrate", vibrate);
        o.put("gradualVolume", gradualVolume); o.put("ringDurationMinutes", ringDurationMinutes);
        o.put("snoozeMinutes", snoozeMinutes); o.put("maxSnoozes", maxSnoozes);
        o.put("snoozeCount", snoozeCount); o.put("skippedOccurrence", skippedOccurrence);
        return o;
    }

    public static Alarm fromJson(JSONObject o) throws JSONException {
        Alarm a = new Alarm(o.getInt("id"), o.getInt("hour"), o.getInt("minute"),
                o.optString("label", "闹钟"), o.optBoolean("enabled", true),
                o.optInt("daysMask", 0));
        a.ringtoneUri = o.optString("ringtoneUri", "");
        a.vibrate = o.optBoolean("vibrate", true);
        a.gradualVolume = o.optBoolean("gradualVolume", true);
        a.ringDurationMinutes = Math.max(1, o.optInt("ringDurationMinutes", 10));
        a.snoozeMinutes = Math.max(1, o.optInt("snoozeMinutes", 5));
        a.maxSnoozes = Math.max(0, o.optInt("maxSnoozes", 3));
        a.snoozeCount = Math.max(0, o.optInt("snoozeCount", 0));
        a.skippedOccurrence = o.optLong("skippedOccurrence", 0L);
        return a;
    }
}
