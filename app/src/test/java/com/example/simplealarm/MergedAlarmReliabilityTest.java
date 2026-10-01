package com.example.simplealarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MergedAlarmReliabilityTest {
    private Context context;
    private ServiceController<AlarmService> serviceController;
    private AlarmService service;
    private ActivityController<AlarmActivity> activityController;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Alarm first = new Alarm(1001, 7, 0, "第一个闹钟", false, 0);
        Alarm second = new Alarm(1002, 7, 1, "第二个闹钟", false, 0);
        AlarmStore.save(context, Arrays.asList(first, second));
        serviceController = Robolectric.buildService(AlarmService.class).create();
        service = serviceController.get();
    }

    @After public void tearDown() {
        if (activityController != null) activityController.pause().stop().destroy();
        serviceController.destroy();
        AlarmStore.save(context, Collections.emptyList());
    }

    @Test public void staleScreenSnoozesEveryCurrentlyRingingAlarm() {
        startAlarm(1001, "第一个闹钟");
        startAlarm(1002, "第二个闹钟");

        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_SNOOZE)
                .putExtra("alarm_id", 1001)
                .putExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS, new int[]{1001}), 0, 3);

        Alarm first = AlarmStore.find(context, 1001);
        Alarm second = AlarmStore.find(context, 1002);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(1, first.snoozeCount);
        assertEquals(1, second.snoozeCount);
        assertTrue(first.pendingSnoozeAt > 0L);
        assertTrue(second.pendingSnoozeAt > 0L);
    }

    @Test public void ringingScreenRefreshesWhenAnotherAlarmJoins() {
        startAlarm(1001, "第一个闹钟");
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                activityIntent("第一个闹钟", 1001)).setup();

        startAlarm(1002, "第二个闹钟");

        assertTrue(hasText(activityController.get().getWindow().getDecorView(),
                "多个闹钟同时响铃（2 个）"));
    }

    @Test public void singleTaskScreenProcessesNewIntent() {
        startAlarm(1001, "第一个闹钟");
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                activityIntent("第一个闹钟", 1001)).setup();

        startAlarm(1002, "第二个闹钟");
        Intent merged = activityIntent("合并后的闹钟", 1001, 1002);
        activityController.newIntent(merged);

        assertEquals(merged, activityController.get().getIntent());
        assertTrue(hasText(activityController.get().getWindow().getDecorView(),
                "多个闹钟同时响铃（2 个）"));
    }

    @Test public void staleSnoozeDoesNotRestartAlarmAfterServiceRecreation() {
        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_SNOOZE)
                .putExtra("alarm_id", 1001)
                .putExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS, new int[]{1001}), 0, 1);

        assertEquals(0, AlarmStore.find(context, 1001).snoozeCount);
        assertEquals(0L, AlarmStore.find(context, 1001).pendingSnoozeAt);
    }

    @Test public void oldNotificationCannotStopAnUnrelatedRingingAlarm() {
        startAlarm(1002, "第二个闹钟");
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                activityIntent("第二个闹钟", 1002)).setup();

        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_STOP).putExtra("alarm_id", 1001), 0, 2);

        assertFalse(activityController.get().isFinishing());
        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_SNOOZE).putExtra("alarm_id", 1002), 0, 3);
        assertEquals(1, AlarmStore.find(context, 1002).snoozeCount);
    }

    @Test
    @Config(sdk = 26)
    public void android8RingingScreenWakesAndShowsOverLockscreen() {
        startAlarm(1001, "第一个闹钟");
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                activityIntent("第一个闹钟", 1001)).setup();

        int flags = activityController.get().getWindow().getAttributes().flags;
        assertTrue((flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0);
        assertTrue((flags & WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) != 0);
    }

    @Test public void ringingScreenClosesAfterNotificationStopsAlarm() {
        startAlarm(1001, "第一个闹钟");
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                activityIntent("第一个闹钟", 1001)).setup();
        assertFalse(activityController.get().isFinishing());

        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_STOP).putExtra("alarm_id", 1001), 0, 2);

        assertTrue(activityController.get().isFinishing());
    }

    @Test public void vibrationOnlyRingingStillSnoozesEveryMergedAlarm() {
        startAlarm(1001, "第一个闹钟");
        // A device without a playable ringtone still has a live ringing session.
        ReflectionHelpers.setField(service, "ringtone", null);
        startAlarm(1002, "第二个闹钟");

        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_SNOOZE).putExtra("alarm_id", 1002), 0, 3);

        assertEquals(1, AlarmStore.find(context, 1001).snoozeCount);
        assertEquals(1, AlarmStore.find(context, 1002).snoozeCount);
    }

    @Test public void laterAlarmReceivesItsFullRingingDuration() {
        startAlarm(1001, "第一个闹钟", 1, false);
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                activityIntent("第一个闹钟", 1001)).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.SECONDS);

        startAlarm(1002, "第二个闹钟", 5, false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS);
        assertFalse(activityController.get().isFinishing());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(279, TimeUnit.SECONDS);
        assertFalse(activityController.get().isFinishing());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertTrue(activityController.get().isFinishing());
    }

    @Test public void shorterJoiningAlarmDoesNotShortenExistingDuration() {
        startAlarm(1001, "第一个闹钟", 5, false);
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                activityIntent("第一个闹钟", 1001)).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.SECONDS);

        startAlarm(1002, "第二个闹钟", 1, false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(249, TimeUnit.SECONDS);
        assertFalse(activityController.get().isFinishing());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertTrue(activityController.get().isFinishing());
    }

    @Test
    @Config(shadows = PlayableRingtoneManager.class)
    public void extendingMergedDurationPreservesGradualVolumeCallbacks() {
        startAlarm(1001, "第一个闹钟", 1, true);
        Ringtone ringtone = ReflectionHelpers.getField(service, "ringtone");
        assertNotNull(ringtone);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS);
        assertTrue(ringtone.getVolume() < 1f);

        startAlarm(1002, "第二个闹钟", 5, false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(24, TimeUnit.SECONDS);

        assertEquals(1f, ringtone.getVolume(), 0.001f);
    }

    @Test
    @Config(shadows = FailSecondSnoozeAlarmManager.class)
    public void failedSnoozeKeepsOnlyUnscheduledAlarmRinging() {
        startAlarm(1001, "第一个闹钟");
        startAlarm(1002, "第二个闹钟");
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                activityIntent("多个闹钟", 1001, 1002)).setup();

        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_SNOOZE).putExtra("alarm_id", 1001)
                .putExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS, new int[]{1001, 1002}), 0, 3);

        assertEquals(1, AlarmStore.find(context, 1001).snoozeCount);
        assertTrue(AlarmStore.find(context, 1001).pendingSnoozeAt > 0L);
        assertEquals(0, AlarmStore.find(context, 1002).snoozeCount);
        assertEquals(0L, AlarmStore.find(context, 1002).pendingSnoozeAt);
        assertFalse(activityController.get().isFinishing());
        assertTrue(hasText(activityController.get().getWindow().getDecorView(), "第二个闹钟"));
    }

    @Implements(AlarmManager.class)
    public static class FailSecondSnoozeAlarmManager extends ShadowAlarmManager {
        @Implementation protected void setAlarmClock(AlarmManager.AlarmClockInfo info,
                                                     PendingIntent operation) {
            Intent intent = Shadows.shadowOf(operation).getSavedIntent();
            if (intent.getBooleanExtra("snooze", false)
                    && intent.getIntExtra("alarm_id", -1) == 1002) {
                throw new SecurityException("Simulated per-alarm scheduling failure");
            }
            super.setAlarmClock(info, operation);
        }
    }

    @Implements(RingtoneManager.class)
    public static class PlayableRingtoneManager {
        @Implementation protected static Ringtone getRingtone(Context context, Uri uri) {
            // No device audio library exists in a local test. Use a deterministic
            // Ringtone with its real volume state to verify the queued ramp.
            return ReflectionHelpers.callConstructor(Ringtone.class,
                    ReflectionHelpers.ClassParameter.from(Context.class, context),
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
        }
    }

    private void startAlarm(int id, String label) {
        startAlarm(id, label, 10, false);
    }

    private void startAlarm(int id, String label, int duration, boolean gradual) {
        service.onStartCommand(new Intent(context, AlarmService.class)
                .putExtra("alarm_id", id).putExtra("label", label)
                .putExtra("vibrate", false).putExtra("gradual", gradual)
                .putExtra("duration", duration), 0, id);
    }

    private Intent activityIntent(String label, int... ids) {
        return new Intent(context, AlarmActivity.class).putExtra("alarm_id", ids[0])
                .putExtra("label", label).putExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS, ids);
    }

    private static boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }
}
