package tv.widgetv.home;

import android.app.Notification;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Fire OS will not give up the Home key. While the panel switch is on, this watches for the Amazon
 * home window and opens WidgeTV. A lapis cover hides the ad row during the few seconds Fire OS holds
 * that launch. Settings screens in the same package are left alone. Turning the switch off stops
 * this service, which is how the Fire Stick settings stay reachable.
 */
public class HomeWatch extends Service {
    private static final String TAG = Config.TAG;
    private static final long POLL_MS = 350;
    private static WeakReference<HomeWatch> live;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastSeen;
    private long lastOpen;
    private boolean running;
    private View cover;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!AmazonHome.present(this) || !PrayerPrefs.amazonHome(this)) {
            stopSelf();
            return;
        }
        running = true;
        live = new WeakReference<>(this);
        Notification notice = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_adhan)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.amazon_home))
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(9, notice);
        main.post(poll);
        Log.i(TAG, "home watch: started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!PrayerPrefs.amazonHome(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null || power.isInteractive()) look();
            main.postDelayed(this, POLL_MS);
        }
    };

    private void look() {
        UsageStatsManager stats = getSystemService(UsageStatsManager.class);
        if (stats == null) return;
        long now = System.currentTimeMillis();
        UsageEvents events = stats.queryEvents(now - 4000, now);
        if (events == null) return;
        UsageEvents.Event event = new UsageEvents.Event();
        String pkg = null;
        String cls = null;
        long when = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
            if (event.getTimeStamp() < when) continue;
            when = event.getTimeStamp();
            pkg = event.getPackageName();
            cls = event.getClassName();
        }
        if (cls == null) return;
        boolean amazonHome = "com.amazon.tv.launcher".equals(pkg) && cls.endsWith("HomeActivity_vNext");
        boolean starter = "com.amazon.firehomestarter".equals(pkg) && cls.endsWith("HomeStarterActivity");
        if (!amazonHome && !starter) {
            lastSeen = when;
            return;
        }
        long uptime = SystemClock.uptimeMillis();
        if (when == lastSeen && uptime - lastOpen < 800) return;
        lastSeen = when;
        if (uptime - lastOpen < 800) return;
        lastOpen = uptime;
        Log.i(TAG, "home watch: replacing " + cls);
        showCover();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** Lapis window over the Amazon home. Their launcher stays focused until the start is released. */
    private void showCover() {
        if (cover != null) return;
        TextView shade = new TextView(this);
        shade.setBackgroundColor(0xFF0C1B33);
        shade.setText(getString(R.string.app_name));
        shade.setTextColor(0xFFE8DCC8);
        shade.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        shade.setGravity(Gravity.CENTER);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(shade, lp);
            cover = shade;
        } catch (RuntimeException denied) {
            Log.w(TAG, "home watch: cover refused", denied);
        }
    }

    /** Called once WidgeTV is actually in front, and when the panel switch is turned off. */
    static void dismissCover() {
        HomeWatch watch = live == null ? null : live.get();
        if (watch != null) watch.hideCover();
    }

    private void hideCover() {
        if (cover == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        } catch (RuntimeException ignored) {
            // Already detached.
        }
        cover = null;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacks(poll);
        hideCover();
        if (live != null && live.get() == this) live = null;
        super.onDestroy();
    }
}
