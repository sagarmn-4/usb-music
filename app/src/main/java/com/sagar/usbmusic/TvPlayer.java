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

        /** Opens the folder that holds the song that is playing. */
        void goToCurrentFolder();
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
    private TextView barTitle, barSub, timeView, tip;
    private ImageView btnPlay, npPlay, npQueueBtn;

    // now playing
    private FrameLayout artHolder, ctlPanel;
    private LinearLayout mainCol, qpanel;
    private TextView npTitle, npArtist, npKick, fromView, curTitle, curSub, curDur, npTime, npTotal, npTip;
    private SeekView npSeek;
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
    private boolean playing, hasTrack, open, ctlShown, queueOpen, autoOpen;
    private int pos, dur;
    private int lastUpnext = -1, lastQueueId = -2;
    /** How long the player controls stay on the full page after the song starts or a button is pressed. */
    private static final long CTL_MS = 15000;
    private final java.util.Set<View> tinted = new java.util.HashSet<>();
    private final Runnable hideCtlRun = new Runnable() {
        @Override public void run() {
            hideCtl();
        }
    };
    private final List<QItem> queueItems = new ArrayList<>();

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
        TvUi.pressToActivate(info, new Runnable() {
            @Override public void run() {
                openNp(false);
            }
        });
        row.addView(info, new LinearLayout.LayoutParams(px(620), px(96)));

        LinearLayout ctls = new LinearLayout(act);
        ctls.setOrientation(LinearLayout.HORIZONTAL);
        ctls.setGravity(Gravity.CENTER);
        ctls.setClipChildren(false);
        ImageView prev = iconBtn(R.drawable.ic_prev, 70, 18, false, new Runnable() {
            @Override public void run() {
                host.command("prev");
            }
        });
        btnPlay = iconBtn(R.drawable.ic_play, 84, 18, true, new Runnable() {
            @Override public void run() {
                host.command("toggle");
            }
        });
        ImageView next = iconBtn(R.drawable.ic_next, 70, 18, false, new Runnable() {
            @Override public void run() {
                host.command("next");
            }
        });
        addBtn(ctls, prev, 70, 11);
        addBtn(ctls, btnPlay, 84, 11);
        addBtn(ctls, next, 70, 11);
        row.addView(ctls, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        right.setClipChildren(false);
        timeView = TvUi.text(act, "0:00 / 0:00", 26, 0xFFCFCFD4, false);
        // Volume is left to the remote's own volume keys (they reach the TV and the receiver).
        ImageView queue = iconBtn(R.drawable.ic_queue, 68, 16, false, new Runnable() {
            @Override public void run() {
                openNp(true);
            }
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.rightMargin = px(16);
        right.addView(timeView, tl);
        final ImageView goFolder = iconBtn(R.drawable.ic_gofolder, 68, 16, false, new Runnable() {
            @Override public void run() {
                host.goToCurrentFolder();
            }
        });
        tipOn(goFolder, "Go to folder");
        tipOn(queue, "Up next");
        addBtn(right, goFolder, 68, 4);
        addBtn(right, queue, 68, 4);
        row.addView(right, new LinearLayout.LayoutParams(px(560), -2));

        tip = TvUi.text(act, "", 22, Color.BLACK, true);
        tip.setBackground(TvUi.round(Color.WHITE, 20));
        tip.setPadding(px(18), px(8), px(18), px(8));
        tip.setVisibility(View.GONE);
        bar.addView(tip, new FrameLayout.LayoutParams(-2, -2));

        seek = new SeekView(act);
        FrameLayout.LayoutParams skp = new FrameLayout.LayoutParams(-1, px(36));
        skp.topMargin = -px(16);
        bar.addView(seek, skp);

        // ---- now-playing page: the whole screen, one centred column; the controls hide by themselves
        np = new FrameLayout(act);
        np.setBackgroundColor(0xFF060606);
        np.setVisibility(View.GONE);
        np.setClipChildren(false);

        tintView = new View(act);
        tintDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        tintDrawable.setGradientRadius(px(1300));
        tintDrawable.setGradientCenter(0.5f, 0.34f);
        setTint(tintColor);
        tintView.setBackground(tintDrawable);
        np.addView(tintView, new FrameLayout.LayoutParams(-1, -1));

        mainCol = new LinearLayout(act);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        mainCol.setGravity(Gravity.CENTER);
        mainCol.setPadding(px(200), 0, px(200), px(310));
        mainCol.setClipChildren(false);
        artHolder = new FrameLayout(act);
        artHolder.setClipChildren(false);
        mainCol.addView(artHolder, new LinearLayout.LayoutParams(px(440), px(440)));
        npKick = TvUi.text(act, "", 24, 0xA6FFFFFF, true);
        npKick.setLetterSpacing(0.12f);
        npKick.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, -2);
        kp.topMargin = px(34);
        mainCol.addView(npKick, kp);
        npTitle = TvUi.text(act, "", 48, TvUi.TEXT, true);
        npTitle.setSingleLine(false);
        npTitle.setMaxLines(2);
        npTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ntp = new LinearLayout.LayoutParams(-1, -2);
        ntp.topMargin = px(12);
        mainCol.addView(npTitle, ntp);
        npArtist = TvUi.text(act, "", 32, 0xFFE0E0E4, false);
        npArtist.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nap = new LinearLayout.LayoutParams(-1, -2);
        nap.topMargin = px(10);
        mainCol.addView(npArtist, nap);
        np.addView(mainCol, new FrameLayout.LayoutParams(-1, -1));

        // Up next: slides in from the right, only while the controls are shown
        qpanel = new LinearLayout(act);
        qpanel.setOrientation(LinearLayout.VERTICAL);
        qpanel.setClipChildren(false);
        qpanel.setBackgroundColor(0xF20E0E10);
        qpanel.setPadding(px(60), px(80), px(60), px(230));
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
        fromView.setSingleLine(false);
        fromView.setMaxLines(2);
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
        qpanel.setVisibility(View.INVISIBLE);
        qpanel.setTranslationX(px(800));
        np.addView(qpanel, new FrameLayout.LayoutParams(px(800), -1, Gravity.END));

        // player controls along the bottom
        ctlPanel = new FrameLayout(act);
        ctlPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xE6000000}));
        ctlPanel.setPadding(px(130), px(150), px(130), px(56));
        ctlPanel.setClipChildren(false);
        ctlPanel.setClipToPadding(false);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        npSeek = new SeekView(act);
        col.addView(npSeek, new LinearLayout.LayoutParams(-1, px(44)));
        LinearLayout times = new LinearLayout(act);
        times.setOrientation(LinearLayout.HORIZONTAL);
        npTime = TvUi.text(act, "0:00", 26, 0xFFCFCFD4, false);
        npTotal = TvUi.text(act, "0:00", 26, 0xFFCFCFD4, false);
        npTotal.setGravity(Gravity.END);
        times.addView(npTime, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(npTotal, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(times, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout controls = new FrameLayout(act);
        controls.setClipChildren(false);
        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER);
        mid.setClipChildren(false);
        ImageView nPrev = iconBtn(R.drawable.ic_prev, 84, 22, false, new Runnable() {
            @Override public void run() {
                host.command("prev");
            }
        });
        npPlay = iconBtn(R.drawable.ic_play, 104, 28, true, new Runnable() {
            @Override public void run() {
                host.command("toggle");
            }
        });
        ImageView nNext = iconBtn(R.drawable.ic_next, 84, 22, false, new Runnable() {
            @Override public void run() {
                host.command("next");
            }
        });
        tint(nPrev, false);
        tint(npPlay, true);
        tint(nNext, false);
        addBtn(mid, nPrev, 84, 15);
        addBtn(mid, npPlay, 104, 15);
        addBtn(mid, nNext, 84, 15);
        controls.addView(mid, new FrameLayout.LayoutParams(-1, px(112), Gravity.CENTER));

        LinearLayout side = new LinearLayout(act);
        side.setOrientation(LinearLayout.HORIZONTAL);
        side.setGravity(Gravity.CENTER_VERTICAL);
        side.setClipChildren(false);
        ImageView nGo = iconBtn(R.drawable.ic_gofolder, 84, 22, false, new Runnable() {
            @Override public void run() {
                host.goToCurrentFolder();
            }
        });
        npQueueBtn = iconBtn(R.drawable.ic_queue, 84, 22, false, new Runnable() {
            @Override public void run() {
                if (queueOpen) hideQueuePanel(true);
                else showQueuePanel();
            }
        });
        tint(nGo, false);
        tint(npQueueBtn, false);
        addBtn(side, nGo, 84, 9);
        addBtn(side, npQueueBtn, 84, 9);
        controls.addView(side, new FrameLayout.LayoutParams(-2, px(112), Gravity.END));
        col.addView(controls, new LinearLayout.LayoutParams(-1, px(112)));
        ctlPanel.addView(col, new FrameLayout.LayoutParams(-1, -2));
        np.addView(ctlPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        npTip = TvUi.text(act, "", 22, Color.BLACK, true);
        npTip.setBackground(TvUi.round(Color.WHITE, 20));
        npTip.setPadding(px(18), px(8), px(18), px(8));
        npTip.setVisibility(View.GONE);
        np.addView(npTip, new FrameLayout.LayoutParams(-2, -2));
        tipNp(nGo, "Go to folder");
        tipNp(npQueueBtn, "Up next");
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

    boolean hasTrack() {
        return hasTrack;
    }

    boolean isSeek(View v) {
        return v == seek || v == npSeek;
    }

    void focusPlay() {
        (open ? npPlay : btnPlay).requestFocus();
    }

    /** A TV action (play a folder, pick a song) asked for playback: open the full page as soon as it starts. */
    void expectPlayback() {
        autoOpen = true;
    }

    boolean inLastQueueRow(View f) {
        int p = queueList.getPositionForView(f);
        return p >= 0 && p == queueAdapterCount() - 1;
    }

    private int queueAdapterCount() {
        return queueItems.size();
    }

    /** Puts the focus on the first row of Up next; false when the queue is empty. */
    boolean focusQueue() {
        View first = queueList.getChildAt(0);
        if (first == null) return false;
        return first.requestFocus();
    }

    // ---- buttons --------------------------------------------------------------------------

    private ImageView iconBtn(int icon, int size, int pad, final boolean big, final Runnable action) {
        final ImageView b = new ImageView(act);
        b.setPadding(px(pad), px(pad), px(pad), px(pad));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setImageResource(icon);
        b.setTag(Boolean.FALSE);
        styleBtn(b, big);
        TvUi.focusable(b, big ? 1.1f : 1.14f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                styleBtn(b, big);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
            }
        });
        TvUi.pressToActivate(b, action);
        return b;
    }

    /** Shows a small label above the button while it has the focus. */
    private void tipOn(final View btn, final String label) {
        final View.OnFocusChangeListener old = btn.getOnFocusChangeListener();
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                if (old != null) old.onFocusChange(v, f);
                if (!f) {
                    tip.setVisibility(View.GONE);
                    return;
                }
                tip.setText(label);
                tip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int[] a = new int[2], b = new int[2];
                v.getLocationInWindow(a);
                bar.getLocationInWindow(b);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tip.getLayoutParams();
                int w = tip.getMeasuredWidth();
                lp.leftMargin = Math.max(0, Math.min(a[0] - b[0] + v.getWidth() / 2 - w / 2,
                        bar.getWidth() - w - px(20)));
                lp.topMargin = -px(66);
                tip.setLayoutParams(lp);
                tip.setVisibility(View.VISIBLE);
            }
        });
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
        d.setColor(f ? Color.WHITE : tinted.contains(b) ? (big ? 0x33FFFFFF : 0x1AFFFFFF)
                : big ? 0x24FFFFFF : Color.TRANSPARENT);
        b.setBackground(d);
        b.setColorFilter(f ? (on ? 0xFFD4153A : Color.BLACK) : on ? TvUi.ACCENT : Color.WHITE,
                PorterDuff.Mode.SRC_IN);
    }

    /** Buttons on the full page have a faint round background even without the focus. */
    private void tint(ImageView b, boolean big) {
        tinted.add(b);
        styleBtn(b, big);
    }

    /** Label above a full-page button while it has the focus. */
    private void tipNp(final View btn, final String label) {
        final View.OnFocusChangeListener old = btn.getOnFocusChangeListener();
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                if (old != null) old.onFocusChange(v, f);
                if (!f) {
                    npTip.setVisibility(View.GONE);
                    return;
                }
                npTip.setText(label);
                npTip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int[] a = new int[2], b = new int[2];
                v.getLocationInWindow(a);
                np.getLocationInWindow(b);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) npTip.getLayoutParams();
                int w = npTip.getMeasuredWidth();
                lp.leftMargin = Math.max(0, Math.min(a[0] - b[0] + v.getWidth() / 2 - w / 2,
                        np.getWidth() - w - px(20)));
                lp.topMargin = a[1] - b[1] - px(64);
                npTip.setLayoutParams(lp);
                npTip.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Left/Right on the seek line jump 10 seconds; the media keys use the same. */
    void seekBy(int deltaMs) {
        if (!hasTrack || dur <= 0) return;
        int to = Math.max(0, Math.min(dur, pos + deltaMs));
        pos = to;
        seek.setFraction(to / (float) dur);
        npSeek.setFraction(to / (float) dur);
        timeView.setText(TvUi.fmt(to) + " / " + TvUi.fmt(dur));
        npTime.setText(TvUi.fmt(to));
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
        npPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        seek.setFraction(dur > 0 ? pos / (float) dur : 0f);
        npSeek.setFraction(dur > 0 ? pos / (float) dur : 0f);
        timeView.setText(TvUi.fmt(pos) + " / " + TvUi.fmt(dur));
        npTime.setText(TvUi.fmt(pos));
        npTotal.setText(TvUi.fmt(dur));
        if (autoOpen) {
            autoOpen = false;
            openNp(false);
        }
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
        npKick.setText(lastSegment(t.folder).toUpperCase());
        fromView.setText("Playing from\n" + lastSegment(t.folder));
        curTitle.setText(t.title);
        curSub.setText(who);
        TvArt.load(act, curArt, t, R.drawable.ic_note);
        final int forId = id;
        showArtPlaceholder();
        TvArt.lookup(act, t, px(440), new TvArt.Result() {
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
            TvUi.ArtView a = new TvUi.ArtView(act, px(440), false, 14);
            a.showBitmap(b);
            artHolder.addView(a, new FrameLayout.LayoutParams(px(440), px(440)));
            animateTint(dominant(b, TvArt.key(t)));
        } else {
            int color = hueColor(TvUi.hash(TvArt.key(t)));
            record = new RecordView(act, color);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(px(420), px(420), Gravity.CENTER);
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
        np.setAlpha(0f);
        np.setScaleX(1.03f);
        np.setScaleY(1.03f);
        np.setVisibility(View.VISIBLE);
        np.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        host.npOpened(true);
        lastQueueId = id;
        lastUpnext = -1;
        refreshQueue();
        syncSpin();
        showCtl();
        np.postDelayed(new Runnable() {
            @Override public void run() {
                if (toQueue) showQueuePanel();
                else npPlay.requestFocus();
            }
        }, 140);
    }

    void closeNp() {
        if (!open) return;
        open = false;
        np.removeCallbacks(hideCtlRun);
        queueOpen = false;
        qpanel.setVisibility(View.INVISIBLE);
        qpanel.setTranslationX(px(800));
        host.npOpened(false);
        np.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
            @Override public void run() {
                if (!open) np.setVisibility(View.GONE);
            }
        }).start();
        syncSpin();
        info.requestFocus();
    }

    /** Back on the full page: closes Up next first, then the page. */
    boolean onBack() {
        if (!open) return false;
        if (queueOpen) hideQueuePanel(true);
        else closeNp();
        return true;
    }

    /** Controls visible: they fade in and the song moves up; they dim to 30% after CTL_MS. */
    private void showCtl() {
        ctlShown = true;
        np.removeCallbacks(hideCtlRun);
        np.postDelayed(hideCtlRun, CTL_MS);
        ctlPanel.animate().alpha(1f).translationY(0).setDuration(350).start();
        mainCol.animate().translationY(0).setDuration(700).start();
    }

    private void hideCtl() {
        if (!open) return;
        ctlShown = false;
        if (queueOpen) {
            hideQueuePanel(false);
            npPlay.requestFocus();
        }
        ctlPanel.animate().alpha(0.3f).translationY(0).setDuration(700).start(); // idle: stay visible, dimmed
        mainCol.animate().translationY(0).setDuration(700).start();
    }

    /**
     * Called for every key press. On the full page a D-pad or OK press with the controls hidden only
     * brings the controls back (returns true: the key is used up); other keys also keep them visible.
     */
    boolean onUserKey(int keyCode) {
        if (!open) return false;
        boolean wasHidden = !ctlShown;
        showCtl();
        if (!wasHidden) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    private void showQueuePanel() {
        queueOpen = true;
        refreshQueue();
        qpanel.setVisibility(View.VISIBLE);
        qpanel.animate().translationX(0).setDuration(400).setInterpolator(new DecelerateInterpolator(2f)).start();
        np.postDelayed(new Runnable() {
            @Override public void run() {
                View first = queueList.getChildAt(0);
                if (first != null) first.requestFocus();
                else npQueueBtn.requestFocus();
            }
        }, 220);
    }

    private void hideQueuePanel(boolean refocus) {
        if (!queueOpen) return;
        queueOpen = false;
        qpanel.animate().translationX(px(800)).setDuration(300).withEndAction(new Runnable() {
            @Override public void run() {
                if (!queueOpen) qpanel.setVisibility(View.INVISIBLE);
            }
        }).start();
        if (refocus) npQueueBtn.requestFocus();
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
            final GradientDrawable rowBg = TvUi.round(0, 14);
            inner.setBackground(rowBg);
            TvUi.focusable(inner, 1.01f, new TvUi.FocusChange() {
                @Override public void onFocus(boolean f) {
                    // Same drawable, new colour: swapping drawables here would loop (see TvBrowse.styleRow).
                    rowBg.setColor(f ? 0x29FFFFFF : 0);
                    rowBg.setStroke(f ? px(4) : 0, Color.WHITE);
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
