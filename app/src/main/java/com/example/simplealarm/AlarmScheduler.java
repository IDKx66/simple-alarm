package com.example.simplealarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import java.util.Calendar;

public final class AlarmScheduler {
    public static final String TIMING_TAG = "SimpleAlarmTiming";
    public static final String EXTRA_DUE_AT = "due_at";
    public static final String EXTRA_RECEIVED_AT = "received_at";
    public static boolean schedule(Context context, Alarm alarm) {
        if (!alarm.enabled) return false;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return false;
        long trigger = nextTrigger(alarm, System.currentTimeMillis());
        PendingIntent pi = pendingIntent(context, alarm.id, trigger);
        Intent showIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPi = PendingIntent.getActivity(context, 700000 + alarm.id, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Alarm-clock alarms are the system's highest-priority exact alarms and are
        // allowed to wake the device out of Doze at the requested wall-clock time.
        try {
            manager.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, showPi), pi);
        } catch (SecurityException error) {
            Log.e(TIMING_TAG, "regular scheduling denied id=" + alarm.id, error);
            return false;
        }
        Log.i(TIMING_TAG, "scheduled regular id="+alarm.id+" due="+trigger);
        return true;
    }

    public static void cancel(Context context, int id) {
        cancelRegular(context,id);
        cancelSnooze(context,id);
    }

    public static void cancelRegular(Context context,int id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent existing = PendingIntent.getBroadcast(context, id,
                new Intent(context, AlarmReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (existing != null) manager.cancel(existing);
    }

    public static void cancelSnooze(Context context,int id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent existing = PendingIntent.getBroadcast(context, 900000 + id,
                new Intent(context, AlarmReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (existing != null) manager.cancel(existing);
    }

    public static boolean scheduleSnooze(Context context,Alarm alarm,long when) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return false;
        Intent showIntent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent showPi=PendingIntent.getActivity(context,990000+alarm.id,showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        try {
            manager.setAlarmClock(new AlarmManager.AlarmClockInfo(when,showPi),snoozePendingIntent(context,alarm.id,when));
        } catch (SecurityException error) {
            Log.e(TIMING_TAG, "snooze scheduling denied id=" + alarm.id, error);
            return false;
        }
        Log.i(TIMING_TAG,"scheduled snooze id="+alarm.id+" due="+when+" now="+System.currentTimeMillis());
        return true;
    }

    public static void rescheduleAll(Context context) {
        for (Alarm alarm : AlarmStore.load(context)) {
            if (alarm.enabled) schedule(context, alarm);
            // One-shot alarms are disabled after their first ring, yet their
            // snooze still needs recovery. A past trigger is delivered immediately;
            // keep its original timestamp so AlarmReceiver can validate it.
            if (alarm.pendingSnoozeAt > 0L) scheduleSnooze(context, alarm, alarm.pendingSnoozeAt);
        }
    }

    public static long nextTrigger(Alarm alarm, long now) {
        Calendar candidate = Calendar.getInstance();
        candidate.setTimeInMillis(now);
        candidate.set(Calendar.HOUR_OF_DAY, alarm.hour);
        candidate.set(Calendar.MINUTE, alarm.minute);
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);
        if (alarm.daysMask == 0) {
            if (candidate.getTimeInMillis() <= now) candidate.add(Calendar.DAY_OF_YEAR, 1);
            return candidate.getTimeInMillis();
        }
        for (int add = 0; add <= 14; add++) {
            Calendar test = (Calendar) candidate.clone();
            test.add(Calendar.DAY_OF_YEAR, add);
            int mondayIndex = (test.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            if ((alarm.daysMask & (1 << mondayIndex)) != 0 && test.getTimeInMillis() > now) {
                long value = test.getTimeInMillis();
                if (Math.abs(value - alarm.skippedOccurrence) < 1000L) continue;
                return value;
            }
        }
        candidate.add(Calendar.DAY_OF_YEAR, 7);
        return candidate.getTimeInMillis();
    }

    private static PendingIntent pendingIntent(Context context, int id,long dueAt) {
        Intent intent = new Intent(context, AlarmReceiver.class).putExtra("alarm_id", id).putExtra(EXTRA_DUE_AT,dueAt);
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent snoozePendingIntent(Context context,int id,long dueAt) {
        Intent intent=new Intent(context,AlarmReceiver.class).putExtra("alarm_id",id).putExtra("snooze",true).putExtra(EXTRA_DUE_AT,dueAt);
        return PendingIntent.getBroadcast(context,900000+id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
}
