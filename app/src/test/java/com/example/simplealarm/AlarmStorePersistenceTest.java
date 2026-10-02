package com.example.simplealarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.UserManager;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AlarmStorePersistenceTest {
    private Context context;
    private SharedPreferences credential;
    private SharedPreferences device;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        credential = context.getSharedPreferences("alarms", Context.MODE_PRIVATE);
        device = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("alarms", Context.MODE_PRIVATE);
        credential.edit().clear().commit();
        device.edit().clear().commit();
    }

    @Test public void savePersistsCurrentAlarmAndSnoozeWithoutCreatingBackup() throws Exception {
        Alarm first = new Alarm(1000, 7, 0, "旧闹钟", true, 127);
        AlarmStore.save(context, Collections.singletonList(first));
        Alarm replacement = new Alarm(1001, 8, 30, "当前闹钟", false, 0);
        replacement.pendingSnoozeAt = System.currentTimeMillis() + 300000L;
        replacement.snoozeCount = 2;

        AlarmStore.save(context, Collections.singletonList(replacement));

        // Storage migration invalidates Android's cached preferences instances.
        device = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("alarms", Context.MODE_PRIVATE);
        credential = context.getSharedPreferences("alarms", Context.MODE_PRIVATE);
        assertEquals("[" + replacement.toJson() + "]", device.getString("items", null));
        assertFalse(device.contains("items_backup"));
        assertFalse(credential.contains("items_backup"));
        assertEquals(1, AlarmStore.load(context).size());
        assertNull(AlarmStore.find(context, first.id));
        Alarm stored = AlarmStore.find(context, replacement.id);
        assertEquals(replacement.pendingSnoozeAt, stored.pendingSnoozeAt);
        assertEquals(2, stored.snoozeCount);
    }

    @Test public void authoritativeDeviceStorageClearsBothLegacyBackups() throws Exception {
        Alarm old = new Alarm(1000, 7, 0, "旧闹钟", true, 127);
        Alarm current = new Alarm(2000, 9, 0, "当前闹钟", true, 127);
        String oldJson = "[" + old.toJson() + "]";
        String currentJson = "[" + current.toJson() + "]";
        credential.edit().putString("items", oldJson).putString("items_backup", oldJson)
                .putInt("next_id", 1001).commit();
        device.edit().putString("items", currentJson).putString("items_backup", oldJson)
                .putInt("next_id", 2001).commit();

        assertEquals("当前闹钟", AlarmStore.load(context).get(0).label);
        assertEquals(2001, AlarmStore.nextId(context));
        assertEquals(2002, AlarmStore.nextId(context));
        assertFalse(device.contains("items_backup"));
        assertFalse(credential.contains("items_backup"));
        assertEquals(currentJson, device.getString("items", null));
    }

    @Test public void damagedCurrentDataDoesNotRestoreOldBackupOrPartialItems() throws Exception {
        Alarm old = new Alarm(1000, 7, 0, "已删除的旧闹钟", true, 127);
        String oldJson = "[" + old.toJson() + "]";
        device.edit().putString("items", "[" + old.toJson() + ",\"broken\"]")
                .putString("items_backup", oldJson).commit();

        assertTrue(AlarmStore.load(context).isEmpty());
        assertFalse(device.contains("items_backup"));
        assertNull(AlarmStore.find(context, old.id));
    }

    @Test public void lockedStorageClearsLegacyBackupAndKeepsCurrentAlarm() throws Exception {
        Alarm alarm = new Alarm(1000, 7, 0, "锁屏闹钟", true, 127);
        String json = "[" + alarm.toJson() + "]";
        device.edit().putString("items", json).putString("items_backup", json).commit();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(false);

        Context locked = context.createDeviceProtectedStorageContext();
        assertEquals("锁屏闹钟", AlarmStore.load(locked).get(0).label);
        assertEquals(json, device.getString("items", null));
        assertFalse(device.contains("items_backup"));
    }

    @Test public void androidSystemBackupIsDisabled() {
        assertEquals(0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
    }
}
