package com.example.simplealarm;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
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
        findText(activity.getWindow().getDecorView(), "权限检查").performClick();
        assertTrue(Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage()
                .toString().contains("通知：未允许"));
    }

    @Test public void permissionPageDetectsBlockedAlarmChannel() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        NotificationManager notifications = activity.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(AlarmService.CHANNEL,
                "闹钟响铃", NotificationManager.IMPORTANCE_NONE));
        findText(activity.getWindow().getDecorView(), "权限检查").performClick();
        assertTrue(Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage()
                .toString().contains("通知：未允许"));
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

    private static View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
