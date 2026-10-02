package com.example.simplealarm;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.io.IOException;

public class MainActivity extends Activity {
    private android.media.Ringtone preview;
    private Button pendingPreviewButton;
    private boolean mainResumed, ringingControlsRequested;
    private final AlarmService.RingingStateListener ringingStateListener =
            this::showRingingControls;
    private final android.os.Handler clockHandler = new android.os.Handler();
    private final Runnable stopPreviewTimeout = this::stopPreview;
    private final Runnable clockTick = new Runnable() { public void run() { if(nextAlarm!=null){updateNextAlarm(AlarmStore.load(MainActivity.this));updatePermissionHint();} clockHandler.postDelayed(this,30000); } };
    private LinearLayout list;
    private TextView nextAlarm, nextDetail, nextCaption, listHeading, permissionHint;
    private static final int BACKGROUND = 0xFF101114;
    private static final int SURFACE = 0xFF1D2026;
    private static final int TEXT = 0xFFF3F4F6;
    private static final int SECONDARY = 0xFF9AA2AF;
    private static final int ACCENT = 0xFF8AB4F8;
    private final String[] dayNames = {"一","二","三","四","五","六","日"};
    private String pendingRingtoneUri = "";
    private TextView pendingRingtoneLabel;
    private static final int PICK_RINGTONE = 41;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        mainResumed=true;ringingControlsRequested=false;
        AlarmService.addRingingStateListener(ringingStateListener);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) AlarmScheduler.rescheduleAll(this);
        if (list != null) refresh();
        UpdateDownloadReceiver.offerInstall(this);
        clockHandler.removeCallbacks(clockTick); clockHandler.post(clockTick);
        showRingingControls(AlarmService.ringingState());
    }

    @Override protected void onPause(){
        mainResumed=false;AlarmService.removeRingingStateListener(ringingStateListener);
        super.onPause();clockHandler.removeCallbacks(clockTick);stopPreview();
    }
    @Override protected void onDestroy(){
        AlarmService.removeRingingStateListener(ringingStateListener);
        super.onDestroy();
    }
    private void showRingingControls(AlarmService.RingingState state){
        if(state==null){ringingControlsRequested=false;return;}
        if(!mainResumed||ringingControlsRequested||isFinishing()||isDestroyed())return;
        // A foreground home screen provides controls after the service is ready,
        // including when notification permissions or the alarm channel are off.
        ringingControlsRequested=true;
        startActivity(new Intent(this,AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("alarm_id",state.alarmId).putExtra("label",state.label)
                .putExtra(AlarmService.EXTRA_ACTIVE_ALARM_IDS,state.activeIds()));
    }
    private android.graphics.drawable.GradientDrawable rounded(int color) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(20)); return d;
    }
    private void buildScreen() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BACKGROUND);
        root.setFitsSystemWindows(true);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(26),dp(14),dp(18),dp(4));
        TextView title=text("闹钟",28,TEXT);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        Button more=smallButton("⋮");more.setTextSize(26);more.setTextColor(SECONDARY);more.setContentDescription("闹钟设置");more.setBackground(ripple(Color.TRANSPARENT,24));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));header.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
        FrameLayout content=new FrameLayout(this);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(26),dp(28),dp(26),dp(116));
        nextCaption=text("下一个闹钟",14,SECONDARY);body.addView(nextCaption);
        nextAlarm=text("—",64,ACCENT);nextAlarm.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));nextAlarm.setIncludeFontPadding(false);
        LinearLayout.LayoutParams timeParams=new LinearLayout.LayoutParams(-1,-2);timeParams.topMargin=dp(12);body.addView(nextAlarm,timeParams);
        nextDetail=text("",14,SECONDARY);nextDetail.setLineSpacing(dp(4),1);nextDetail.setPadding(0,dp(10),0,dp(28));body.addView(nextDetail);
        permissionHint=text("",13,0xFFE9C48B);permissionHint.setContentDescription("响铃权限提示");permissionHint.setPadding(dp(14),dp(12),dp(14),dp(12));permissionHint.setBackground(ripple(0xFF28241E,14));permissionHint.setMinimumHeight(dp(48));permissionHint.setOnClickListener(v->showPermissionStatus());body.addView(permissionHint,new LinearLayout.LayoutParams(-1,-2));
        listHeading=text("全部闹钟",13,SECONDARY);listHeading.setPadding(0,dp(22),0,dp(6));body.addView(listHeading);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);body.addView(list);scroll.addView(body);content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        Button add=smallButton("＋");add.setTextSize(32);add.setTextColor(0xFF10233E);add.setBackground(ripple(ACCENT,32));add.setContentDescription("添加闹钟");add.setElevation(dp(4));
        FrameLayout.LayoutParams floating=new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.BOTTOM|Gravity.END);floating.bottomMargin=dp(24);floating.rightMargin=dp(24);content.addView(add,floating);
        root.addView(header);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        add.setOnClickListener(v->showEditor(null));
        more.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("设置").setItems(new String[]{"闹钟音量","权限检查","测试响铃","检查更新"},(d,w)->{
            if(w==0)showVolume();else if(w==1)showPermissionStatus();else if(w==2)testAlarm();else UpdateManager.check(this);
        }).show());refresh();
    }

    private void refresh() {
        list.removeAllViews(); List<Alarm> alarms = AlarmStore.load(this);
        updateNextAlarm(alarms);
        updatePermissionHint();
        listHeading.setText(alarms.isEmpty()?"全部闹钟":"全部闹钟 · "+alarms.size());
        alarms.sort(Comparator.comparingInt(a -> a.hour * 60 + a.minute));
        if (alarms.isEmpty()) {
            TextView empty=text("还没有闹钟\n点击 ＋ 添加",16,SECONDARY);empty.setGravity(Gravity.CENTER);empty.setLineSpacing(dp(8),1);empty.setPadding(0,dp(44),0,dp(44));list.addView(empty,new LinearLayout.LayoutParams(-1,-2));return;
        }
        for (int i=0;i<alarms.size();i++) {
            if(i>0){View divider=new View(this);divider.setBackgroundColor(0xFF272B32);list.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
            list.addView(alarmRow(alarms.get(i)),new LinearLayout.LayoutParams(-1,-2));
        }
    }

    private View alarmRow(Alarm alarm) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(20),0,dp(20));row.setBackground(ripple(Color.TRANSPARENT,16));
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
        boolean pendingSnooze=alarm.pendingSnoozeAt>0L;
        String timeText=String.format(Locale.getDefault(),"%02d:%02d",alarm.hour,alarm.minute);
        TextView time=text(timeText,46,alarm.enabled||pendingSnooze?TEXT:0xFF707887);time.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));time.setIncludeFontPadding(false);
        TextView sub=text(alarm.label,15,alarm.enabled||pendingSnooze?TEXT:SECONDARY);sub.setPadding(0,dp(6),dp(12),0);
        String details=daysText(alarm.daysMask)+(alarm.vibrate?" · 振动":"")+(alarm.skippedOccurrence>System.currentTimeMillis()?" · 已跳过下次":"");
        TextView repeat=text(details,13,SECONDARY);repeat.setPadding(0,dp(5),dp(12),0);info.addView(time);info.addView(sub);info.addView(repeat);
        Switch sw=new Switch(this);sw.setContentDescription("启用 "+timeText+" 闹钟");sw.setChecked(alarm.enabled);sw.setMinHeight(dp(48));sw.setMinimumWidth(dp(52));tintSwitch(sw);
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1));row.addView(sw,new LinearLayout.LayoutParams(-2,dp(56)));
        row.setOnClickListener(v -> showEditor(alarm));
        sw.setOnCheckedChangeListener((v, checked) -> {
            alarm.enabled = checked;
            if (checked && !AlarmScheduler.schedule(this, alarm)) {
                alarm.enabled = false; showExactAlarmPermission();
            } else if (checked) { AlarmScheduler.cancelSnooze(this,alarm.id); alarm.pendingSnoozeAt=0L; }
            else { AlarmScheduler.cancel(this, alarm.id); alarm.pendingSnoozeAt=0L; }
            update(alarm); refresh();
        });
        row.setOnLongClickListener(v -> { new AlertDialog.Builder(this).setTitle("删除闹钟？").setMessage(String.format("%02d:%02d  %s", alarm.hour,alarm.minute,alarm.label)).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->delete(alarm)).show(); return true; });
        info.setOnClickListener(v -> showEditor(alarm));
        info.setOnLongClickListener(v -> { row.performLongClick(); return true; });
        time.setOnClickListener(v -> showEditor(alarm));
        time.setOnLongClickListener(v -> { row.performLongClick(); return true; });
        sub.setOnClickListener(v -> showEditor(alarm));
        sub.setOnLongClickListener(v -> { row.performLongClick(); return true; });
        if (pendingSnooze) {
            TextView snoozeStatus=text(alarm.pendingSnoozeAt<=System.currentTimeMillis()?"稍后提醒等待响铃":"稍后提醒 · "+new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(alarm.pendingSnoozeAt)),13,ACCENT);snoozeStatus.setPadding(0,dp(10),0,0);info.addView(snoozeStatus);
            Button cancelSnooze=smallButton("取消稍后提醒");
            LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-2,dp(48));actionParams.topMargin=dp(6);info.addView(cancelSnooze,actionParams);
            cancelSnooze.setOnClickListener(v->cancelPendingSnooze(alarm));
        }
        return row;
    }

    private void showEditor(Alarm existing) {
        Calendar now = Calendar.getInstance(); int h = existing == null ? now.get(Calendar.HOUR_OF_DAY) : existing.hour; int m = existing == null ? now.get(Calendar.MINUTE) : existing.minute;
        TimePicker picker = (TimePicker)getLayoutInflater().inflate(R.layout.time_picker,null); picker.setIs24HourView(true); picker.setHour(h); picker.setMinute(m);
        EditText label = new EditText(this); label.setHint("闹钟名称"); label.setText(existing == null ? "闹钟" : existing.label); label.setSingleLine(true);label.setTextSize(18);label.setTextColor(TEXT);label.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        final int initialMask=existing==null?0:existing.daysMask;
        final int[] customMask={initialMask==0||initialMask==31||initialMask==127?0:initialMask};
        Spinner repeatMode=spinner(new String[]{"仅一次","工作日","每天","自选星期"},initialMask==0?0:initialMask==31?1:initialMask==127?2:3);
        Button chooseDays=smallButton(customMask[0]==0?"选择星期":"已选择："+daysText(customMask[0]));
        chooseDays.setVisibility(repeatMode.getSelectedItemPosition()==3?View.VISIBLE:View.GONE);
        repeatMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){chooseDays.setVisibility(position==3?View.VISIBLE:View.GONE);}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        chooseDays.setOnClickListener(v->{
            final int[] draft={customMask[0]}; boolean[] checked=new boolean[7];
            for(int i=0;i<7;i++)checked[i]=(draft[0]&(1<<i))!=0;
            new AlertDialog.Builder(this).setTitle("选择重复日期").setMultiChoiceItems(new String[]{"周一","周二","周三","周四","周五","周六","周日"},checked,(dialog,day,on)->{if(on)draft[0]|=1<<day;else draft[0]&=~(1<<day);}).setPositiveButton("确定",(dialog,button)->{customMask[0]=draft[0];chooseDays.setText(customMask[0]==0?"选择星期":"已选择："+daysText(customMask[0]));}).setNegativeButton("取消",null).show();
        });
        pendingRingtoneUri = existing == null ? "" : existing.ringtoneUri;
        pendingRingtoneLabel = text(pendingRingtoneUri.isEmpty()?"系统默认铃声 · 点击选择":"自定义铃声 · 点击更换",16,ACCENT);pendingRingtoneLabel.setPadding(dp(8),dp(10),dp(8),dp(10));
        pendingRingtoneLabel.setOnClickListener(v -> chooseRingtone());
        Switch vibrate = new Switch(this); vibrate.setText("响铃时振动"); vibrate.setTextSize(16);vibrate.setTextColor(TEXT);vibrate.setMinHeight(dp(56)); vibrate.setChecked(existing == null || existing.vibrate);tintSwitch(vibrate);
        CheckBox gradual = new CheckBox(this); gradual.setText("30 秒渐强音量");gradual.setTextSize(16);gradual.setTextColor(TEXT);gradual.setChecked(existing == null || existing.gradualVolume);gradual.setButtonTintList(controlColors(ACCENT,SECONDARY));
        Spinner duration = spinner(new String[]{"响铃 5 分钟","响铃 10 分钟","响铃 20 分钟"}, existing == null ? 1 : indexOf(new int[]{5,10,20}, existing.ringDurationMinutes));
        Spinner snooze = spinner(new String[]{"稍后 5 分钟","稍后 10 分钟","稍后 15 分钟"}, existing == null ? 0 : indexOf(new int[]{5,10,15}, existing.snoozeMinutes));
        Spinner snoozeMax = spinner(new String[]{"最多稍后 1 次","最多稍后 3 次","最多稍后 5 次"}, existing == null ? 1 : indexOf(new int[]{1,3,5}, existing.maxSnoozes));
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(12),dp(20),dp(28));
        box.setBackgroundColor(BACKGROUND);box.addView(picker,new LinearLayout.LayoutParams(-1,dp(220)));
        addGroup(box,"闹钟名称",label);
        addGroup(box,"重复方式",repeatMode,chooseDays);
        Button listen=smallButton("试听铃声");pendingPreviewButton=listen;listen.setOnClickListener(v->previewRingtone());
        Button reset=smallButton("默认铃声");reset.setContentDescription("使用系统默认铃声");reset.setOnClickListener(v->{stopPreview();pendingRingtoneUri="";pendingRingtoneLabel.setText("系统默认铃声 · 点击选择");});
        Button volume=smallButton("闹钟音量");volume.setOnClickListener(v->showVolume());
        android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);
        TextView warning=new TextView(this);warning.setTextColor(0xFFFFB170);warning.setPadding(dp(8),dp(8),dp(8),dp(8));
        warning.setText(audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM)<=1 ? "闹钟音量较低，请检查后保存" : "铃声使用系统闹钟音量");
        LinearLayout soundActions=new LinearLayout(this);
        soundActions.addView(listen,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout.LayoutParams resetParams=new LinearLayout.LayoutParams(0,dp(48),1);resetParams.leftMargin=dp(8);soundActions.addView(reset,resetParams);
        addGroup(box,"声音与振动",pendingRingtoneLabel,soundActions,vibrate,gradual,volume,warning);
        addGroup(box,"稍后提醒与响铃时长",snooze,snoozeMax,duration);
        ScrollView editorScroll=new ScrollView(this);editorScroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"添加闹钟":"编辑闹钟").setView(editorScroll).setNegativeButton("取消",null).setPositiveButton("完成",null).create();
        if(existing!=null&&existing.enabled&&existing.daysMask!=0){
            Button skip=smallButton(existing.skippedOccurrence>System.currentTimeMillis()?"恢复下一次响铃":"跳过下一次响铃");
            LinearLayout.LayoutParams skipParams=new LinearLayout.LayoutParams(-1,dp(52));skipParams.topMargin=dp(20);box.addView(skip,skipParams);
            skip.setOnClickListener(v->{dialog.dismiss();Alarm current=AlarmStore.find(this,existing.id);if(current!=null)skipNext(current);});
        }
        if(existing!=null){
            Button remove=smallButton("删除闹钟");remove.setTextColor(0xFFF0AAA7);remove.setBackground(ripple(Color.TRANSPARENT,14));
            LinearLayout.LayoutParams removeParams=new LinearLayout.LayoutParams(-1,dp(52));removeParams.topMargin=dp(12);box.addView(remove,removeParams);
            remove.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("删除闹钟？").setMessage(String.format(Locale.getDefault(),"%02d:%02d  %s",existing.hour,existing.minute,existing.label)).setNegativeButton("取消",null).setPositiveButton("删除",(confirmation,which)->{dialog.dismiss();delete(existing);}).show());
        }
        dialog.setOnDismissListener(d->{stopPreview();pendingRingtoneLabel=null;pendingPreviewButton=null;});
        dialog.setOnShowListener(x -> dialog.getButton(-1).setOnClickListener(v -> {
            picker.clearFocus();
            int hour=picker.getHour(),minute=picker.getMinute();
            Alarm conflict=findTimeConflict(hour,minute,existing==null?-1:existing.id);
            if(conflict!=null){new AlertDialog.Builder(this).setTitle("时间已被使用").setMessage(String.format(Locale.CHINA,"%02d:%02d 已有闹钟“%s”。请修改时间或删除原闹钟。",hour,minute,conflict.label)).setPositiveButton("知道了",null).show();return;}
            int mode=repeatMode.getSelectedItemPosition();
            int mask=mode==0?0:mode==1?31:mode==2?127:customMask[0];
            if(mode==3&&mask==0){Toast.makeText(this,"请至少选择一个星期",Toast.LENGTH_SHORT).show();return;}
            String name=label.getText().toString().trim();if(name.isEmpty())name="闹钟";
            Alarm a=new Alarm(existing==null?AlarmStore.nextId(this):existing.id,hour,minute,name,existing==null||existing.enabled||hour!=existing.hour||minute!=existing.minute,mask);
            a.ringtoneUri=pendingRingtoneUri;a.vibrate=vibrate.isChecked();a.gradualVolume=gradual.isChecked();
            a.ringDurationMinutes=new int[]{5,10,20}[duration.getSelectedItemPosition()];a.snoozeMinutes=new int[]{5,10,15}[snooze.getSelectedItemPosition()];a.maxSnoozes=new int[]{1,3,5}[snoozeMax.getSelectedItemPosition()];
            AlarmScheduler.cancel(this,a.id);
            if(a.enabled&&!AlarmScheduler.schedule(this,a)){a.enabled=false;update(a);showExactAlarmPermission();refresh();return;}
            update(a);Toast.makeText(this,a.enabled?"已保存并重新设置闹钟":"已保存（保持关闭）",Toast.LENGTH_SHORT).show();dialog.dismiss();refresh();
        }));dialog.show();dialog.getWindow().setBackgroundDrawable(rounded(BACKGROUND));dialog.getWindow().setLayout(-1,-1);
    }

    private void addGroup(LinearLayout box,String title,View... views){
        TextView heading=text(title,13,SECONDARY);heading.setPadding(dp(4),dp(22),0,dp(10));box.addView(heading);
        LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);group.setPadding(dp(14),dp(8),dp(14),dp(8));group.setBackground(rounded(SURFACE));
        for(int i=0;i<views.length;i++){View view=views[i];view.setMinimumHeight(dp(52));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);if(i>0)params.topMargin=dp(6);group.addView(view,params);}
        box.addView(group);
    }
    private void stopPreview(){clockHandler.removeCallbacks(stopPreviewTimeout);if(preview!=null){preview.stop();preview=null;}if(pendingPreviewButton!=null)pendingPreviewButton.setText("试听铃声");}
    private void previewRingtone(){
        if(preview!=null){stopPreview();return;}
        try{
            Uri uri=pendingRingtoneUri.isEmpty()?android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM):Uri.parse(pendingRingtoneUri);
            preview=android.media.RingtoneManager.getRingtone(this,uri);
            if(preview==null)throw new IOException();
            preview.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
            preview.play();if(pendingPreviewButton!=null)pendingPreviewButton.setText("停止试听");clockHandler.postDelayed(stopPreviewTimeout,8000);
        }catch(Exception e){stopPreview();Toast.makeText(this,"无法试听，请重新选择铃声",Toast.LENGTH_LONG).show();}
    }
    private void showVolume(){
        android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(16),dp(24),dp(16));
        TextView value=text("",16,TEXT);box.addView(value);
        SeekBar bar=new SeekBar(this);bar.setMax(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM));bar.setProgress(audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM));box.addView(bar);
        value.setText("系统闹钟音量："+bar.getProgress()+" / "+bar.getMax());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){if(user)audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM,p,0);value.setText("系统闹钟音量："+p+" / "+b.getMax());}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });
        new AlertDialog.Builder(this).setTitle("闹钟音量").setView(box).setPositiveButton("完成",null).show();
    }
    private void update(Alarm alarm) { List<Alarm> all=AlarmStore.load(this); boolean found=false; for(int i=0;i<all.size();i++) if(all.get(i).id==alarm.id){all.set(i,alarm);found=true;} if(!found)all.add(alarm); AlarmStore.save(this,all); }
    private Alarm findTimeConflict(int hour,int minute,int ownId) { for(Alarm alarm:AlarmStore.load(this)) if(alarm.id!=ownId&&alarm.hour==hour&&alarm.minute==minute)return alarm;return null; }
    private void delete(Alarm alarm) { List<Alarm> all=AlarmStore.load(this); all.removeIf(a->a.id==alarm.id); AlarmStore.save(this,all); AlarmScheduler.cancel(this,alarm.id); refresh(); }
    private void cancelPendingSnooze(Alarm alarm) { AlarmScheduler.cancelSnooze(this,alarm.id); alarm.pendingSnoozeAt=0L; update(alarm); refresh(); }
    private String daysText(int mask) { if(mask==0)return "仅一次"; if(mask==31)return "工作日"; if(mask==127)return "每天"; StringBuilder s=new StringBuilder("周"); for(int i=0;i<7;i++)if((mask&(1<<i))!=0)s.append(dayNames[i]); return s.toString(); }
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private RippleDrawable ripple(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));GradientDrawable mask=new GradientDrawable();mask.setColor(Color.WHITE);mask.setCornerRadius(dp(radius));return new RippleDrawable(ColorStateList.valueOf(0x338AB4F8),shape,mask);}
    private ColorStateList controlColors(int checked,int unchecked){return new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{checked,unchecked});}
    private void tintSwitch(Switch view){view.setThumbTintList(controlColors(ACCENT,0xFF8B929E));view.setTrackTintList(controlColors(0xFF3E587C,0xFF343A44));}
    private Button smallButton(String text) { Button b=new Button(this);b.setText(text);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(ACCENT);b.setMinHeight(dp(48));b.setMinimumWidth(0);b.setPadding(dp(12),0,dp(12),0);b.setBackground(ripple(0xFF272C34,14));return b; }
    private Spinner spinner(String[] items, int selected) { Spinner s=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,items);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(adapter);s.setSelection(Math.max(0, selected));return s; }
    private int indexOf(int[] values, int value) { for(int i=0;i<values.length;i++) if(values[i]==value)return i; return 0; }
    private void chooseRingtone() { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,PICK_RINGTONE); }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if(requestCode==PICK_RINGTONE){ try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){} pendingRingtoneUri=uri.toString(); if(pendingRingtoneLabel!=null)pendingRingtoneLabel.setText("自定义铃声 · 点击更换"); }
    }
    private void skipNext(Alarm alarm) { if(alarm.daysMask==0){Toast.makeText(this,"一次性闹钟不能跳过下次，可直接关闭",Toast.LENGTH_SHORT).show();return;} boolean restore=alarm.skippedOccurrence>System.currentTimeMillis(); alarm.skippedOccurrence=restore ? 0 : AlarmScheduler.nextTrigger(alarm,System.currentTimeMillis()); AlarmScheduler.cancelRegular(this,alarm.id); update(alarm); AlarmScheduler.schedule(this,alarm); Toast.makeText(this,restore ? "已恢复下一次响铃" : "已跳过下一次响铃",Toast.LENGTH_SHORT).show(); refresh(); }
    private void updateNextAlarm(List<Alarm> alarms) {
        long now=System.currentTimeMillis(),best=Long.MAX_VALUE;boolean snooze=false;
        for(Alarm alarm:alarms){if(alarm.enabled){long regular=AlarmScheduler.nextTrigger(alarm,now);if(regular<best){best=regular;snooze=false;}}if(alarm.pendingSnoozeAt>0L&&alarm.pendingSnoozeAt<best){best=alarm.pendingSnoozeAt;snooze=true;}}
        nextCaption.setText(snooze?"下一个稍后提醒":"下一个闹钟");
        if(best==Long.MAX_VALUE){nextAlarm.setText("—");nextAlarm.setTextColor(0xFF707887);nextDetail.setText("当前没有待响的闹钟");return;}
        nextAlarm.setText(new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(best)));nextAlarm.setTextColor(ACCENT);
        if(snooze&&best<=now){nextDetail.setText("已到提醒时间，等待响铃");return;}
        long minutes=Math.max(0,(best-now+59999)/60000);
        String remaining=minutes>=60?(minutes/60)+" 小时 "+(minutes%60)+" 分钟后":minutes+" 分钟后";
        nextDetail.setText(new java.text.SimpleDateFormat("M月d日 E",Locale.CHINA).format(new Date(best))+" · "+remaining);
    }
    private void testAlarm() { Intent i=new Intent(this,AlarmService.class).putExtra("alarm_id",-777).putExtra("label","测试闹钟").putExtra("vibrate",true).putExtra("gradual",false).putExtra("duration",1).putExtra("max_snoozes",0); if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i); }
    private boolean hasExactAlarmPermission(){AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);return Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms();}
    private boolean hasNotificationPermission(){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);NotificationChannel channel=nm.getNotificationChannel(AlarmService.CHANNEL);
        return nm.areNotificationsEnabled()&&(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);
    }
    private boolean hasFullScreenPermission(){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);return Build.VERSION.SDK_INT<34||nm.canUseFullScreenIntent();}
    private void updatePermissionHint(){
        if(permissionHint==null)return;
        String missing=!hasExactAlarmPermission()?"需要允许闹钟和提醒 · 点此设置":!hasNotificationPermission()?"响铃通知未开启 · 点此设置":!hasFullScreenPermission()?"锁屏提醒未开启 · 点此设置":"";
        permissionHint.setText(missing);permissionHint.setVisibility(missing.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void showPermissionStatus() {
        boolean exact=hasExactAlarmPermission(),notice=hasNotificationPermission(),full=hasFullScreenPermission();
        String message="精确闹钟："+(exact?"已允许":"未允许")+"\n通知："+(notice?"已允许":"未允许")+"\n锁屏全屏提醒："+(full?"已允许":"未允许")+"\n自启动/后台运行：vivo 系统需手动确认";
        new AlertDialog.Builder(this).setTitle("权限检查").setMessage(message).setNegativeButton("关闭",null).setPositiveButton("修复权限",(d,w)->openMissingPermission(exact,notice,full)).show();
    }
    private void openMissingPermission(boolean exact,boolean notice,boolean full){
        try{
            if(!exact){startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));return;}
            if(!full && Build.VERSION.SDK_INT>=34){startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName())));return;}
            if(!notice){
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);return;}
                NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel channel=nm.getNotificationChannel(AlarmService.CHANNEL);
                if(nm.areNotificationsEnabled() && channel!=null && channel.getImportance()==NotificationManager.IMPORTANCE_NONE){
                    startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,AlarmService.CHANNEL));
                } else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
                return;
            }
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
        }catch(Exception ignored){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    }
    private void showExactAlarmPermission() {
        new AlertDialog.Builder(this).setTitle("需要精确闹钟权限")
                .setMessage("没有该权限，应用无法安排精确闹钟。请在下一页允许“闹钟和提醒”。")
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
