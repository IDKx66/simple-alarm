package com.example.simplealarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;

public final class AlarmScheduler {
    public static boolean schedule(Context context, Alarm alarm) {
        if (!alarm.enabled) return false;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return false;
        long trigger = nextTrigger(alarm, System.currentTimeMillis());
        PendingIntent pi = pendingIntent(context, alarm.id);
        Intent showIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPi = PendingIntent.getActivity(context, 700000 + alarm.id, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Alarm-clock alarms are the system's highest-priority exact alarms and are
        // allowed to wake the device out of Doze at the requested wall-clock time.
        manager.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, showPi), pi);
        return true;
    }

    public static void cancel(Context context, int id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        manager.cancel(pendingIntent(context, id));
        Intent snooze = new Intent(context, AlarmReceiver.class).putExtra("alarm_id", id).putExtra("snooze", true);
        manager.cancel(PendingIntent.getBroadcast(context, 900000 + id, snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    public static void rescheduleAll(Context context) {
        for (Alarm alarm : AlarmStore.load(context)) if (alarm.enabled) schedule(context, alarm);
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

    private static PendingIntent pendingIntent(Context context, int id) {
        Intent intent = new Intent(context, AlarmReceiver.class).putExtra("alarm_id", id);
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
