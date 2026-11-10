package ua.hahacam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Плаваюче вікно поверх інших програм: живе прев'ю кружечка + кнопка «Почати»/«Стоп».
 * Потребує дозволу «Поверх інших програм». У режимі «одна програма» запису екрана
 * це вікно не потрапляє у відео (Android записує лише вибрану програму).
 * Усі методи — лише з головного потоку.
 */
final class OverlayController {

    interface Listener {
        void onPreviewTexture(SurfaceTexture st);
        void onStartTap();
        void onStopTap();
        void onCancelTap();
    }

    static boolean allowed(Context c) { return Settings.canDrawOverlays(c); }

    private final Context ctx;
    private final Listener listener;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float dp;

    private LinearLayout root;
    private FrameLayout circle;
    private TextureView tv;
    private TextView primary, cancel, timer, bigCount;
    private WindowManager.LayoutParams lp;
    private float aspect = 0f;           // ширина/висота кадру камери «стоячи»
    private boolean shown;

    OverlayController(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.dp = ctx.getResources().getDisplayMetrics().density;
    }

    /** @return false, якщо показати вікно не вдалося */
    @SuppressLint("ClickableViewAccessibility")
    boolean show() {
        if (shown) return true;
        int size = (int) (112 * dp);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (8 * dp);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xCC15151B);
        card.setCornerRadius(22 * dp);
        root.setBackground(card);

        // Кружечок з живим прев'ю
        circle = new FrameLayout(ctx);
        circle.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        circle.setClipToOutline(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke((int) (3 * dp), Color.WHITE);
        circle.setForeground(ring);
        circle.setBackgroundColor(Color.BLACK);

        tv = new TextureView(ctx);
        tv.setOpaque(false);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                applyTransform();
                listener.onPreviewTexture(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applyTransform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        circle.addView(tv, new FrameLayout.LayoutParams(size, size));

        bigCount = new TextView(ctx);
        bigCount.setTextColor(Color.WHITE);
        bigCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        bigCount.setTypeface(Typeface.DEFAULT_BOLD);
        bigCount.setGravity(Gravity.CENTER);
        bigCount.setBackgroundColor(0x66000000);
        bigCount.setVisibility(View.GONE);
        circle.addView(bigCount, new FrameLayout.LayoutParams(size, size));

        root.addView(circle, new LinearLayout.LayoutParams(size, size));

        timer = new TextView(ctx);
        timer.setTextColor(Color.WHITE);
        timer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        timer.setTypeface(Typeface.MONOSPACE);
        timer.setGravity(Gravity.CENTER);
        timer.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = (int) (4 * dp);
        root.addView(timer, tlp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        primary = new TextView(ctx);
        primary.setTextColor(Color.WHITE);
        primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        primary.setTypeface(Typeface.DEFAULT_BOLD);
        primary.setGravity(Gravity.CENTER);
        int ph = (int) (14 * dp), pv = (int) (8 * dp);
        primary.setPadding(ph, pv, ph, pv);
        primary.setOnClickListener(v -> {
            int st = RecorderService.state;
            if (st == RecorderService.RECORDING) listener.onStopTap();
            else if (st == RecorderService.READY && RecorderService.countdownUntilWall <= System.currentTimeMillis()) listener.onStartTap();
        });
        row.addView(primary);

        cancel = new TextView(ctx);
        cancel.setText("✕");
        cancel.setTextColor(0xCCFFFFFF);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        cancel.setPadding((int) (12 * dp), pv, (int) (6 * dp), pv);
        cancel.setOnClickListener(v -> listener.onCancelTap());
        row.addView(cancel);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = (int) (6 * dp);
        root.addView(row, rlp);

        // Перетягування за кружечок
        circle.setOnTouchListener(new View.OnTouchListener() {
            final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            float sx, sy; int ox, oy; boolean drag;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; drag = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!drag && Math.hypot(dx, dy) > slop) drag = true;
                        if (drag) {
                            lp.x = ox + (int) dx;
                            lp.y = oy + (int) dy;
                            try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (drag) Prefs.sp(ctx).edit().putInt("ovX", lp.x).putInt("ovY", lp.y).apply();
                        return true;
                }
                return false;
            }
        });

        SharedPreferences sp = Prefs.sp(ctx);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("ovX", (int) (16 * dp));
        lp.y = sp.getInt("ovY", (int) (140 * dp));
        try {
            wm.addView(root, lp);
            shown = true;
        } catch (Exception e) {
            shown = false;
            return false;
        }
        ui.post(ticker);
        return true;
    }

    /** Пропорції кадру камери «стоячи» — щоб прев'ю не розтягувалось. */
    void setUprightAspect(float a) {
        aspect = a;
        applyTransform();
    }

    private void applyTransform() {
        if (tv == null || aspect <= 0 || tv.getWidth() == 0) return;
        Matrix m = new Matrix();
        float cx = tv.getWidth() / 2f, cy = tv.getHeight() / 2f;
        if (aspect < 1f) m.setScale(1f, 1f / aspect, cx, cy);
        else m.setScale(aspect, 1f, cx, cy);
        tv.setTransform(m);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!shown) return;
            refresh();
            ui.postDelayed(this, 200);
        }
    };

    private void refresh() {
        int st = RecorderService.state;
        long now = System.currentTimeMillis();
        long cd = RecorderService.countdownUntilWall - now;
        boolean counting = st == RecorderService.READY && cd > 0;

        bigCount.setVisibility(counting ? View.VISIBLE : View.GONE);
        if (counting) bigCount.setText(String.valueOf((cd + 999) / 1000));

        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(999);
        if (st == RecorderService.RECORDING) {
            pill.setColor(0xFF37373F);
            primary.setText(ctx.getString(R.string.ov_stop));
            timer.setVisibility(View.VISIBLE);
            timer.setText("● " + MainActivity.fmtDuration(now - RecorderService.startedAtWall));
            cancel.setVisibility(View.GONE);
        } else if (st == RecorderService.READY) {
            pill.setColor(counting ? 0xFF7A2A2A : 0xFFE53935);
            primary.setText(ctx.getString(counting ? R.string.ov_wait : R.string.ov_start));
            timer.setVisibility(View.GONE);
            cancel.setVisibility(counting ? View.GONE : View.VISIBLE);
        } else {
            pill.setColor(0xFF37373F);
            primary.setText(ctx.getString(st == RecorderService.FINISHING ? R.string.ov_saving : R.string.ov_preparing));
            timer.setVisibility(View.GONE);
            cancel.setVisibility(st == RecorderService.FINISHING ? View.GONE : View.VISIBLE);
        }
        primary.setBackground(pill);
    }

    void remove() {
        shown = false;
        ui.removeCallbacks(ticker);
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
        }
        root = null;
        tv = null;
    }
}
