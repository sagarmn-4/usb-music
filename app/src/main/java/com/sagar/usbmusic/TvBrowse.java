package com.sagar.usbmusic;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.sagar.usbmusic.TvUi.px;

/**
 * The TV screen: header with the tabs, then Folders / Albums / Songs / Artists, and a folder,
 * album or artist screen on top. It talks to the same MusicService as the phone page, so both
 * always show the same queue and state. Step 1 of the TV player: browsing and starting songs.
 */
final class TvBrowse {
    private static final int COLS = 5;
    private static final String[] TABS = {"Folders", "Albums", "Songs", "Artists"};

    private final Activity act;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final FrameLayout content;
    private final TextView message;
    private final TextView[] tabViews = new TextView[TABS.length];
    private final TextView urlView;
    private final ImageView qrView;
    private String qrFor = "";

    private LibraryIndex idx = new LibraryIndex(new ArrayList<Track>());
    private int libVer = -1;
    private int tab = 0;
    private final List<Detail> stack = new ArrayList<>();
    private int rootFocus = 0;

    private int curId = -1;
    private boolean playing;
    private int artVer = -1;
    private ListView list;
    private BaseAdapter adapter;
    private Runnable pendingTab;
    /** False while a tab is being previewed, so the new list does not take the focus off the tabs. */
    private boolean grab = true;

    /** One folder / album / artist screen. */
    private static final class Detail {
        String title, kind, sub1, scope, treePath;
        List<Integer> ids = new ArrayList<>(), all = new ArrayList<>();
        List<LibraryIndex.Node> children = new ArrayList<>();
        int focus = 0;
    }

    private static final class Card {
        String title, sub;
        Track art;
        boolean folder;
        Runnable open;
    }

