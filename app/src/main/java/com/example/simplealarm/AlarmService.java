package com.example.simplealarm;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
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
    // Service and Activity lifecycle callbacks run on the main thread. Keep this
    // observer in-process so ringing controls never rely on broadcast payloads.
    interface RingingStateListener { void onRingingStateChanged(RingingState state); }
    static final class RingingState {
        final int alarmId;
        final String label;
        final boolean vibrate;
        private final int[] ids;
        RingingState(int alarmId, String label, boolean vibrate, int[] ids) {
            this.alarmId = alarmId;
            this.label = label;
            this.vibrate = vibrate;
            this.ids = ids.clone();
        }
        int[] activeIds() { return ids.clone(); }
    }
    private static RingingState currentState;
    private static final Set<RingingStateListener> stateListeners = new LinkedHashSet<>();
    static RingingState ringingState() { return currentState; }
    static void addRingingStateListener(RingingStateListener listener) { stateListeners.add(listener); }
    static void removeRingingStateListener(RingingStateListener listener) { stateListeners.remove(listener); }
    private Ringtone ringtone;
    private MediaPlayer legacyPlayer;
    private Vibrator vibrator;
    private int alarmId = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopAlarm;
    private long autoStopAt;
    private int snoozeMinutes = 5, maxSnoozes = 3, snoozeCount = 0;
    private String label = "闹钟";
    private boolean vibrateEnabled = true;
    private final Set<Integer> activeAlarmIds = new LinkedHashSet<>();

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_VIBRATION.equals(intent.getAction())) {
            if (matchesActiveAlarm(intent)) {
                vibrateEnabled = intent.getBooleanExtra("vibrate", true); applyVibration();
                publishRingingState();
            } else if (activeAlarmIds.isEmpty()) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            if (matchesActiveAlarm(intent)) stopAlarm();
            else if (activeAlarmIds.isEmpty()) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(intent.getAction())) {
            if (matchesActiveAlarm(intent)) {
                Log.i(AlarmScheduler.TIMING_TAG,"snooze requested ids="+activeAlarmIds+" at="+System.currentTimeMillis());
                snooze();
            } else if (activeAlarmIds.isEmpty()) stopSelf();
            return START_NOT_STICKY;
        }
        int incomingId = intent.getIntExtra("alarm_id", -1);
        if (incomingId == -1) {
            if (activeAlarmIds.isEmpty()) stopSelf();
            return START_NOT_STICKY;
        }
        // A ringtone can be unavailable while vibration and the foreground
        // notification are still active; the live IDs define the session.
        if (!activeAlarmIds.isEmpty()) {
            if (activeAlarmIds.add(incomingId)) extendRingingDuration(intent);
            updateNotificationAndState();
            return START_NOT_STICKY;
        }
        alarmId = incomingId;
        activeAlarmIds.clear(); activeAlarmIds.add(alarmId);
        label = intent.getStringExtra("label") == null ? "闹钟" : intent.getStringExtra("label");
        snoozeMinutes = intent.getIntExtra("snooze_minutes", 5);
        maxSnoozes = intent.getIntExtra("max_snoozes", 3);
        snoozeCount = intent.getIntExtra("snooze_count", 0);
        vibrateEnabled = intent.getBooleanExtra("vibrate", true);
        long dueAt=intent.getLongExtra(AlarmScheduler.EXTRA_DUE_AT,0L);
        long receivedAt=intent.getLongExtra(AlarmScheduler.EXTRA_RECEIVED_AT,0L);
        if(dueAt>0L)Log.i(AlarmScheduler.TIMING_TAG,"service start id="+alarmId+" due="+dueAt+" received="+receivedAt+" serviceAt="+System.currentTimeMillis());
        startForeground(77, buildNotification(label));
        handler.removeCallbacksAndMessages(null);
        startSoundAndVibration(intent.getStringExtra("ringtone_uri"), intent.getBooleanExtra("gradual", true),dueAt,receivedAt);
        publishRingingState();
        extendRingingDuration(intent);
        return START_NOT_STICKY;
    }

    private void extendRingingDuration(Intent intent) {
        long duration = Math.max(1, intent.getIntExtra("duration", 10)) * 60_000L;
        // The group keeps one sound until its latest member's deadline. A later
        // alarm must not be cut short by the first one's timer, and shorter or
        // duplicate members must not shorten or repeatedly extend the session.
        autoStopAt = Math.max(autoStopAt, SystemClock.uptimeMillis() + duration);
        handler.removeCallbacks(autoStop);
        handler.postAtTime(autoStop, autoStopAt);
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
                .setContentTitle(label).setContentText("闹钟正在响铃 · 展开通知可操作")
                .setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true).setFullScreenIntent(fullPi, true).setContentIntent(fullPi);
        Icon actionIcon=Icon.createWithResource(this,R.drawable.ic_alarm);
        if (canSnoozeAny()) builder.addAction(new Notification.Action.Builder(actionIcon, "稍后提醒", snoozePi).build());
        builder.addAction(new Notification.Action.Builder(actionIcon, "停止", stopPi).build());
        return builder.build();
    }

    private void startSoundAndVibration(String customUri, boolean gradual,long dueAt,long receivedAt) {
        if (Build.VERSION.SDK_INT < 28) {
            // Ringtone's public looping API starts at Android 9. Use a public
            // MediaPlayer on Android 8 so a short sound lasts for the ring session.
            startLegacySound(customUri, gradual, dueAt, receivedAt);
            applyVibration();
            return;
        }
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
            if (!playRingtone(ringtone, gradual, dueAt, receivedAt)) {
                ringtone = safeRingtone(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
                if (ringtone != null && !playRingtone(ringtone, gradual, dueAt, receivedAt)) {
                    ringtone = null;
                }
            }
            if (ringtone == null) {
                Toast.makeText(this,"无法播放铃声，请检查系统声音设置",Toast.LENGTH_LONG).show();
            } else if (gradual) {
                for (int i = 1; i <= 10; i++) {
                    final float volume = 0.12f + i * 0.088f;
                    handler.postDelayed(() -> { if (ringtone != null) ringtone.setVolume(Math.min(1f, volume)); }, i * 3000L);
                }
            }
        }
        applyVibration();
    }

    private boolean playRingtone(Ringtone candidate, boolean gradual, long dueAt, long receivedAt) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            // A replacement Ringtone has fresh defaults; configure fallback
            // playback just like the selected sound before attempting to play.
            candidate.setLooping(true);
            candidate.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            if (gradual) candidate.setVolume(0.12f);
            candidate.play();
            logPlayback(dueAt, receivedAt);
            return true;
        } catch (Exception error) {
            try { candidate.stop(); } catch (Exception ignored) { }
            Log.w(AlarmScheduler.TIMING_TAG, "ringtone playback unavailable", error);
            return false;
        }
    }

    private void startLegacySound(String customUri, boolean gradual, long dueAt, long receivedAt) {
        if (legacyPlayer != null) return;
        Set<Uri> candidates = new LinkedHashSet<>();
        if (customUri != null && !customUri.isEmpty()) {
            try { candidates.add(Uri.parse(customUri)); } catch (RuntimeException ignored) { }
        }
        candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        for (Uri uri : candidates) {
            if (uri == null) continue;
            MediaPlayer candidate = new MediaPlayer();
            try {
                candidate.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                candidate.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
                candidate.setDataSource(this, uri);
                candidate.setLooping(true);
                if (gradual) candidate.setVolume(0.12f, 0.12f);
                candidate.prepare();
                candidate.start();
                legacyPlayer = candidate;
                logPlayback(dueAt, receivedAt);
                if (gradual) {
                    for (int i = 1; i <= 10; i++) {
                        final float volume = Math.min(1f, 0.12f + i * 0.088f);
                        handler.postDelayed(() -> {
                            if (legacyPlayer != null) legacyPlayer.setVolume(volume, volume);
                        }, i * 3000L);
                    }
                }
                return;
            } catch (Exception error) {
                candidate.release();
                Log.w(AlarmScheduler.TIMING_TAG, "sound unavailable; trying default fallback", error);
            }
        }
        Toast.makeText(this,"无法播放铃声，请检查系统声音设置",Toast.LENGTH_LONG).show();
    }

    private void logPlayback(long dueAt,long receivedAt) {
        if(dueAt<=0L)return;
        long now=System.currentTimeMillis();
        Log.i(AlarmScheduler.TIMING_TAG,"ringtone play id="+alarmId+" due="+dueAt+" received="+receivedAt+" playAt="+now+" lateMs="+(now-dueAt)+" appMs="+(now-receivedAt));
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
        java.util.List<Alarm> stored = AlarmStore.load(this); boolean scheduled = false;
        Set<Integer> failedIds = new LinkedHashSet<>();
        boolean eligible = false;
        long requestedAt = System.currentTimeMillis();
        for (int id : activeAlarmIds) {
            for (Alarm alarm : stored) if (alarm.id == id && alarm.snoozeCount < alarm.maxSnoozes) {
                eligible = true;
                long when=requestedAt+Math.max(1,alarm.snoozeMinutes)*60_000L;
                boolean success = false;
                try {
                    if(AlarmScheduler.scheduleSnooze(this,alarm,when)) {
                        alarm.snoozeCount++;
                        alarm.pendingSnoozeAt=when;
                        scheduled=true;
                        success=true;
                    }
                } catch(Exception error) { Log.e(AlarmScheduler.TIMING_TAG,"snooze scheduling failed id="+id,error); }
                if (!success) failedIds.add(id);
            }
        }
        if (scheduled) AlarmStore.save(this,stored);
        if (!eligible) {
            Toast.makeText(this,"已达到稍后提醒次数上限",Toast.LENGTH_LONG).show();
        } else if (failedIds.isEmpty()) {
            stopAlarm();
        } else {
            // Keep failed items ringing; successfully scheduled items must not
            // remain active and accidentally receive another snooze request.
            activeAlarmIds.clear();
            activeAlarmIds.addAll(failedIds);
            alarmId = activeAlarmIds.iterator().next();
            for (Alarm alarm : stored) if (alarm.id == alarmId) {
                label = alarm.label;
                snoozeCount = alarm.snoozeCount;
                maxSnoozes = alarm.maxSnoozes;
                break;
            }
            updateNotificationAndState();
            Toast.makeText(this,scheduled ? "部分闹钟未能设置稍后提醒，仍在响铃，请检查闹钟权限"
                    : "稍后提醒未能设置，闹钟仍在响铃，请检查闹钟权限",Toast.LENGTH_LONG).show();
        }
    }

    private void stopAlarm() {
        handler.removeCallbacksAndMessages(null);
        autoStopAt = 0L;
        if (ringtone != null) { ringtone.stop(); ringtone = null; }
        if (legacyPlayer != null) { legacyPlayer.release(); legacyPlayer = null; }
        if (vibrator != null) vibrator.cancel();
        currentVibration = false;
        activeAlarmIds.clear();
        publishRingingState();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    private boolean matchesActiveAlarm(Intent intent) {
        if (activeAlarmIds.contains(intent.getIntExtra("alarm_id", -1))) return true;
        int[] ids=intent.getIntArrayExtra(EXTRA_ACTIVE_ALARM_IDS);
        if (ids != null) for (int id : ids) if (activeAlarmIds.contains(id)) return true;
        return false;
    }

    private String ringingLabel() {
        return activeAlarmIds.size() > 1 ? "多个闹钟（"+activeAlarmIds.size()+"个）" : label;
    }

    private void updateNotificationAndState() {
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(77, buildNotification(ringingLabel()));
        publishRingingState();
    }

    private void publishRingingState() {
        currentState = activeAlarmIds.isEmpty() ? null : new RingingState(alarmId,
                ringingLabel(), vibrateEnabled, activeAlarmIds.stream().mapToInt(Integer::intValue).toArray());
        for (RingingStateListener listener : new LinkedHashSet<>(stateListeners)) {
            listener.onRingingStateChanged(currentState);
        }
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
