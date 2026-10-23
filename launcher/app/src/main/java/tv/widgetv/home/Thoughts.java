package tv.widgetv.home;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Thought of the day from res/raw/thoughts.json:
 * {"weekday":[{"text":"…","source":"…"|null}], "friday":[…]}. The pick is stable for the whole local
 * (TV time zone) day. Slot 0 is weekday[epochDay % n], or friday[(epochDay / 7) % n] on Fridays.
 * Later slots walk that same list.
 */
final class Thoughts {
    private static final String TAG = Config.TAG;

    static final class Thought {
        final boolean friday;
        final String text, source; // source may be null

        Thought(boolean friday, String text, String source) {
            this.friday = friday;
            this.text = text;
            this.source = source;
        }
    }

    private JSONArray weekday, friday;

    Thoughts(Context c) {
        try (InputStream in = c.getResources().openRawResource(R.raw.thoughts)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject root = new JSONObject(out.toString("UTF-8"));
            weekday = root.optJSONArray("weekday");
            friday = root.optJSONArray("friday");
            Log.i(TAG, "thoughts: weekday=" + size(weekday) + " friday=" + size(friday));
        } catch (Exception e) {
            Log.w(TAG, "thoughts: cannot read res/raw/thoughts.json", e);
        }
    }

    /** How many lines rotate on this date. 0 when the file failed to load. */
    int count(LocalDate day) {
        return size(listFor(day));
    }

    /** null when there is nothing to show for that day. Slot 0 is the old once-a-day pick. */
    Thought pick(LocalDate day) {
        return at(day, 0);
    }

    /** The line `slot` steps after today's stable pick, wrapping through that day's list. */
    Thought at(LocalDate day, int slot) {
        JSONArray list = listFor(day);
        if (size(list) == 0) return null;
        boolean fromFriday = list == friday;
        long epochDay = day.toEpochDay();
        int base = (int) Math.floorMod(fromFriday ? epochDay / 7 : epochDay, (long) list.length());
        int i = Math.floorMod(base + slot, list.length());
        JSONObject o = list.optJSONObject(i);
        if (o == null) return null;
        boolean ar = "ar".equals(Locale.getDefault().getLanguage());
        String text = ar ? o.optString("text_ar", "") : "";
        if (text.isEmpty()) text = o.optString("text", "");
        if (text.isEmpty()) return null;
        String source = ar && !o.isNull("source_ar") ? o.optString("source_ar", null) : null;
        if (source == null || source.isEmpty()) source = o.isNull("source") ? null : o.optString("source", null);
        if (source != null && source.isEmpty()) source = null;
        return new Thought(fromFriday, text, source); // the Jumu'ah tag only with a Friday entry
    }

    private JSONArray listFor(LocalDate day) {
        boolean fri = day.getDayOfWeek() == DayOfWeek.FRIDAY;
        return fri && size(friday) > 0 ? friday : weekday;
    }

    private static int size(JSONArray a) {
        return a == null ? 0 : a.length();
    }
}
