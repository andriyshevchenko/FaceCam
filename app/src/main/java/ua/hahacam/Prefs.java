package ua.hahacam;

import android.content.Context;
import android.content.SharedPreferences;

/** Налаштування запису. */
final class Prefs {
    static final int[] DELAYS_SEC = {0, 3, 5, 10};
    static final int[] AUTOSTOP_MIN = {0, 1, 3, 5, 10, 15, 30, 60};
    static final int[] TAIL_SEC = {1, 2, 3, 4};
    static final float[] BUBBLE_SIZE = {0.28f, 0.36f, 0.46f};

    static final int MODE_CAMERA = 0, MODE_SCREEN = 1;

    final int mode;
    final boolean front;
    final boolean hd1080;
    final boolean audio;
    final boolean appAudio;
    final boolean hiddenNotif;
    final int delayIdx;
    final int autoStopIdx;
    final int corner;
    final int bubbleIdx;
    final boolean trim;
    final int tailIdx;

    private Prefs(SharedPreferences p) {
        mode = p.getInt("mode", MODE_CAMERA) == MODE_SCREEN ? MODE_SCREEN : MODE_CAMERA;
        front = p.getBoolean("front", true);
        hd1080 = p.getBoolean("hd1080", true);
        audio = p.getBoolean("audio", true);
        appAudio = p.getBoolean("appAudio", true);
        hiddenNotif = BuildConfig.ALLOW_HIDDEN_NOTIFICATION && p.getBoolean("hidden", false);
        delayIdx = clamp(p.getInt("delay", 0), DELAYS_SEC.length);
        autoStopIdx = clamp(p.getInt("autostop", 0), AUTOSTOP_MIN.length);
        corner = clamp(p.getInt("corner", 1), 4);
        bubbleIdx = clamp(p.getInt("bubble", 1), BUBBLE_SIZE.length);
        trim = p.getBoolean("trim", true);
        tailIdx = clamp(p.getInt("tail", 1), TAIL_SEC.length);
    }

    boolean screen() { return mode == MODE_SCREEN; }
    int delaySec() { return screen() ? 0 : DELAYS_SEC[delayIdx]; }
    int autoStopMin() { return AUTOSTOP_MIN[autoStopIdx]; }
    int tailSec() { return TAIL_SEC[tailIdx]; }
    float bubbleFrac() { return BUBBLE_SIZE[bubbleIdx]; }

    static Prefs load(Context c) { return new Prefs(sp(c)); }

    static final int CONSENT_VERSION = 2;   // 2: додано запис екрана

    static boolean hasConsent(Context c) { return sp(c).getInt("consent", 0) >= CONSENT_VERSION; }
    static void setConsent(Context c) { sp(c).edit().putInt("consent", CONSENT_VERSION).apply(); }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("hahacam", Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int len) { return v < 0 || v >= len ? 0 : v; }
}
