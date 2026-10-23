package tv.widgetv.home;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import com.batoulapps.adhan.CalculationMethod;
import com.batoulapps.adhan.Madhab;

import java.time.ZoneId;
import java.util.Locale;

/**
 * Every user choice from res/values/config.xml, read once. Bad values never crash the home screen
 * (a crashing HOME app leaves the TV on its fallback home): they are logged under the SkyLauncher log tag
 * and replaced with a safe default.
 */
final class Config {
    static final String TAG = "SkyLauncher";

    final boolean prayerEnabled;
    final String cityLabel;
    final double lat, lng;
    final CalculationMethod method;
    final Madhab madhab;

    final boolean secondEnabled;
    final String secondLabel;
    final ZoneId secondZone;
    /** Flag drawable resource id, or 0 when the configured name does not exist. */
    final int secondFlag;
    final double secondLat, secondLng;

    final boolean weatherEnabled;
    /** "celsius" or "fahrenheit", exactly as Open-Meteo's temperature_unit expects. */
    final String weatherUnits;

    final boolean thoughtsEnabled;
    /** Defaults for the Prayer settings panel: 0 Cormorant, 1 Outfit; 0 Normal, 1 Large, 2 Larger. */
    final int defaultClockFont, defaultTextSize;
    /** PrayerPrefs.ADHAN_FULL .. ADHAN_OFF. */
    final int defaultAdhan;
    /** Looping fireplace behind the home screen until the panel turns it off. */
    final boolean defaultFire;

    private static Config instance;

    static synchronized Config get(Context c) {
        if (instance == null) instance = new Config(c.getApplicationContext());
        return instance;
    }

    private Config(Context c) {
        Resources r = c.getResources();
        prayerEnabled = r.getBoolean(R.bool.prayer_enabled);
        cityLabel = r.getString(R.string.prayer_city_label).trim();
        lat = coordinate(r.getString(R.string.prayer_lat), 90, "prayer_lat");
        lng = coordinate(r.getString(R.string.prayer_lng), 180, "prayer_lng");
        method = method(r.getString(R.string.prayer_method));
        madhab = "SHAFI".equalsIgnoreCase(r.getString(R.string.prayer_madhab).trim()) ? Madhab.SHAFI : Madhab.HANAFI;

        secondEnabled = r.getBoolean(R.bool.second_clock_enabled);
        secondLabel = r.getString(R.string.second_city_label).trim();
        secondZone = zone(r.getString(R.string.second_zone));
        String flagName = r.getString(R.string.second_flag).trim();
        int flag = r.getIdentifier(flagName, "drawable", c.getPackageName());
        if (flag == 0) Log.w(TAG, "config: second_flag '" + flagName + "' is not a drawable in res/drawable; the flag is hidden");
        secondFlag = flag;
        secondLat = coordinate(r.getString(R.string.second_lat), 90, "second_lat");
        secondLng = coordinate(r.getString(R.string.second_lng), 180, "second_lng");

        weatherEnabled = r.getBoolean(R.bool.weather_enabled);
        weatherUnits = "fahrenheit".equalsIgnoreCase(r.getString(R.string.weather_units).trim()) ? "fahrenheit" : "celsius";

        thoughtsEnabled = r.getBoolean(R.bool.thoughts_enabled);
        defaultClockFont = "outfit".equalsIgnoreCase(r.getString(R.string.clock_font_pref).trim()) ? 1 : 0;
        String size = r.getString(R.string.text_size_pref).trim().toLowerCase(Locale.ROOT);
        defaultTextSize = size.equals("larger") ? 2 : size.equals("large") ? 1 : 0;
        String adhan = r.getString(R.string.adhan_pref).trim().toLowerCase(Locale.ROOT);
        defaultAdhan = "off".equals(adhan) ? PrayerPrefs.ADHAN_OFF
                : "beep".equals(adhan) ? PrayerPrefs.ADHAN_BEEP
                : "takbir".equals(adhan) ? PrayerPrefs.ADHAN_TAKBIR
                : PrayerPrefs.ADHAN_FULL;
        if (!"full".equals(adhan) && !"takbir".equals(adhan) && !"beep".equals(adhan) && !"off".equals(adhan)) {
            Log.e(TAG, "config: adhan_pref '" + adhan + "' is unknown; using full");
        }
        defaultFire = r.getBoolean(R.bool.fire_enabled);

        Log.i(TAG, "config: prayer=" + (prayerEnabled ? "on" : "off") + " city=" + cityLabel + " lat=" + lat + " lng=" + lng + " method=" + method + " madhab=" + madhab
                + " second=" + (secondEnabled ? secondLabel + " " + secondZone : "off")
                + " weather=" + (weatherEnabled ? weatherUnits : "off"));
    }

    private static double coordinate(String raw, double limit, String key) {
        try {
            double v = Double.parseDouble(raw.trim());
            if (Math.abs(v) <= limit) return v;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        Log.e(TAG, "config: " + key + " '" + raw + "' is not a valid coordinate; using 0");
        return 0;
    }

    private static CalculationMethod method(String raw) {
        try {
            CalculationMethod m = CalculationMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            if (m != CalculationMethod.OTHER) return m; // OTHER has no angles of its own
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        Log.e(TAG, "config: prayer_method '" + raw + "' is unknown; using MUSLIM_WORLD_LEAGUE");
        return CalculationMethod.MUSLIM_WORLD_LEAGUE;
    }

    private static ZoneId zone(String raw) {
        try {
            return ZoneId.of(raw.trim());
        } catch (RuntimeException bad) {
            Log.e(TAG, "config: second_zone '" + raw + "' is not an IANA zone id; using the TV's zone");
            return ZoneId.systemDefault();
        }
    }
}
