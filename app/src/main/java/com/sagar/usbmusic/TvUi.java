package com.sagar.usbmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Shared look of the TV screen. All sizes are written for a 1920x1080 layout ("design px") and
 * scaled to the real screen width, so the screen looks the same whatever density the TV reports.
 */
final class TvUi {
    static final int BG = 0xFF030303;
    static final int CARD = 0xFF1B1B1D;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFA8A8AD;
    static final int ACCENT = 0xFFFF2A4D;
    static final int DARK_TEXT = 0xFF000000;
    static final int DARK_MUTED = 0xFF444444;

    private static float unit = 1f;

    private TvUi() {}

    static void init(Context c) {
        unit = c.getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    static int px(float design) {
        return Math.round(design * unit);
    }

    static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        return d;
    }

    static GradientDrawable ring(float radius, float stroke, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(px(radius));
        d.setStroke(px(stroke), color);
        return d;
    }

    /** Soft cover gradient picked from a number, so a folder keeps the same colours. */
    static GradientDrawable coverGradient(int hue) {
        float h = ((hue % 360) + 360) % 360;
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                Color.HSVToColor(new float[]{h, 0.62f, 0.55f}),
                Color.HSVToColor(new float[]{(h + 45f) % 360f, 0.58f, 0.28f})});
        return d;
    }

    static int hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
        return (h & 0x7fffffff) % 360;
    }

    /** Focus feedback shared by every focusable thing: a short grow with an ease-out. */
    interface FocusChange {
        void onFocus(boolean focused);
    }

    static void focusable(View v, final float scale, final FocusChange extra) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean f) {
                view.animate().scaleX(f ? scale : 1f).scaleY(f ? scale : 1f)
                        .setDuration(f ? 170 : 130)
                        .setInterpolator(new DecelerateInterpolator()).start();
                if (extra != null) extra.onFocus(f);
            }
        });
    }

    /**
     * Runs the action as soon as OK is pressed, instead of waiting for the key to come back up.
     * A normal click needs a short press: if the key is held a little too long (remotes repeat
     * the key then) Android treats it as a long press and the click never happens.
     */
    static void pressToActivate(View v, final Runnable action) {
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int code, android.view.KeyEvent e) {
                if (code != android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        && code != android.view.KeyEvent.KEYCODE_ENTER
                        && code != android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    return false;
                }
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) action.run();
                return true;
            }
        });
    }

    static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    static String plural(int n, String w) {
        return n + " " + w + (n == 1 ? "" : "s");
    }

    static String span(long ms) {
        long m = Math.round(ms / 60000.0);
        if (m < 60) return m + (m == 1 ? " minute" : " minutes");
        long h = m / 60, r = m % 60;
        return h + (h == 1 ? " hour" : " hours") + (r > 0 ? ", " + r + " minutes" : "");
    }

    /** Cover picture on a rounded (or round) tile; shows a coloured tile with an icon until/unless art loads. */
    static final class ArtView extends ImageView {
        private final int size;
        /** Focus ring, created once and only faded in and out: adding or removing a foreground asks for a new layout. */
        private final GradientDrawable ringDrawable = ring(0, 6, Color.WHITE);
        private int padNow = -1;

        ArtView(Context c, final int sizePx, final boolean circle, final float radius) {
            super(c);
            this.size = sizePx;
            ringDrawable.setAlpha(0);
            setForeground(ringDrawable);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline o) {
                    if (circle) o.setOval(0, 0, view.getWidth(), view.getHeight());
                    else o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(radius));
                }
            });
            setClipToOutline(true);
        }

        int size() {
            return size;
        }

        void showIcon(int hue, int iconRes, boolean grey) {
            setBackground(grey
                    ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF3A3A40, 0xFF222226})
                    : coverGradient(hue));
            pad(Math.round(size * 0.29f));
            setScaleType(ScaleType.FIT_CENTER);
            setImageResource(iconRes);
            setColorFilter(grey ? 0xFFC9C9CF : Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        }

        void showBitmap(Bitmap b) {
            clearColorFilter();
            pad(0);
            setScaleType(ScaleType.CENTER_CROP);
            setImageBitmap(b);
        }

        /** Only changes the padding when it really differs; every change asks the parent for a layout. */
        private void pad(int p) {
            if (padNow == p) return;
            padNow = p;
            setPadding(p, p, p, p);
        }

        /** White ring drawn inside the edge while the tile is focused. */
        void setRing(boolean on) {
            ringDrawable.setAlpha(on ? 255 : 0);
        }
    }

    /** Three or five bars that move while music plays; still when paused. */
    static final class EqView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int bars;
        private boolean running = true;
        private final RectF r = new RectF();

        EqView(Context c, int bars, int color) {
            super(c);
            this.bars = bars;
            paint.setColor(color);
        }

        void setRunning(boolean on) {
            running = on;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float gap = w * 0.12f;
            float bw = (w - gap * (bars - 1)) / bars;
            long t = android.os.SystemClock.uptimeMillis();
            for (int i = 0; i < bars; i++) {
                double phase = running ? t / (260.0 + i * 55.0) + i * 1.7 : i * 1.3 + 1;
                float f = 0.25f + 0.75f * (float) Math.abs(Math.sin(phase));
                float bh = h * f;
                float x = i * (bw + gap);
                r.set(x, h - bh, x + bw, h);
                cv.drawRoundRect(r, bw / 2, bw / 2, paint);
            }
            if (running && isShown()) postInvalidateOnAnimation();
        }
    }
}
