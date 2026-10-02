package com.example.simplealarm;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.UserManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowAlarmManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MainScreenUiTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        context.getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().clear().commit();
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().clear().commit();
        Shadows.shadowOf(context.getSystemService(NotificationManager.class))
                .setNotificationsEnabled(true);
    }

    @Test public void homeKeepsToolsInSettingsAndRemovesBackup() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        View home = activity.getWindow().getDecorView();

        assertNotNull(findDescription(home, "添加闹钟"));
        assertNull("权限检查不应固定占据首页", findText(home, "权限检查"));
        assertNull(findTextContaining(home, "备份"));
        assertNull(findTextContaining(home, "恢复"));
        findDescription(home, "闹钟设置").performClick();
        ListView menu = ShadowAlertDialog.getLatestAlertDialog().getListView();
        List<String> items = new ArrayList<>();
        for (int i = 0; i < menu.getAdapter().getCount(); i++) {
            items.add(menu.getAdapter().getItem(i).toString());
        }
        assertEquals(4, items.size());
        assertTrue(items.contains("权限检查"));
        assertTrue(items.contains("闹钟音量"));
        assertTrue(items.contains("测试响铃"));
        assertTrue(items.contains("检查更新"));
    }

    @Test public void healthyPermissionsDoNotShowHomeWarning() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        View warning = findDescription(activity.getWindow().getDecorView(), "响铃权限提示");
        assertNotNull(warning);
        assertEquals(View.GONE, warning.getVisibility());
    }

    @Test public void missingNotificationsShowClickableWarningAndRefreshOnReturn() {
        Shadows.shadowOf(context.getSystemService(NotificationManager.class))
                .setNotificationsEnabled(false);
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        View warning = findDescription(activity.getWindow().getDecorView(), "响铃权限提示");

        assertEquals(View.VISIBLE, warning.getVisibility());
        warning.performClick();
        assertTrue(Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage()
                .toString().contains("通知：未允许"));
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        controller.pause();
        Shadows.shadowOf(context.getSystemService(NotificationManager.class))
                .setNotificationsEnabled(true);
        controller.resume();
        assertEquals(View.GONE, warning.getVisibility());
    }

    @Test @Config(sdk = 35)
    public void missingPermissionsDoNotInterruptHomeWithAutomaticRequests() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false);
        Shadows.shadowOf(context.getSystemService(NotificationManager.class))
                .setNotificationsEnabled(false);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        assertEquals(View.VISIBLE, findDescription(activity.getWindow().getDecorView(),
                "响铃权限提示").getVisibility());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void addAlarmSavesAndRefreshesHome() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        findDescription(activity.getWindow().getDecorView(), "添加闹钟").performClick();
        idleMain();
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        TimePicker time = findType(editor.getWindow().getDecorView(), TimePicker.class);
        time.setHour(7);
        time.setMinute(15);
        findEditableLabel(editor.getWindow().getDecorView()).setText("早起散步");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idleMain();

        List<Alarm> alarms = AlarmStore.load(context);
        assertEquals(1, alarms.size());
        assertEquals("早起散步", alarms.get(0).label);
        assertEquals(7, alarms.get(0).hour);
        assertEquals(15, alarms.get(0).minute);
        assertTrue(alarms.get(0).enabled);
        assertFalse(editor.isShowing());
        assertNotNull(findText(activity.getWindow().getDecorView(), "07:15"));
        assertNotNull(findTextContaining(activity.getWindow().getDecorView(), "早起散步"));
        assertEquals(1, Shadows.shadowOf(context.getSystemService(AlarmManager.class))
                .getScheduledAlarms().size());
    }

    @Test public void editingNameKeepsDisabledAlarmOffAndChangingTimeEnablesIt() {
        Alarm alarm = new Alarm(1000, 7, 15, "早起", false, 0);
        AlarmStore.save(context, Collections.singletonList(alarm));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        findClickableText(activity.getWindow().getDecorView(), "07:15").performClick();
        idleMain();
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        findEditableLabel(editor.getWindow().getDecorView()).setText("散步");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idleMain();

        assertEquals("散步", AlarmStore.find(context, alarm.id).label);
        assertFalse(AlarmStore.find(context, alarm.id).enabled);
        assertNotNull(findTextContaining(activity.getWindow().getDecorView(), "散步"));
        findClickableText(activity.getWindow().getDecorView(), "07:15").performClick();
        idleMain();
        editor = ShadowAlertDialog.getLatestAlertDialog();
        findType(editor.getWindow().getDecorView(), TimePicker.class).setMinute(30);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idleMain();

        assertEquals(1, AlarmStore.load(context).size());
        assertEquals(30, AlarmStore.find(context, alarm.id).minute);
        assertTrue(AlarmStore.find(context, alarm.id).enabled);
        assertNotNull(findText(activity.getWindow().getDecorView(), "07:30"));
        assertNull(findText(activity.getWindow().getDecorView(), "07:15"));
    }

    @Test public void togglingAlarmPersistsAndRefreshesSchedule() {
        Alarm alarm = new Alarm(1000, 7, 15, "早起", true, 127);
        AlarmStore.save(context, Collections.singletonList(alarm));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Switch enabled = findType(activity.getWindow().getDecorView(), Switch.class);
        assertTrue(enabled.isChecked());
        enabled.setChecked(false);

        assertFalse(AlarmStore.find(context, alarm.id).enabled);
        assertFalse(findType(activity.getWindow().getDecorView(), Switch.class).isChecked());
        assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager.class))
                .getScheduledAlarms().isEmpty());
        findType(activity.getWindow().getDecorView(), Switch.class).setChecked(true);
        assertTrue(AlarmStore.find(context, alarm.id).enabled);
        assertTrue(findType(activity.getWindow().getDecorView(), Switch.class).isChecked());
        assertEquals(1, Shadows.shadowOf(context.getSystemService(AlarmManager.class))
                .getScheduledAlarms().size());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void exportActualViewsWhenPreviewIsRequested() throws Exception {
        Assume.assumeTrue("设置 SIMPLE_ALARM_UI_PREVIEW=1 才生成实际 View 预览",
                "1".equals(System.getenv("SIMPLE_ALARM_UI_PREVIEW")));
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-mdpi");
        AlarmStore.save(context, Arrays.asList(
                new Alarm(1000, 7, 0, "早起散步", true, 31),
                new Alarm(1001, 8, 30, "慢慢开始周末", false, 96),
                new Alarm(1002, 12, 0, "午间休息", true, 127)));
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        idleMain();
        capture(activity.findViewById(android.R.id.content), "home", 390, 844);
        findClickableText(activity.getWindow().getDecorView(), "07:00").performClick();
        idleMain();
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        capture(editor.getWindow().getDecorView(), "editor", 390, 844);
        captureEditorOptions(editor, "editor", 390, 844);
        editor.dismiss();
        controller.pause().stop().destroy();

        Shadows.shadowOf(context.getSystemService(NotificationManager.class))
                .setNotificationsEnabled(false);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        idleMain();
        assertEquals(View.VISIBLE, findDescription(controller.get().getWindow().getDecorView(),
                "响铃权限提示").getVisibility());
        capture(controller.get().findViewById(android.R.id.content), "permission-warning", 390, 844);
        controller.pause().stop().destroy();
        Shadows.shadowOf(context.getSystemService(NotificationManager.class))
                .setNotificationsEnabled(true);

        AlarmStore.save(context, Collections.emptyList());
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        idleMain();
        capture(controller.get().findViewById(android.R.id.content), "empty", 390, 844);
        controller.pause().stop().destroy();

        RuntimeEnvironment.setQualifiers("w320dp-h640dp-mdpi");
        RuntimeEnvironment.setFontScale(1.5f);
        AlarmStore.save(context, Collections.singletonList(
                new Alarm(1000, 7, 0, "早起散步", true, 31)));
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        idleMain();
        capture(activity.findViewById(android.R.id.content), "home-narrow-large-font", 320, 640);
        findClickableText(activity.getWindow().getDecorView(), "07:00").performClick();
        idleMain();
        editor = ShadowAlertDialog.getLatestAlertDialog();
        capture(editor.getWindow().getDecorView(), "editor-narrow-large-font", 320, 640);
        captureEditorOptions(editor, "editor-narrow-large-font", 320, 640);
        editor.dismiss();
        controller.pause().stop().destroy();
    }

    private static void captureEditorOptions(AlertDialog editor, String name,
            int width, int height) throws Exception {
        View decor = editor.getWindow().getDecorView();
        View ancestor = findEditableLabel(decor);
        while (!(ancestor instanceof ScrollView)) ancestor = (View) ancestor.getParent();
        ScrollView scroll = (ScrollView) ancestor;
        View soundHeading = findText(decor, "声音与振动");
        Rect position = new Rect();
        soundHeading.getDrawingRect(position);
        scroll.offsetDescendantRectToMyCoords(soundHeading, position);
        scroll.scrollTo(0, position.top);
        idleMain();
        capture(decor, name + "-options", width, height);
        assertLabelFits((TextView) findText(decor, "试听铃声"));
        assertLabelFits((TextView) findText(decor, "默认铃声"));
        assertFullyVisible(editor.getButton(AlertDialog.BUTTON_POSITIVE));
        assertFullyVisible(editor.getButton(AlertDialog.BUTTON_NEGATIVE));

        // Large font settings cannot show every option at once. Capture the
        // bottom as well so snooze/duration controls and fixed dialog actions
        // can be inspected without mistaking ordinary scrolling for clipping.
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        idleMain();
        capture(decor, name + "-bottom", width, height);
        List<Spinner> choices = new ArrayList<>();
        collectTypes(decor, Spinner.class, choices);
        assertEquals(4, choices.size());
        for (Spinner choice : choices) {
            assertLabelFits((TextView) choice.getSelectedView());
        }
        assertFullyVisible(editor.getButton(AlertDialog.BUTTON_POSITIVE));
        assertFullyVisible(editor.getButton(AlertDialog.BUTTON_NEGATIVE));
    }

    private static void assertLabelFits(TextView label) {
        assertNotNull(label);
        assertNotNull(label.getLayout());
        int availableWidth = label.getWidth() - label.getCompoundPaddingLeft()
                - label.getCompoundPaddingRight();
        int availableHeight = label.getHeight() - label.getCompoundPaddingTop()
                - label.getCompoundPaddingBottom();
        assertTrue(label.getText() + " 的文字高度不应裁切",
                label.getLayout().getHeight() <= availableHeight);
        for (int line = 0; line < label.getLayout().getLineCount(); line++) {
            assertEquals(label.getText() + " 不应省略", 0,
                    label.getLayout().getEllipsisCount(line));
            assertTrue(label.getText() + " 的文字宽度不应裁切",
                    label.getLayout().getLineWidth(line) <= availableWidth + 1);
        }
    }

    private static void assertFullyVisible(View view) {
        Rect visible = new Rect();
        assertTrue(view.getGlobalVisibleRect(visible));
        assertEquals(view.getWidth(), visible.width());
        assertEquals(view.getHeight(), visible.height());
    }

    private static <T extends View> void collectTypes(View view, Class<T> type,
            List<T> result) {
        if (type.isInstance(view)) result.add(type.cast(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTypes(group.getChildAt(i), type, result);
            }
        }
    }

    private static void capture(View root, String name, int width, int height) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < height; y += 10) {
            for (int x = 0; x < width; x += 10) colors.add(bitmap.getPixel(x, y));
        }
        assertTrue("原生绘制应包含界面内容而非空白位图", colors.size() > 3);
        File workspace = new File(System.getProperty("user.dir"));
        while (!new File(workspace, "settings.gradle").isFile()
                && workspace.getParentFile() != null) workspace = workspace.getParentFile();
        File directory = new File(workspace, "build/ui-preview");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static EditText findEditableLabel(View view) {
        if (view instanceof EditText && ((EditText) view).getHint() != null
                && "闹钟名称".contentEquals(((EditText) view).getHint())) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditableLabel(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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

    private static View findClickableText(View view, String text) {
        if (view instanceof TextView && view.isClickable()
                && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findTextContaining(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTextContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T findType(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findType(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
