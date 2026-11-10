package ua.hahacam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Смуга вибору фрагмента над стрічкою кадрів: два повзунки, затемнення поза вибором, курсор відтворення. */
public class RangeView extends View {

    interface Listener {
        /** @param which 0 — початок, 1 — кінець; done — палець відпущено */
        void onRange(float start, float end, int which, boolean done);
    }

    private final float dp;
    private final Paint dim = new Paint(), frame = new Paint(Paint.ANTI_ALIAS_FLAG),
            handle = new Paint(Paint.ANTI_ALIAS_FLAG), grip = new Paint(Paint.ANTI_ALIAS_FLAG),
            play = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float start = 0f, end = 1f, playhead = -1f, minFrac = 0.02f;
    private int dragging = -1;
    private Listener listener;

    public RangeView(Context c) { this(c, null); }

    public RangeView(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        dim.setColor(0xAA000000);
        frame.setColor(0xFFFFFFFF);
        handle.setColor(0xFFFFFFFF);
        grip.setColor(0xFF15151B);
        grip.setStrokeWidth(2 * dp);
        grip.setStrokeCap(Paint.Cap.ROUND);
        play.setColor(0xFFE53935);
        play.setStrokeWidth(2 * dp);
    }

    void setListener(Listener l) { listener = l; }
    void setMinFraction(float f) { minFrac = Math.max(0.001f, Math.min(0.5f, f)); }
    float getStart() { return start; }
    float getEnd() { return end; }

    void setRange(float s, float e) {
        start = clamp(s);
        end = clamp(e);
        invalidate();
    }

    void setPlayhead(float f) { playhead = f; invalidate(); }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    private float handleW() { return 14 * dp; }

    /** Робоча ширина між повзунками (повзунки стоять усередині вибору). */
    private float track() { return getWidth() - 2 * handleW(); }
    private float xOf(float f) { return handleW() + f * track(); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), hw = handleW();
        float xs = xOf(start), xe = xOf(end);
        c.drawRect(0, 0, xs - hw, h, dim);
        c.drawRect(xe + hw, 0, w, h, dim);
        float b = 3 * dp;
        c.drawRect(xs, 0, xe, b, frame);
        c.drawRect(xs, h - b, xe, h, frame);
        float r = 6 * dp;
        c.drawRoundRect(new RectF(xs - hw, 0, xs, h), r, r, handle);
        c.drawRoundRect(new RectF(xe, 0, xe + hw, h), r, r, handle);
        c.drawLine(xs - hw / 2, h * 0.35f, xs - hw / 2, h * 0.65f, grip);
        c.drawLine(xe + hw / 2, h * 0.35f, xe + hw / 2, h * 0.65f, grip);
        if (playhead >= 0) {
            float xp = xOf(playhead);
            c.drawLine(xp, 0, xp, h, play);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float f = clamp((x - handleW()) / Math.max(1f, track()));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float ds = Math.abs(x - (xOf(start) - handleW() / 2)), de = Math.abs(x - (xOf(end) + handleW() / 2));
                float reach = 36 * dp;
                if (Math.min(ds, de) > reach) { dragging = -1; return false; }
                dragging = ds <= de ? 0 : 1;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging < 0) return false;
                if (dragging == 0) start = Math.min(f, end - minFrac); else end = Math.max(f, start + minFrac);
                start = clamp(start);
                end = clamp(end);
                invalidate();
                if (listener != null) listener.onRange(start, end, dragging, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging >= 0 && listener != null) listener.onRange(start, end, dragging, true);
                dragging = -1;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
