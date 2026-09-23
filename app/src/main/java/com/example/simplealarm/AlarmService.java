package com.example.simplealarm;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Toast;
import java.util.LinkedHashSet;
import java.util.Set;

public class AlarmService extends Service {
    public static final String CHANNEL = "alarm_ringing";
    public static final String ACTION_STOP = "com.example.simplealarm.STOP";
    public static final String ACTION_SNOOZE = "com.example.simplealarm.SNOOZE";
    public static final String ACTION_VIBRATION = "com.example.simplealarm.VIBRATION";
    public static final String EXTRA_ACTIVE_ALARM_IDS = "active_alarm_ids";
    private static boolean currentVibration;
    public static boolean isVibrationEnabled() { return currentVibration; }
    private Ringtone ringtone;
    private Vibrator vibrator;
    private int alarmId = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int snoozeMinutes = 5, maxSnoozes = 3, snoozeCount = 0;
    private String label = "闹钟";
    private boolean vibrateEnabled = true;
    private final Set<Integer> activeAlarmIds = new LinkedHashSet<>();

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_VIBRATION.equals(intent.getAction())) {
            int requestedId=intent.getIntExtra("alarm_id", -1);
            boolean belongs=activeAlarmIds.contains(requestedId);
            int[] requestedIds=intent.getIntArrayExtra(EXTRA_ACTIVE_ALARM_IDS);
            if(requestedIds!=null)for(int id:requestedIds)if(activeAlarmIds.contains(id))belongs=true;
            if (ringtone != null && belongs) {
                vibrateEnabled = intent.getBooleanExtra("vibrate", true); applyVibration();
            } else if (ringtone == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) { stopAlarm(); return START_NOT_STICKY; }
        if (ACTION_SNOOZE.equals(intent.getAction())) { restoreActiveIds(intent); snooze(); return START_NOT_STICKY; }
        int incomingId = intent.getIntExtra("alarm_id", -1);
        if (ringtone != null) {
            activeAlarmIds.add(incomingId);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(77, buildNotification("多个闹钟（"+activeAlarmIds.size()+"个）"));
            return START_NOT_STICKY;
        }
        alarmId = incomingId;
        activeAlarmIds.clear(); activeAlarmIds.add(alarmId);
        label = intent.getStringExtra("label") == null ? "闹钟" : intent.getStringExtra("label");
        snoozeMinutes = intent.getIntExtra("snooze_minutes", 5);
        maxSnoozes = intent.getIntExtra("max_snoozes", 3);
        snoozeCount = intent.getIntExtra("snooze_count", 0);
        vibrateEnabled = intent.getBooleanExtra("vibrate", true);
        startForeground(77, buildNotification(label));
        handler.removeCallbacksAndMessages(null);
        startSoundAndVibration(intent.getStringExtra("ringtone_uri"), intent.getBooleanExtra("gradual", true));
        int duration = Math.max(1, intent.getIntExtra("duration", 10));
        handler.postDelayed(this::stopAlarm, duration * 60_000L);
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String label) {
        int[] ids=activeAlarmIds.stream().mapToInt(Integer::intValue).toArray();
        Intent full = new Intent(this, AlarmActivity.class).putExtra("alarm_id", alarmId).putExtra("label", label).putExtra(EXTRA_ACTIVE_ALARM_IDS,ids);
        PendingIntent fullPi = PendingIntent.getActivity(this, 30000 + alarmId, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPi = PendingIntent.getService(this, 40000 + alarmId,
                new Intent(this, AlarmService.class).setAction(ACTION_STOP).putExtra("alarm_id", alarmId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent snoozePi = PendingIntent.getService(this, 50000 + alarmId,
                new Intent(this, AlarmService.class).setAction(ACTION_SNOOZE).putExtra("alarm_id", alarmId).putExtra(EXTRA_ACTIVE_ALARM_IDS,ids),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.example.simplealarm.R.drawable.ic_alarm)
                .setContentTitle(label).setContentText("闹钟正在响铃")
                .setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true).setFullScreenIntent(fullPi, true).setContentIntent(fullPi);
        if (canSnoozeAny()) builder.addAction(new Notification.Action.Builder(null, "稍后提醒", snoozePi).build());
        builder.addAction(new Notification.Action.Builder(null, "停止", stopPi).build());
        return builder.build();
    }

    private void startSoundAndVibration(String customUri, boolean gradual) {
        if (ringtone == null) {
            Uri uri = null;
            if (customUri != null && !customUri.isEmpty()) try { uri = Uri.parse(customUri); } catch (Exception ignored) { }
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ringtone = safeRingtone(uri);
            if (ringtone == null || (customUri != null && !customUri.isEmpty() && uri != null && ringtone == null))
                ringtone = safeRingtone(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            if (ringtone == null) ringtone = safeRingtone(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (ringtone == null) { Toast.makeText(this,"无法播放铃声，请检查系统声音设置",Toast.LENGTH_LONG).show(); applyVibration(); return; }
            if (Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
            ringtone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            if (Build.VERSION.SDK_INT >= 28 && gradual) {
                ringtone.setVolume(0.12f);
                for (int i = 1; i <= 10; i++) {
                    final float volume = 0.12f + i * 0.088f;
                    handler.postDelayed(() -> { if (ringtone != null) ringtone.setVolume(Math.min(1f, volume)); }, i * 3000L);
                }
            }
            try { ringtone.play(); } catch (Exception error) {
                ringtone = safeRingtone(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
                if (ringtone != null) ringtone.play();
            }
        }
        applyVibration();
    }

    private void applyVibration() {
        currentVibration = vibrateEnabled;
        if (vibrator != null) vibrator.cancel();
        if (vibrateEnabled) {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = {0, 700, 500};
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            else vibrator.vibrate(pattern, 0);
        }
    }

    private void snooze() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            Toast.makeText(this,"无法设置稍后提醒：请允许精确闹钟权限",Toast.LENGTH_LONG).show(); return;
        }
        java.util.List<Alarm> stored = AlarmStore.load(this); boolean scheduled = false;
        for (int id : activeAlarmIds) {
            for (Alarm alarm : stored) if (alarm.id == id && alarm.snoozeCount < alarm.maxSnoozes) {
                alarm.snoozeCount++;
                long when=System.currentTimeMillis()+Math.max(1,alarm.snoozeMinutes)*60_000L;
                Intent i=new Intent(this,AlarmReceiver.class).putExtra("alarm_id",alarm.id).putExtra("snooze",true);
                PendingIntent pi=PendingIntent.getBroadcast(this,900000+alarm.id,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                PendingIntent showPi=PendingIntent.getActivity(this,990000+alarm.id,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when,showPi),pi); scheduled=true;
            }
        }
        if (scheduled) { AlarmStore.save(this,stored); stopAlarm(); }
        else Toast.makeText(this,"已达到稍后提醒次数上限",Toast.LENGTH_LONG).show();
    }

    private void stopAlarm() {
        handler.removeCallbacksAndMessages(null);
        if (ringtone != null) { ringtone.stop(); ringtone = null; }
        if (vibrator != null) vibrator.cancel();
        currentVibration = false;
        activeAlarmIds.clear();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    private void restoreActiveIds(Intent intent) {
        int[] ids=intent.getIntArrayExtra(EXTRA_ACTIVE_ALARM_IDS);
        if(ids!=null && ids.length>0){activeAlarmIds.clear();for(int id:ids)activeAlarmIds.add(id);}
    }

    private boolean canSnoozeAny() {
        if (activeAlarmIds.isEmpty()) return snoozeCount < maxSnoozes;
        for (int id : activeAlarmIds) { Alarm alarm=AlarmStore.find(this,id); if(alarm!=null && alarm.snoozeCount<alarm.maxSnoozes) return true; }
        return false;
    }

    private Ringtone safeRingtone(Uri uri) {
        if (uri == null) return null;
        try { return RingtoneManager.getRingtone(this,uri); } catch (Exception ignored) { return null; }
    }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CHANNEL, "闹钟响铃", NotificationManager.IMPORTANCE_HIGH);
        c.setDescription("在闹钟时间显示并响铃"); c.setSound(null, null); c.enableVibration(false);
        c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }
    @Override public void onDestroy() { stopAlarm(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
