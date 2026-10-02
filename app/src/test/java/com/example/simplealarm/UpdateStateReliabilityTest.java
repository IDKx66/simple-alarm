package com.example.simplealarm;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = UpdateStateReliabilityTest.DownloadUris.class)
public class UpdateStateReliabilityTest {
    private Activity activity;
    private SharedPreferences preferences;
    private DownloadManager downloads;
    private final byte[] payload = "verified update package".getBytes(StandardCharsets.UTF_8);

    @Before public void setUp() {
        DownloadUris.completed.clear();
        DownloadUris.beforeQuery = null;
        ThreadCheckedToast.createdOn = null;
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        preferences = activity.getSharedPreferences("updates", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        downloads = activity.getSystemService(DownloadManager.class);
        Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
    }

    @Test public void startingNewDownloadDoesNotOfferOldVerifiedState() {
        preferences.edit().putLong("pending_download_id", 90L)
                .putBoolean("ready_to_install", true).commit();

        startDownload();

        assertTrue(preferences.contains("pending_download_id"));
        assertFalse(preferences.getBoolean("ready_to_install", false));
        UpdateDownloadReceiver.offerInstall(activity);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void failedDownloadClearsAllInstallationState() throws Exception {
        long id = enqueue(DownloadManager.STATUS_FAILED);
        storePending(id, sha256(payload), true);

        complete(id);

        assertFalse(preferences.contains("pending_download_id"));
        assertFalse(preferences.contains("pending_sha256"));
        assertFalse(preferences.getBoolean("ready_to_install", false));
        UpdateDownloadReceiver.offerInstall(activity);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void missingDownloadedUriDoesNotKeepOfferingInstallation() throws Exception {
        long id = enqueue(DownloadManager.STATUS_SUCCESSFUL);
        storePending(id, sha256(payload), true);
        UpdateDownloadReceiver.offerInstall(activity);
        AlertDialog offered = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(offered);

        offered.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse(preferences.getBoolean("ready_to_install", false));
        assertFalse(preferences.contains("pending_download_id"));
        assertFalse(offered.isShowing());
        UpdateDownloadReceiver.offerInstall(activity);
        assertSame(offered, ShadowAlertDialog.getLatestAlertDialog());
        assertFalse(offered.isShowing());
    }

    @Test public void currentCompletedDownloadIsOfferedOnlyAfterValidHash() throws Exception {
        long id = enqueue(DownloadManager.STATUS_SUCCESSFUL);
        storePending(id, sha256(payload), false);
        registerPayload(id, new ByteArrayInputStream(payload));
        UpdateDownloadReceiver.offerInstall(activity);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());

        complete(id);

        assertTrue(preferences.getBoolean("ready_to_install", false));
        assertEquals(id, preferences.getLong("pending_download_id", -1L));
        UpdateDownloadReceiver.offerInstall(activity);
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent install = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(install);
        assertEquals(DownloadUris.completed.get(id), install.getData());
        assertEquals(Intent.ACTION_VIEW, install.getAction());
    }

    @Test public void rejectedHashClearsStateAndNeverOffersInstallation() throws Exception {
        long id = enqueue(DownloadManager.STATUS_SUCCESSFUL);
        storePending(id, sha256("different".getBytes(StandardCharsets.UTF_8)), true);
        registerPayload(id, new ByteArrayInputStream(payload));

        complete(id);

        assertFalse(preferences.getBoolean("ready_to_install", false));
        assertFalse(preferences.contains("pending_download_id"));
        UpdateDownloadReceiver.offerInstall(activity);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void oldCompletionDoesNotChangeNewDownload() throws Exception {
        long oldId = enqueue(DownloadManager.STATUS_SUCCESSFUL);
        long newId = enqueue(DownloadManager.STATUS_PENDING);
        storePending(newId, sha256(payload), false);
        registerPayload(oldId, new ByteArrayInputStream(payload));

        complete(oldId);

        assertEquals(newId, preferences.getLong("pending_download_id", -1L));
        assertFalse(preferences.getBoolean("ready_to_install", false));
    }

    @Test public void oldDownloadFailureCannotClearNewDownload() throws Exception {
        long oldId = enqueue(DownloadManager.STATUS_FAILED);
        long newId = enqueue(DownloadManager.STATUS_PENDING);
        String newHash = sha256(payload);
        storePending(oldId, newHash, false);
        DownloadUris.beforeQuery = () -> storePending(newId, newHash, false);

        complete(oldId);

        assertEquals(newId, preferences.getLong("pending_download_id", -1L));
        assertEquals(newHash, preferences.getString("pending_sha256", ""));
        assertFalse(preferences.getBoolean("ready_to_install", false));
    }

    @Test public void oldVerificationFinishingAfterNewDownloadDoesNotMarkItReady() throws Exception {
        long oldId = enqueue(DownloadManager.STATUS_SUCCESSFUL);
        long newId = enqueue(DownloadManager.STATUS_PENDING);
        storePending(oldId, sha256(payload), false);
        registerPayload(oldId, new ByteArrayInputStream(payload) {
            private boolean replaced;
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                if (!replaced) {
                    replaced = true;
                    storePending(newId, preferences.getString("pending_sha256", ""), false);
                }
                return super.read(buffer, offset, length);
            }
        });

        complete(oldId);

        assertEquals(newId, preferences.getLong("pending_download_id", -1L));
        assertFalse(preferences.getBoolean("ready_to_install", false));
    }

    @Test public void oldRejectedVerificationCannotClearNewDownload() throws Exception {
        long oldId = enqueue(DownloadManager.STATUS_SUCCESSFUL);
        long newId = enqueue(DownloadManager.STATUS_PENDING);
        String newHash = sha256(payload);
        storePending(oldId, sha256("different".getBytes(StandardCharsets.UTF_8)), false);
        registerPayload(oldId, new ByteArrayInputStream(payload) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                storePending(newId, newHash, false);
                return super.read(buffer, offset, length);
            }
        });

        complete(oldId);

        assertEquals(newId, preferences.getLong("pending_download_id", -1L));
        assertEquals(newHash, preferences.getString("pending_sha256", ""));
        assertFalse(preferences.getBoolean("ready_to_install", false));
    }

