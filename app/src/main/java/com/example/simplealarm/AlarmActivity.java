package com.example.simplealarm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlarmActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER); root.setPadding(dp(28), dp(48), dp(28), dp(48)); root.setBackgroundColor(Color.rgb(17,24,39));
        TextView time = text(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 64, Color.WHITE);
        TextView label = text(getIntent().getStringExtra("label") == null ? "闹钟" : getIntent().getStringExtra("label"), 24, 0xFFCBD5E1);
        Alarm alarm = AlarmStore.find(this, getIntent().getIntExtra("alarm_id", -1));
        int[] activeIds=getIntent().getIntArrayExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS);
        boolean merged=activeIds!=null && activeIds.length>1;
        TextView remaining = text(merged ? "多个闹钟同时响铃（"+activeIds.length+" 个）" : alarm==null ? "测试响铃" : "最多响铃 " + alarm.ringDurationMinutes + " 分钟", 14, 0xFF94A3B8);
        int snoozeMinutes = alarm == null ? 5 : alarm.snoozeMinutes;
        Button snooze = button(alarm==null ? "稍后提醒不可用" : snoozeMinutes + "分钟后提醒", 0xFF2563EB);
        Button stop = button("停止闹钟", 0xFFDC2626);
        Switch vibration = new Switch(this); vibration.setText("本次响铃振动");
        vibration.setTextSize(18); vibration.setTextColor(Color.WHITE); vibration.setMinHeight(dp(56));
        vibration.setChecked(AlarmService.isVibrationEnabled());
        root.addView(time); root.addView(label, margins(0, 12, 0, 8)); root.addView(remaining,margins(0,0,0,28));
        root.addView(snooze, match(72)); root.addView(stop, marginsHeight(0,16,0,16,72));
        root.addView(vibration, match(56));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        boolean canSnooze=false;
        if(merged){for(int id:activeIds){Alarm item=AlarmStore.find(this,id);if(item!=null&&item.snoozeCount<item.maxSnoozes)canSnooze=true;}}
        else canSnooze=alarm!=null&&alarm.snoozeCount<alarm.maxSnoozes;
        snooze.setEnabled(canSnooze);
        if(!canSnooze) snooze.setText("已达到稍后提醒上限");
        snooze.setOnClickListener(v -> act(AlarmService.ACTION_SNOOZE));
        stop.setOnClickListener(v -> act(AlarmService.ACTION_STOP));
        vibration.setOnCheckedChangeListener((v, checked) -> startService(new Intent(this, AlarmService.class)
                .setAction(AlarmService.ACTION_VIBRATION).putExtra("alarm_id", getIntent().getIntExtra("alarm_id", -1)).putExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS,activeIds)
                .putExtra("vibrate", checked)));

    }
    private void act(String action) { startService(new Intent(this, AlarmService.class).setAction(action).putExtra("alarm_id", getIntent().getIntExtra("alarm_id", -1)).putExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS,getIntent().getIntArrayExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS))); finish(); }
    private TextView text(String s, int sp, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setGravity(Gravity.CENTER); return v; }
    private Button button(String s, int color) { Button b = new Button(this); b.setText(s); b.setTextSize(18); b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color)); return b; }
    private LinearLayout.LayoutParams match(int h) { return new LinearLayout.LayoutParams(-1, dp(h)); }
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private LinearLayout.LayoutParams marginsHeight(int l,int t,int r,int b,int h) { LinearLayout.LayoutParams p=match(h); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }
}
