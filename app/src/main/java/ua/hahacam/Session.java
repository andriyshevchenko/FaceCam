package ua.hahacam;

import android.os.SystemClock;

/**
 * Моменти часу (SystemClock.elapsedRealtime) для автообрізання:
 * коли почався запис, коли користувач пішов з Hahacam і коли повернувся.
 */
final class Session {
    private Session() { }

    static volatile boolean appVisible;
    static volatile long appShownAt;        // останній раз, коли Hahacam став видимим

    static volatile boolean recording;
    static volatile long recStart;          // старт запису (перший кадр)
    static volatile boolean visibleAtStart; // чи був Hahacam на екрані в момент старту
    static volatile long firstHiddenAt;     // перший вихід з Hahacam після старту

    static void onAppVisible(boolean visible) {
        long t = SystemClock.elapsedRealtime();
        appVisible = visible;
        if (visible) {
            appShownAt = t;
        } else if (recording && firstHiddenAt == 0) {
            firstHiddenAt = t;
        }
    }

    static void markRecordingStart(long elapsed) {
        recStart = elapsed;
        visibleAtStart = appVisible;
        firstHiddenAt = 0;
        recording = true;
    }

    static void markRecordingEnd() { recording = false; }

    /** Звідки зупинили запис. */
    enum StopSource { APP, NOTIFICATION, AUTO }

    /**
     * Повертає {startMs, endMs} відносно початку файлу; endMs = -1 означає «до кінця».
     */
    static long[] computeTrim(boolean enabled, int tailSec, StopSource src, long stopElapsed) {
        long start = 0, end = -1;
        if (!enabled || recStart <= 0) return new long[]{0, -1};
        long total = stopElapsed - recStart;

        if (visibleAtStart && firstHiddenAt > recStart) start = firstHiddenAt - recStart;

        if (src == StopSource.APP) {
            // Повернулися в Hahacam і натиснули «Стоп»: кінець — момент повернення.
            if (appVisible && appShownAt > recStart + start) end = appShownAt - recStart;
        } else if (src == StopSource.NOTIFICATION) {
            end = total - tailSec * 1000L;
        }

        // Не обрізаємо так, щоб лишилося менше секунди.
        long effEnd = end < 0 ? total : end;
        if (effEnd - start < 1000) {
            if (total - start >= 1000) end = -1; else { start = 0; end = -1; }
        }
        return new long[]{Math.max(0, start), end};
    }

    static long now() { return SystemClock.elapsedRealtime(); }
}
