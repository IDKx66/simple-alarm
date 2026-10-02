package com.example.simplealarm;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AlarmPermissionReliabilityTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
    }

    @Test public void permissionPageDetectsDisabledAppNotifications() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Shadows.shadowOf(activity.getSystemService(NotificationManager.class))
                .setNotificationsEnabled(false);
        openPermissionPage(activity);
        assertTrue(Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage()
                .toString().contains("通知：未允许"));
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent settings = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.getAction());
        assertEquals(activity.getPackageName(),
                settings.getStringExtra(Settings.EXTRA_APP_PACKAGE));
    }

    @Test public void permissionPageDetectsBlockedAlarmChannel() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        NotificationManager notifications = activity.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(AlarmService.CHANNEL,
                "闹钟响铃", NotificationManager.IMPORTANCE_NONE));
        openPermissionPage(activity);
        assertTrue(Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage()
                .toString().contains("通知：未允许"));
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent settings = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, settings.getAction());
        assertEquals(activity.getPackageName(),
                settings.getStringExtra(Settings.EXTRA_APP_PACKAGE));
        assertEquals(AlarmService.CHANNEL,
                settings.getStringExtra(Settings.EXTRA_CHANNEL_ID));
    }

    @Test @Config(sdk = 35) public void permissionDenialPreservesSnoozeUntilGrant() {
        Alarm alarm = new Alarm(1000, 7, 0, "每天", true, 127);
        alarm.pendingSnoozeAt = System.currentTimeMillis() + 300000L;
        AlarmStore.save(context, Collections.singletonList(alarm));
        ShadowAlarmManager.setCanScheduleExactAlarms(false);
        AlarmScheduler.rescheduleAll(context);
        assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager.class))
                .getScheduledAlarms().isEmpty());
        assertEquals(alarm.pendingSnoozeAt, AlarmStore.find(context, alarm.id).pendingSnoozeAt);
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        new BootReceiver().onReceive(context,
                new Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED));
        assertEquals(2, Shadows.shadowOf(context.getSystemService(AlarmManager.class))
                .getScheduledAlarms().size());
    }

    private static void openPermissionPage(MainActivity activity) {
        View settings = findDescription(activity.getWindow().getDecorView(), "闹钟设置");
        assertNotNull("首页应保留设置入口", settings);
        settings.performClick();
        ListView choices = ShadowAlertDialog.getLatestAlertDialog().getListView();
        for (int i = 0; i < choices.getAdapter().getCount(); i++) {
            if ("权限检查".contentEquals(choices.getAdapter().getItem(i).toString())) {
                choices.performItemClick(null, i, choices.getAdapter().getItemId(i));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                return;
            }
        }
        fail("设置菜单应包含权限检查");
    }

    private static View findDescription(View view, String text) {
        if (view.getContentDescription() != null
                && text.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
