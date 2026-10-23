package tv.widgetv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.Locale;

/**
 * The day as a line: five evenly spaced columns (Fajr, Dhuhr, Asr, Maghrib, Isha) whose centres are the
 * prayers' x positions, a gold fill from Fajr to "now" and a dot at now. Between two prayers the dot sits
 * at the time-proportional point: x = x(prev) + (x(next) − x(prev)) × (now − t(prev)) / (t(next) − t(prev)).
 * The night is drawn as two dimmer stubs half a column wide: left of Fajr (yesterday's Isha → Fajr, no fill)
 * and right of Isha (Isha → tomorrow's Fajr, filled up to the dot). Redrawn on the minute tick only.
 */
public final class PrayerTimeline extends View {
    private static final String TAG = Config.TAG;
    static final int[] COLUMNS = {PrayerDay.FAJR, PrayerDay.DHUHR, PrayerDay.ASR, PrayerDay.MAGHRIB, PrayerDay.ISHA};

    private final float d;
    private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG), stub = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), time = new Paint(Paint.ANTI_ALIAS_FLAG),
            nextTime = new Paint(Paint.ANTI_ALIAS_FLAG), name = new Paint(Paint.ANTI_ALIAS_FLAG),
            nextName = new Paint(Paint.ANTI_ALIAS_FLAG);
    private PrayerDay day;
    /** Baselines (px) for the time and name rows; set by setTextScale. */
    private float timeY, nameY;
    private boolean h24;
    /** Last drawn values, for the debug receipt in the log. */
    float lastFraction, lastDotX;
    private String lastLog = "";

    public PrayerTimeline(Context c, AttributeSet a) {
        super(c, a);
        // This strip stays left to right even when the rest of the screen is Arabic: Fajr on the
        // left, the gold bar growing toward Isha on the right, English names and countdown.
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        d = getResources().getDisplayMetrics().density;
        Typeface ui = Fonts.get(getContext(), R.font.alexandria);
        Typeface uiMedium = Fonts.get(getContext(), R.font.alexandria_medium);
        hair.setColor(Lapis.alpha(Lapis.PLASTER_100, 0.18f));
        hair.setStrokeWidth(Math.max(1f, d / 2));
        stub.setColor(Lapis.alpha(Lapis.PLASTER_100, 0.09f));
        stub.setStrokeWidth(Math.max(1f, d / 2));
        fill.setColor(Lapis.GOLD_400);
        fill.setStrokeWidth(d);
        fill.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(Lapis.GOLD_400);
        ring.setColor(Lapis.alpha(Lapis.GOLD_400, 0.28f));
        setText(time, ui, 15, Lapis.alpha(Lapis.PLASTER_100, 0.80f));
        setText(nextTime, uiMedium, 17, Lapis.GOLD_400);
        setText(name, ui, 11, Lapis.MIST_400);
        setText(nextName, ui, 11, Lapis.GOLD_400);
        setTextScale(15, 11);
    }

    /**
     * Prayer-time and name sizes in sp (Normal 15/11, Large 17/13, Larger 19/15); the next prayer's time is
     * 2sp larger. Returns the view height (dp) the rows need, which MainActivity applies.
     */
    int setTextScale(float timeSp, float nameSp) {
        float sd = getResources().getDisplayMetrics().scaledDensity;
        time.setTextSize(timeSp * sd);
        nextTime.setTextSize((timeSp + 2) * sd);
        name.setTextSize(nameSp * sd);
        nextName.setTextSize(nameSp * sd);
        float timeDp = 30 + timeSp * 0.95f, nameDp = timeDp + 6 + nameSp * 1.25f;
        timeY = timeDp * d;
        nameY = nameDp * d;
        invalidate();
        return Math.round(nameDp + nameSp * 0.4f + 4);
    }

    private void setText(Paint p, Typeface tf, float sp, int color) {
        p.setTypeface(tf);
        p.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
    }

    void setDay(PrayerDay day) {
        this.day = day;
        h24 = DateFormat.is24HourFormat(getContext());
        invalidate();
    }

    /** Centre of column i. Fajr is always the left column. */
    float columnX(int i) {
        return getWidth() / 5f * (i + 0.5f);
    }

    /** Index into COLUMNS of the next prayer; 5 means tomorrow's Fajr (shown in the Fajr column). */
    int nextColumn() {
        for (int i = 0; i < 5; i++) if (day.times[COLUMNS[i]] > day.now) return i;
        return 5;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (day == null) return;
        int w = getWidth();
        float lineY = 8 * d, x0 = columnX(0), x4 = columnX(4);
        long now = day.now;
        c.drawLine(0, lineY, x0, lineY, stub);
        c.drawLine(x0, lineY, x4, lineY, hair);
        c.drawLine(x4, lineY, w, lineY, stub);

        int next = nextColumn();
        float frac, dotX;
        String seg;
        if (next == 0) {
            frac = fraction(now, day.yesterdayIsha, day.times[PrayerDay.FAJR]);
            dotX = x0 * frac; // left stub: yesterday's Isha at the line start, Fajr at its column
            seg = "Isha(yesterday)->Fajr";
        } else if (next == 5) {
            frac = fraction(now, day.times[PrayerDay.ISHA], day.tomorrowFajr);
            dotX = x4 + (w - x4) * frac; // right stub: tomorrow's Fajr at the line end
            seg = "Isha->Fajr(tomorrow)";
        } else {
            int prev = next - 1;
            frac = fraction(now, day.times[COLUMNS[prev]], day.times[COLUMNS[next]]);
            dotX = columnX(prev) + (columnX(next) - columnX(prev)) * frac;
            seg = PrayerDay.NAMES[COLUMNS[prev]] + "->" + PrayerDay.NAMES[COLUMNS[next]];
        }
        if (next > 0) c.drawLine(x0, lineY, dotX, lineY, fill);
        c.drawCircle(dotX, lineY, 7 * d, ring);
        c.drawCircle(dotX, lineY, 3.5f * d, dot);
        lastFraction = frac;
        lastDotX = dotX;
        String log = String.format(Locale.US, "timeline: segment=%s fraction=%.4f dotX=%.1f width=%d colX=[%.1f,%.1f,%.1f,%.1f,%.1f]",
                seg, frac, dotX, w, x0, columnX(1), columnX(2), columnX(3), x4);
        if (!log.equals(lastLog)) {
            lastLog = log;
            Log.i(TAG, log);
        }

        for (int i = 0; i < 5; i++) {
            int prayer = COLUMNS[i];
            boolean isNext = i == next || (next == 5 && i == 0);
            long t = next == 5 && i == 0 ? day.tomorrowFajr : day.times[prayer];
            float x = columnX(i);
            c.drawText(clock(t), x, timeY, isNext ? nextTime : time);
            String label = PrayerDay.NAMES[prayer];
            if (isNext) label += "  ·  in " + countdown(t - now);
            c.drawText(label, x, nameY, isNext ? nextName : name);
        }
    }

    static float fraction(long now, long from, long to) {
        if (to <= from) return 0f;
        return Math.max(0f, Math.min(1f, (now - from) / (float) (to - from)));
    }

    private String clock(long t) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(t);
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY), m = cal.get(java.util.Calendar.MINUTE);
        if (h24) return String.format(Locale.US, "%02d:%02d", h, m);
        return String.format(Locale.US, "%d:%02d", h % 12 == 0 ? 12 : h % 12, m);
    }

    private String countdown(long ms) {
        long min = Math.max(0, (ms + 59_999) / 60_000);
        if (min >= 60) return (min / 60) + "h " + (min % 60) + "m";
        return min + "m";
    }
}
