package tv.widgetv.home;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Loads the bundled faces from assets. res/font cannot be opened on Android 7. */
final class Fonts {
    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private Fonts() {}

    static Typeface get(Context c, int fontRes) {
        String name = c.getResources().getResourceEntryName(fontRes);
        Typeface cached = CACHE.get(name);
        if (cached != null) return cached;
        try {
            Typeface tf = Typeface.createFromAsset(c.getAssets(), "fonts/" + name + ".ttf");
            CACHE.put(name, tf);
            return tf;
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    static void weight(TextView t, String variation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) t.setFontVariationSettings(variation);
    }
}
