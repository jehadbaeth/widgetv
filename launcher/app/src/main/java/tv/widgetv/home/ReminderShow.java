package tv.widgetv.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.PixelFormat;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Covers the screen with a reminder until OK or Back. The window needs the overlay permission
 * (appops SYSTEM_ALERT_WINDOW), the same grant the Fire Stick cover uses.
 */
public class ReminderShow extends Service {
    private static final String CHANNEL = "reminder_due";
    private static final int NOTIF = 11;
    private static final long STAY_MS = 10 * 60_000L;
    /** Six double-beeps, played twice, then the card stays quiet. */
    private static final long BEEP_MS = 10_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private View card;
    private PowerManager.WakeLock lock;
    private MediaPlayer beep;
    private AudioFocusRequest focusRequest;

    static void start(Context context, String text, long at) {
        Context c = context.getApplicationContext();
        Intent intent = new Intent(c, ReminderShow.class).putExtra("text", text).putExtra("at", at);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent);
        else c.startService(intent);
    }

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
        String text = intent != null ? intent.getStringExtra("text") : null;
        long at = intent != null ? intent.getLongExtra("at", 0) : 0;
        if (text == null || text.isEmpty()) {
            startInFront(notification(""));
            stopSelf();
            return START_NOT_STICKY;
        }
        startInFront(notification(text));
        wake();
        show(text, at);
        playBeep();
        main.removeCallbacks(dismiss);
        main.postDelayed(dismiss, STAY_MS);
        return START_NOT_STICKY;
    }

    private void startInFront(Notification notice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.reminder_due_channel), NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        startForeground(NOTIF, notice);
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_adhan)
                .setContentTitle(getString(R.string.reminders))
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .build();
    }

    @SuppressWarnings("deprecation")
    private void wake() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) return;
        if (lock != null && lock.isHeld()) lock.release();
        lock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "widgetv:reminder");
        lock.acquire(STAY_MS);
    }

    private void show(String text, long at) {
        hide();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xE60C1B33);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && dismissKey(keyCode)) dismiss.run();
            return true;
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Lapis.LAPIS_800);
        column.setPadding(dp(36), dp(28), dp(36), dp(28));
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = line(getString(R.string.reminders), 18, Lapis.GOLD_400);
        TextView body = line(text, 40, Lapis.PLASTER_100);
        String when = at > 0
                ? DateTimeFormatter.ofPattern("HH:mm", Locale.US)
                .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
                : "";
        TextView time = line(when, 18, Lapis.MIST_400);
        TextView ok = line(getString(R.string.reminder_ok), 18, Lapis.GOLD_400);
        column.addView(title);
        column.addView(body);
        if (!when.isEmpty()) column.addView(time);
        column.addView(ok);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(dp(760),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(column, cardLp);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        if (!add(root, type) && type != WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) {
            add(root, WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        }
        root.post(root::requestFocus);
    }

    private boolean add(View root, int type) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(root, lp);
            card = root;
            Log.i(Config.TAG, "reminder: card shown type=" + type);
            return true;
        } catch (RuntimeException denied) {
            Log.w(Config.TAG, "reminder: card refused type=" + type, denied);
            return false;
        }
    }

    private TextView line(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    /** The fireplace drops its crackle while this holds audio focus. */
    private void playBeep() {
        releaseBeep();
        AudioManager audio = getSystemService(AudioManager.class);
        requestFocus(audio);
        try {
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.reminder_beep);
            MediaPlayer mp = new MediaPlayer();
            beep = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setLooping(true);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnErrorListener((player, what, extra) -> {
                Log.w(Config.TAG, "reminder: beep error " + what + "/" + extra);
                releaseBeep();
                return true;
            });
            mp.prepareAsync();
            main.removeCallbacks(stopBeep);
            main.postDelayed(stopBeep, BEEP_MS);
            int vol = audio == null ? -1 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audio == null ? -1 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            Log.i(Config.TAG, "reminder: beep volume=" + vol + "/" + max);
        } catch (Exception e) {
            Log.w(Config.TAG, "reminder: cannot beep", e);
            releaseBeep();
        }
    }

    @SuppressWarnings("deprecation")
    private void requestFocus(AudioManager audio) {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
            audio.requestAudioFocus(focusRequest);
        } else {
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    private final Runnable stopBeep = this::releaseBeep;

    private void releaseBeep() {
        main.removeCallbacks(stopBeep);
        MediaPlayer mp = beep;
        beep = null;
        if (mp != null) {
            try {
                mp.setOnCompletionListener(null);
                mp.setOnErrorListener(null);
                if (mp.isPlaying()) mp.stop();
            } catch (IllegalStateException ignored) {
                // Not started yet.
            }
            mp.release();
        }
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(null);
        }
        focusRequest = null;
    }

    private static boolean dismissKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_ESCAPE;
    }

    private final Runnable dismiss = () -> {
        Log.i(Config.TAG, "reminder: dismissed");
        stopSelf();
    };

    private void hide() {
        if (card == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(card);
        } catch (RuntimeException ignored) {
            // Already detached.
        }
        card = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(dismiss);
        releaseBeep();
        hide();
        if (lock != null && lock.isHeld()) lock.release();
        stopForeground(true);
        super.onDestroy();
    }
}
