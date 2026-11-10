package ua.facecam;

import android.content.Context;
import android.content.SharedPreferences;

/** Налаштування запису. */
final class Prefs {
    static final int[] DELAYS_SEC = {0, 3, 5, 10};

    static final int[] AUTOSTOP_MIN = {0, 1, 3, 5, 10, 15, 30, 60};

    final boolean front;
    final boolean hd1080;
    final boolean audio;
    final boolean hiddenNotif;
    final int delayIdx;
    final int autoStopIdx;

    private Prefs(SharedPreferences p) {
        front = p.getBoolean("front", true);
        hd1080 = p.getBoolean("hd1080", true);
        audio = p.getBoolean("audio", true);
        hiddenNotif = BuildConfig.ALLOW_HIDDEN_NOTIFICATION && p.getBoolean("hidden", false);
        delayIdx = clamp(p.getInt("delay", 0), DELAYS_SEC.length);
        autoStopIdx = clamp(p.getInt("autostop", 0), AUTOSTOP_MIN.length);
    }

    int delaySec() { return DELAYS_SEC[delayIdx]; }
    int autoStopMin() { return AUTOSTOP_MIN[autoStopIdx]; }

    static Prefs load(Context c) { return new Prefs(sp(c)); }

    static final int CONSENT_VERSION = 1;

    static boolean hasConsent(Context c) { return sp(c).getInt("consent", 0) >= CONSENT_VERSION; }
    static void setConsent(Context c) { sp(c).edit().putInt("consent", CONSENT_VERSION).apply(); }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("facecam", Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int len) { return v < 0 || v >= len ? 0 : v; }
}
