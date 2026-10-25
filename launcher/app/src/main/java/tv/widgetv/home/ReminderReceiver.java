package tv.widgetv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Fires one saved reminder. Boot and clock changes reschedule from {@link AdhanReceiver}. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Reminders.ACTION.equals(intent.getAction())) return;
        Reminders.Item item = Reminders.takeDue(context, intent.getStringExtra("id"));
        if (item == null) return;
        Log.i(Config.TAG, "reminder: show " + item.id + " repeat=" + item.repeat);
        ReminderShow.start(context, item.text, item.at);
    }
}