    @Test public void staleInstallationDialogCannotOpenNewUnverifiedDownload() throws Exception {
        long oldId = enqueue(DownloadManager.STATUS_SUCCESSFUL);
        storePending(oldId, sha256(payload), true);
        registerPayload(oldId, new ByteArrayInputStream(payload));
        UpdateDownloadReceiver.offerInstall(activity);
        AlertDialog offered = ShadowAlertDialog.getLatestAlertDialog();
        long newId = enqueue(DownloadManager.STATUS_SUCCESSFUL);
        registerPayload(newId, new ByteArrayInputStream(payload));
        storePending(newId, sha256(payload), false);

        offered.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertEquals(newId, preferences.getLong("pending_download_id", -1L));
        assertFalse(preferences.getBoolean("ready_to_install", false));
    }

    @Test public void repeatedDownloadUsesDifferentSafeLocalDestination() {
        startDownload();
        long firstId = preferences.getLong("pending_download_id", -1L);
        ShadowDownloadManager.ShadowRequest first = Shadow.extract(
                Shadows.shadowOf(downloads).getRequest(firstId));
        startDownload();
        long secondId = preferences.getLong("pending_download_id", -1L);
        ShadowDownloadManager.ShadowRequest second = Shadow.extract(
                Shadows.shadowOf(downloads).getRequest(secondId));

        assertNotEquals(first.getDestination(), second.getDestination());
        assertTrue(second.getDestination().getLastPathSegment().endsWith(".apk"));
    }

