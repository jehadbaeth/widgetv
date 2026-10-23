package tv.widgetv.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Sounds the adhan and asks other apps to pause, then gives audio focus back so they can resume.
 * An app or an HDMI input that ignores audio focus keeps playing under the call.
 * Full and takbir clips are File:Adhan.ogg by Aishatu98, CC0, Wikimedia Commons.
 */
public class AdhanPlayer extends Service {
    private static final String TAG = Config.TAG;
    private static final String CHANNEL = "adhan";
    private static final int NOTIF_PLAYING = 7;
    private static final int NOTIF_BLOB = 8;
    private static final long BLOB_MS = 8_000L;

    /** Home screen shows a gold pill while this is running. Cleared when the activity pauses. */
    interface Cue {
        void onAdhan(int prayer, int mode);
        void onAdhanEnd();
    }

    private static volatile Cue cue;
    private static volatile int activePrayer = -1;
    private static volatile int activeMode = -1;

    static void setCue(Cue next) {
        cue = next;
    }

    static int activePrayer() {
        return activePrayer;
    }

    static int activeMode() {
        return activeMode;
    }

    static void start(Context context, int prayer, long at, boolean preview) {
        start(context, prayer, at, preview, -1);
    }

    static void start(Context context, int prayer, long at, boolean preview, int modeOverride) {
        Context c = context.getApplicationContext();
        int mode = modeOverride >= 0 ? modeOverride : PrayerPrefs.adhanMode(c);
        if (mode < 0 || mode >= PrayerPrefs.ADHAN_COUNT) mode = PrayerPrefs.ADHAN_FULL;
        if (!preview && mode == PrayerPrefs.ADHAN_OFF) return;
        if (mode == PrayerPrefs.ADHAN_OFF) mode = PrayerPrefs.ADHAN_TAKBIR;
        if (!preview && at > 0) {
            if (AdhanAlarm.played(c, prayer, at)) return;
            AdhanAlarm.markPlayed(c, prayer, at);
        }
        Intent intent = new Intent(c, AdhanPlayer.class)
                .putExtra("prayer", prayer)
                .putExtra("mode", mode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent);
        else c.startService(intent);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener focusListener = this::onFocus;
    private AudioFocusRequest focusRequest;
    private MediaPlayer player;
    private PowerManager.WakeLock lock;
    private int generation;
    private int prayer = PrayerDay.FAJR;
    private int mode = PrayerPrefs.ADHAN_FULL;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiLang.wrap(base));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int gen = ++generation;
        prayer = intent.getIntExtra("prayer", PrayerDay.FAJR);
        mode = intent.getIntExtra("mode", PrayerPrefs.adhanMode(this));
        if (prayer < 0 || prayer >= PrayerDay.NAMES.length) prayer = PrayerDay.FAJR;
        releasePlayer();
        activePrayer = prayer;
        activeMode = mode;
        acquireLock();
        Notification notice = notification(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_PLAYING, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_PLAYING, notice);
        }
        Cue showing = cue;
        if (showing != null) showing.onAdhan(prayer, mode);
        int granted = requestFocus();
        AudioManager audio = getSystemService(AudioManager.class);
        int vol = audio == null ? -1 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio == null ? -1 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Log.i(TAG, "adhan: play " + PrayerDay.NAMES[prayer] + " mode=" + mode + " focus=" + granted
                + " volume=" + vol + "/" + max);
        // Let the other app pause before the first syllable.
        main.postDelayed(() -> startClip(gen), 280);
        return START_NOT_STICKY;
    }

    private void startClip(int gen) {
        if (gen != generation) return;
        int res = mode == PrayerPrefs.ADHAN_FULL ? R.raw.adhan
                : mode == PrayerPrefs.ADHAN_TAKBIR ? R.raw.adhan_takbir : R.raw.adhan_beep;
        try {
            AssetFileDescriptor afd = getResources().openRawResourceFd(res);
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setOnPreparedListener(prepared -> {
                if (gen == generation) prepared.start();
            });
            mp.setOnCompletionListener(done -> finish(gen));
            mp.setOnErrorListener((mp1, what, extra) -> {
                Log.w(TAG, "adhan: player error " + what + "/" + extra);
                finish(gen);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "adhan: cannot play", e);
            finish(gen);
        }
    }

    private void finish(int gen) {
        if (gen != generation) return;
        generation++;
        boolean beep = mode == PrayerPrefs.ADHAN_BEEP;
        releasePlayer();
        abandonFocus();
        activePrayer = -1;
        activeMode = -1;
        Cue showing = cue;
        if (showing != null) showing.onAdhanEnd();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (beep && nm != null) {
            nm.notify(NOTIF_BLOB, notification(false));
            main.postDelayed(() -> nm.cancel(NOTIF_BLOB), BLOB_MS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        releaseLock();
        stopSelf();
        Log.i(TAG, "adhan: finished, audio focus released");
    }

    private void onFocus(int change) {
        main.post(() -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Log.i(TAG, "adhan: audio focus lost (" + change + ")");
                finish(generation);
            }
        });
    }

    @SuppressWarnings("deprecation") // AudioManager.requestAudioFocus(listener, stream, hint) before Android 8
    private int requestFocus() {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            return audio.requestAudioFocus(focusRequest);
        }
        return audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
    }

    @SuppressWarnings("deprecation") // the pre-Android 8 focus request
    private void abandonFocus() {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(focusListener);
        }
        focusRequest = null;
    }

    private void releasePlayer() {
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            mp.setOnCompletionListener(null);
            mp.setOnErrorListener(null);
            if (mp.isPlaying()) mp.stop();
        } catch (IllegalStateException ignored) {
            // already released or not started
        }
        mp.release();
    }

    private void acquireLock() {
        if (lock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "widgetv:adhan");
            lock.setReferenceCounted(false);
        }
        lock.acquire(90_000L);
    }

    private void releaseLock() {
        if (lock != null && lock.isHeld()) lock.release();
    }

    private Notification notification(boolean ongoing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.adhan_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(channel);
        }
        String name = getResources().getStringArray(R.array.prayer_names)[prayer];
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 8, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_adhan)
                .setContentTitle(getString(R.string.adhan))
                .setContentText(name)
                .setContentIntent(tap)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setSound(null);
        }
        if (!ongoing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTimeoutAfter(BLOB_MS);
        return b.build();
    }

    @Override
    public void onDestroy() {
        generation++;
        releasePlayer();
        abandonFocus();
        releaseLock();
        activePrayer = -1;
        activeMode = -1;
        super.onDestroy();
    }
}
