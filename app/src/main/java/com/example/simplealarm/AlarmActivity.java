package com.example.simplealarm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlarmActivity extends Activity {
    private TextView labelView, remainingView;
    private Button snoozeButton;
    private Switch vibrationSwitch;
    private boolean refreshingVibration;
    private final AlarmService.RingingStateListener ringingListener = this::refreshState;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER); root.setPadding(dp(28), dp(48), dp(28), dp(48)); root.setBackgroundColor(Color.rgb(17,24,39));
        TextView time = text(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 64, Color.WHITE);
        labelView = text("闹钟", 24, 0xFFCBD5E1);
        remainingView = text("", 14, 0xFF94A3B8);
        snoozeButton = button("稍后提醒", 0xFF2563EB);
        Button stop = button("停止闹钟", 0xFFDC2626);
        vibrationSwitch = new Switch(this); vibrationSwitch.setText("本次响铃振动");
        vibrationSwitch.setTextSize(18); vibrationSwitch.setTextColor(Color.WHITE); vibrationSwitch.setMinHeight(dp(56));
        root.addView(time); root.addView(labelView, margins(0, 12, 0, 8)); root.addView(remainingView,margins(0,0,0,28));
        root.addView(snoozeButton, match(72)); root.addView(stop, marginsHeight(0,16,0,16,72));
        root.addView(vibrationSwitch, match(56));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
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
        AlarmService.addRingingStateListener(ringingListener);
        refreshState(AlarmService.ringingState());
    }

    @Override protected void onStop() {
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
    private TextView text(String s, int sp, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setGravity(Gravity.CENTER); return v; }
    private Button button(String s, int color) { Button b = new Button(this); b.setText(s); b.setTextSize(18); b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color)); return b; }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)); }
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private LinearLayout.LayoutParams marginsHeight(int l,int t,int r,int b,int h) { LinearLayout.LayoutParams p=match(h); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }
}
