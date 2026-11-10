package ua.hahacam;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ручне обрізання як у Telegram: стрічка кадрів, два повзунки, перегляд вибраного фрагмента.
 * Обрізає без перекодування (миттєво, без втрати якості); початок прилипає до ключового кадру.
 */
public class TrimActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Uri uri;
    private long durMs;
    private VideoView video;
    private RangeView range;
    private TextView rangeText, btnPlay;
    private LinearLayout frames;
    private Button btnCopy, btnReplace;
    private boolean playing, busy;

    static Intent intent(android.content.Context c, Uri uri) {
        return new Intent(c, TrimActivity.class).setData(uri);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_trim);
        // Темний екран — світлі іконки в рядку стану незалежно від теми.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        uri = getIntent().getData();
        if (uri == null) { finish(); return; }

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        video = findViewById(R.id.video);
        range = findViewById(R.id.range);
        rangeText = findViewById(R.id.rangeText);
        btnPlay = findViewById(R.id.btnPlay);
        frames = findViewById(R.id.frames);
        btnCopy = findViewById(R.id.btnCopy);
        btnReplace = findViewById(R.id.btnReplace);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> togglePlay());
        video.setOnClickListener(v -> togglePlay());
        btnCopy.setOnClickListener(v -> save(false));
        btnReplace.setOnClickListener(v -> save(true));

        video.setVideoURI(uri);
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            if (durMs <= 0) { durMs = mp.getDuration(); onDurationKnown(); }
            video.seekTo(1);
        });
        video.setOnCompletionListener(mp -> stopPlay());
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show();
            return true;
        });

        range.setListener((s, e, which, done) -> {
            if (playing) stopPlay();
            if (durMs <= 0) return;
            video.seekTo((int) ((which == 0 ? s : e) * durMs));
            updateText();
            if (done && which == 0) snapStart();
        });

        io.execute(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, uri);
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long ms = d == null ? 0 : Long.parseLong(d);
                ui.post(() -> { if (ms > 0) { durMs = ms; onDurationKnown(); } });
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }
        });
    }

    private boolean durationHandled;

    private void onDurationKnown() {
        if (durationHandled || durMs <= 0) return;
        durationHandled = true;
        range.setMinFraction(Math.min(0.5f, 1000f / durMs));
        updateText();
        frames.post(this::loadFrames);
    }

    private void updateText() {
        long s = (long) (range.getStart() * durMs), e = (long) (range.getEnd() * durMs);
        rangeText.setText(MainActivity.fmtDuration(s) + "  –  " + MainActivity.fmtDuration(e)
                + "   ·   " + MainActivity.fmtDuration(e - s));
    }

    /** Початок прилипає до найближчого наступного ключового кадру — саме з нього почнеться відео. */
    private void snapStart() {
        final long startUs = (long) (range.getStart() * durMs * 1000);
        io.execute(() -> {
            long snapped = Trimmer.nextSyncUs(this, uri, startUs);
            ui.post(() -> {
                float f = snapped / 1000f / durMs;
                if (f < range.getEnd() - 0.5f * 1000f / durMs && f >= 0) {
                    range.setRange(f, range.getEnd());
                    video.seekTo((int) (f * durMs));
                    updateText();
                }
            });
        });
    }

    private void loadFrames() {
        final int count = 10;
        final int fw = Math.max(1, frames.getWidth() / count), fh = Math.max(1, frames.getHeight());
        io.execute(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, uri);
                for (int i = 0; i < count; i++) {
                    long t = (long) ((i + 0.5) / count * durMs * 1000);
                    Bitmap bm;
                    try {
                        bm = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, fw * 2, fh * 2);
                    } catch (Exception e) { bm = null; }
                    final Bitmap fb = bm;
                    ui.post(() -> {
                        if (isFinishing()) return;
                        ImageView iv = new ImageView(this);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        if (fb != null) iv.setImageBitmap(fb);
                        frames.addView(iv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
                    });
                }
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }
        });
    }

    // ---------------------------------------------------------------- відтворення

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            int pos = video.getCurrentPosition();
            if (durMs > 0) range.setPlayhead(pos / (float) durMs);
            if (pos >= range.getEnd() * durMs) { stopPlay(); return; }
            ui.postDelayed(this, 40);
        }
    };

    private void togglePlay() {
        if (busy || durMs <= 0) return;
        if (playing) { stopPlay(); return; }
        playing = true;
        btnPlay.setVisibility(View.GONE);
        video.seekTo((int) (range.getStart() * durMs));
        video.start();
        ui.post(ticker);
    }

    private void stopPlay() {
        playing = false;
        ui.removeCallbacks(ticker);
        if (video.isPlaying()) video.pause();
        video.seekTo((int) (range.getStart() * durMs));
        range.setPlayhead(-1);
        btnPlay.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onPause() {
        if (playing) stopPlay();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- збереження

    private void save(boolean replace) {
        if (busy || durMs <= 0) return;
        busy = true;
        if (playing) stopPlay();
        btnCopy.setEnabled(false);
        btnReplace.setEnabled(false);
        video.stopPlayback();
        Toast.makeText(this, R.string.st_saving, Toast.LENGTH_SHORT).show();

        final long startUs = (long) (range.getStart() * durMs * 1000);
        final long endUs = range.getEnd() >= 0.999f ? -1 : (long) (range.getEnd() * durMs * 1000);

        io.execute(() -> {
            File tmp = new File(getCacheDir(), "trim_" + System.currentTimeMillis() + ".mp4");
            Uri result = null;
            boolean replaced = false;
            String error = null;
            try {
                try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
                    Trimmer.remux(this, uri, raf.getFD(), startUs, endUs);
                }
                ContentResolver cr = getContentResolver();
                if (replace) {
                    try (OutputStream out = cr.openOutputStream(uri, "wt");
                         InputStream in = new FileInputStream(tmp)) {
                        copy(in, out);
                        result = uri;
                        replaced = true;
                    } catch (SecurityException se) {
                        result = null;   // не наш файл — збережемо копію
                    }
                }
                if (result == null) result = saveCopy(cr, tmp);
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                tmp.delete();
            }
            final Uri res = result;
            final boolean rep = replaced;
            final String err = error;
            ui.post(() -> {
                if (res != null) {
                    RecorderService.lastSavedUri = res;
                    Toast.makeText(this, rep ? R.string.trim_done_replaced
                            : replace ? R.string.trim_done_copy_fallback : R.string.trim_done_copy,
                            Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    Toast.makeText(this, getString(R.string.err_save, err), Toast.LENGTH_LONG).show();
                    busy = false;
                    btnCopy.setEnabled(true);
                    btnReplace.setEnabled(true);
                    video.setVideoURI(uri);
                }
            });
        });
    }

    private Uri saveCopy(ContentResolver cr, File src) throws Exception {
        String name = "Hahacam_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_cut.mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Hahacam");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri dst = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (dst == null) throw new IllegalStateException("insert failed");
        try (OutputStream out = cr.openOutputStream(dst, "w"); InputStream in = new FileInputStream(src)) {
            copy(in, out);
        } catch (Exception e) {
            cr.delete(dst, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Video.Media.IS_PENDING, 0);
        cr.update(dst, done, null, null);
        return dst;
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
