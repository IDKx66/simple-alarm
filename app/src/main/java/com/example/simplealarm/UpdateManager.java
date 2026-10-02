package com.example.simplealarm;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks a small JSON manifest, downloads a newer APK and opens Android's installer. */
public final class UpdateManager {
    private static final String PREFS = "updates";
    private static final String KEY_URL = "manifest_url";
    private static final String DEFAULT_URL = "https://simple-alarm-updates.idkx66.chatgpt.site/update.json";

    public static void check(Activity activity) {
        String url = activity.getSharedPreferences(PREFS, 0).getString(KEY_URL, DEFAULT_URL);
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> fetch(activity, url)).start();
    }

    private static void askForSource(Activity activity) {
        EditText input = new EditText(activity);
        input.setHint("https://你的地址/update.json"); input.setSingleLine(true);
        input.setPadding(32, 8, 32, 8);
        new AlertDialog.Builder(activity).setTitle("设置更新地址")
                .setMessage("应用会从此地址读取版本信息。地址只需设置一次，以后点击即可自动检查并下载更新。")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存并检查", (d,w) -> {
                    String value=input.getText().toString().trim();
                    if (!value.startsWith("https://")) { Toast.makeText(activity,"请输入 HTTPS 地址",Toast.LENGTH_SHORT).show(); return; }
                    activity.getSharedPreferences(PREFS,0).edit().putString(KEY_URL,value).apply(); check(activity);
                }).show();
    }

    private static void fetch(Activity activity, String source) {
        try {
            HttpURLConnection c=(HttpURLConnection)new URL(Uri.parse(source).buildUpon().appendQueryParameter("_check", String.valueOf(System.currentTimeMillis())).build().toString()).openConnection();
            c.setUseCaches(false); c.setRequestProperty("Cache-Control", "no-cache, no-store");
            c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setRequestProperty("Accept","application/json");
            if(c.getResponseCode()!=200)throw new Exception("HTTP "+c.getResponseCode());
            BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream())); StringBuilder s=new StringBuilder(); String line; while((line=r.readLine())!=null)s.append(line); r.close();
            JSONObject o=new JSONObject(s.toString()); int latest=o.getInt("versionCode"); String name=o.optString("versionName",String.valueOf(latest)); String apk=o.getString("apkUrl"); String sha256=o.optString("sha256",""); String notes=o.optString("notes","修复问题并改进体验");
            if (latest > activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode && !sha256.matches("[0-9a-fA-F]{64}")) throw new Exception("更新清单缺少有效的安装包校验值");
            android.content.pm.PackageInfo installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0);
            int current=installed.versionCode;
            String currentName=installed.versionName;
            String versions="当前安装："+currentName+"（"+current+"）\n服务器版本："+name+"（"+latest+"）";
            activity.runOnUiThread(() -> { if(latest<=current)new AlertDialog.Builder(activity).setTitle(latest == current ? "已是最新版本" : "当前安装版本高于更新源").setMessage(versions).setPositiveButton("确定",null).show(); else new AlertDialog.Builder(activity).setTitle("发现新版本 "+name).setMessage(versions+"\n\n"+notes).setNegativeButton("稍后",null).setPositiveButton("下载并更新",(d,w)->download(activity,apk,name,sha256)).show(); });
        } catch(Exception e) { activity.runOnUiThread(() -> new AlertDialog.Builder(activity).setTitle("检查更新失败").setMessage("无法读取更新信息。请检查网络或更新地址。\n\n"+e.getMessage()).setNegativeButton("关闭",null).setPositiveButton("重新设置地址",(d,w)->{activity.getSharedPreferences(PREFS,0).edit().remove(KEY_URL).apply();askForSource(activity);}).show()); }
    }

    private static void download(Activity activity, String url, String version, String sha256) {
        if(!url.startsWith("https://")){Toast.makeText(activity,"下载地址不安全",Toast.LENGTH_SHORT).show();return;}
        if(Build.VERSION.SDK_INT>=26 && !activity.getPackageManager().canRequestPackageInstalls()){
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));
            Toast.makeText(activity,"请先允许安装此来源，再点击检查更新",Toast.LENGTH_LONG).show();return;
        }
        DownloadManager dm=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url)).setTitle("简洁闹钟 "+version).setDescription("正在下载更新").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setMimeType("application/vnd.android.package-archive").setDestinationInExternalFilesDir(activity,"updates","SimpleAlarm-"+java.util.UUID.randomUUID()+".apk");
        long id=dm.enqueue(request);
        UpdateDownloadReceiver.setPendingDownload(activity,id,sha256);
        Toast.makeText(activity,"已开始下载，完成后会打开安装界面",Toast.LENGTH_LONG).show();
    }
}
