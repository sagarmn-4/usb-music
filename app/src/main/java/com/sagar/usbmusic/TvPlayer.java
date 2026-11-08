package com.sagar.usbmusic;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SweepGradient;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static com.sagar.usbmusic.TvUi.px;

/**
 * The player bar along the bottom of the TV screen and the full now-playing page above it
 * (cover or spinning record, Up next queue). Driven by the same state the phone page polls.
 */
final class TvPlayer {

    interface Host {
        LibraryIndex index();

        void command(String cmd, String... kv);

        JSONObject query(String cmd, String... kv);

        void toast(String s);

        /** The bar appeared or went away (nothing is loaded). */
        void barShown(boolean shown);

        /** The now-playing page opened or closed; the screen behind it should not take focus. */
        void npOpened(boolean open);

        void showMenu(String title, String sub, Track art, String[] labels, Runnable[] actions);
    }

    private static final class QItem {
        int id, dur;
        String title, artist;
    }

    private final Activity act;
    private final Host host;
    private final FrameLayout bar;
    private final FrameLayout np;

    // bar
    private SeekView seek;
    private LinearLayout info;
    private TvUi.ArtView barArt;
    private TextView barTitle, barSub, timeView, volView;
    private ImageView btnShuffle, btnPlay, btnRepeat;

    // now playing
    private FrameLayout artHolder;
    private LinearLayout nleft, qpanel;
    private TextView npTitle, npArtist, fromView, curTitle, curSub, curDur;
    private TvUi.ArtView curArt;
    private View tintView;
    private ListView queueList;
    private QueueAdapter qAdapter;
    private RecordView record;
    private TvUi.EqView labelEq;
    private ObjectAnimator spin;
    private final GradientDrawable tintDrawable = new GradientDrawable();
    private int tintColor = 0xFF333333;

    // state
    private int id = -1;
    private boolean playing, hasTrack, open, ambient;
    private boolean shuffleOn, repeatOn;
    private int pos, dur, vol, maxVol = 100, volTarget = -1;
    private int lastUpnext = -1, lastQueueId = -2;
    private String repeatMode = "off";
    private final List<QItem> queueItems = new ArrayList<>();
    private Runnable volSend;

    TvPlayer(Activity activity, Host h) {
        act = activity;
        host = h;

        // ---- bar
        bar = new FrameLayout(act);
        bar.setBackgroundColor(0xFF1B1B1B);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(40), px(6), px(40), 0);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        bar.addView(row, new FrameLayout.LayoutParams(-1, -1));

