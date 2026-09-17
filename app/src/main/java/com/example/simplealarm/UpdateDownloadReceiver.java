package com.example.simplealarm;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import java.io.InputStream;
import java.security.MessageDigest;

/** Continues an app update even if Android recreated the application process. */
public class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        final PendingResult pending=goAsync();
        new Thread(()->{try{handle(context,intent);}finally{pending.finish();}}).start();
    }
    private void handle(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        long expectedId = context.getSharedPreferences("updates", 0)
                .getLong("pending_download_id", -1L);
        if (completedId < 0 || completedId != expectedId) return;

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(completedId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(context, "更新包下载失败，请重新检查更新", Toast.LENGTH_LONG).show();
                clearPending(context);
                return;
            }
        }

        Uri apk = manager.getUriForDownloadedFile(completedId);
        if (apk == null) {
            Toast.makeText(context, "下载完成，但无法读取安装包", Toast.LENGTH_LONG).show();
            return;
        }
        String expectedHash = context.getSharedPreferences("updates", 0)
                .getString("pending_sha256", "");
        if (!verifySha256(context, apk, expectedHash)) {
            Toast.makeText(context, "更新包校验失败，请重新下载", Toast.LENGTH_LONG).show();
            manager.remove(completedId);
            clearPending(context);
            return;
        }
        context.getSharedPreferences("updates",0).edit().putBoolean("ready_to_install",true).apply();
        android.app.NotificationManager nm=(android.app.NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new android.app.NotificationChannel("app_update","应用更新",android.app.NotificationManager.IMPORTANCE_DEFAULT));
        android.app.PendingIntent open=android.app.PendingIntent.getActivity(context,88,
            new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE);
        nm.notify(88,new android.app.Notification.Builder(context,"app_update").setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("更新包已校验，点击安装").setContentText("打开简洁闹钟继续安装").setContentIntent(open).setAutoCancel(true).build());
    }
    public static void offerInstall(android.app.Activity activity){
        android.content.SharedPreferences p=activity.getSharedPreferences("updates",0);
        if(!p.getBoolean("ready_to_install",false))return;
        new android.app.AlertDialog.Builder(activity).setTitle("新版已下载").setMessage("安装包已通过完整性校验，是否继续安装？")
        .setNegativeButton("稍后",null).setPositiveButton("安装",(d,w)->{
            if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));return;
            }
            DownloadManager dm=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri=dm.getUriForDownloadedFile(p.getLong("pending_download_id",-1));
            if(uri==null){clearPending(activity);return;}
            try{activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                p.edit().putBoolean("ready_to_install",false).apply();
            }catch(Exception e){Toast.makeText(activity,"无法打开安装程序，请从下载通知打开",Toast.LENGTH_LONG).show();}
        }).show();
    }

    private static void clearPending(Context context) {
        context.getSharedPreferences("updates", 0).edit()
                .remove("pending_download_id").remove("pending_sha256").apply();
    }

    private static boolean verifySha256(Context context, Uri uri, String expected) {
        if (expected == null || !expected.matches("[0-9a-f]{64}")) return false;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int read;
            while (input != null && (read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            StringBuilder actual = new StringBuilder();
            for (byte b : digest.digest()) actual.append(String.format("%02x", b));
            return expected.equals(actual.toString());
        } catch (Exception ignored) { return false; }
    }
}
