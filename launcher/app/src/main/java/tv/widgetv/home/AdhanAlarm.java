package tv.widgetv.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

/**
 * One exact alarm for the next of the five prayers (not sunrise). Fires {@link AdhanReceiver}, which
 * plays the chosen call and schedules the one after it. A prayer more than a few minutes late is skipped
 * so turning the TV on at night does not play the afternoon adhan.
 */
final class AdhanAlarm {
    static final String ACTION = "tv.widgetv.home.ADHAN";
    /** How long after a prayer time we still sound the call. */
    static final long LATE_MS = 3 * 60_000L;
    private static final int REQUEST = 41;

    private AdhanAlarm() {
    }

    static void schedule(Context context) {
        Context c = context.getApplicationContext();
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        PendingIntent existing = pending(c, 0, 0);
        if (alarms != null) alarms.cancel(existing);
        if (!Config.get(c).prayerEnabled || PrayerPrefs.adhanMode(c) == PrayerPrefs.ADHAN_OFF) {
            Log.i(Config.TAG, "adhan: alarm off");
            return;
        }
        long now = System.currentTimeMillis();
        PrayerDay day = PrayerPrefs.day(c, now);
        int[] prayers = PrayerPrefs.OFFSET_PRAYERS;
        for (int prayer : prayers) {
            long at = day.times[prayer];
            if (at <= now && now - at <= LATE_MS && !played(c, prayer, at)) {
                Log.i(Config.TAG, "adhan: catch-up " + PrayerDay.NAMES[prayer]);
                AdhanPlayer.start(c, prayer, at, false);
                break;
            }
        }
        long when = day.tomorrowFajr;
        int which = PrayerDay.FAJR;
        for (int prayer : prayers) {
            long at = day.times[prayer];
            if (at > now + 2_000 && at < when) {
                when = at;
                which = prayer;
            }
        }
        if (alarms == null || when <= now) {
            Log.w(Config.TAG, "adhan: no upcoming prayer to schedule");
            return;
        }
        PendingIntent fire = pending(c, which, when);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, fire);
                Log.w(Config.TAG, "adhan: exact alarms not allowed; using an inexact alarm");
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, fire);
            }
        } catch (SecurityException denied) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, fire);
            Log.w(Config.TAG, "adhan: exact alarm denied", denied);
        }
        Calendar clock = Calendar.getInstance();
        clock.setTimeInMillis(when);
        Log.i(Config.TAG, String.format(Locale.US, "adhan: next %s at %02d:%02d mode=%d",
                PrayerDay.NAMES[which], clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE),
                PrayerPrefs.adhanMode(c)));
    }

    static boolean played(Context c, int prayer, long at) {
        return key(prayer, at).equals(PrayerPrefs.of(c).getString("adhan_played", ""));
    }

    static void markPlayed(Context c, int prayer, long at) {
        PrayerPrefs.of(c).edit().putString("adhan_played", key(prayer, at)).apply();
    }

    private static String key(int prayer, long at) {
        return prayer + ":" + (at / 60_000L);
    }

    private static PendingIntent pending(Context c, int prayer, long at) {
        Intent intent = new Intent(c, AdhanReceiver.class).setAction(ACTION)
                .putExtra("prayer", prayer).putExtra("at", at);
        return PendingIntent.getBroadcast(c, REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
