package com.example.simplealarm;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.UserManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AlarmRecoveryTest {
    private Context context;
    private ShadowAlarmManager alarms;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        context.getSharedPreferences("alarms", 0).edit().clear().commit();
        context.createDeviceProtectedStorageContext().getSharedPreferences("alarms", 0)
                .edit().clear().commit();
        alarms = Shadows.shadowOf(context.getSystemService(AlarmManager.class));
    }

    @Test public void rebootRestoresDisabledOneShotSnooze() throws Exception {
        Alarm alarm = new Alarm(1000, 7, 0, "一次性", false, 0);
        alarm.pendingSnoozeAt = System.currentTimeMillis() + 300000L;
        alarm.snoozeCount = 1;
        AlarmStore.save(context, Collections.singletonList(alarm));

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertEquals(1, alarms.getScheduledAlarms().size());
        Intent delivered = Shadows.shadowOf(alarms.getScheduledAlarms().get(0).operation)
                .getSavedIntent();
        assertTrue(delivered.getBooleanExtra("snooze", false));
        assertEquals(alarm.pendingSnoozeAt,
                delivered.getLongExtra(AlarmScheduler.EXTRA_DUE_AT, 0));
    }

    @Test public void recoveryKeepsOverdueSnoozeTokenAndDoesNotDuplicateIt() {
        Alarm alarm = new Alarm(1000, 7, 0, "过期稍后提醒", false, 0);
        alarm.pendingSnoozeAt = System.currentTimeMillis() - 60000L;
        AlarmStore.save(context, Collections.singletonList(alarm));

        AlarmScheduler.rescheduleAll(context);
        AlarmScheduler.rescheduleAll(context);

        assertEquals(1, alarms.getScheduledAlarms().size());
        ShadowAlarmManager.ScheduledAlarm scheduled = alarms.getScheduledAlarms().get(0);
        assertEquals(alarm.pendingSnoozeAt, scheduled.triggerAtTime);
        assertEquals(alarm.pendingSnoozeAt, Shadows.shadowOf(scheduled.operation)
                .getSavedIntent().getLongExtra(AlarmScheduler.EXTRA_DUE_AT, 0));
        assertEquals(alarm.pendingSnoozeAt, AlarmStore.find(context, alarm.id).pendingSnoozeAt);
    }

    @Test public void recoverySchedulesRegularAndSnoozeIndependently() {
        Alarm alarm = new Alarm(1000, 7, 0, "每天", true, 127);
        alarm.pendingSnoozeAt = System.currentTimeMillis() + 300000L;
        AlarmStore.save(context, Collections.singletonList(alarm));
        AlarmScheduler.rescheduleAll(context);
        assertEquals(2, alarms.getScheduledAlarms().size());
    }

    @Test public void unknownBootActionDoesNotReschedule() {
        AlarmStore.save(context, Collections.singletonList(
                new Alarm(1000, 7, 0, "每天", true, 127)));
        new BootReceiver().onReceive(context, new Intent("untrusted.action"));
        assertTrue(alarms.getScheduledAlarms().isEmpty());
    }

    @Test public void appUpdateRestoresAlarms() {
        AlarmStore.save(context, Collections.singletonList(
                new Alarm(1000, 7, 0, "每天", true, 127)));
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertEquals(1, alarms.getScheduledAlarms().size());
    }

    @Test public void migratesAlarmBackupAndNextIdToDeviceStorage() throws Exception {
        Alarm alarm = new Alarm(1000, 7, 0, "旧数据", true, 127);
        String json = "[" + alarm.toJson() + "]";
        context.getSharedPreferences("alarms", 0).edit()
                .putString("items", json).putString("items_backup", json)
                .putInt("next_id", 4321).commit();

        assertEquals("旧数据", AlarmStore.load(context).get(0).label);
        SharedPreferences device = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("alarms", 0);
        assertEquals(json, device.getString("items", null));
        assertEquals(json, device.getString("items_backup", null));
        assertEquals(4321, AlarmStore.nextId(context));
        device.edit().putString("items", "broken").commit();
        assertEquals("旧数据", AlarmStore.load(context).get(0).label);
    }

    @Test public void lockedBootReadsDeviceStorageAndRestoresSnooze() {
        Alarm alarm = new Alarm(1000, 7, 0, "锁定前", false, 0);
        alarm.pendingSnoozeAt = System.currentTimeMillis() + 300000L;
        AlarmStore.save(context, Collections.singletonList(alarm));
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(false);
        Context device = context.createDeviceProtectedStorageContext();

        assertEquals(1, AlarmStore.load(device).size());
        new BootReceiver().onReceive(device, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        assertEquals(1, Shadows.shadowOf(device.getSystemService(AlarmManager.class))
                .getScheduledAlarms().size());
    }

    @Test public void disabledAlarmWithoutSnoozeStaysDisabled() {
        AlarmStore.save(context, Collections.singletonList(
                new Alarm(1000, 7, 0, "已关闭", false, 0)));
        AlarmScheduler.rescheduleAll(context);
        assertTrue(alarms.getScheduledAlarms().isEmpty());
    }
}
