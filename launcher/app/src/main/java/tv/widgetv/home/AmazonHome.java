package tv.widgetv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** The Amazon launcher exists only on the Fire Stick. The TCL never starts the home watch. */
final class AmazonHome {
    private AmazonHome() {
    }

    static boolean present(Context c) {
        try {
            c.getPackageManager().getPackageInfo("com.amazon.tv.launcher", 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }

    /** Start the watch while the panel switch is on. Stop it, and drop the cover, when it is off. */
    static void apply(Context c) {
        if (!present(c)) return;
        Context app = c.getApplicationContext();
        if (PrayerPrefs.amazonHome(app)) {
            app.startService(new Intent(app, HomeWatch.class));
        } else {
            HomeWatch.dismissCover();
            app.stopService(new Intent(app, HomeWatch.class));
        }
    }
}
