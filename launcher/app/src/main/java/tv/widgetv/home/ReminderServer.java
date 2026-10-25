package tv.widgetv.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Page and JSON API on port {@link Reminders#PORT}, bound on every interface so Tailscale and
 * the home Wi-Fi both reach it. Every route except login needs the password from the TV panel.
 */
public class ReminderServer extends Service {
    private static final String CHANNEL = "reminder_page";
    private static final int NOTIF = 10;
    private static final long SESSION_MS = 30L * 24 * 60 * 60 * 1000;
    private static final ConcurrentHashMap<String, Long> SESSIONS = new ConcurrentHashMap<>();

    private volatile boolean running;
    private ServerSocket socket;
    private Thread thread;

    static void start(Context context) {
        if (!Reminders.enabled(context)) return;
        Context c = context.getApplicationContext();
        Intent intent = new Intent(c, ReminderServer.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent);
            else c.startService(intent);
        } catch (IllegalStateException denied) {
            Log.w(Config.TAG, "reminder: page start refused", denied);
        }
    }

    static void clearSessions() {
        SESSIONS.clear();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiLang.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification notice = builder
                .setSmallIcon(R.drawable.ic_adhan)
                .setContentTitle(getString(R.string.reminders))
                .setContentText(Reminders.panelAddress())
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIF, notice);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Reminders.enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        listen();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void listen() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "reminder-http");
        thread.start();
    }

    private void loop() {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", Reminders.PORT));
            server.setSoTimeout(1000);
            socket = server;
            Log.i(Config.TAG, "reminder: listening " + Reminders.urls());
            while (running) {
                try {
                    Socket client = server.accept();
                    handle(client);
                } catch (SocketTimeoutException ignored) {
                    // Lets stop() close the socket within a second.
                } catch (Throwable bad) {
                    Log.w(Config.TAG, "reminder: request crashed", bad);
                }
            }
        } catch (IOException e) {
            if (running) Log.w(Config.TAG, "reminder: page stopped", e);
        } finally {
            running = false;
            socket = null;
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(8000);
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int prev = 0;
            while (head.size() < 8192) {
                int b = in.read();
                if (b < 0) return;
                head.write(b);
                if (prev == '\n' && b == '\n') break;
                if (head.size() >= 4) {
                    byte[] raw = head.toByteArray();
                    int n = raw.length;
                    if (raw[n - 4] == '\r' && raw[n - 3] == '\n' && raw[n - 2] == '\r' && raw[n - 1] == '\n') break;
                }
                prev = b;
            }
            String header = new String(head.toByteArray(), StandardCharsets.UTF_8);
            int split = header.indexOf("\r\n");
            if (split < 0) return;
            String[] request = header.substring(0, split).split(" ");
            if (request.length < 2) return;
            String method = request[0];
            String target = request[1];
            int q = target.indexOf('?');
            String path = q >= 0 ? target.substring(0, q) : target;
            Map<String, String> query = q >= 0 ? form(target.substring(q + 1)) : new LinkedHashMap<>();
            Map<String, String> headers = headers(header.substring(split + 2));
            int length = 0;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
                length = 0;
            }
            if (length < 0 || length > 8192) {
                send(socket, 413, "text/plain; charset=utf-8", "too large".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            byte[] body = length == 0 ? new byte[0] : readBytes(in, length);
            route(socket, method, path, query, headers, body);
        } catch (IOException e) {
            Log.w(Config.TAG, "reminder: request failed", e);
        }
    }

    private void route(Socket socket, String method, String path, Map<String, String> query,
                       Map<String, String> headers, byte[] body) throws IOException {
        String type = headers.getOrDefault("content-type", "");
        Map<String, String> form = type.contains("json") ? new LinkedHashMap<>() : form(new String(body, StandardCharsets.UTF_8));
        JSONObject json = type.contains("json") ? json(body) : new JSONObject();
        boolean authed = authorized(headers, form);
        boolean api = path.startsWith("/api/");

        if ("POST".equals(method) && ("/login".equals(path) || "/api/login".equals(path))) {
            String password = first(form.get("password"), json.optString("password", ""));
            if (!passwordMatches(password)) {
                if (api) sendJson(socket, 401, error("password"));
                else sendHtml(socket, 401, page(null, "Wrong password."));
                return;
            }
            String session = session();
            String cookie = "wv=" + session + "; HttpOnly; Path=/; SameSite=Lax; Max-Age=2592000";
            if (api) send(socket, 200, "application/json; charset=utf-8",
                    "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), cookie);
            else send(socket, 303, "text/plain; charset=utf-8", new byte[0], cookie, "/");
            return;
        }
        if (!authed) {
            if (api) sendJson(socket, 401, error("password"));
            else if ("GET".equals(method) && "/".equals(path)) sendHtml(socket, 200, page(null, null));
            else sendHtml(socket, 401, page(null, "Sign in first."));
            return;
        }
        if ("POST".equals(method) && "/logout".equals(path)) {
            dropSession(headers);
            send(socket, 303, "text/plain; charset=utf-8", new byte[0], "wv=; HttpOnly; Path=/; Max-Age=0", "/");
            return;
        }
        if ("GET".equals(method) && ("/".equals(path) || "/reminders".equals(path))) {
            sendHtml(socket, 200, page(Reminders.list(this), null));
            return;
        }
        if ("POST".equals(method) && "/add".equals(path)) {
            String problem = Reminders.add(this, form.get("text"),
                    Reminders.parseWhen(form.get("at"), form.get("when")), form.get("repeat"));
            if (problem != null) sendHtml(socket, 400, page(Reminders.list(this), message(problem)));
            else send(socket, 303, "text/plain; charset=utf-8", new byte[0], null, "/");
            return;
        }
        if ("POST".equals(method) && "/delete".equals(path)) {
            Reminders.delete(this, form.get("id"));
            send(socket, 303, "text/plain; charset=utf-8", new byte[0], null, "/");
            return;
        }
        if ("POST".equals(method) && "/password".equals(path)) {
            String next = form.get("next");
            if (next == null || next.trim().length() < 4) sendHtml(socket, 400, page(Reminders.list(this), "Use at least 4 characters."));
            else {
                Reminders.setPassword(this, next);
                sendHtml(socket, 200, page(null, "Password changed. Sign in again."));
            }
            return;
        }
        if ("GET".equals(method) && "/api/reminders".equals(path)) {
            sendJson(socket, 200, listJson());
            return;
        }
        if ("POST".equals(method) && "/api/reminders".equals(path)) {
            String text = first(form.get("text"), json.optString("text", ""));
            String at = first(form.get("at"), json.has("at") ? String.valueOf(json.opt("at")) : "");
            String when = first(form.get("when"), json.optString("when", ""));
            String repeat = first(form.get("repeat"), json.optString("repeat", Reminders.ONCE));
            String problem = Reminders.add(this, text, Reminders.parseWhen(at, when), repeat);
            if (problem != null) sendJson(socket, 400, error(problem));
            else sendJson(socket, 200, listJson());
            return;
        }
        if ("DELETE".equals(method) && path.startsWith("/api/reminders")) {
            String id = path.length() > "/api/reminders/".length()
                    ? decode(path.substring("/api/reminders/".length()))
                    : query.get("id");
            boolean removed = Reminders.delete(this, id);
            sendJson(socket, removed ? 200 : 404, removed ? listJson() : error("missing"));
            return;
        }
        send(socket, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8), null);
    }

    private boolean authorized(Map<String, String> headers, Map<String, String> form) {
        String bearer = headers.getOrDefault("authorization", "");
        if (bearer.regionMatches(true, 0, "Bearer ", 0, 7) && passwordMatches(bearer.substring(7).trim())) return true;
        String cookie = headers.getOrDefault("cookie", "");
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.startsWith("wv=")) continue;
            Long until = SESSIONS.get(trimmed.substring(3));
            if (until != null && until > System.currentTimeMillis()) return true;
        }
        return passwordMatches(form.get("password"));
    }

    private boolean passwordMatches(String given) {
        if (given == null) return false;
        byte[] expect = Reminders.password(this).getBytes(StandardCharsets.UTF_8);
        byte[] got = given.getBytes(StandardCharsets.UTF_8);
        if (expect.length != got.length) return false;
        return MessageDigest.isEqual(expect, got);
    }

    private static void dropSession(Map<String, String> headers) {
        String cookie = headers.getOrDefault("cookie", "");
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("wv=")) SESSIONS.remove(trimmed.substring(3));
        }
    }

    private static String session() {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder out = new StringBuilder(32);
        for (byte b : raw) out.append(String.format("%02x", b));
        String token = out.toString();
        SESSIONS.put(token, System.currentTimeMillis() + SESSION_MS);
        return token;
    }

    private JSONObject listJson() {
        JSONArray arr = new JSONArray();
        for (Reminders.Item item : Reminders.list(this)) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", item.id);
                o.put("text", item.text);
                o.put("at", item.at);
                o.put("repeat", item.repeat);
            } catch (org.json.JSONException ignored) {
                continue;
            }
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("reminders", arr);
        } catch (org.json.JSONException ignored) {
            // The key is a constant.
        }
        return root;
    }

    private static JSONObject error(String code) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", code);
        } catch (org.json.JSONException ignored) {
            // A fixed object cannot fail.
        }
        return o;
    }

    private static JSONObject json(byte[] body) {
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (org.json.JSONException bad) {
            return new JSONObject();
        }
    }

    private String page(List<Reminders.Item> items, String error) {
        if (items == null) {
            return shell("Sign in",
                    "<form method=\"post\" action=\"/login\">"
                            + "<label>Password</label>"
                            + "<input name=\"password\" type=\"password\" autocomplete=\"current-password\" autofocus>"
                            + "<button>Open</button></form>"
                            + note(error)
                            + "<p class=\"hint\">The password is on the TV, in prayer settings, on the reminder row.</p>");
        }
        StringBuilder rows = new StringBuilder();
        if (items.isEmpty()) rows.append("<p class=\"hint\">Nothing set.</p>");
        for (Reminders.Item item : items) {
            rows.append("<li><div><b>")
                    .append(esc(item.whenLabel()))
                    .append("</b><i>")
                    .append(esc(item.repeatLabel()))
                    .append("</i><span>")
                    .append(esc(item.text))
                    .append("</span></div><form method=\"post\" action=\"/delete\">")
                    .append("<input type=\"hidden\" name=\"id\" value=\"")
                    .append(esc(item.id))
                    .append("\"><button class=\"quiet\">Remove</button></form></li>");
        }
        StringBuilder addresses = new StringBuilder();
        for (String url : Reminders.urls()) {
            addresses.append("<li>").append(esc(url)).append("</li>");
        }
        return shell("Reminders",
                "<form method=\"post\" action=\"/add\" id=\"add\">"
                        + "<label>Reminder</label>"
                        + "<textarea name=\"text\" maxlength=\"240\" required autofocus></textarea>"
                        + "<label>When</label>"
                        + "<input name=\"when\" type=\"datetime-local\" required>"
                        + "<label>Repeat</label>"
                        + "<select name=\"repeat\">"
                        + "<option value=\"once\">One time</option>"
                        + "<option value=\"daily\">Daily</option>"
                        + "<option value=\"weekly\">Weekly</option>"
                        + "<option value=\"workdays\">Workdays</option>"
                        + "<option value=\"weekends\">Weekends</option>"
                        + "</select>"
                        + "<button>Save on the TV</button></form>"
                        + note(error)
                        + "<ul class=\"list\">" + rows + "</ul>"
                        + "<form method=\"post\" action=\"/password\" class=\"minor\">"
                        + "<label>New password</label>"
                        + "<input name=\"next\" minlength=\"4\" autocomplete=\"new-password\">"
                        + "<button class=\"quiet\">Change password</button></form>"
                        + "<form method=\"post\" action=\"/logout\"><button class=\"quiet\">Sign out</button></form>"
                        + "<p class=\"hint\">Open one of these from a phone on Tailscale or the home Wi-Fi.</p>"
                        + "<ul class=\"hint\">" + addresses + "</ul>"
                        + "<script>document.getElementById('add').addEventListener('submit',function(){"
                        + "var w=this.when.value;if(!w)return;var ms=new Date(w).getTime();if(!ms)return;"
                        + "var h=document.createElement('input');h.type='hidden';h.name='at';h.value=String(ms);this.appendChild(h);});</script>");
    }

    private static String shell(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title><style>"
                + "body{margin:0;background:#0C1B33;color:#EFE7D6;font:18px/1.4 sans-serif}"
                + "main{max-width:34rem;margin:0 auto;padding:1.25rem}"
                + "h1{font-size:1.6rem;color:#D8B46A;font-weight:600;margin:0 0 1rem}"
                + "label{display:block;margin:.8rem 0 .3rem;color:#9FB0CC}"
                + "input,textarea,button{font:inherit;width:100%;box-sizing:border-box}"
                + "input,textarea,select{background:#12264A;color:#EFE7D6;border:1px solid #305080;border-radius:10px;padding:.7rem}"
                + "select{width:100%}"
                + "textarea{min-height:5rem}"
                + "button{margin-top:.8rem;background:#D8B46A;color:#0C1B33;border:0;border-radius:10px;padding:.75rem;font-weight:700}"
                + "button.quiet{background:transparent;color:#D8B46A;border:1px solid #D8B46A}"
                + ".err{color:#F0D79C}.hint{color:#9FB0CC;font-size:.9rem}"
                + "ul.list{list-style:none;padding:0}ul.list li{display:flex;gap:.8rem;align-items:center;"
                + "border-top:1px solid #23406e;padding:.7rem 0}ul.list div{flex:1}ul.list span,ul.list i{display:block}"
                + "ul.list i{color:#9FB0CC;font-style:normal;font-size:.85rem}"
                + "ul.list button{width:auto;margin:0}form.minor{margin-top:1.5rem}"
                + "</style></head><body><main><h1>" + title + "</h1>" + body + "</main></body></html>";
    }

    private static String note(String error) {
        if (error == null || error.isEmpty()) return "";
        return "<p class=\"err\">" + esc(error) + "</p>";
    }

    private static String message(String problem) {
        if ("empty".equals(problem)) return "Write a reminder first.";
        if ("past".equals(problem)) return "That time has already passed.";
        if ("full".equals(problem)) return "The list is full. Remove one first.";
        return "That time could not be read.";
    }

    private static String esc(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String first(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    private static Map<String, String> form(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String key = decode(eq >= 0 ? part.substring(0, eq) : part);
            String value = decode(eq >= 0 ? part.substring(eq + 1) : "");
            if (!key.isEmpty()) out.put(key, value);
        }
        return out;
    }

    private static Map<String, String> headers(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : raw.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            out.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.US), line.substring(colon + 1).trim());
        }
        return out;
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int got = 0;
        while (got < length) {
            int n = in.read(body, got, length - got);
            if (n < 0) break;
            got += n;
        }
        if (got == length) return body;
        byte[] cut = new byte[got];
        System.arraycopy(body, 0, cut, 0, got);
        return cut;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", " "), "UTF-8");
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException bad) {
            return value;
        }
    }

    private static void sendHtml(Socket socket, int status, String html) throws IOException {
        send(socket, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), null);
    }

    private static void sendJson(Socket socket, int status, JSONObject json) throws IOException {
        send(socket, status, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static void send(Socket socket, int status, String type, byte[] body, String cookie) throws IOException {
        send(socket, status, type, body, cookie, null);
    }

    private static void send(Socket socket, int status, String type, byte[] body, String cookie, String location) throws IOException {
        String reason = "Error";
        if (status == 200) reason = "OK";
        else if (status == 303) reason = "See Other";
        else if (status == 400) reason = "Bad Request";
        else if (status == 401) reason = "Unauthorized";
        else if (status == 404) reason = "Not Found";
        else if (status == 413) reason = "Payload Too Large";
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n");
        if (cookie != null) head.append("Set-Cookie: ").append(cookie).append("\r\n");
        if (location != null) head.append("Location: ").append(location).append("\r\n");
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    @Override
    public void onDestroy() {
        running = false;
        ServerSocket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // Closing the listen socket unblocks accept.
            }
        }
        stopForeground(true);
        super.onDestroy();
    }
}
