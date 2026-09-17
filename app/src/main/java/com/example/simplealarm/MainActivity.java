package com.example.simplealarm;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.io.*;

public class MainActivity extends Activity {
    private android.media.Ringtone preview;
    private final android.os.Handler clockHandler = new android.os.Handler();
    private final Runnable clockTick = new Runnable() { public void run() { if(nextAlarm!=null) updateNextAlarm(AlarmStore.load(MainActivity.this)); clockHandler.postDelayed(this,30000); } };
    private LinearLayout list;
    private TextView nextAlarm;
    private final String[] dayNames = {"一","二","三","四","五","六","日"};
    private String pendingRingtoneUri = "";
    private TextView pendingRingtoneLabel;
    private static final int PICK_RINGTONE = 41;
    private static final int EXPORT_ALARMS = 42;
    private static final int IMPORT_ALARMS = 43;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); requestNeededPermissions(); buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) AlarmScheduler.rescheduleAll(this);
        if (list != null) refresh();
        UpdateDownloadReceiver.offerInstall(this);
        clockHandler.removeCallbacks(clockTick); clockHandler.post(clockTick);
    }

    @Override protected void onPause(){super.onPause();clockHandler.removeCallbacks(clockTick);stopPreview();}
    private android.graphics.drawable.GradientDrawable rounded(int color) {
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(24)); return d;
    }
    private void buildScreen() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(24),dp(16),dp(16),dp(12));
        TextView title=new TextView(this);title.setText("闹钟");title.setTextSize(30);title.setTextColor(Color.WHITE);title.setTypeface(null,1);
        Button more=smallButton("⋮");more.setTextSize(28);more.setContentDescription("更多设置");
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));header.addView(more,new LinearLayout.LayoutParams(dp(56),dp(56)));
        nextAlarm=new TextView(this);nextAlarm.setTextColor(0xFFFF676C);nextAlarm.setTextSize(15);nextAlarm.setPadding(dp(24),0,dp(24),dp(18));
        TextView tip=new TextView(this);tip.setText("点击时间编辑 · 长按删除");tip.setTextColor(0xFF919196);tip.setPadding(dp(24),0,0,dp(8));
        FrameLayout content=new FrameLayout(this);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);list.setPadding(dp(20),0,dp(20),dp(112));scroll.addView(list);content.addView(scroll);
        Button add=smallButton("＋");add.setTextSize(36);add.setTextColor(Color.WHITE);add.setBackground(rounded(0xFFEC4E58));add.setContentDescription("添加闹钟");
        FrameLayout.LayoutParams floating=new FrameLayout.LayoutParams(dp(72),dp(72),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);floating.bottomMargin=dp(24);content.addView(add,floating);
        root.addView(header);root.addView(nextAlarm);root.addView(tip);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        add.setOnClickListener(v->showEditor(null));
        more.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("闹钟设置").setItems(new String[]{"检查更新","权限检查","测试响铃","备份与恢复","闹钟音量"},(d,w)->{
            if(w==0)UpdateManager.check(this);else if(w==1)showPermissionStatus();else if(w==2)testAlarm();else if(w==3)showBackupMenu();else showVolume();
        }).show());refresh();
    }

    private void refresh() {
        list.removeAllViews(); List<Alarm> alarms = AlarmStore.load(this);
        updateNextAlarm(alarms);
        alarms.sort(Comparator.comparingInt(a -> a.hour * 60 + a.minute));
        if (alarms.isEmpty()) {
            TextView empty = new TextView(this); empty.setText("还没有闹钟\n点击下方 ＋ 添加一个"); empty.setTextSize(18); empty.setTextColor(0xFF6B7280); empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(100), 0, 0); list.addView(empty, new LinearLayout.LayoutParams(-1,-2)); return;
        }
        for (Alarm alarm : alarms) list.addView(alarmRow(alarm), rowParams());
    }

    private View alarmRow(Alarm alarm) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(18), dp(14), dp(12), dp(14)); row.setBackground(rounded(0xFF202022)); row.setElevation(0);
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
        TextView time = new TextView(this); time.setText(String.format(Locale.getDefault(), "%02d:%02d", alarm.hour, alarm.minute)); time.setTextSize(44); time.setTextColor(alarm.enabled ? Color.WHITE : 0xFF929296); time.setTypeface(null, 1);
        TextView sub = new TextView(this); sub.setText(alarm.label + "  ·  " + daysText(alarm.daysMask) + (alarm.skippedOccurrence > System.currentTimeMillis() ? "  ·  已跳过下次" : "")); sub.setTextSize(14); sub.setTextColor(0xFFA0A0A5); info.addView(time); info.addView(sub);
        Switch vibration = new Switch(this); vibration.setText("振动"); vibration.setTextColor(0xFFB7B7BC); vibration.setMinHeight(dp(48));
        vibration.setChecked(alarm.vibrate); info.addView(vibration);
        vibration.setOnCheckedChangeListener((view, checked) -> {
            Alarm saved = AlarmStore.find(this, alarm.id);
            if (saved != null) { saved.vibrate = checked; alarm.vibrate = checked; update(saved); }
        });
        Switch sw = new Switch(this); sw.setContentDescription("启用闹钟"); sw.setChecked(alarm.enabled);
        row.addView(info, new LinearLayout.LayoutParams(0,-2,1)); sw.setText("启用  ");sw.setTextColor(0xFFB7B7BC);sw.setMinHeight(dp(48));row.addView(sw);
        row.setOnClickListener(v -> showEditor(alarm));
        sw.setOnCheckedChangeListener((v, checked) -> {
            alarm.enabled = checked;
            if (checked && !AlarmScheduler.schedule(this, alarm)) {
                alarm.enabled = false; showExactAlarmPermission();
            } else if (!checked) AlarmScheduler.cancel(this, alarm.id);
            update(alarm); refresh();
        });
        row.setOnLongClickListener(v -> { new AlertDialog.Builder(this).setTitle("删除闹钟？").setMessage(String.format("%02d:%02d  %s", alarm.hour,alarm.minute,alarm.label)).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->delete(alarm)).show(); return true; });
        info.setOnClickListener(v -> showEditor(alarm));
        info.setOnLongClickListener(v -> { row.performLongClick(); return true; });
        time.setOnClickListener(v -> showEditor(alarm));
        time.setOnLongClickListener(v -> { row.performLongClick(); return true; });
        sub.setOnClickListener(v -> showEditor(alarm));
        sub.setOnLongClickListener(v -> { row.performLongClick(); return true; });
        if (alarm.enabled && alarm.daysMask != 0) {
            Button skip = smallButton(alarm.skippedOccurrence > System.currentTimeMillis() ? "恢复下一次" : "跳过下一次");
            info.addView(skip);
            skip.setOnClickListener(v -> skipNext(alarm));
        }
        return row;
    }

    private void showEditor(Alarm existing) {
        Calendar now = Calendar.getInstance(); int h = existing == null ? now.get(Calendar.HOUR_OF_DAY) : existing.hour; int m = existing == null ? now.get(Calendar.MINUTE) : existing.minute;
        TimePicker picker = (TimePicker)getLayoutInflater().inflate(R.layout.time_picker,null); picker.setIs24HourView(true); picker.setHour(h); picker.setMinute(m);
        EditText label = new EditText(this); label.setHint("闹钟名称"); label.setText(existing == null ? "闹钟" : existing.label); label.setSingleLine(true);
        TextView days = new TextView(this); days.setTextSize(16); days.setTextColor(0xFFFF676C); days.setPadding(dp(8),dp(16),dp(8),dp(16));
        final int[] mask = {existing == null ? 0 : existing.daysMask}; days.setText("重复：" + daysText(mask[0]));
        days.setOnClickListener(v -> { final int[] draft={mask[0]}; boolean[] checked=new boolean[7]; for(int i=0;i<7;i++) checked[i]=(mask[0]&(1<<i))!=0; new AlertDialog.Builder(this).setTitle("选择重复日期").setMultiChoiceItems(new String[]{"周一","周二","周三","周四","周五","周六","周日"},checked,(d,which,on)->{if(on)draft[0]|=1<<which;else draft[0]&=~(1<<which);}).setPositiveButton("确定",(d,w)->{mask[0]=draft[0];days.setText("重复："+daysText(mask[0]));}).setNegativeButton("取消",null).show(); });
        pendingRingtoneUri = existing == null ? "" : existing.ringtoneUri;
        pendingRingtoneLabel = new TextView(this); pendingRingtoneLabel.setText(pendingRingtoneUri.isEmpty() ? "铃声：系统默认（点击选择）" : "铃声：已选择自定义音频"); pendingRingtoneLabel.setTextColor(0xFFFF676C); pendingRingtoneLabel.setTextSize(16); pendingRingtoneLabel.setPadding(dp(8),dp(10),dp(8),dp(10));
        pendingRingtoneLabel.setOnClickListener(v -> chooseRingtone());
        Switch vibrate = new Switch(this); vibrate.setText("响铃时振动"); vibrate.setMinHeight(dp(56)); vibrate.setChecked(existing == null || existing.vibrate);
        CheckBox gradual = new CheckBox(this); gradual.setText("30秒渐强音量"); gradual.setChecked(existing == null || existing.gradualVolume);
        Spinner duration = spinner(new String[]{"响铃 5 分钟","响铃 10 分钟","响铃 20 分钟"}, existing == null ? 1 : indexOf(new int[]{5,10,20}, existing.ringDurationMinutes));
        Spinner snooze = spinner(new String[]{"稍后 5 分钟","稍后 10 分钟","稍后 15 分钟"}, existing == null ? 0 : indexOf(new int[]{5,10,15}, existing.snoozeMinutes));
        Spinner snoozeMax = spinner(new String[]{"最多稍后 1 次","最多稍后 3 次","最多稍后 5 次"}, existing == null ? 1 : indexOf(new int[]{1,3,5}, existing.maxSnoozes));
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(20),0,dp(20),dp(28));
        box.setBackgroundColor(Color.BLACK);box.addView(picker,new LinearLayout.LayoutParams(-1,dp(230)));
        addGroup(box,"时间与重复",label,days);
        Button listen=smallButton("试听 / 停止");listen.setOnClickListener(v->previewRingtone());
        Button reset=smallButton("恢复系统默认");reset.setOnClickListener(v->{stopPreview();pendingRingtoneUri="";pendingRingtoneLabel.setText("铃声：系统默认（点击选择）");});
        Button volume=smallButton("闹钟音量");volume.setOnClickListener(v->showVolume());
        android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);
        TextView warning=new TextView(this);warning.setTextColor(0xFFFFB170);warning.setPadding(dp(8),dp(8),dp(8),dp(8));
        warning.setText(audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM)<=1 ? "闹钟音量较低，请检查后保存" : "铃声使用系统闹钟音量");
        addGroup(box,"铃声与振动",pendingRingtoneLabel,listen,reset,vibrate,gradual,volume,warning);
        addGroup(box,"稍后提醒与响铃时长",snooze,snoozeMax,duration);
        ScrollView editorScroll=new ScrollView(this);editorScroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"添加闹钟":"编辑闹钟").setView(editorScroll).setNegativeButton("取消",null).setPositiveButton("完成",null).create();
        dialog.setOnDismissListener(d->{stopPreview();pendingRingtoneLabel=null;});
        dialog.setOnShowListener(x -> dialog.getButton(-1).setOnClickListener(v -> { picker.clearFocus(); String name=label.getText().toString().trim(); if(name.isEmpty()) name="闹钟"; Alarm a = new Alarm(existing==null ? AlarmStore.nextId(this) : existing.id,picker.getHour(),picker.getMinute(),name,existing==null || existing.enabled || picker.getHour()!=existing.hour || picker.getMinute()!=existing.minute,mask[0]); a.hour=picker.getHour();a.minute=picker.getMinute();a.label=name;a.daysMask=mask[0]; a.ringtoneUri=pendingRingtoneUri; a.vibrate=vibrate.isChecked(); a.gradualVolume=gradual.isChecked(); a.ringDurationMinutes=new int[]{5,10,20}[duration.getSelectedItemPosition()]; a.snoozeMinutes=new int[]{5,10,15}[snooze.getSelectedItemPosition()]; a.maxSnoozes=new int[]{1,3,5}[snoozeMax.getSelectedItemPosition()]; a.skippedOccurrence=0; AlarmScheduler.cancel(this,a.id); if(a.enabled && !AlarmScheduler.schedule(this,a)){ a.enabled=false; update(a); showExactAlarmPermission(); refresh(); return; } update(a); Toast.makeText(this,a.enabled ? "已保存并重新设置闹钟" : "已保存（保持关闭）",Toast.LENGTH_SHORT).show(); dialog.dismiss(); pendingRingtoneLabel=null; refresh(); })); dialog.show(); dialog.getWindow().setLayout(-1,-1);
    }

    private void addGroup(LinearLayout box,String title,View... views){
        TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(14);heading.setTextColor(0xFF9A9AA0);heading.setPadding(dp(10),dp(20),0,dp(10));box.addView(heading);
        LinearLayout group=new LinearLayout(this);group.setOrientation(1);group.setPadding(dp(16),dp(10),dp(16),dp(10));group.setBackground(rounded(0xFF202022));
        for(View view:views){view.setMinimumHeight(dp(52));group.addView(view,new LinearLayout.LayoutParams(-1,-2));}
        box.addView(group);
    }
    private void stopPreview(){if(preview!=null){preview.stop();preview=null;}}
    private void previewRingtone(){
        if(preview!=null){stopPreview();return;}
        try{
            Uri uri=pendingRingtoneUri.isEmpty()?android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM):Uri.parse(pendingRingtoneUri);
            preview=android.media.RingtoneManager.getRingtone(this,uri);
            if(preview==null)throw new IOException();
            preview.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
            preview.play();clockHandler.postDelayed(()->stopPreview(),8000);
        }catch(Exception e){stopPreview();Toast.makeText(this,"无法试听，请重新选择铃声",Toast.LENGTH_LONG).show();}
    }
    private void showVolume(){
        android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(24),dp(16),dp(24),dp(16));
        TextView value=new TextView(this);box.addView(value);
        SeekBar bar=new SeekBar(this);bar.setMax(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM));bar.setProgress(audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM));box.addView(bar);
        value.setText("系统闹钟音量："+bar.getProgress()+" / "+bar.getMax());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){if(user)audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM,p,0);value.setText("系统闹钟音量："+p+" / "+b.getMax());}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });
        new AlertDialog.Builder(this).setTitle("闹钟音量").setView(box).setPositiveButton("完成",null).show();
    }
    private void update(Alarm alarm) { List<Alarm> all=AlarmStore.load(this); boolean found=false; for(int i=0;i<all.size();i++) if(all.get(i).id==alarm.id){all.set(i,alarm);found=true;} if(!found)all.add(alarm); AlarmStore.save(this,all); }
    private void delete(Alarm alarm) { List<Alarm> all=AlarmStore.load(this); all.removeIf(a->a.id==alarm.id); AlarmStore.save(this,all); AlarmScheduler.cancel(this,alarm.id); refresh(); }
    private String daysText(int mask) { if(mask==0)return "仅一次"; if(mask==31)return "工作日"; if(mask==127)return "每天"; StringBuilder s=new StringBuilder("周"); for(int i=0;i<7;i++)if((mask&(1<<i))!=0)s.append(dayNames[i]); return s.toString(); }
    private LinearLayout.LayoutParams rowParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(8));return p;}
    private Button smallButton(String text) { Button b=new Button(this); b.setText(text); b.setTextSize(12); b.setAllCaps(false); return b; }
    private Spinner spinner(String[] items, int selected) { Spinner s=new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items)); s.setSelection(Math.max(0, selected)); return s; }
    private int indexOf(int[] values, int value) { for(int i=0;i<values.length;i++) if(values[i]==value)return i; return 0; }
    private void chooseRingtone() { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,PICK_RINGTONE); }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if(requestCode==PICK_RINGTONE){ try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){} pendingRingtoneUri=uri.toString(); if(pendingRingtoneLabel!=null)pendingRingtoneLabel.setText("铃声：已选择自定义音频"); return; }
        if(requestCode==EXPORT_ALARMS){
            try(OutputStreamWriter writer=new OutputStreamWriter(getContentResolver().openOutputStream(uri))){writer.write(AlarmStore.exportJson(this));Toast.makeText(this,"闹钟备份已导出",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"导出失败",Toast.LENGTH_LONG).show();}
        } else if(requestCode==IMPORT_ALARMS){
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri)))){
                StringBuilder raw=new StringBuilder();String line;while((line=reader.readLine())!=null)raw.append(line);
                List<Alarm> old=AlarmStore.load(this);
                if(!AlarmStore.importJson(this,raw.toString())){Toast.makeText(this,"备份文件格式不正确",Toast.LENGTH_LONG).show();return;}
                for(Alarm alarm:old)AlarmScheduler.cancel(this,alarm.id);AlarmScheduler.rescheduleAll(this);refresh();Toast.makeText(this,"闹钟已恢复",Toast.LENGTH_SHORT).show();
            }catch(Exception e){Toast.makeText(this,"导入失败",Toast.LENGTH_LONG).show();}
        }
    }

    private void showBackupMenu(){
        new AlertDialog.Builder(this).setTitle("备份与恢复").setItems(new String[]{"导出闹钟备份","从备份恢复"},(d,which)->{
            if(which==0){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"简洁闹钟备份.json");startActivityForResult(i,EXPORT_ALARMS);}
            else{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT_ALARMS);}
        }).show();
    }
    private void skipNext(Alarm alarm) { if(alarm.daysMask==0){Toast.makeText(this,"一次性闹钟不能跳过下次，可直接关闭",Toast.LENGTH_SHORT).show();return;} boolean restore=alarm.skippedOccurrence>System.currentTimeMillis(); alarm.skippedOccurrence=restore ? 0 : AlarmScheduler.nextTrigger(alarm,System.currentTimeMillis()); AlarmScheduler.cancel(this,alarm.id); update(alarm); AlarmScheduler.schedule(this,alarm); Toast.makeText(this,restore ? "已恢复下一次响铃" : "已跳过下一次响铃",Toast.LENGTH_SHORT).show(); refresh(); }
    private void updateNextAlarm(List<Alarm> alarms) { long now=System.currentTimeMillis(), best=Long.MAX_VALUE; for(Alarm a:alarms)if(a.enabled)best=Math.min(best,AlarmScheduler.nextTrigger(a,now)); if(best==Long.MAX_VALUE){nextAlarm.setText("当前没有启用的闹钟");return;} long minutes=Math.max(0,(best-now+59999)/60000); nextAlarm.setText(new java.text.SimpleDateFormat("M月d日 E HH:mm",Locale.CHINA).format(new Date(best))+"\n距响铃 "+(minutes/60)+"小时"+(minutes%60)+"分钟后"); }
    private void testAlarm() { Intent i=new Intent(this,AlarmService.class).putExtra("alarm_id",-777).putExtra("label","测试闹钟").putExtra("vibrate",true).putExtra("gradual",false).putExtra("duration",1).putExtra("max_snoozes",0); if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i); }
    private void showPermissionStatus() {
        AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE); NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        boolean exact=Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms(); boolean notice=Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        boolean full=Build.VERSION.SDK_INT<34||nm.canUseFullScreenIntent();
        String message="精确闹钟："+(exact?"已允许":"未允许")+"\n通知："+(notice?"已允许":"未允许")+"\n锁屏全屏提醒："+(full?"已允许":"未允许")+"\n自启动/后台运行：vivo 系统需手动确认";
        new AlertDialog.Builder(this).setTitle("权限检查").setMessage(message).setNegativeButton("关闭",null).setPositiveButton("修复权限",(d,w)->openMissingPermission(exact,notice,full)).show();
    }
    private void openMissingPermission(boolean exact,boolean notice,boolean full){
        try{
            if(!exact){startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));return;}
            if(!full && Build.VERSION.SDK_INT>=34){startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName())));return;}
            if(!notice && Build.VERSION.SDK_INT>=33){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);return;}
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
        }catch(Exception ignored){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    }
    private void requestNeededPermissions() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
        if(Build.VERSION.SDK_INT>=31){ AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE); if(!am.canScheduleExactAlarms()) showExactAlarmPermission(); }
    }
    private void showExactAlarmPermission() {
        new AlertDialog.Builder(this).setTitle("需要精确闹钟权限")
                .setMessage("没有该权限，安卓会延迟闹钟。请在下一页允许“闹钟和提醒”。")
                .setNegativeButton("暂不", null)
                .setPositiveButton("去允许", (d,w) -> {
                    try { startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName()))); }
                    catch (Exception ignored) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))); }
                }).show();
    }
    private int dp(int x){return(int)(x*getResources().getDisplayMetrics().density+.5f);}
}
