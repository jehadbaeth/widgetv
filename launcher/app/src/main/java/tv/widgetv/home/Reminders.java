package tv.widgetv.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Custom reminders set from the phone page. One exact alarm each, then the card on the TV. */
final class Reminders {
    static final String ACTION = "tv.widgetv.home.REMINDER";
    static final int PORT = 8765;
    /** Still show a reminder if the TV was asleep for a few minutes. */
    static final long LATE_MS = 15 * 60_000L;
    static final String ONCE = "once";
    static final String DAILY = "daily";
    static final String WEEKLY = "weekly";
    static final String WORKDAYS = "workdays";
    static final String WEEKENDS = "weekends";
    private static final int MAX = 40;
    private static final int MAX_TEXT = 240;
    private static final char[] PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    static final class Item {
        final String id;
        final String text;
        final long at;
        /** once, daily, weekly, workdays, or weekends. */
        final String repeat;

        Item(String id, String text, long at, String repeat) {
            this.id = id;
            this.text = text;
            this.at = at;
            this.repeat = repeat;
        }

        String whenLabel() {
            return DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.US)
                    .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
        }

        String repeatLabel() {
            if (DAILY.equals(repeat)) return "Daily";
            if (WEEKLY.equals(repeat)) return "Weekly";
            if (WORKDAYS.equals(repeat)) return "Workdays";
            if (WEEKENDS.equals(repeat)) return "Weekends";
            return "One time";
        }
    }

    static String repeat(String raw) {
        if (raw == null) return ONCE;
        String value = raw.trim().toLowerCase(Locale.US);
        if (DAILY.equals(value) || WEEKLY.equals(value) || WORKDAYS.equals(value) || WEEKENDS.equals(value)) return value;
        return ONCE;
    }

    private Reminders() {
    }

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("reminders", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return of(c).getBoolean("enabled", true);
    }

    static void setEnabled(Context c, boolean on) {
        of(c).edit().putBoolean("enabled", on).apply();
        if (on) ReminderServer.start(c);
        else c.getApplicationContext().stopService(new Intent(c, ReminderServer.class));
    }

    static synchronized String password(Context c) {
        SharedPreferences prefs = of(c);
        String pw = prefs.getString("password", "");
        if (pw == null || pw.isEmpty()) {
            pw = generate();
            prefs.edit().putString("password", pw).apply();
        }
        return pw;
    }

    static synchronized void setPassword(Context c, String next) {
        String trimmed = next == null ? "" : next.trim();
        if (trimmed.length() < 4 || trimmed.length() > 64) return;
        of(c).edit().putString("password", trimmed).apply();
        ReminderServer.clearSessions();
    }

    /** "host:port" for the panel. Tailscale first, then Wi-Fi, then any other IPv4. */
    static String panelAddress() {
        String tail = null, wifi = null, any = null;
        for (String[] row : interfaces()) {
            String name = row[0];
            String host = row[1];
            if (name.startsWith("tailscale") || name.startsWith("tun")) tail = host;
            else if (name.startsWith("wlan")) wifi = host;
            else if (any == null) any = host;
        }
        String host = tail != null ? tail : wifi != null ? wifi : any;
        return (host == null ? "" : host) + ":" + PORT;
    }

    static List<String> urls() {
        ArrayList<String> urls = new ArrayList<>();
        for (String[] row : interfaces()) urls.add("http://" + row[1] + ":" + PORT);
        return urls;
    }

    static synchronized List<Item> list(Context c) {
        JSONArray arr = raw(c);
        ArrayList<Item> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Item item = item(arr.optJSONObject(i));
            if (item != null) items.add(item);
        }
        items.sort((a, b) -> Long.compare(a.at, b.at));
        return items;
    }

    /** Null when the reminder was saved. Otherwise "empty", "past", "full", or "time". */
    static synchronized String add(Context c, String text, long at, String repeatRaw) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) return "empty";
        if (body.length() > MAX_TEXT) body = body.substring(0, MAX_TEXT);
        if (at <= 0) return "time";
        String every = repeat(repeatRaw);
        at = align(at, every);
        if (at < System.currentTimeMillis() - 5_000) return "past";
        JSONArray arr = raw(c);
        if (arr.length() >= MAX) return "full";
        String id = Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(new SecureRandom().nextInt(36 * 36), 36);
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("text", body);
            o.put("at", at);
            o.put("repeat", every);
            arr.put(o);
        } catch (JSONException bad) {
            return "time";
        }
        of(c).edit().putString("items", arr.toString()).apply();
        schedule(c);
        Log.i(Config.TAG, "reminder: saved " + id + " at " + at + " repeat=" + every);
        return null;
    }

    /**
     * Shows a due reminder. A repeating one moves to its next day; a one-time one is removed.
     * A reminder more than a few minutes late is not shown, but a repeating one still advances.
     */
    static synchronized Item takeDue(Context c, String id) {
        long now = System.currentTimeMillis();
        Item item = find(c, id);
        if (item == null) {
            schedule(c);
            return null;
        }
        if (now + 5_000 < item.at) {
            Log.w(Config.TAG, "reminder: early alarm for " + id);
            schedule(c);
            return null;
        }
        boolean late = now - item.at > LATE_MS;
        if (ONCE.equals(item.repeat)) {
            delete(c, id);
            if (late) {
                Log.w(Config.TAG, "reminder: late, dropped " + id);
                return null;
            }
            return item;
        }
        long next = nextAfter(item.at, item.repeat, now);
        saveAt(c, id, next);
        schedule(c);
        Log.i(Config.TAG, "reminder: next " + id + " at " + next + " repeat=" + item.repeat);
        if (late) {
            Log.w(Config.TAG, "reminder: late, skipped show " + id);
            return null;
        }
        return item;
    }

    static synchronized Item find(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray arr = raw(c);
        for (int i = 0; i < arr.length(); i++) {
            Item item = item(arr.optJSONObject(i));
            if (item != null && id.equals(item.id)) return item;
        }
        return null;
    }

    static synchronized boolean delete(Context c, String id) {
        if (id == null || id.isEmpty()) return false;
        JSONArray arr = raw(c);
        JSONArray next = new JSONArray();
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) {
                found = true;
                continue;
            }
            next.put(o);
        }
        if (!found) return false;
        of(c).edit().putString("items", next.toString()).apply();
        schedule(c);
        return true;
    }

    /** Epoch millis from a phone (at) or a datetime-local value in the TV's zone (when). */
    static long parseWhen(String at, String when) {
        long millis = epoch(at);
        if (millis > 0) return millis;
        millis = epoch(when);
        if (millis > 0) return millis;
        if (when == null || when.trim().isEmpty()) return -1;
        try {
            return LocalDateTime.parse(when.trim()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException bad) {
            return -1;
        }
    }

    static synchronized void schedule(Context c) {
        Context app = c.getApplicationContext();
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        SharedPreferences prefs = of(app);
        if (alarms != null) {
            for (String part : prefs.getString("codes", "").split(",")) {
                if (part.isEmpty()) continue;
                try {
                    alarms.cancel(pending(app, Integer.parseInt(part), "x"));
                } catch (NumberFormatException ignored) {
                    // A broken code list is replaced below.
                }
            }
        }
        JSONArray arr = raw(app);
        long now = System.currentTimeMillis();
        StringBuilder codes = new StringBuilder();
        int armed = 0;
        if (alarms != null) {
            for (int i = 0; i < arr.length(); i++) {
                Item item = item(arr.optJSONObject(i));
                if (item == null || item.at <= now) continue;
                int code = code(item.id);
                PendingIntent fire = pending(app, code, item.id);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, fire);
                    } else {
                        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, fire);
                    }
                } catch (SecurityException denied) {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, fire);
                }
                if (codes.length() > 0) codes.append(',');
                codes.append(code);
                armed++;
            }
        }
        prefs.edit().putString("codes", codes.toString()).apply();
        Log.i(Config.TAG, "reminder: armed " + armed);
    }

    private static long epoch(String raw) {
        if (raw == null) return -1;
        String value = raw.trim();
        if (value.isEmpty()) return -1;
        try {
            long ms = Long.parseLong(value);
            if (ms > 1_000_000_000_000L) return ms;
        } catch (NumberFormatException ignored) {
            // Not a millisecond timestamp.
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static JSONArray raw(Context c) {
        try {
            return new JSONArray(of(c).getString("items", "[]"));
        } catch (JSONException bad) {
            return new JSONArray();
        }
    }

    private static Item item(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        String text = o.optString("text", "");
        long at = o.optLong("at", 0);
        if (id.isEmpty() || text.isEmpty() || at <= 0) return null;
        return new Item(id, text, at, repeat(o.optString("repeat", ONCE)));
    }

    /** Moves a workday or weekend reminder onto the next matching day, keeping the clock time. */
    private static long align(long at, String every) {
        ZonedDateTime z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        if (matches(z, every)) return at;
        for (int i = 0; i < 7; i++) {
            z = z.plusDays(1);
            if (matches(z, every)) return z.toInstant().toEpochMilli();
        }
        return at;
    }

    private static long nextAfter(long at, String every, long now) {
        ZonedDateTime z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        for (int i = 0; i < 400; i++) {
            z = step(z, every);
            long ms = z.toInstant().toEpochMilli();
            if (ms > now) return ms;
        }
        return z.toInstant().toEpochMilli();
    }

    private static ZonedDateTime step(ZonedDateTime z, String every) {
        if (WEEKLY.equals(every)) return z.plusDays(7);
        if (WORKDAYS.equals(every) || WEEKENDS.equals(every)) {
            ZonedDateTime next = z.plusDays(1);
            while (!matches(next, every)) next = next.plusDays(1);
            return next;
        }
        return z.plusDays(1);
    }

    private static boolean matches(ZonedDateTime z, String every) {
        int day = z.getDayOfWeek().getValue();
        if (WORKDAYS.equals(every)) return day <= 5;
        if (WEEKENDS.equals(every)) return day >= 6;
        return true;
    }

    private static void saveAt(Context c, String id, long at) {
        JSONArray arr = raw(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !id.equals(o.optString("id"))) continue;
            try {
                o.put("at", at);
            } catch (JSONException ignored) {
                return;
            }
        }
        of(c).edit().putString("items", arr.toString()).apply();
    }

    private static int code(String id) {
        return 0x51000000 | (id.hashCode() & 0x00FFFFFF);
    }

    private static PendingIntent pending(Context c, int code, String id) {
        Intent intent = new Intent(c, ReminderReceiver.class).setAction(ACTION).putExtra("id", id);
        return PendingIntent.getBroadcast(c, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String generate() {
        SecureRandom random = new SecureRandom();
        char[] out = new char[8];
        for (int i = 0; i < out.length; i++) out[i] = PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)];
        return new String(out);
    }

    private static List<String[]> interfaces() {
        ArrayList<String[]> rows = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    rows.add(new String[]{nif.getName(), addr.getHostAddress()});
                }
            }
        } catch (SocketException e) {
            Log.w(Config.TAG, "reminder: interfaces", e);
        }
        return rows;
    }
}
