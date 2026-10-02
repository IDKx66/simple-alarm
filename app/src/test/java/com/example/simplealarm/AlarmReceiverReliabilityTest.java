package com.example.simplealarm;

import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.junit.After;
import java.util.Calendar;
import java.util.Collections;
import java.util.TimeZone;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AlarmReceiverReliabilityTest {
    private Context context;
    private Alarm alarm;
    private TimeZone originalTimeZone;

    @Before public void setUp() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        alarm = new Alarm(1000, 7, 0, "一次性", false, 0);
        alarm.snoozeCount = 1;
        alarm.pendingSnoozeAt = System.currentTimeMillis() + 300000L;
        AlarmStore.save(context, Collections.singletonList(alarm));
    }

    @After public void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test public void editingTimeRejectsAlreadyQueuedRegularDelivery() {
        long dueAt = enableRegularAlarm();
        alarm.hour = 9;
        AlarmStore.save(context, Collections.singletonList(alarm));
        AlarmScheduler.schedule(context, alarm);

        assertRegularRejected(dueAt);
    }

    @Test public void editingRepeatDaysRejectsAlreadyQueuedRegularDelivery() {
        long dueAt = enableRegularAlarm();
        Calendar due = Calendar.getInstance();
        due.setTimeInMillis(dueAt);
        int mondayIndex = (due.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        alarm.daysMask = 1 << ((mondayIndex + 1) % 7);
        AlarmStore.save(context, Collections.singletonList(alarm));
        AlarmScheduler.schedule(context, alarm);

        assertRegularRejected(dueAt);
    }

    @Test public void skippingOccurrenceRejectsAlreadyQueuedRegularDelivery() {
        long dueAt = enableRegularAlarm();
        alarm.skippedOccurrence = dueAt;
        AlarmStore.save(context, Collections.singletonList(alarm));
        AlarmScheduler.schedule(context, alarm);

        assertRegularRejected(dueAt);
    }

    @Test public void changingTimeZoneRejectsOldWallClockDelivery() {
        long dueAt = enableRegularAlarm();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        AlarmScheduler.schedule(context, alarm);

        assertRegularRejected(dueAt);
    }

    @Test public void delayedRegularDeliveryStillRingsAfterNextOccurrenceWasRescheduled() {
        enableRegularAlarm();
        long dueAt = AlarmScheduler.nextTrigger(alarm,
                System.currentTimeMillis() - 2 * 24 * 60 * 60_000L);
        assertTrue(dueAt < System.currentTimeMillis());
        AlarmScheduler.schedule(context, alarm);

        new AlarmReceiver().onReceive(context, regular(dueAt));

        assertNotNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        assertTrue(AlarmStore.find(context, alarm.id).enabled);
    }

    @Test public void newTimeZoneDeliveryStillRings() {
        enableRegularAlarm();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        long dueAt = AlarmScheduler.nextTrigger(alarm, System.currentTimeMillis());

        new AlarmReceiver().onReceive(context, regular(dueAt));

        assertNotNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @Test public void staleSnoozeDeliveryDoesNotStartRinging() {
        new AlarmReceiver().onReceive(context, snooze(alarm.pendingSnoozeAt - 1));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        assertEquals(alarm.pendingSnoozeAt, AlarmStore.find(context, alarm.id).pendingSnoozeAt);
    }

    @Test public void cancelledSnoozeRejectsLegacyDeliveryWithoutTimestamp() {
        alarm.pendingSnoozeAt = 0L;
        AlarmStore.save(context, Collections.singletonList(alarm));
        AlarmScheduler.cancelSnooze(context, alarm.id);
        new AlarmReceiver().onReceive(context, snooze(0L));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        assertEquals(0L, AlarmStore.find(context, alarm.id).pendingSnoozeAt);
    }

    @Test public void snoozeIsConsumedOnceAndNotRestoredAfterDelivery() {
        Intent intent = snooze(alarm.pendingSnoozeAt);
        new AlarmReceiver().onReceive(context, intent);
        assertNotNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        assertEquals(0L, AlarmStore.find(context, alarm.id).pendingSnoozeAt);
        new AlarmReceiver().onReceive(context, intent);
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        AlarmScheduler.rescheduleAll(context);
        assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager.class))
                .getScheduledAlarms().isEmpty());
    }

    @Test public void failedServiceStartPreservesPendingSnoozeForRecovery() {
        Context failing = new ContextWrapper(context) {
            @Override public ComponentName startForegroundService(Intent service) {
                throw new SecurityException("service start rejected");
            }
        };
        new AlarmReceiver().onReceive(failing, snooze(alarm.pendingSnoozeAt));
        assertEquals(alarm.pendingSnoozeAt, AlarmStore.find(context, alarm.id).pendingSnoozeAt);
    }

    @Test public void entireRingingPathSupportsDirectBootAndIsPrivate() throws Exception {
        PackageManager pm = context.getPackageManager();
        assertTrue(pm.getReceiverInfo(new ComponentName(context, BootReceiver.class), 0).directBootAware);
        assertTrue(pm.getReceiverInfo(new ComponentName(context, AlarmReceiver.class), 0).directBootAware);
        assertFalse(pm.getReceiverInfo(new ComponentName(context, AlarmReceiver.class), 0).exported);
        assertTrue(pm.getServiceInfo(new ComponentName(context, AlarmService.class), 0).directBootAware);
        assertFalse(pm.getServiceInfo(new ComponentName(context, AlarmService.class), 0).exported);
        assertTrue(pm.getActivityInfo(new ComponentName(context, AlarmActivity.class), 0).directBootAware);
        assertFalse(pm.getActivityInfo(new ComponentName(context, AlarmActivity.class), 0).exported);
    }

    private Intent snooze(long dueAt) {
        return new Intent(context, AlarmReceiver.class).putExtra("alarm_id", alarm.id)
                .putExtra("snooze", true).putExtra(AlarmScheduler.EXTRA_DUE_AT, dueAt);
    }

    private long enableRegularAlarm() {
        alarm.enabled = true;
        alarm.daysMask = 127;
        alarm.pendingSnoozeAt = 0L;
        AlarmStore.save(context, Collections.singletonList(alarm));
        long dueAt = AlarmScheduler.nextTrigger(alarm, System.currentTimeMillis());
        AlarmScheduler.schedule(context, alarm);
        return dueAt;
    }

    private Intent regular(long dueAt) {
        return new Intent(context, AlarmReceiver.class).putExtra("alarm_id", alarm.id)
                .putExtra(AlarmScheduler.EXTRA_DUE_AT, dueAt);
    }

    private void assertRegularRejected(long dueAt) {
        new AlarmReceiver().onReceive(context, regular(dueAt));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        assertTrue(AlarmStore.find(context, alarm.id).enabled);
    }
}
