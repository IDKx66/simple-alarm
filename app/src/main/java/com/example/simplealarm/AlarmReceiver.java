package com.example.simplealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        long receivedAt=System.currentTimeMillis();
        if (intent == null) return;
        int id = intent.getIntExtra("alarm_id", -1);
        Alarm alarm = AlarmStore.find(context, id);
        boolean snooze = intent.getBooleanExtra("snooze", false);
        long dueAt=intent.getLongExtra(AlarmScheduler.EXTRA_DUE_AT,0L);
        if (alarm == null || (!alarm.enabled && !snooze)) return;
        if (snooze && (dueAt<=0L || alarm.pendingSnoozeAt!=dueAt)) return;
        // Cancellation cannot retract a broadcast already queued by Android.
        // Validate against its original due time, so edits, skips and timezone
        // changes reject old occurrences without rejecting a delayed valid ring.
        if (!snooze && dueAt > 0L
                && AlarmScheduler.nextTrigger(alarm, dueAt - 1L) != dueAt) return;
        if(dueAt>0L) Log.i(AlarmScheduler.TIMING_TAG,"received "+(snooze?"snooze":"regular")+" id="+id+" due="+dueAt+" received="+receivedAt+" lateMs="+(receivedAt-dueAt));
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SimpleAlarm:ring-start");
        wakeLock.acquire(10_000L);
        Intent service = new Intent(context, AlarmService.class)
                .putExtra("alarm_id", id).putExtra("label", alarm.label)
                .putExtra(AlarmScheduler.EXTRA_DUE_AT,dueAt)
                .putExtra(AlarmScheduler.EXTRA_RECEIVED_AT,receivedAt)
                .putExtra("ringtone_uri", alarm.ringtoneUri)
                .putExtra("vibrate", alarm.vibrate)
                .putExtra("gradual", alarm.gradualVolume)
                .putExtra("duration", alarm.ringDurationMinutes)
                .putExtra("snooze_minutes", alarm.snoozeMinutes)
                .putExtra("max_snoozes", alarm.maxSnoozes)
                .putExtra("snooze_count", snooze ? alarm.snoozeCount : 0);
        try {
            context.startForegroundService(service);
        } catch (RuntimeException error) {
            // Keep the persisted reminder available for a later recovery attempt.
            wakeLock.release();
            Log.e(AlarmScheduler.TIMING_TAG, "ringing service start failed id=" + id, error);
            return;
        }
        java.util.List<Alarm> alarms=AlarmStore.load(context);
        if (snooze) alarm.pendingSnoozeAt=0L;
        else {
            alarm.snoozeCount=0;
            if (alarm.daysMask == 0) alarm.enabled=false;
        }
        for (int i=0;i<alarms.size();i++) if(alarms.get(i).id==id) alarms.set(i,alarm);
        AlarmStore.save(context,alarms);
        if (snooze) return;
        if (alarm.daysMask != 0) AlarmScheduler.schedule(context, alarm);
    }
}
