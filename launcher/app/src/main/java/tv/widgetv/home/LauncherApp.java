package tv.widgetv.home;

import android.app.Application;

/** Starts the reminder page whenever the launcher process comes up. */
public class LauncherApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Reminders.schedule(this);
        ReminderServer.start(this);
    }
}
