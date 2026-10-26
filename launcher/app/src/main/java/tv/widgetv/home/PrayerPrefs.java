package tv.widgetv.home;

import android.content.Context;
import android.content.SharedPreferences;

import com.batoulapps.adhan.CalculationMethod;
import com.batoulapps.adhan.Coordinates;
import com.batoulapps.adhan.Madhab;

/**
 * Prayer settings chosen on the TV (SharedPreferences "prayer"). Every value starts from
 * res/values/config.xml (see Config) until it is changed in the Prayer settings panel.
 * Adapted from Afnon's Prefs.java (MIT, https://github.com/egyjs/afnon-android-tv-launcher): the
 * method/madhab preferences and the per-prayer offsets ("offset_<prayer>" keys, clamped to -30..+30).
 */
final class PrayerPrefs {
    /** Every method adhan-java 1.2.1 offers except OTHER, in the order the panel cycles through them. */
    static final CalculationMethod[] METHODS = {
            CalculationMethod.KARACHI, CalculationMethod.MUSLIM_WORLD_LEAGUE, CalculationMethod.UMM_AL_QURA,
            CalculationMethod.EGYPTIAN, CalculationMethod.NORTH_AMERICA, CalculationMethod.DUBAI,
            CalculationMethod.KUWAIT, CalculationMethod.QATAR, CalculationMethod.SINGAPORE,
            CalculationMethod.MOON_SIGHTING_COMMITTEE};
    static final String[] METHOD_NAMES = {
            "Karachi", "Muslim World League", "Umm al-Qura", "Egyptian", "North America (ISNA)", "Dubai",
            "Kuwait", "Qatar", "Singapore", "Moonsighting Committee"};

    /** The five prayers that take a minute offset, indexed like PrayerDay.times. */
    static final int[] OFFSET_PRAYERS = {PrayerDay.FAJR, PrayerDay.DHUHR, PrayerDay.ASR, PrayerDay.MAGHRIB, PrayerDay.ISHA};

    private PrayerPrefs() {
    }

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("prayer", Context.MODE_PRIVATE);
    }

    static Coordinates where(Context c) {
        Config cfg = Config.get(c);
        return new Coordinates(cfg.lat, cfg.lng);
    }

    static int methodIndex(Context c) {
        String name = of(c).getString("method", Config.get(c).method.name());
        for (int i = 0; i < METHODS.length; i++) if (METHODS[i].name().equals(name)) return i;
        return 0;
    }

    static void setMethodIndex(Context c, int i) {
        of(c).edit().putString("method", METHODS[i].name()).apply();
    }

    static boolean hanafi(Context c) {
        String fallback = Config.get(c).madhab == Madhab.HANAFI ? "hanafi" : "shafi";
        return "hanafi".equals(of(c).getString("madhab", fallback));
    }

    static void setHanafi(Context c, boolean hanafi) {
        of(c).edit().putString("madhab", hanafi ? "hanafi" : "shafi").apply();
    }

    static int offset(Context c, int prayer) {
        return of(c).getInt("offset_" + prayer, 0);
    }

    static void setOffset(Context c, int prayer, int minutes) {
        of(c).edit().putInt("offset_" + prayer, Math.max(-30, Math.min(30, minutes))).apply();
    }

    static int[] offsets(Context c) {
        int[] out = new int[6];
        for (int p : OFFSET_PRAYERS) out[p] = offset(c, p);
        return out;
    }

    /** Adhan at the five prayer times: full call, the opening takbir, a beep, or nothing. */
    static final int ADHAN_FULL = 0, ADHAN_TAKBIR = 1, ADHAN_BEEP = 2, ADHAN_OFF = 3, ADHAN_COUNT = 4;

    static int adhanMode(Context c) {
        int fallback = Config.get(c).defaultAdhan;
        int mode = of(c).getInt("adhan", fallback);
        return mode < 0 || mode >= ADHAN_COUNT ? fallback : mode;
    }

    static void setAdhanMode(Context c, int mode) {
        of(c).edit().putInt("adhan", Math.max(0, Math.min(ADHAN_COUNT - 1, mode))).apply();
    }

    /** Picture behind the home screen: the fire, the ocean, the rain, or nothing. */
    static final int BACKDROP_FIRE = 0, BACKDROP_OCEAN = 1, BACKDROP_RAIN = 2, BACKDROP_OFF = 3, BACKDROP_COUNT = 4;

    static int backdrop(Context c) {
        SharedPreferences prefs = of(c);
        // Off used to be stored as 2, before rain took that slot.
        if (prefs.getInt("backdrop_rev", 0) < 1) {
            SharedPreferences.Editor edit = prefs.edit().putInt("backdrop_rev", 1);
            if (prefs.getInt("backdrop", -1) == 2) edit.putInt("backdrop", BACKDROP_OFF);
            edit.apply();
        }
        int fallback = Config.get(c).defaultBackdrop;
        if (prefs.contains("backdrop")) {
            int mode = prefs.getInt("backdrop", fallback);
            return mode < 0 || mode >= BACKDROP_COUNT ? fallback : mode;
        }
        // Older builds stored only an on/off switch for the fire.
        if (prefs.contains("fire") && !prefs.getBoolean("fire", true)) return BACKDROP_OFF;
        return fallback;
    }

    static void setBackdrop(Context c, int mode) {
        int next = mode < 0 || mode >= BACKDROP_COUNT ? BACKDROP_FIRE : mode;
        of(c).edit().putInt("backdrop", next).apply();
    }

    static String backdropAsset(int mode) {
        if (mode == BACKDROP_OCEAN) return "ocean.mp4";
        if (mode == BACKDROP_RAIN) return "rain.mp4";
        if (mode == BACKDROP_FIRE) return "fire.mp4";
        return "";
    }

    /**
     * Replace the Amazon home with WidgeTV. Default on. Off leaves the Fire Stick home and its
     * settings alone. The TCL never reads this: it has no Amazon launcher.
     */
    static boolean amazonHome(Context c) {
        return of(c).getBoolean("amazon_home", true);
    }

    static void setAmazonHome(Context c, boolean on) {
        of(c).edit().putBoolean("amazon_home", on).apply();
    }

    /** Header clock font: 0 Cormorant Garamond Light, 1 Outfit Thin. */
    static final String[] CLOCK_FONTS = {"Cormorant", "Outfit"};

    static int clockFont(Context c) {
        return of(c).getInt("clock_font", Config.get(c).defaultClockFont) == 1 ? 1 : 0;
    }

    static void setClockFont(Context c, int font) {
        of(c).edit().putInt("clock_font", font).apply();
    }

    /** Secondary text size: 0 Normal, 1 Large, 2 Larger. */
    static final String[] TEXT_SIZES = {"Normal", "Large", "Larger"};

    static int textSize(Context c) {
        return Math.max(0, Math.min(2, of(c).getInt("text_size", Config.get(c).defaultTextSize)));
    }

    static void setTextSize(Context c, int level) {
        of(c).edit().putInt("text_size", Math.max(0, Math.min(2, level))).apply();
    }

    /**
     * Sun times only, for the sky when prayer_enabled is false: the configured method, Shafi'i Asr and no
     * offsets, so the panel's prayer settings never shift the sky.
     */
    static PrayerDay skyDay(Context c, long now) {
        return new PrayerDay(now, where(c), Config.get(c).method, Madhab.SHAFI, new int[6]);
    }

    static PrayerDay day(Context c, long now) {
        return new PrayerDay(now, where(c), METHODS[methodIndex(c)], hanafi(c) ? Madhab.HANAFI : Madhab.SHAFI, offsets(c));
    }
}