        info = new LinearLayout(act);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(px(12), 0, px(12), 0);
        barArt = new TvUi.ArtView(act, px(84), false, 8);
        info.addView(barArt, new LinearLayout.LayoutParams(px(84), px(84)));
        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        barTitle = TvUi.text(act, "", 30, TvUi.TEXT, true);
        barSub = TvUi.text(act, "", 23, TvUi.MUTED, false);
        texts.addView(barTitle);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = px(4);
        texts.addView(barSub, sp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = px(22);
        info.addView(texts, tp);
        TvUi.focusable(info, 1f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                if (f) {
                    GradientDrawable d = TvUi.round(0x2EFFFFFF, 16);
                    d.setStroke(px(4), Color.WHITE);
                    info.setBackground(d);
                } else {
                    info.setBackground(null);
                }
            }
        });
        info.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openNp(false);
            }
        });
        row.addView(info, new LinearLayout.LayoutParams(px(620), px(96)));

        LinearLayout ctls = new LinearLayout(act);
        ctls.setOrientation(LinearLayout.HORIZONTAL);
        ctls.setGravity(Gravity.CENTER);
        ctls.setClipChildren(false);
        btnShuffle = iconBtn(R.drawable.ic_shuffle, 76, 20, false, new Runnable() {
            @Override public void run() {
                host.command("shuffle", "on", shuffleOn ? "0" : "1");
            }
        });
        ImageView prev = iconBtn(R.drawable.ic_prev, 76, 20, false, new Runnable() {
            @Override public void run() {
                host.command("prev");
            }
        });
        btnPlay = iconBtn(R.drawable.ic_play, 100, 26, true, new Runnable() {
            @Override public void run() {
                host.command("toggle");
            }
        });
        ImageView next = iconBtn(R.drawable.ic_next, 76, 20, false, new Runnable() {
            @Override public void run() {
                host.command("next");
            }
        });
        btnRepeat = iconBtn(R.drawable.ic_repeat, 76, 20, false, new Runnable() {
            @Override public void run() {
                String m = "off".equals(repeatMode) ? "all" : "all".equals(repeatMode) ? "one" : "off";
                host.command("repeat", "mode", m);
            }
        });
        addBtn(ctls, btnShuffle, 76, 11);
        addBtn(ctls, prev, 76, 11);
        addBtn(ctls, btnPlay, 100, 11);
        addBtn(ctls, next, 76, 11);
        addBtn(ctls, btnRepeat, 76, 11);
        row.addView(ctls, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        right.setClipChildren(false);
        timeView = TvUi.text(act, "0:00 / 0:00", 26, 0xFFCFCFD4, false);
        right.addView(timeView, new LinearLayout.LayoutParams(-2, -2));
        ImageView vdn = iconBtn(R.drawable.ic_minus, 68, 16, false, new Runnable() {
            @Override public void run() {
                stepVolume(-1);
            }
        });
        volView = TvUi.text(act, "0", 26, 0xFFCFCFD4, false);
        volView.setGravity(Gravity.CENTER);
        ImageView vup = iconBtn(R.drawable.ic_plus, 68, 16, false, new Runnable() {
            @Override public void run() {
                stepVolume(1);
            }
        });
        ImageView queue = iconBtn(R.drawable.ic_queue, 68, 16, false, new Runnable() {
            @Override public void run() {
                openNp(true);
            }
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.rightMargin = px(16);
        right.removeAllViews();
        right.addView(timeView, tl);
        addBtn(right, vdn, 68, 4);
        right.addView(volView, new LinearLayout.LayoutParams(px(60), -2));
        addBtn(right, vup, 68, 4);
        addBtn(right, queue, 68, 4);
        row.addView(right, new LinearLayout.LayoutParams(px(560), -2));

        seek = new SeekView(act);
        FrameLayout.LayoutParams skp = new FrameLayout.LayoutParams(-1, px(36));
        skp.topMargin = -px(16);
        bar.addView(seek, skp);

        // ---- now-playing page
        np = new FrameLayout(act);
        np.setBackgroundColor(0xFF060606);
        np.setVisibility(View.GONE);
        np.setClipChildren(false);

        tintView = new View(act);
        tintDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        tintDrawable.setGradientRadius(px(1100));
        tintDrawable.setGradientCenter(0.25f, 0.42f);
        setTint(tintColor);
        tintView.setBackground(tintDrawable);
        np.addView(tintView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(act);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(px(110), px(60), px(90), 0);
        page.setClipChildren(false);
        np.addView(page, new FrameLayout.LayoutParams(-1, -1));

        nleft = new LinearLayout(act);
        nleft.setOrientation(LinearLayout.VERTICAL);
        nleft.setGravity(Gravity.CENTER_HORIZONTAL);
        nleft.setClipChildren(false);
        artHolder = new FrameLayout(act);
        artHolder.setClipChildren(false);
        nleft.addView(artHolder, new LinearLayout.LayoutParams(px(560), px(560)));
        npTitle = TvUi.text(act, "", 52, TvUi.TEXT, true);
        npTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ntp = new LinearLayout.LayoutParams(-1, -2);
        ntp.topMargin = px(34);
        nleft.addView(npTitle, ntp);
        npArtist = TvUi.text(act, "", 32, 0xFFD8D8DC, false);
        npArtist.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nap = new LinearLayout.LayoutParams(-1, -2);
        nap.topMargin = px(8);
        nleft.addView(npArtist, nap);
        page.addView(nleft, new LinearLayout.LayoutParams(px(600), -1));

        qpanel = new LinearLayout(act);
        qpanel.setOrientation(LinearLayout.VERTICAL);
        qpanel.setClipChildren(false);
        TextView tab = TvUi.text(act, "UP NEXT", 28, TvUi.TEXT, true);
        tab.setLetterSpacing(0.06f);
        qpanel.addView(tab, new LinearLayout.LayoutParams(-2, -2));
        View under = new View(act);
        under.setBackgroundColor(Color.WHITE);
        qpanel.addView(under, new LinearLayout.LayoutParams(px(130), Math.max(2, px(4))));
        View rule = new View(act);
        rule.setBackgroundColor(0xFF2C2C2C);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        qpanel.addView(rule, rl);
        fromView = TvUi.text(act, "", 23, TvUi.MUTED, false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = px(22);
        fp.leftMargin = px(8);
        qpanel.addView(fromView, fp);

        LinearLayout cur = new LinearLayout(act);
        cur.setOrientation(LinearLayout.HORIZONTAL);
        cur.setGravity(Gravity.CENTER_VERTICAL);
        cur.setPadding(px(14), 0, px(18), 0);
        cur.setBackground(TvUi.round(0x1AFFFFFF, 14));
        curArt = new TvUi.ArtView(act, px(66), false, 8);
        cur.addView(curArt, new LinearLayout.LayoutParams(px(66), px(66)));
        LinearLayout ct = new LinearLayout(act);
        ct.setOrientation(LinearLayout.VERTICAL);
        curTitle = TvUi.text(act, "", 28, TvUi.ACCENT, true);
        curSub = TvUi.text(act, "", 22, TvUi.MUTED, false);
        ct.addView(curTitle);
        ct.addView(curSub);
        LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(0, -2, 1f);
        ctp.leftMargin = px(22);
        cur.addView(ct, ctp);
        TvUi.EqView eq = new TvUi.EqView(act, 3, TvUi.ACCENT);
        cur.addView(eq, new LinearLayout.LayoutParams(px(34), px(26)));
        curDur = TvUi.text(act, "", 24, TvUi.MUTED, false);
        LinearLayout.LayoutParams cdp = new LinearLayout.LayoutParams(-2, -2);
        cdp.leftMargin = px(20);
        cur.addView(curDur, cdp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, px(94));
        cp.topMargin = px(10);
        qpanel.addView(cur, cp);

        queueList = new ListView(act);
        queueList.setDivider(null);
        queueList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        queueList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        queueList.setVerticalScrollBarEnabled(false);
        queueList.setItemsCanFocus(true);
        queueList.setClipChildren(false);
        queueList.setClipToPadding(false);
        queueList.setPadding(0, px(8), 0, px(40));
        qAdapter = new QueueAdapter();
        queueList.setAdapter(qAdapter);
        qpanel.addView(queueList, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(0, -1, 1f);
        qp.leftMargin = px(70);
        page.addView(qpanel, qp);
    }

    View bar() {
        return bar;
    }

    View np() {
        return np;
    }

    boolean isOpen() {
        return open;
    }

    boolean isAmbient() {
        return ambient;
    }

    // ---- buttons --------------------------------------------------------------------------

    private ImageView iconBtn(int icon, int size, int pad, final boolean big, final Runnable action) {
        final ImageView b = new ImageView(act);
        b.setPadding(px(pad), px(pad), px(pad), px(pad));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setImageResource(icon);
        b.setTag(Boolean.FALSE);
        styleBtn(b, big);
        TvUi.focusable(b, 1.14f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                styleBtn(b, big);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private void addBtn(LinearLayout parent, ImageView b, int size, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(size), px(size));
        lp.leftMargin = lp.rightMargin = px(gap);
        parent.addView(b, lp);
    }

    private void styleBtn(ImageView b, boolean big) {
        boolean f = b.hasFocus();
        boolean on = Boolean.TRUE.equals(b.getTag());
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(f ? Color.WHITE : big ? 0x24FFFFFF : Color.TRANSPARENT);
        b.setBackground(d);
        b.setColorFilter(f ? (on ? 0xFFD4153A : Color.BLACK) : on ? TvUi.ACCENT : Color.WHITE,
                PorterDuff.Mode.SRC_IN);
    }

    private void setOn(ImageView b, boolean on, boolean big) {
        if (Boolean.valueOf(on).equals(b.getTag())) return;
        b.setTag(on);
        styleBtn(b, big);
    }

    private void stepVolume(int d) {
        int base = volTarget < 0 ? vol : volTarget;
        volTarget = Math.max(0, Math.min(maxVol, base + d));
        volView.setText(String.valueOf(volTarget));
        if (volSend != null) bar.removeCallbacks(volSend);
        volSend = new Runnable() {
            @Override public void run() {
                int v = volTarget;
                host.command("volume", "v", String.valueOf(v));
                volTarget = -1;
            }
        };
        bar.postDelayed(volSend, 150);
    }

    /** Left/Right on the seek line jump 10 seconds; the media keys use the same. */
    void seekBy(int deltaMs) {
        if (!hasTrack || dur <= 0) return;
        int to = Math.max(0, Math.min(dur, pos + deltaMs));
        pos = to;
        seek.setFraction(to / (float) dur);
        timeView.setText(TvUi.fmt(to) + " / " + TvUi.fmt(dur));
        host.command("seek", "ms", String.valueOf(to));
        host.toast((deltaMs > 0 ? "+10s  " : "−10s  ") + TvUi.fmt(to));
    }

    // ---- state ----------------------------------------------------------------------------

    void update(JSONObject st, LibraryIndex idx) {
        int nid = st.optInt("id", -1);
        boolean has = nid >= 0 && nid < idx.tracks.size();
        if (has != hasTrack) {
            hasTrack = has;
            bar.setVisibility(has ? View.VISIBLE : View.GONE);
            host.barShown(has);
            if (!has && open) closeNp();
        }
        playing = st.optBoolean("playing");
        pos = st.optInt("pos", 0);
        dur = st.optInt("dur", 0);
        vol = st.optInt("vol", 0);
        maxVol = Math.max(1, st.optInt("maxVol", 100));
        shuffleOn = st.optBoolean("shuffle");
        repeatMode = st.optString("repeat", "off");
        repeatOn = !"off".equals(repeatMode);
        int upnext = st.optInt("upnext", 0);
        if (!has) {
            id = -1;
            return;
        }
        if (nid != id) {
            id = nid;
            bindTrack(idx.tracks.get(nid), idx);
        }
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        setOn(btnShuffle, shuffleOn, false);
        setOn(btnRepeat, repeatOn, false);
        seek.setFraction(dur > 0 ? pos / (float) dur : 0f);
        timeView.setText(TvUi.fmt(pos) + " / " + TvUi.fmt(dur));
        if (volTarget < 0) volView.setText(String.valueOf(vol));
        curDur.setText(TvUi.fmt(dur));
        if (labelEq != null) labelEq.setRunning(playing);
        syncSpin();
        if (open && (nid != lastQueueId || upnext != lastUpnext)) {
            lastQueueId = nid;
            lastUpnext = upnext;
            refreshQueue();
        }
    }

    private void bindTrack(final Track t, final LibraryIndex idx) {
        String who = t.artist.isEmpty() ? t.composer : t.artist;
        barTitle.setText(t.title);
        barSub.setText(who.isEmpty() ? lastSegment(t.folder) : who + " • " + lastSegment(t.folder));
        TvArt.load(act, barArt, t, R.drawable.ic_note);
        npTitle.setText(t.title);
        npArtist.setText(who);
        fromView.setText("Playing from\n" + lastSegment(t.folder));
        curTitle.setText(t.title);
        curSub.setText(who);
        TvArt.load(act, curArt, t, R.drawable.ic_note);
        final int forId = id;
        showArtPlaceholder();
        TvArt.lookup(act, t, px(560), new TvArt.Result() {
            @Override public void done(Bitmap b) {
                if (forId != id) return;
                showNpArt(t, b);
            }
        });
    }

    private static String lastSegment(String folder) {
        int i = folder.lastIndexOf('/');
        return i >= 0 ? folder.substring(i + 1) : folder;
    }

    private void showArtPlaceholder() {
        artHolder.removeAllViews();
        record = null;
        labelEq = null;
        stopSpin();
    }

    private void showNpArt(Track t, Bitmap b) {
        artHolder.removeAllViews();
        stopSpin();
        record = null;
        labelEq = null;
        if (b != null) {
            TvUi.ArtView a = new TvUi.ArtView(act, px(560), false, 14);
            a.showBitmap(b);
            artHolder.addView(a, new FrameLayout.LayoutParams(px(560), px(560)));
            animateTint(dominant(b, TvArt.key(t)));
        } else {
            int color = hueColor(TvUi.hash(TvArt.key(t)));
            record = new RecordView(act, color);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(px(540), px(540), Gravity.CENTER);
            artHolder.addView(record, rp);
            labelEq = new TvUi.EqView(act, 5, 0xEBFFFFFF);
            labelEq.setRunning(playing);
            FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(px(110), px(90), Gravity.CENTER);
            ep.bottomMargin = px(0);
            artHolder.addView(labelEq, ep);
            animateTint(color);
            spin = ObjectAnimator.ofFloat(record, View.ROTATION, 0f, 360f);
            spin.setDuration(7000);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.start();
            syncSpin();
        }
    }

    private void stopSpin() {
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
    }

    private void syncSpin() {
        if (spin == null) return;
        if (playing && open) {
            if (spin.isPaused()) spin.resume();
        } else if (spin.isRunning() && !spin.isPaused()) {
            spin.pause();
        }
    }

    // ---- tint -----------------------------------------------------------------------------

    private void setTint(int color) {
        tintColor = color;
        int clear = color & 0x00FFFFFF;
        tintDrawable.setColors(new int[]{color, clear});
        tintView.invalidate();
    }

    private void animateTint(int to) {
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), tintColor, to);
        a.setDuration(700);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                setTint((Integer) va.getAnimatedValue());
            }
        });
        a.start();
    }

    private static int hueColor(int hue) {
        return Color.HSVToColor(new float[]{hue % 360, 0.62f, 0.34f});
    }

    /** The most vivid colour of the cover (greys, near-black and near-white are ignored), darkened. */
    private static int dominant(Bitmap src, String fallbackKey) {
        try {
            Bitmap b = Bitmap.createScaledBitmap(src, 32, 32, true);
            double[] w = new double[36], hs = new double[36], ss = new double[36];
            float[] hsv = new float[3];
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    int c = b.getPixel(x, y);
                    Color.colorToHSV(c, hsv);
                    float sat = hsv[1], val = hsv[2];
                    if (sat < 0.25f || val < 0.2f || val > 0.97f) continue;
                    int k = ((int) (hsv[0] / 10f)) % 36;
                    double wt = sat * val;
                    w[k] += wt;
                    hs[k] += hsv[0] * wt;
                    ss[k] += sat * wt;
                }
            }
            int best = 0;
            for (int k = 1; k < 36; k++) if (w[k] > w[best]) best = k;
            if (w[best] < 2) return 0xFF333333;
            float h = (float) (hs[best] / w[best]);
            float s = (float) Math.max(0.4, Math.min(0.65, ss[best] / w[best] * 0.8));
            return hsl(h, s, 0.24f);
        } catch (Exception e) {
            return hueColor(TvUi.hash(fallbackKey));
        }
    }

    private static int hsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = l - c / 2;
        float r, g, bl;
        if (h < 60) { r = c; g = x; bl = 0; }
        else if (h < 120) { r = x; g = c; bl = 0; }
        else if (h < 180) { r = 0; g = c; bl = x; }
        else if (h < 240) { r = 0; g = x; bl = c; }
        else if (h < 300) { r = x; g = 0; bl = c; }
        else { r = c; g = 0; bl = x; }
        return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((bl + m) * 255));
    }

    // ---- open / close ---------------------------------------------------------------------

    void openNp(final boolean toQueue) {
        if (!hasTrack || open) return;
        open = true;
        np.setVisibility(View.VISIBLE);
        np.setTranslationY(np.getHeight() > 0 ? np.getHeight() : px(950));
        np.animate().translationY(0).setDuration(420).setInterpolator(new DecelerateInterpolator(2f)).start();
        host.npOpened(true);
        lastQueueId = id;
        lastUpnext = -1;
        refreshQueue();
        syncSpin();
        bar.postDelayed(new Runnable() {
            @Override public void run() {
                View first = toQueue ? queueList.getChildAt(0) : null;
                if (first != null) first.requestFocus();
                else btnPlay.requestFocus();
            }
        }, 140);
    }

    void closeNp() {
        if (!open) return;
        open = false;
        setAmbient(false);
        host.npOpened(false);
        np.animate().translationY(np.getHeight()).setDuration(300)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                    @Override public void run() {
                        if (!open) np.setVisibility(View.GONE);
                    }
                }).start();
        syncSpin();
        info.requestFocus();
    }

    /** Ambient mode: only the cover and title stay; the rest fades out until a key is pressed. */
    void setAmbient(boolean on) {
        if (ambient == on) return;
        ambient = on;
        float a = on ? 0f : 1f;
        qpanel.animate().alpha(a).setDuration(1400).start();
        bar.animate().alpha(a).setDuration(1400).start();
        nleft.animate().translationX(on ? px(280) : 0f).scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f)
                .setDuration(on ? 3000 : 400).start();
    }

    // ---- queue ----------------------------------------------------------------------------

    private void refreshQueue() {
        JSONObject q = host.query("queue");
        if (q == null) return;
        queueItems.clear();
        JSONArray items = q.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONArray a = items.optJSONArray(i);
                if (a == null) continue;
                QItem it = new QItem();
                it.id = a.optInt(0);
                it.title = a.optString(1);
                it.artist = a.optString(2);
                it.dur = a.optInt(3);
                queueItems.add(it);
            }
        }
        qAdapter.notifyDataSetChanged();
    }

    private final class QueueAdapter extends BaseAdapter {
        @Override public int getCount() {
            return queueItems.size();
        }

        @Override public Object getItem(int i) {
            return queueItems.get(i);
        }

        @Override public long getItemId(int i) {
            return i;
        }

        @Override public View getView(final int pos, View convert, ViewGroup parent) {
            QRow r;
            if (convert == null) {
                r = new QRow();
                convert = r.build();
                convert.setTag(r);
            } else {
                r = (QRow) convert.getTag();
            }
            r.bind(queueItems.get(pos), pos);
            return convert;
        }
    }

    private final class QRow {
        FrameLayout wrap;
        LinearLayout inner;
        TvUi.ArtView art;
        TextView title, sub, dur;
        int index;
        QItem item;

        View build() {
            wrap = new FrameLayout(act);
            wrap.setPadding(0, 0, 0, px(6));
            wrap.setClipChildren(false);
            wrap.setClipToPadding(false);
            inner = new LinearLayout(act);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            inner.setPadding(px(14), 0, px(18), 0);
            art = new TvUi.ArtView(act, px(66), false, 8);
            inner.addView(art, new LinearLayout.LayoutParams(px(66), px(66)));
            LinearLayout t = new LinearLayout(act);
            t.setOrientation(LinearLayout.VERTICAL);
            title = TvUi.text(act, "", 28, TvUi.TEXT, true);
            sub = TvUi.text(act, "", 22, TvUi.MUTED, false);
            t.addView(title);
            t.addView(sub);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
            tp.leftMargin = px(22);
            inner.addView(t, tp);
            dur = TvUi.text(act, "", 24, TvUi.MUTED, false);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
            dp.leftMargin = px(20);
            inner.addView(dur, dp);
            wrap.addView(inner, new FrameLayout.LayoutParams(-1, px(88)));
            TvUi.focusable(inner, 1.01f, new TvUi.FocusChange() {
                @Override public void onFocus(boolean f) {
                    if (f) {
                        GradientDrawable d = TvUi.round(0x29FFFFFF, 14);
                        d.setStroke(px(4), Color.WHITE);
                        inner.setBackground(d);
                    } else {
                        inner.setBackground(null);
                    }
                }
            });
            inner.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    host.command("qmove", "from", String.valueOf(index), "to", "0");
                    host.command("next");
                }
            });
            inner.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    openQueueMenu(item, index);
                    return true;
                }
            });
            return wrap;
        }

        void bind(QItem it, int i) {
            item = it;
            index = i;
            title.setText(it.title);
            sub.setText(it.artist);
            dur.setText(it.dur > 0 ? TvUi.fmt(it.dur) : "");
            LibraryIndex idx = host.index();
            if (it.id >= 0 && it.id < idx.tracks.size()) {
                TvArt.load(act, art, idx.tracks.get(it.id), R.drawable.ic_note);
            }
        }
    }

    private void openQueueMenu(final QItem it, final int index) {
        LibraryIndex idx = host.index();
        Track t = it.id >= 0 && it.id < idx.tracks.size() ? idx.tracks.get(it.id) : null;
        host.showMenu(it.title, it.artist, t, new String[]{"Play now", "Remove from queue"}, new Runnable[]{
                new Runnable() {
                    @Override public void run() {
                        host.command("qmove", "from", String.valueOf(index), "to", "0");
                        host.command("next");
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        host.command("qremove", "i", String.valueOf(index));
                        refreshQueue();
                        host.toast("Removed from the queue");
                    }
                }});
    }

    // ---- custom views ---------------------------------------------------------------------

    /** The thin progress line on top of the bar; grows with a knob when the remote is on it. */
    private final class SeekView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float frac;

        SeekView(Activity a) {
            super(a);
            setFocusable(true);
            setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean f) {
                    invalidate();
                }
            });
            setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int code, KeyEvent e) {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        seekBy(-10000);
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        seekBy(10000);
                        return true;
                    }
                    return false;
                }
            });
        }

        void setFraction(float f) {
            frac = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            boolean f = isFocused();
            float w = getWidth(), h = getHeight();
            float th = px(f ? 14 : 6);
            float top = h - th;
            p.setColor(0x38FFFFFF);
            c.drawRect(0, top, w, h, p);
            p.setColor(TvUi.ACCENT);
            c.drawRect(0, top, w * frac, h, p);
            float r = px(f ? 20 : 9);
            float cx = Math.max(r, Math.min(w - r, w * frac)), cy = top + th / 2;
            if (f) {
                p.setColor(0x4DFFFFFF);
                c.drawCircle(cx, cy, r + px(8), p);
            }
            p.setColor(TvUi.ACCENT);
            c.drawCircle(cx, cy, r, p);
        }
    }

    /** A vinyl record with a coloured label, drawn in code. */
    private static final class RecordView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int base;

        RecordView(Activity a, int baseColor) {
            super(a);
            base = baseColor;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float cx = w / 2f, cy = getHeight() / 2f, R = w / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0B0B0B);
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, px(1.6f)));
            boolean alt = false;
            for (float r = R * 0.34f; r < R * 0.97f; r += px(5)) {
                p.setColor(alt ? 0xFF181818 : 0xFF0F0F0F);
                alt = !alt;
                c.drawCircle(cx, cy, r, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setShader(new SweepGradient(cx, cy,
                    new int[]{0x00FFFFFF, 0x18FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.12f, 0.24f, 0.55f, 0.7f, 0.85f}));
            c.drawCircle(cx, cy, R * 0.96f, p);
            p.setShader(null);
            float[] hsv = new float[3];
            Color.colorToHSV(base, hsv);
            int lo = Color.HSVToColor(new float[]{hsv[0], 0.70f, 0.85f});
            int hi = Color.HSVToColor(new float[]{(hsv[0] + 40) % 360, 0.65f, 0.55f});
            float lr = R * 0.41f;
            p.setShader(new LinearGradient(cx - lr, cy - lr, cx + lr, cy + lr, lo, hi, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, lr, p);
            p.setShader(null);
            p.setColor(0xFF0A0A0A);
            c.drawCircle(cx, cy, px(10), p);
        }
    }
}
