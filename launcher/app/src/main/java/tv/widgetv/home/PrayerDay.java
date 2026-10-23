package tv.widgetv.home;

import android.icu.util.IslamicCalendar;
import android.util.Log;

import com.batoulapps.adhan.CalculationMethod;
import com.batoulapps.adhan.CalculationParameters;
import com.batoulapps.adhan.Coordinates;
import com.batoulapps.adhan.Madhab;
import com.batoulapps.adhan.PrayerTimes;
import com.batoulapps.adhan.data.DateComponents;

import java.util.Calendar;
import java.util.Date;

/**
 * Today's prayer times plus the night edges around them (yesterday's Isha, tomorrow's Fajr) and the
 * Hijri date. Ported from Afnon's PrayerDay.java (MIT, https://github.com/egyjs/afnon-android-tv-launcher):
 * parameters and per-prayer adjustments as at PrayerDay.java:41-48, the Umm al-Qura Hijri date as at
 * :80-86 and the month names from Text.java:310. Unlike Afnon, times[] always holds today's times; the next Fajr is kept in tomorrowFajr.
 */
final class PrayerDay {
    static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final String[] HIJRI_MONTHS = {"Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani",
            "Jumada al-Ula", "Jumada al-Akhirah", "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qadah",
            "Dhu al-Hijjah"}; // Afnon Text.java:310

    final long now;
    /** Today's six times, epoch millis. */
    final long[] times = new long[6];
    final long yesterdayIsha, tomorrowFajr;
    final int hijriDay, hijriMonth, hijriYear;

    PrayerDay(long now, Coordinates where, CalculationMethod method, Madhab madhab, int[] offsets) {
        this.now = now;
        CalculationParameters params = method.getParameters();
        params.madhab = madhab;
        params.adjustments.fajr = offsets[FAJR];
        params.adjustments.dhuhr = offsets[DHUHR];
        params.adjustments.asr = offsets[ASR];
        params.adjustments.maghrib = offsets[MAGHRIB];
        params.adjustments.isha = offsets[ISHA];

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        long yIsha, tFajr;
        try {
            PrayerTimes today = new PrayerTimes(where, DateComponents.from(c.getTime()), params);
            times[FAJR] = today.fajr.getTime();
            times[SUNRISE] = today.sunrise.getTime();
            times[DHUHR] = today.dhuhr.getTime();
            times[ASR] = today.asr.getTime();
            times[MAGHRIB] = today.maghrib.getTime();
            times[ISHA] = today.isha.getTime();
            c.add(Calendar.DAY_OF_MONTH, -1);
            yIsha = new PrayerTimes(where, DateComponents.from(c.getTime()), params).isha.getTime();
            c.add(Calendar.DAY_OF_MONTH, 2);
            tFajr = new PrayerTimes(where, DateComponents.from(c.getTime()), params).fajr.getTime();
        } catch (RuntimeException noSolution) { // adhan-java leaves a time null when the sun never reaches it
            yIsha = Long.MAX_VALUE;
            tFajr = 0;
        }
        if (!ordered(yIsha, times, tFajr)) {
            // Far north/south in summer the sun may not set or dip far enough: fall back to fixed hours so
            // the sky and the timeline still work. This never happens at ordinary latitudes.
            Log.w(Config.TAG, "prayer: times out of order at this latitude; using fixed fallback hours");
            int[] minutes = {5 * 60, 6 * 60 + 30, 12 * 60 + 30, 15 * 60 + 30, 18 * 60 + 30, 20 * 60};
            Calendar d = Calendar.getInstance();
            d.setTimeInMillis(now);
            d.set(Calendar.SECOND, 0);
            d.set(Calendar.MILLISECOND, 0);
            for (int i = 0; i < 6; i++) {
                d.set(Calendar.HOUR_OF_DAY, minutes[i] / 60);
                d.set(Calendar.MINUTE, minutes[i] % 60);
                times[i] = d.getTimeInMillis();
            }
            yIsha = times[ISHA] - 24 * 3_600_000L;
            tFajr = times[FAJR] + 24 * 3_600_000L;
        }
        yesterdayIsha = yIsha;
        tomorrowFajr = tFajr;

        IslamicCalendar h = new IslamicCalendar();
        h.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        h.setTime(new Date(now));
        hijriDay = h.get(IslamicCalendar.DAY_OF_MONTH);
        hijriMonth = h.get(IslamicCalendar.MONTH);
        hijriYear = h.get(IslamicCalendar.YEAR);
    }

    private static boolean ordered(long before, long[] t, long after) {
        long prev = before;
        for (long v : t) {
            if (v <= prev) return false;
            prev = v;
        }
        return after > prev;
    }

    String hijri(android.content.Context c) {
        String[] months = c.getResources().getStringArray(R.array.hijri_months);
        return hijriDay + " " + months[hijriMonth] + " " + hijriYear;
    }
}
