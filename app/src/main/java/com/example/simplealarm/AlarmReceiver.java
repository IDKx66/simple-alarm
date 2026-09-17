package com.example.simplealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SimpleAlarm:ring-start");
        wakeLock.acquire(10_000L);
        int id = intent.getIntExtra("alarm_id", -1);
        Alarm alarm = AlarmStore.find(context, id);
        boolean snooze = intent.getBooleanExtra("snooze", false);
        if (alarm == null || (!alarm.enabled && !snooze)) return;
        Intent service = new Intent(context, AlarmService.class)
                .putExtra("alarm_id", id).putExtra("label", alarm.label)
                .putExtra("ringtone_uri", alarm.ringtoneUri)
                .putExtra("vibrate", alarm.vibrate)
                .putExtra("gradual", alarm.gradualVolume)
                .putExtra("duration", alarm.ringDurationMinutes)
                .putExtra("snooze_minutes", alarm.snoozeMinutes)
                .putExtra("max_snoozes", alarm.maxSnoozes)
                .putExtra("snooze_count", alarm.snoozeCount);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
        if (snooze) return;
        alarm.snoozeCount = 0;
        if (alarm.daysMask != 0) AlarmScheduler.schedule(context, alarm);
        else {
            alarm.enabled = false;
            java.util.List<Alarm> alarms = AlarmStore.load(context);
            for (int i = 0; i < alarms.size(); i++) if (alarms.get(i).id == id) alarms.set(i, alarm);
            AlarmStore.save(context, alarms);
        }
    }
}
