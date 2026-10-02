package com.example.simplealarm;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlarmActivity extends Activity {
    private static final int BACKGROUND = 0xFF101114;
    private static final int SURFACE = 0xFF202228;
    private static final int FOREGROUND = 0xFFF3F4F6;
    private static final int MUTED = 0xFF9AA0AA;
    private static final int ACCENT = 0xFF8AB4F8;
    private TextView timeView, dateView;
    private TextView labelView, remainingView;
    private Button snoozeButton;
    private Switch vibrationSwitch;
    private boolean refreshingVibration;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            clockHandler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L);
        }
    };
    private final AlarmService.RingingStateListener ringingListener = this::refreshState;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER); root.setPadding(dp(28), dp(36), dp(28), dp(36));
        root.setBackgroundColor(BACKGROUND);
        TextView status = text("正在响铃", 14, ACCENT);
        status.setLetterSpacing(.08f);
        timeView = text("", 88, FOREGROUND);
        timeView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timeView.setIncludeFontPadding(false);
        timeView.setMaxLines(1);
        timeView.setHorizontallyScrolling(false);
        timeView.setAutoSizeTextTypeUniformWithConfiguration(32, 88, 2,
                TypedValue.COMPLEX_UNIT_SP);
        dateView = text("", 14, MUTED);
        labelView = text("闹钟", 24, FOREGROUND);
        remainingView = text("", 14, MUTED);
        remainingView.setLineSpacing(dp(4), 1f);
        Button stop = button("停止闹钟", ACCENT, BACKGROUND);
        snoozeButton = button("稍后提醒", SURFACE, FOREGROUND);
        vibrationSwitch = new Switch(this); vibrationSwitch.setText("本次响铃振动");
        vibrationSwitch.setTextSize(16); vibrationSwitch.setTextColor(FOREGROUND);
        vibrationSwitch.setPadding(dp(20), 0, dp(20), 0);
        vibrationSwitch.setBackground(rounded(SURFACE, 24));
        vibrationSwitch.setSwitchPadding(dp(16));
        vibrationSwitch.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, MUTED}));
        vibrationSwitch.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0x668AB4F8, 0xFF414650}));
        root.addView(status, margins(0, 0, 0, 18));
        root.addView(timeView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(dateView, margins(0, 12, 0, 32));
        root.addView(labelView, margins(0, 0, 0, 8));
        root.addView(remainingView, margins(0, 0, 0, 36));
        root.addView(stop, match(72));
        root.addView(snoozeButton, marginsHeight(0, 12, 0, 24, 64));
        root.addView(vibrationSwitch, match(72));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(root); setContentView(scroll);
        updateClock();
        snoozeButton.setOnClickListener(v -> act(AlarmService.ACTION_SNOOZE));
        stop.setOnClickListener(v -> act(AlarmService.ACTION_STOP));
        vibrationSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (refreshingVibration) return;
            Intent command = command(AlarmService.ACTION_VIBRATION);
            if (command != null) startService(command.putExtra("vibrate", checked));
        });
        refreshState(AlarmService.ringingState());
    }

    @Override protected void onStart() {
        super.onStart();
        clockHandler.removeCallbacks(clockTick);
        clockTick.run();
        AlarmService.addRingingStateListener(ringingListener);
        refreshState(AlarmService.ringingState());
    }

    @Override protected void onStop() {
        clockHandler.removeCallbacks(clockTick);
        AlarmService.removeRingingStateListener(ringingListener);
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshState(AlarmService.ringingState());
    }

    private void refreshState(AlarmService.RingingState state) {
        if (state == null) { finish(); return; }
        int[] activeIds = state.activeIds();
        Alarm alarm = AlarmStore.find(this, state.alarmId);
        boolean merged = activeIds.length > 1;
        labelView.setText(state.label);
        remainingView.setText(merged ? "多个闹钟同时响铃（"+activeIds.length+" 个）"
                : alarm == null ? "测试响铃" : "最多响铃 " + alarm.ringDurationMinutes + " 分钟");
        boolean canSnooze = false;
        for (int id : activeIds) {
            Alarm item = AlarmStore.find(this, id);
            if (item != null && item.snoozeCount < item.maxSnoozes) canSnooze = true;
        }
        snoozeButton.setEnabled(canSnooze);
        snoozeButton.setText(!canSnooze ? alarm == null ? "稍后提醒不可用" : "已达到稍后提醒上限"
                : merged ? "稍后提醒" : alarm.snoozeMinutes + "分钟后提醒");
        refreshingVibration = true;
        vibrationSwitch.setChecked(state.vibrate);
        refreshingVibration = false;
    }

    private Intent command(String action) {
        AlarmService.RingingState state = AlarmService.ringingState();
        if (state == null) { finish(); return null; }
        return new Intent(this, AlarmService.class).setAction(action)
                .putExtra("alarm_id", state.alarmId)
                .putExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS, state.activeIds());
    }

    private void act(String action) {
        Intent command = command(action);
        if (command != null) startService(command);
        // The service closes this page when ringing really stops. A failed
        // snooze leaves controls available for the alarms still ringing.
    }
    private void updateClock() {
        Date now = new Date();
        String pattern = android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        timeView.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        dateView.setText(android.text.format.DateFormat.format("M月d日 EEEE", now));
        if (!android.text.format.DateFormat.is24HourFormat(this)) {
            dateView.setText(new SimpleDateFormat("a", Locale.getDefault()).format(now)
                    + " · " + dateView.getText());
        }
    }
    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp);
        v.setTextColor(color); v.setGravity(Gravity.CENTER); return v;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private Button button(String s, int color, int textColor) {
        Button b = new Button(this); b.setText(s); b.setTextSize(18);
        b.setAllCaps(false); b.setPadding(dp(20), 0, dp(20), 0);
        b.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xFF666B75, textColor}));
        StateListDrawable surface = new StateListDrawable();
        surface.addState(new int[]{-android.R.attr.state_enabled}, rounded(SURFACE, 32));
        surface.addState(new int[]{}, rounded(color, 32));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                surface, rounded(0xFFFFFFFF, 32)));
        b.setElevation(0); return b;
    }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)); }
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private LinearLayout.LayoutParams marginsHeight(int l,int t,int r,int b,int h) { LinearLayout.LayoutParams p=match(h); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }
}
