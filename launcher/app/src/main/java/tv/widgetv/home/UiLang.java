package tv.widgetv.home;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Applies res/values/config.xml ui_language to a context. The TV's system language stays as it is. */
final class UiLang {
    private UiLang() {
    }

    static Context wrap(Context base) {
        String lang = base.getString(R.string.ui_language).trim();
        if (!"ar".equals(lang) && !"en".equals(lang)) return base;
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }
}
