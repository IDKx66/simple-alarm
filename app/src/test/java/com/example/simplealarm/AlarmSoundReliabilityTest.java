package com.example.simplealarm;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Looper;
import android.os.UserManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 27}, shadows = AlarmSoundReliabilityTest.LocalRingtoneManager.class)
public class AlarmSoundReliabilityTest {
    private static final Uri SHORT_SOUND = Uri.parse("content://simplealarm.test/short-sound");
    private Context context;
    private ServiceController<AlarmService> controller;
    private AlarmService service;
    private final List<MediaPlayer> players = new ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        context.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        AlarmStore.save(context, Collections.singletonList(
                new Alarm(1000, 7, 0, "短铃声", false, 0)));
        ShadowMediaPlayer.setMediaInfoProvider(source -> shortSound());
        ShadowMediaPlayer.setCreateListener((player, shadow) -> players.add(player));
        controller = Robolectric.buildService(AlarmService.class).create();
        service = controller.get();
    }

    @After public void tearDown() {
        controller.destroy();
        AlarmStore.save(context, Collections.emptyList());
    }

    @Test public void shortSoundKeepsPlayingBeyondItsOwnLengthOnAndroid8() {
        startAlarm(1000, SHORT_SOUND, false, 5);
        assertNotNull("铃声应开始播放", playingPlayer());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS);

        MediaPlayer playing = playingPlayer();
        assertNotNull("3秒铃声播放结束后必须继续循环直到用户停止或响铃超时", playing);
        assertEquals(AudioAttributes.USAGE_ALARM,
                Shadows.shadowOf(playing).getAudioAttributes().getUsage());
    }

    @Test public void stoppingAlarmReleasesSoundAndClearsRingingState() {
        startAlarm(1000, SHORT_SOUND, false, 5);
        assertNotNull(playingPlayer());

        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_STOP).putExtra("alarm_id", 1000), 0, 2);

        assertNull(playingPlayer());
        assertNull(AlarmService.ringingState());
        for (MediaPlayer player : players) {
            assertEquals(ShadowMediaPlayer.State.END, Shadows.shadowOf(player).getState());
        }
    }

    @Test public void loopingSoundStopsAtConfiguredTimeout() {
        startAlarm(1000, SHORT_SOUND, false, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(59, TimeUnit.SECONDS);
        assertNotNull("铃声应持续至一分钟响铃上限", playingPlayer());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

        assertNull(playingPlayer());
        assertNull(AlarmService.ringingState());
    }

    @Test public void unreadableCustomSoundFallsBackToLoopingSystemAlarmSound() {
        ShadowMediaPlayer.addException(DataSource.toDataSource(context, SHORT_SOUND),
                new IOException("custom document is unavailable"));

        startAlarm(1000, SHORT_SOUND, false, 5);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS);

        MediaPlayer playing = playingPlayer();
        assertNotNull("自定义文件失效后系统默认闹铃应持续播放", playing);
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                Shadows.shadowOf(playing).getSourceUri());
    }

    @Test public void joiningAlarmKeepsOneLoopingSoundAndExtendsTimeout() {
        startAlarm(1000, SHORT_SOUND, false, 1);
        MediaPlayer first = playingPlayer();
        assertNotNull(first);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.SECONDS);

        startAlarm(1001, SHORT_SOUND, false, 5);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS);

        assertSame("合并响铃继续使用同一播放器", first, playingPlayer());
        assertArrayEquals(new int[]{1000, 1001}, AlarmService.ringingState().activeIds());
    }

    @Test @Config(sdk = 28) public void android9KeepsExistingLoopingRingtonePath() {
        startAlarm(1000, SHORT_SOUND, false, 5);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS);

        assertNotNull(playingPlayer());
        Ringtone ringtone = ReflectionHelpers.getField(service, "ringtone");
        assertNotNull(ringtone);
        assertTrue(ringtone.isLooping());
    }

    @Test @Config(sdk = 28, shadows = {LocalRingtoneManager.class, FailingCustomRingtone.class})
    public void customPlaybackFailureKeepsDefaultRingtoneLoopingAndAlarmAudioUsage() {
        startAlarm(1000, SHORT_SOUND, false, 5);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS);

        MediaPlayer playing = playingPlayer();
        assertNotNull("自定义铃声播放失败后的默认声音也必须循环", playing);
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                Shadows.shadowOf(playing).getSourceUri());
        assertEquals(AudioAttributes.USAGE_ALARM,
                Shadows.shadowOf(playing).getAudioAttributes().getUsage());
    }

    @Test @Config(sdk = 28, shadows = {LocalRingtoneManager.class, FailingEveryRingtone.class})
    public void fallbackPlaybackFailureKeepsVibrationAndStopControlsAvailable() {
        service.onStartCommand(new Intent(context, AlarmService.class)
                .putExtra("alarm_id", 1000).putExtra("ringtone_uri", SHORT_SOUND.toString())
                .putExtra("vibrate", true).putExtra("gradual", false), 0, 1);

        assertNull(playingPlayer());
        assertTrue(AlarmService.isVibrationEnabled());
        assertNotNull(AlarmService.ringingState());
        assertTrue(Shadows.shadowOf(context.getSystemService(android.os.Vibrator.class)).isVibrating());
        service.onStartCommand(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_STOP).putExtra("alarm_id", 1000), 0, 2);
        assertNull(AlarmService.ringingState());
        assertFalse(AlarmService.isVibrationEnabled());
    }

    private static ShadowMediaPlayer.MediaInfo shortSound() {
        ShadowMediaPlayer.MediaInfo info = new ShadowMediaPlayer.MediaInfo(3000, 0);
        info.events.clear();
        info.scheduleEventAtOffset(3000, (player, shadow) -> {
            shadow.doStop();
            if (player.isLooping()) {
                shadow.setCurrentPosition(0);
                shadow.doStart();
                // ShadowMediaPlayer schedules again after this callback. Keep
                // one completion event per loop, rather than duplicate timers.
                shadow.getHandler().removeMessages(ShadowMediaPlayer.MEDIA_EVENT);
            } else {
                shadow.invokeCompletionListener();
            }
        });
        return info;
    }

    private void startAlarm(int id, Uri uri, boolean gradual, int duration) {
        service.onStartCommand(new Intent(context, AlarmService.class)
                .putExtra("alarm_id", id).putExtra("ringtone_uri", uri.toString())
                .putExtra("vibrate", false).putExtra("gradual", gradual)
                .putExtra("duration", duration), 0, id);
    }

    private MediaPlayer playingPlayer() {
        for (MediaPlayer player : players) {
            if (Shadows.shadowOf(player).getState() == ShadowMediaPlayer.State.STARTED
                    && player.isPlaying()) return player;
        }
        return null;
    }

    @Implements(RingtoneManager.class)
    public static class LocalRingtoneManager {
        @Implementation protected static Ringtone getRingtone(Context context, Uri uri) {
            // Give the original Ringtone path a real local player with a simulated
            // three-second source. Only the test fixture invokes hidden constructors.
            Ringtone ringtone = ReflectionHelpers.callConstructor(Ringtone.class,
                    ReflectionHelpers.ClassParameter.from(Context.class, context),
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
            ReflectionHelpers.callInstanceMethod(ringtone, "setUri",
                    ReflectionHelpers.ClassParameter.from(Uri.class, uri));
            return ringtone;
        }
    }

    @Implements(Ringtone.class)
    public static class FailingCustomRingtone {
        @RealObject private Ringtone ringtone;
        @Implementation protected void play() {
            if (SHORT_SOUND.equals(ReflectionHelpers.getField(ringtone, "mUri"))) {
                throw new IllegalStateException("custom source cannot play");
            }
            Shadow.directlyOn(ringtone, Ringtone.class, "play");
        }
    }

    @Implements(Ringtone.class)
    public static class FailingEveryRingtone {
        @Implementation protected void play() {
            throw new IllegalStateException("system audio is unavailable");
        }
    }
}