    @Test @Config(sdk = 28, shadows = {DownloadUris.class, ThreadCheckedToast.class})
    public void workerFailureShowsFeedbackOnMainLooper() throws Exception {
        assertWorkerFailureFeedback();
    }

    @Test @Config(sdk = 26, shadows = {DownloadUris.class, ThreadCheckedToast.class})
    public void android8WorkerFailureShowsFeedbackOnMainLooper() throws Exception {
        assertWorkerFailureFeedback();
    }

    private void assertWorkerFailureFeedback() throws Exception {
        long id = enqueue(DownloadManager.STATUS_FAILED);
        storePending(id, sha256(payload), true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { complete(id); } catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        worker.join(5000L);
        assertFalse("下载完成处理不应一直等待主线程", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull("无 Looper 的工作线程不能直接构造 Toast", failure.get());
        assertSame(Looper.getMainLooper(), ThreadCheckedToast.createdOn);
        assertFalse(preferences.getBoolean("ready_to_install", false));
        assertFalse(preferences.contains("pending_download_id"));
        assertEquals("更新包下载失败，请重新检查更新", ShadowToast.getTextOfLatestToast());
    }

    private long enqueue(int status) {
        long id = downloads.enqueue(new DownloadManager.Request(Uri.parse("https://example.com/update.apk")));
        ShadowDownloadManager.ShadowRequest request = Shadow.extract(Shadows.shadowOf(downloads).getRequest(id));
        request.setStatus(status);
        return id;
    }

    private void startDownload() {
        ReflectionHelpers.callStaticMethod(UpdateManager.class, "download",
                ClassParameter.from(Activity.class, activity),
                ClassParameter.from(String.class, "https://example.com/update.apk"),
                ClassParameter.from(String.class, "2.0"),
                ClassParameter.from(String.class, "a".repeat(64)));
    }

    private void storePending(long id, String hash, boolean ready) {
        preferences.edit().putLong("pending_download_id", id).putString("pending_sha256", hash)
                .putBoolean("ready_to_install", ready).commit();
    }

    private void registerPayload(long id, ByteArrayInputStream input) {
        Uri uri = Uri.parse("content://downloads/test/" + id);
        DownloadUris.completed.put(id, uri);
        Shadows.shadowOf(activity.getContentResolver()).registerInputStream(uri, input);
        ResolveInfo installer = new ResolveInfo();
        installer.activityInfo = new ActivityInfo();
        installer.activityInfo.name = "PackageInstallerActivity";
        installer.activityInfo.packageName = "com.android.packageinstaller";
        installer.activityInfo.applicationInfo = new ApplicationInfo();
        installer.activityInfo.applicationInfo.packageName = installer.activityInfo.packageName;
        Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                        "application/vnd.android.package-archive"), installer);
    }

    private void complete(long id) {
        ReflectionHelpers.callInstanceMethod(new UpdateDownloadReceiver(), "handle",
                ClassParameter.from(Context.class, activity),
                ClassParameter.from(Intent.class, new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                        .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    @Implements(DownloadManager.class)
    public static class DownloadUris extends ShadowDownloadManager {
        static final Map<Long, Uri> completed = new HashMap<>();
        static Runnable beforeQuery;
        @Implementation @Override protected Cursor query(DownloadManager.Query query) {
            Runnable change = beforeQuery;
            beforeQuery = null;
            if (change != null) change.run();
            return super.query(query);
        }
        @Implementation protected Uri getUriForDownloadedFile(long id) {
            return completed.get(id);
        }
    }

    @Implements(Toast.class)
    public static class ThreadCheckedToast extends ShadowToast {
        static Looper createdOn;
        @Implementation public static Toast makeText(Context context, CharSequence text, int duration) {
            createdOn = Looper.myLooper();
            // API 26/28 Toast.TN requires this Looper; ShadowToast normally skips it.
            if (createdOn == null) throw new RuntimeException("Can't toast without a Looper");
            return ShadowToast.makeText(context, text, duration);
        }
    }
}
