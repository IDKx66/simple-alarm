package com.example.simplealarm;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.UserManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Assume;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RingingScreenUiTest {
    private Context context;
    private ServiceController<AlarmService> serviceController;
    private ActivityController<AlarmActivity> activityController;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        context.getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().clear().commit();
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        closeRinging();
        AlarmStore.save(context, Collections.emptyList());
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test public void clockAndControlsFitStandardPhone() {
        AlarmActivity activity = openRinging(390, 844, 1f);
        assertReadableLayout(activity);
    }

    @Test public void largeFontOnNarrowPhoneKeepsClockOnOneLineAndControlsReachable() {
        AlarmActivity activity = openRinging(320, 640, 1.5f);
        assertReadableLayout(activity);
    }

    @Test public void exportActualRingingViewsWhenPreviewIsRequested() throws Exception {
        Assume.assumeTrue("设置 SIMPLE_ALARM_UI_PREVIEW=1 才生成实际 View 预览",
                "1".equals(System.getenv("SIMPLE_ALARM_UI_PREVIEW")));
        AlarmActivity activity = openRinging(390, 844, 1f);
        capture(activity.findViewById(android.R.id.content), "ringing", 390, 844);
        assertReadableLayout(activity);
        closeRinging();

        activity = openRinging(320, 640, 1.5f);
        capture(activity.findViewById(android.R.id.content),
                "ringing-narrow-large-font", 320, 640);
        assertReadableLayout(activity);
    }

    private AlarmActivity openRinging(int width, int height, float fontScale) {
        RuntimeEnvironment.setQualifiers("w" + width + "dp-h" + height + "dp-mdpi");
        RuntimeEnvironment.setFontScale(fontScale);
        Alarm alarm = new Alarm(1001, 7, 0, "早起散步", true, 31);
        AlarmStore.save(context, Collections.singletonList(alarm));
        serviceController = Robolectric.buildService(AlarmService.class).create();
        serviceController.get().onStartCommand(new Intent(context, AlarmService.class)
                .putExtra("alarm_id", alarm.id).putExtra("label", alarm.label)
                .putExtra("vibrate", true).putExtra("gradual", false)
                .putExtra("duration", alarm.ringDurationMinutes), 0, 1);
        activityController = Robolectric.buildActivity(AlarmActivity.class,
                new Intent(context, AlarmActivity.class).putExtra("alarm_id", alarm.id)).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlarmActivity activity = activityController.get();
        measure(activity.findViewById(android.R.id.content), width, height);
        return activity;
    }

    private void closeRinging() {
        if (activityController != null) {
            activityController.pause().stop().destroy();
            activityController = null;
        }
        if (serviceController != null) {
            serviceController.destroy();
            serviceController = null;
        }
    }

    private static void assertReadableLayout(AlarmActivity activity) {
        View content = activity.findViewById(android.R.id.content);
        TextView time = ReflectionHelpers.getField(activity, "timeView");
        assertNotNull(time.getLayout());
        assertEquals("大号时间必须保持完整的一行", 1, time.getLineCount());
        assertTrue("时间不能依赖横向滚动显示",
                time.getLayout().getLineWidth(0)
                        <= time.getWidth() - time.getTotalPaddingLeft()
                        - time.getTotalPaddingRight() + 1);
        Button stop = findText(content, "停止闹钟", Button.class);
        Button snooze = ReflectionHelpers.getField(activity, "snoozeButton");
        Switch vibration = ReflectionHelpers.getField(activity, "vibrationSwitch");
        assertNotNull(stop);
        assertTrue(snooze.isEnabled());
        ScrollView scroll = findType(content, ScrollView.class);
        for (TextView control : new TextView[]{stop, snooze, vibration}) {
            ViewGroup parent = (ViewGroup) control.getParent();
            assertTrue("控件触控区域不可横向裁切", control.getLeft() >= 0
                    && control.getRight() <= parent.getWidth());
            assertTrue("控件必须完整保留在可滚动内容内", control.getTop() >= 0
                    && control.getBottom() <= parent.getHeight());
            assertTrue("控件文字不能横向裁切",
                    control.getLayout().getLineWidth(0)
                            <= control.getWidth() - control.getTotalPaddingLeft()
                            - control.getTotalPaddingRight() + 1);
            control.requestRectangleOnScreen(new Rect(0, 0,
                    control.getWidth(), control.getHeight()), true);
            Rect visible = new Rect(0, 0, control.getWidth(), control.getHeight());
            scroll.offsetDescendantRectToMyCoords(control, visible);
            // This method gives content coordinates. The ScrollView's own scroll
            // offset is excluded, so apply it before comparing to the viewport.
            Rect contentBounds = new Rect(visible);
            visible.offset(-scroll.getScrollX(), -scroll.getScrollY());
            String geometry = control.getText() + ": content=" + contentBounds.toShortString()
                    + ", viewport=" + visible.toShortString() + ", scrollY="
                    + scroll.getScrollY() + ", viewportHeight=" + scroll.getHeight()
                    + ", control=" + control.getWidth() + "×" + control.getHeight();
            System.out.println("Ringing control geometry: " + geometry);
            assertTrue("向下滚动后操作必须可以完整触达：" + geometry,
                    visible.top >= scroll.getPaddingTop()
                            && visible.bottom <= scroll.getHeight() - scroll.getPaddingBottom());
        }
        scroll.scrollTo(0, 0);
    }

    private static void measure(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static void capture(View root, String name, int width, int height) throws Exception {
        measure(root, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < height; y += 10) {
            for (int x = 0; x < width; x += 10) colors.add(bitmap.getPixel(x, y));
        }
        assertTrue("原生绘制应包含响铃界面内容", colors.size() > 3);
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

    private static <T extends View> T findText(View view, String text, Class<T> type) {
        if (type.isInstance(view) && view instanceof TextView
                && text.contentEquals(((TextView) view).getText())) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findText(group.getChildAt(i), text, type);
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