    private static final class RowItem {
        int id = -1;
        LibraryIndex.Node folder;
        String title, sub, dur;
        int number;
        Track art;
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 1000);
        }
    };

    TvBrowse(Activity activity) {
        act = activity;
        TvUi.init(activity);
        root = new FrameLayout(activity);
        root.setBackgroundColor(TvUi.BG);

        // ---- header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(px(96), px(30), px(96), 0);
        header.setClipChildren(false);
        root.addView(header, new FrameLayout.LayoutParams(-1, px(170)));

        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.drawable.ic_launcher);
        header.addView(logo, new LinearLayout.LayoutParams(px(56), px(56)));
        TextView brand = TvUi.text(activity, "USB Music", 34, TvUi.TEXT, true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.leftMargin = px(16);
        bp.rightMargin = px(40);
        header.addView(brand, bp);

        for (int i = 0; i < TABS.length; i++) {
            final int n = i;
            final TextView t = TvUi.text(activity, TABS[i], 30, 0xFFCFCFD4, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(34), 0, px(34), 0);
            TvUi.focusable(t, 1.06f, new TvUi.FocusChange() {
                @Override public void onFocus(boolean f) {
                    styleTabs();
                    if (pendingTab != null) handler.removeCallbacks(pendingTab);
                    if (f && n != tab) {
                        // Resting on a tab for a moment opens it, so moving across the tabs previews each one.
                        pendingTab = new Runnable() {
                            @Override public void run() {
                                selectTab(n, false);
                            }
                        };
                        handler.postDelayed(pendingTab, 280);
                    }
                }
            });
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectTab(n, true);
                }
            });
            tabViews[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(68));
            lp.rightMargin = px(8);
            header.addView(t, lp);
        }
        header.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView rescan = TvUi.text(activity, "Rescan", 26, 0xFFCFCFD4, true);
        rescan.setGravity(Gravity.CENTER);
        rescan.setPadding(px(30), 0, px(30), 0);
        pillStyle(rescan, false, false);
        TvUi.focusable(rescan, 1.06f, new PillFocus(rescan, false));
        rescan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                act.startService(new Intent(act, MusicService.class).setAction(MusicService.ACTION_RESCAN));
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, px(60));
        rp.rightMargin = px(28);
        header.addView(rescan, rp);

        LinearLayout qrBox = new LinearLayout(activity);
        qrBox.setOrientation(LinearLayout.HORIZONTAL);
        qrBox.setGravity(Gravity.CENTER_VERTICAL);
        qrBox.setPadding(px(10), px(10), px(22), px(10));
        qrBox.setBackground(TvUi.round(0x12FFFFFF, 20));
        FrameLayout qrCard = new FrameLayout(activity);
        qrCard.setBackground(TvUi.round(Color.WHITE, 10));
        qrCard.setPadding(px(6), px(6), px(6), px(6));
        qrView = new ImageView(activity);
        qrCard.addView(qrView, new FrameLayout.LayoutParams(px(76), px(76)));
        qrBox.addView(qrCard, new LinearLayout.LayoutParams(px(88), px(88)));
        LinearLayout qrText = new LinearLayout(activity);
        qrText.setOrientation(LinearLayout.VERTICAL);
        qrText.setPadding(px(16), 0, 0, 0);
        qrText.addView(TvUi.text(activity, "Open on phone", 22, TvUi.TEXT, true));
        urlView = TvUi.text(activity, "starting…", 20, TvUi.MUTED, false);
        qrText.addView(urlView);
        qrBox.addView(qrText);
        header.addView(qrBox);

        // ---- content
        content = new FrameLayout(activity);
        content.setClipChildren(false);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
        cp.topMargin = px(170);
        cp.bottomMargin = px(30);
        root.addView(content, cp);

        message = TvUi.text(activity, "", 30, TvUi.MUTED, false);
        message.setSingleLine(false);
        message.setGravity(Gravity.CENTER);
        message.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        mp.leftMargin = mp.rightMargin = px(300);
        content.addView(message, mp);

        styleTabs();
    }

    View view() {
        return root;
    }

    void start() {
        handler.post(refresh);
    }

    void stop() {
        handler.removeCallbacks(refresh);
    }

    /** Back button: goes up one level. Returns false when already at the top. */
    boolean onBack() {
        if (!stack.isEmpty()) {
            stack.remove(stack.size() - 1);
            showCurrent(true);
            return true;
        }
        if (tab != 0) {
            selectTab(0, true);
            return true;
        }
        return false;
    }

    // ---- media keys (also used by the activity) -------------------------------------------

    void command(String cmd, String... kv) {
        MusicService s = MusicService.get();
        if (s != null) {
            s.command(cmd, kv);
            tick();
        }
    }

    // ---- state ----------------------------------------------------------------------------

    private void tick() {
        MusicService s = MusicService.get();
        if (s == null) return;
        String address = "http://" + WebServer.localIp() + ":" + s.port();
        urlView.setText(address.replace("http://", ""));
        if (!address.equals(qrFor) && s.port() > 0) {
            qrFor = address;
            qrView.setImageBitmap(makeQr(address, px(76)));
        }
        if (s.libraryVersion() != libVer) {
            libVer = s.libraryVersion();
            idx = new LibraryIndex(s.librarySnapshot());
            TvArt.reset();
            stack.clear();
            rootFocus = 0;
            showCurrent(false);
        } else if (idx.tracks.isEmpty()) {
            updateMessage(s);
        }
        try {
            JSONObject st = s.state();
            int id = st.optInt("id", -1);
            boolean pl = st.optBoolean("playing");
            int art = st.optInt("art", 0);
            boolean changed = id != curId || pl != playing;
            curId = id;
            playing = pl;
            if (art != artVer) {
                artVer = art;
                TvArt.reset();
                changed = true;
            }
            if (changed && adapter != null) adapter.notifyDataSetChanged();
        } catch (Exception ignored) {
        }
    }

    private void updateMessage(MusicService s) {
        message.setText(s.isScanning() ? "Scanning the USB drive…"
                : "No music found.\nPlug the USB drive into the TV, then choose Rescan.");
    }

    // ---- tabs -----------------------------------------------------------------------------

    private void selectTab(int n, boolean takeFocus) {
        if (pendingTab != null) handler.removeCallbacks(pendingTab);
        boolean same = n == tab && stack.isEmpty();
        tab = n;
        stack.clear();
        rootFocus = 0;
        styleTabs();
        grab = takeFocus;
        if (!same) showCurrent(false);
        grab = true;
        if (takeFocus) focusFirst();
    }

    private void styleTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            TextView t = tabViews[i];
            boolean f = t.hasFocus();
            boolean on = i == tab;
            t.setBackground(f ? TvUi.round(Color.WHITE, 34) : on ? TvUi.round(0x1FFFFFFF, 34) : null);
            t.setTextColor(f ? TvUi.DARK_TEXT : on ? TvUi.TEXT : 0xFFCFCFD4);
        }
    }

    private void pillStyle(TextView t, boolean primary, boolean focused) {
        if (focused) {
            t.setBackground(TvUi.round(TvUi.ACCENT, 38));
            t.setTextColor(Color.WHITE);
        } else if (primary) {
            t.setBackground(TvUi.round(Color.WHITE, 38));
            t.setTextColor(Color.BLACK);
        } else {
            t.setBackground(TvUi.round(0x1FFFFFFF, 38));
            t.setTextColor(0xFFE6E6EA);
        }
    }

    private final class PillFocus implements TvUi.FocusChange {
        private final TextView t;
        private final boolean primary;

        PillFocus(TextView t, boolean primary) {
            this.t = t;
            this.primary = primary;
        }

        @Override public void onFocus(boolean f) {
            pillStyle(t, primary, f);
        }
    }

    private TextView pill(String label, boolean primary, final Runnable action) {
        TextView t = TvUi.text(act, label, 28, Color.BLACK, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(44), 0, px(44), 0);
        pillStyle(t, primary, false);
        TvUi.focusable(t, 1.07f, new PillFocus(t, primary));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
            }
        });
        return t;
    }

    // ---- screens --------------------------------------------------------------------------

    private void showCurrent(boolean restoreFocus) {
        content.removeAllViews();
        content.addView(message);
        list = null;
        adapter = null;
        MusicService s = MusicService.get();
        if (idx.tracks.isEmpty()) {
            message.setVisibility(View.VISIBLE);
            if (s != null) updateMessage(s);
            return;
        }
        message.setVisibility(View.GONE);
        if (!stack.isEmpty()) {
            showDetail(stack.get(stack.size() - 1), restoreFocus);
            return;
        }
        int focus = restoreFocus ? rootFocus : 0;
        switch (tab) {
            case 0:
                showGrid(foldersCards(), "Folders", false, focus);
                break;
            case 1:
                showGrid(albumCards(), "Albums", false, focus);
                break;
            case 2:
                showSongs(focus);
                break;
            default:
                showGrid(artistCards(), "Artists", true, focus);
                break;
        }
    }

    private List<Card> foldersCards() {
        List<Card> out = new ArrayList<>();
        for (final LibraryIndex.Node n : idx.tree.sortedKids()) {
            Card c = new Card();
            c.title = n.name;
            c.folder = !n.children.isEmpty();
            c.art = c.folder || n.all.isEmpty() ? null : idx.tracks.get(n.all.get(0));
            c.sub = folderSub(n);
            final int pos = out.size();
            c.open = new Runnable() {
                @Override public void run() {
                    rootFocus = pos;
                    open(folderDetail(n));
                }
            };
            out.add(c);
        }
        return out;
    }

    private String folderSub(LibraryIndex.Node n) {
        StringBuilder sb = new StringBuilder();
        if (!n.children.isEmpty()) sb.append(TvUi.plural(n.children.size(), "folder")).append(" • ");
        sb.append(TvUi.plural(n.all.size(), "song"));
        return sb.toString();
    }

    private List<Card> albumCards() {
        List<Card> out = new ArrayList<>();
        for (final LibraryIndex.Group g : idx.albums) {
            Card c = new Card();
            c.title = g.name;
            c.sub = g.sub.isEmpty() ? TvUi.plural(g.ids.size(), "song") : g.sub;
            c.art = idx.tracks.get(g.ids.get(0));
            final int pos = out.size();
            c.open = new Runnable() {
                @Override public void run() {
                    rootFocus = pos;
                    Detail d = new Detail();
                    d.title = g.name;
                    d.kind = "Album";
                    d.sub1 = g.sub;
                    d.scope = "album";
                    d.ids.addAll(g.ids);
                    d.all.addAll(g.ids);
                    open(d);
                }
            };
            out.add(c);
        }
        return out;
    }

    private List<Card> artistCards() {
        List<Card> out = new ArrayList<>();
        for (final LibraryIndex.Group g : idx.artists) {
            Card c = new Card();
            c.title = g.name;
            c.sub = TvUi.plural(g.ids.size(), "song");
            c.art = idx.tracks.get(g.ids.get(0));
            final int pos = out.size();
            c.open = new Runnable() {
                @Override public void run() {
                    rootFocus = pos;
                    Detail d = new Detail();
                    d.title = g.name;
                    d.kind = "Artist";
                    d.scope = "artist";
                    d.ids.addAll(g.ids);
                    d.all.addAll(g.ids);
                    open(d);
                }
            };
            out.add(c);
        }
        return out;
    }

    private Detail folderDetail(LibraryIndex.Node n) {
        Detail d = new Detail();
        d.title = n.name;
        d.kind = "Folder";
        d.sub1 = n.children.isEmpty() ? "" : TvUi.plural(n.children.size(), "folder");
        d.scope = "tree";
        d.treePath = n.full;
        d.ids.addAll(n.ids);
        d.all.addAll(n.all);
        d.children = n.sortedKids();
        return d;
    }

    private void open(Detail d) {
        stack.add(d);
        showCurrent(false);
        focusFirstRow();
    }

    // ---- grid -----------------------------------------------------------------------------

    private TextView heading(String s) {
        TextView h = TvUi.text(act, s, 46, TvUi.TEXT, true);
        h.setPadding(px(110), px(8), 0, px(22));
        return h;
    }

    private ListView newList() {
        ListView lv = new ListView(act);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lv.setVerticalScrollBarEnabled(false);
        lv.setItemsCanFocus(true);
        lv.setClipChildren(false);
        lv.setClipToPadding(false);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setPadding(0, px(14), 0, px(60));
        return lv;
    }

    private void showGrid(List<Card> cards, String title, boolean circle, int focus) {
        ListView lv = newList();
        lv.addHeaderView(heading(title), null, false);
        final CardAdapter a = new CardAdapter(cards, circle);
        lv.setAdapter(a);
        content.addView(lv, new FrameLayout.LayoutParams(-1, -1));
        list = lv;
        adapter = a;
        focusItem(focus, COLS);
    }

    private final class CardAdapter extends BaseAdapter {
        private final List<Card> cards;
        private final boolean circle;

        CardAdapter(List<Card> cards, boolean circle) {
            this.cards = cards;
            this.circle = circle;
        }

        @Override public int getCount() {
            return (cards.size() + COLS - 1) / COLS;
        }

        @Override public Object getItem(int i) {
            return null;
        }

        @Override public long getItemId(int i) {
            return i;
        }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setPadding(px(110), px(10), 0, px(36));
                for (int c = 0; c < COLS; c++) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(300), -2);
                    lp.rightMargin = px(52);
                    row.addView(newCard(circle), lp);
                }
            }
            for (int c = 0; c < COLS; c++) {
                int i = pos * COLS + c;
                View v = row.getChildAt(c);
                if (i < cards.size()) {
                    v.setVisibility(View.VISIBLE);
                    bindCard(v, cards.get(i), circle);
                } else {
                    v.setVisibility(View.INVISIBLE);
                    v.setFocusable(false);
                }
            }
            return row;
        }
    }

    private static final class CardHolder {
        TvUi.ArtView art;
        TextView title, sub;
        Runnable open;
    }

    private View newCard(boolean circle) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        final CardHolder h = new CardHolder();
        h.art = new TvUi.ArtView(act, px(300), circle, 18);
        c.addView(h.art, new LinearLayout.LayoutParams(px(300), px(300)));
        h.title = TvUi.text(act, "", 28, TvUi.TEXT, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = px(16);
        c.addView(h.title, tp);
        h.sub = TvUi.text(act, "", 23, TvUi.MUTED, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = px(5);
        c.addView(h.sub, sp);
        if (circle) {
            h.title.setGravity(Gravity.CENTER);
            h.sub.setGravity(Gravity.CENTER);
        }
        c.setTag(h);
        TvUi.focusable(c, 1.07f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                h.art.setRing(f);
            }
        });
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (h.open != null) h.open.run();
            }
        });
        return c;
    }

    private void bindCard(View v, Card card, boolean circle) {
        CardHolder h = (CardHolder) v.getTag();
        v.setFocusable(true);
        h.title.setText(card.title);
        h.sub.setText(card.sub);
        h.open = card.open;
        if (card.folder || card.art == null) {
            h.art.setTag(null);
            h.art.showIcon(0, R.drawable.ic_folder, true);
        } else {
            TvArt.load(act, h.art, card.art, circle ? R.drawable.ic_person : R.drawable.ic_note);
        }
    }

    // ---- song rows ------------------------------------------------------------------------

    private String subOf(Track t) {
        String who = t.artist.isEmpty() ? t.composer : t.artist;
        StringBuilder sb = new StringBuilder(who);
        if (!t.album.isEmpty()) sb.append(sb.length() > 0 ? " • " : "").append(t.album);
        if (sb.length() > 0) return sb.toString();
        String f = t.folder;
        int i = f.lastIndexOf('/');
        return i >= 0 ? f.substring(i + 1) : f;
    }

    private RowItem songItem(int id, int number, boolean albumView) {
        Track t = idx.tracks.get(id);
        RowItem r = new RowItem();
        r.id = id;
        r.title = t.title;
        r.sub = albumView ? t.artist : subOf(t);
        r.dur = t.durationMs > 0 ? TvUi.fmt(t.durationMs) : "";
        r.number = number;
        r.art = t;
        return r;
    }

    private void showSongs(int focus) {
        ListView lv = newList();
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(heading("Songs"));
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(px(110), 0, 0, px(20));
        final List<Integer> all = new ArrayList<>();
        for (int i = 0; i < idx.tracks.size(); i++) all.add(i);
        btns.addView(pill("Play all", true, new Runnable() {
            @Override public void run() {
                playAll(all, "all", "");
            }
        }), new LinearLayout.LayoutParams(-2, px(76)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, px(76));
        sp.leftMargin = px(16);
        btns.addView(pill("Shuffle", false, new Runnable() {
            @Override public void run() {
                shuffleAll(all, "all", "");
            }
        }), sp);
        head.addView(btns);
        lv.addHeaderView(head, null, false);
        List<RowItem> items = new ArrayList<>();
        for (int id : all) items.add(songItem(id, 0, false));
        final RowAdapter a = new RowAdapter(items, "all", "", px(110));
        lv.setAdapter(a);
        content.addView(lv, new FrameLayout.LayoutParams(-1, -1));
        list = lv;
        adapter = a;
        focusItem(focus, 1);
    }

    private void showDetail(Detail d, boolean restoreFocus) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setClipChildren(false);
        box.setPadding(px(96), px(10), px(60), 0);

        LinearLayout side = new LinearLayout(act);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setClipChildren(false);
        TvUi.ArtView hero = new TvUi.ArtView(act, px(460), false, 24);
        if (!d.children.isEmpty() || d.all.isEmpty()) hero.showIcon(0, R.drawable.ic_folder, true);
        else TvArt.load(act, hero, idx.tracks.get(d.all.get(0)), R.drawable.ic_note);
        side.addView(hero, new LinearLayout.LayoutParams(px(460), px(460)));
        TextView title = TvUi.text(act, d.title, 46, TvUi.TEXT, true);
        title.setSingleLine(false);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = px(26);
        side.addView(title, tp);
        String l1 = d.kind + (d.sub1 == null || d.sub1.isEmpty() ? "" : " • " + d.sub1);
        side.addView(TvUi.text(act, l1, 25, TvUi.MUTED, false));
        long ms = 0;
        for (int id : d.all) ms += idx.tracks.get(id).durationMs;
        String l2 = TvUi.plural(d.all.size(), "song") + (ms > 0 ? " • " + TvUi.span(ms) : "");
        LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(-1, -2);
        l2p.topMargin = px(4);
        side.addView(TvUi.text(act, l2, 25, TvUi.MUTED, false), l2p);
        if (!d.all.isEmpty()) {
            LinearLayout btns = new LinearLayout(act);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setClipChildren(false);
            final Detail dd = d;
            btns.addView(pill("Play", true, new Runnable() {
                @Override public void run() {
                    playAll(dd.all, dd.scope, dd.treePath == null ? "" : dd.treePath);
                }
            }), new LinearLayout.LayoutParams(-2, px(76)));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, px(76));
            sp.leftMargin = px(16);
            btns.addView(pill("Shuffle", false, new Runnable() {
                @Override public void run() {
                    shuffleAll(dd.all, dd.scope, dd.treePath == null ? "" : dd.treePath);
                }
            }), sp);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.topMargin = px(28);
            side.addView(btns, bp);
        }
        box.addView(side, new LinearLayout.LayoutParams(px(460), -1));

        ListView lv = newList();
        List<RowItem> items = new ArrayList<>();
        for (LibraryIndex.Node n : d.children) {
            RowItem r = new RowItem();
            r.folder = n;
            r.title = n.name;
            r.sub = folderSub(n);
            r.art = n.children.isEmpty() && !n.all.isEmpty() ? idx.tracks.get(n.all.get(0)) : null;
            items.add(r);
        }
        boolean albumView = "album".equals(d.scope);
        for (int i = 0; i < d.ids.size(); i++) items.add(songItem(d.ids.get(i), i + 1, albumView));
        final RowAdapter a = new RowAdapter(items, d.scope, d.treePath == null ? "" : d.treePath, 0);
        a.detail = d;
        lv.setAdapter(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = px(60);
        box.addView(lv, lp);
        content.addView(box, new FrameLayout.LayoutParams(-1, -1));
        list = lv;
        adapter = a;
        if (restoreFocus) focusItem(d.focus, 1);
    }

    private void playAll(List<Integer> ids, String scope, String path) {
        if (ids.isEmpty()) return;
        command("shuffle", "on", "0");
        command("play", "id", String.valueOf(ids.get(0)), "scope", scope, "path", path);
    }

    private void shuffleAll(List<Integer> ids, String scope, String path) {
        if (ids.isEmpty()) return;
        command("shuffle", "on", "1");
        int pick = ids.get(new Random().nextInt(ids.size()));
        command("play", "id", String.valueOf(pick), "scope", scope, "path", path);
    }

    private static final class RowHolder {
        TextView number, title, sub, dur;
        TvUi.ArtView art;
        TvUi.EqView eq;
        LinearLayout inner;
        boolean focused;
        boolean current;
        RowItem item;
        int position;
        String scope, path;
        Detail detail;
    }

    private final class RowAdapter extends BaseAdapter {
        private final List<RowItem> items;
        private final String scope, path;
        private final int leftPad;
        Detail detail;

        RowAdapter(List<RowItem> items, String scope, String path, int leftPad) {
            this.items = items;
            this.scope = scope;
            this.path = path;
            this.leftPad = leftPad;
        }

        @Override public int getCount() {
            return items.size();
        }

        @Override public Object getItem(int i) {
            return items.get(i);
        }

        @Override public long getItemId(int i) {
            return i;
        }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            if (convert == null) convert = newRow(this);
            RowHolder h = (RowHolder) convert.getTag();
            h.position = pos;
            bindRow(h, items.get(pos), scope, path, detail);
            return convert;
        }
    }

    private View newRow(RowAdapter a) {
        FrameLayout wrap = new FrameLayout(act);
        wrap.setPadding(a.leftPad, 0, a.leftPad > 0 ? px(96) : 0, px(6));
        wrap.setClipChildren(false);
        final RowHolder h = new RowHolder();
        h.inner = new LinearLayout(act);
        h.inner.setOrientation(LinearLayout.HORIZONTAL);
        h.inner.setGravity(Gravity.CENTER_VERTICAL);
        h.inner.setPadding(px(26), 0, px(30), 0);
        h.number = TvUi.text(act, "", 28, TvUi.MUTED, false);
        h.number.setGravity(Gravity.END);
        h.inner.addView(h.number, new LinearLayout.LayoutParams(px(56), -2));
        h.art = new TvUi.ArtView(act, px(76), false, 12);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(76), px(76));
        ap.leftMargin = px(4);
        h.inner.addView(h.art, ap);
        LinearLayout meta = new LinearLayout(act);
        meta.setOrientation(LinearLayout.VERTICAL);
        h.title = TvUi.text(act, "", 32, TvUi.TEXT, true);
        h.sub = TvUi.text(act, "", 23, TvUi.MUTED, false);
        meta.addView(h.title);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = px(5);
        meta.addView(h.sub, sp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
        mp.leftMargin = px(24);
        h.inner.addView(meta, mp);
        h.eq = new TvUi.EqView(act, 3, TvUi.ACCENT);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(px(34), px(26));
        ep.leftMargin = px(20);
        h.inner.addView(h.eq, ep);
        h.dur = TvUi.text(act, "", 26, TvUi.MUTED, false);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
        dp.leftMargin = px(24);
        h.inner.addView(h.dur, dp);
        wrap.addView(h.inner, new FrameLayout.LayoutParams(-1, px(104)));
        wrap.setTag(h);
        TvUi.focusable(h.inner, 1.015f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                h.focused = f;
                styleRow(h);
            }
        });
        h.inner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                clickRow(h);
            }
        });
        h.scope = a.scope;
        h.path = a.path;
        return wrap;
    }

    private void bindRow(RowHolder h, RowItem r, String scope, String path, Detail detail) {
        h.item = r;
        h.scope = scope;
        h.path = path;
        h.detail = detail;
        h.title.setText(r.title);
        h.sub.setText(r.sub);
        h.dur.setText(r.dur == null ? "" : r.dur);
        boolean numbered = r.number > 0;
        h.number.setVisibility(numbered ? View.VISIBLE : View.GONE);
        h.number.setText(numbered ? String.valueOf(r.number) : "");
        h.art.setVisibility(numbered ? View.GONE : View.VISIBLE);
        if (!numbered) {
            if (r.folder != null && r.art == null) {
                h.art.setTag(null);
                h.art.showIcon(0, R.drawable.ic_folder, true);
            } else if (r.art != null) {
                TvArt.load(act, h.art, r.art, R.drawable.ic_note);
            }
        }
        h.current = r.id >= 0 && r.id == curId;
        styleRow(h);
    }

    private void styleRow(RowHolder h) {
        boolean f = h.focused;
        h.inner.setBackground(f ? TvUi.round(Color.WHITE, 18) : null);
        h.title.setTextColor(h.current ? (f ? 0xFFD4153A : TvUi.ACCENT) : f ? TvUi.DARK_TEXT : TvUi.TEXT);
        h.sub.setTextColor(f ? TvUi.DARK_MUTED : TvUi.MUTED);
        h.dur.setTextColor(f ? TvUi.DARK_MUTED : TvUi.MUTED);
        h.number.setTextColor(h.current ? TvUi.ACCENT : f ? TvUi.DARK_MUTED : TvUi.MUTED);
        h.eq.setVisibility(h.current ? View.VISIBLE : View.GONE);
        h.eq.setRunning(playing);
    }

    private void clickRow(RowHolder h) {
        RowItem r = h.item;
        if (r == null) return;
        if (r.folder != null) {
            if (h.detail != null) h.detail.focus = h.position;
            open(folderDetail(r.folder));
        } else {
            if (h.detail != null) h.detail.focus = h.position;
            command("play", "id", String.valueOf(r.id), "scope", h.scope, "path", h.path);
        }
    }

    // ---- focus helpers --------------------------------------------------------------------

    private void focusFirst() {
        if (list == null) return;
        focusItem(0, COLS);
    }

    private void focusFirstRow() {
        if (list == null) return;
        focusItem(0, 1);
    }

    /** Puts the remote focus on item {@code index} (a card, or a row when cols is 1). */
    private void focusItem(final int index, final int cols) {
        final ListView lv = list;
        if (lv == null || !grab) return;
        final int pos = index / cols + lv.getHeaderViewsCount();
        lv.setSelection(pos);
        lv.postDelayed(new Runnable() {
            @Override public void run() {
                if (lv != list) return;
                View row = lv.getChildAt(pos - lv.getFirstVisiblePosition());
                if (row == null) return;
                View target = row;
                if (cols > 1 && row instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) row;
                    target = g.getChildAt(Math.min(index % cols, g.getChildCount() - 1));
                } else if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0) {
                    target = ((ViewGroup) row).getChildAt(0);
                }
                target.requestFocus();
            }
        }, 60);
    }

    // ---- QR -------------------------------------------------------------------------------

    private Bitmap makeQr(String contents, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 0, 0, hints);
            int scale = Math.max(1, sizePx / m.getWidth());
            int w = m.getWidth() * scale, h = m.getHeight() * scale;
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = m.get(x / scale, y / scale) ? TvUi.BG : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }
}
