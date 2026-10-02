package com.example.simplealarm;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.UserManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import java.util.Collections;
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
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MainScreenRingingAccessTest {
    private Context context;
    private ActivityController<MainActivity> mainController;
    private ActivityController<AlarmActivity> ringingController;
    private ServiceController<AlarmService> serviceController;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        context.getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().clear().commit();
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().clear().commit();
        AlarmStore.save(context, Collections.singletonList(
                new Alarm(1000, 7, 30, "早起", false, 0)));
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        Shadows.shadowOf(context.getSystemService(NotificationManager.class))
                .setNotificationsEnabled(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    @After public void tearDown() {
        if (ringingController != null) ringingController.pause().stop().destroy();
        if (mainController != null) mainController.pause().stop().destroy();
        if (serviceController != null) serviceController.destroy();
        AlarmStore.save(context, Collections.emptyList());
    }

    @Test public void deniedNotificationPermissionStillProvidesWorkingTestAlarmControls() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        MainActivity main = openHome();
        Intent requested = requestTestAlarm(main);
        assertNull("服务尚未准备好时不能打开会立即关闭的响铃页",
                Shadows.shadowOf(main).getNextStartedActivity());

        startService(requested);
        Intent controls = requireControls(main, -777);
        ringingController = Robolectric.buildActivity(AlarmActivity.class, controls).setup();
        idleMain();
        AlarmActivity ringing = ringingController.get();
        assertFalse(ringing.isFinishing());
        View stop = findText(ringing.getWindow().getDecorView(), "停止闹钟");
        assertNotNull(stop);
        stop.performClick();
        Intent command = Shadows.shadowOf(ringing).getNextStartedService();
        assertNotNull(command);
        assertEquals(AlarmService.ACTION_STOP, command.getAction());
        serviceController.get().onStartCommand(command, 0, 2);
        idleMain();
        assertNull(AlarmService.ringingState());
        assertTrue(ringing.isFinishing());
    }

    @Test public void blockedAlarmChannelStillOpensReadyTestAlarmControls() {
        context.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(AlarmService.CHANNEL, "闹钟响铃",
                        NotificationManager.IMPORTANCE_NONE));
        MainActivity main = openHome();
        Intent requested = requestTestAlarm(main);
        assertNull(Shadows.shadowOf(main).getNextStartedActivity());

        startService(requested);

        assertEquals(NotificationManager.IMPORTANCE_NONE,
                context.getSystemService(NotificationManager.class)
                        .getNotificationChannel(AlarmService.CHANNEL).getImportance());
        requireControls(main, -777);
    }

    @Test @Config(sdk = 28)
    public void normalNotificationSettingsStillOpenTestAlarmControls() {
        MainActivity main = openHome();
        startService(requestTestAlarm(main));
        requireControls(main, -777);
    }

    @Test public void alreadyRingingAlarmOpensControlsWhenHomeResumes() {
        startService(ringingIntent(1000));
        MainActivity main = openHome();
        requireControls(main, 1000);
    }

    @Test public void pausedHomeWaitsUntilResumeBeforeOpeningControls() {
        MainActivity main = openHome();
        mainController.pause();
        startService(ringingIntent(1000));
        assertNull("后台首页不能反复拉起响铃页",
                Shadows.shadowOf(main).getNextStartedActivity());

        mainController.resume();
        idleMain();

        requireControls(main, 1000);
    }

    @Test public void ringingUpdatesDoNotOpenDuplicateControlPages() {
        MainActivity main = openHome();
        startService(ringingIntent(1000));
        requireControls(main, 1000);
        serviceController.get().onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_VIBRATION).putExtra("alarm_id", 1000)
                .putExtra("vibrate", false), 0, 2);
        serviceController.get().onStartCommand(ringingIntent(1001), 0, 3);
        idleMain();

        assertNull("振动或合并状态更新不能重复打开控制页",
                Shadows.shadowOf(main).getNextStartedActivity());
    }

    @Test public void stoppedAlarmDoesNotOpenAStalePageWhenHomeResumes() {
        MainActivity main = openHome();
        mainController.pause();
        startService(ringingIntent(1000));
        serviceController.get().onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_STOP).putExtra("alarm_id", 1000), 0, 2);
        mainController.resume();
        idleMain();

        assertNull(AlarmService.ringingState());
        assertNull(Shadows.shadowOf(main).getNextStartedActivity());
    }

    private MainActivity openHome() {
        mainController = Robolectric.buildActivity(MainActivity.class).setup();
        idleMain();
        return mainController.get();
    }

    private Intent requestTestAlarm(MainActivity activity) {
        View settings = findDescription(activity.getWindow().getDecorView(), "闹钟设置");
        assertNotNull(settings);
        settings.performClick();
        AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
        ListView choices = menu.getListView();
        boolean clicked = false;
        for (int i = 0; i < choices.getAdapter().getCount(); i++) {
            if ("测试响铃".equals(choices.getAdapter().getItem(i).toString())) {
                choices.performItemClick(null, i, choices.getAdapter().getItemId(i));
                clicked = true;
                break;
            }
        }
        assertTrue(clicked);
        idleMain();
        Intent requested = Shadows.shadowOf(activity).getNextStartedService();
        assertNotNull(requested);
        assertEquals(AlarmService.class.getName(), requested.getComponent().getClassName());
        assertEquals(-777, requested.getIntExtra("alarm_id", -1));
        return requested;
    }

    private void startService(Intent intent) {
        if (serviceController == null) {
            serviceController = Robolectric.buildService(AlarmService.class).create();
        }
        serviceController.get().onStartCommand(intent, 0, 1);
        idleMain();
        assertNotNull("本用例要求服务已进入响铃状态", AlarmService.ringingState());
    }

    private Intent ringingIntent(int id) {
        return new Intent(context, AlarmService.class).putExtra("alarm_id", id)
                .putExtra("label", "响铃测试").putExtra("vibrate", true)
                .putExtra("gradual", false).putExtra("duration", 1);
    }

    private static Intent requireControls(MainActivity activity, int id) {
        Intent controls = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("首页应在服务准备好后直接打开停止/稍后提醒控制页", controls);
        assertNotNull(controls.getComponent());
        assertEquals(AlarmActivity.class.getName(), controls.getComponent().getClassName());
        assertEquals(id, controls.getIntExtra("alarm_id", -1));
        assertNotNull(AlarmService.ringingState());
        return controls;
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static View findDescription(View view, String description) {
        if (view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
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
