package tv.widgetv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Boot, clock changes, and the prayer alarm. The alarm starts the player; the others only reschedule. */
public class AdhanReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (AdhanAlarm.ACTION.equals(intent.getAction())) {
            int prayer = intent.getIntExtra("prayer", PrayerDay.FAJR);
            long at = intent.getLongExtra("at", 0);
            long now = System.currentTimeMillis();
            if (at > 0 && (now + 5_000 < at || now - at > AdhanAlarm.LATE_MS)) {
                Log.w(Config.TAG, "adhan: ignoring stale alarm for " + PrayerDay.NAMES[prayer]);
            } else if (prayer >= 0 && prayer < PrayerDay.NAMES.length && prayer != PrayerDay.SUNRISE) {
                AdhanPlayer.start(context, prayer, at, false);
            }
        }
        AdhanAlarm.schedule(context);
    }
}
