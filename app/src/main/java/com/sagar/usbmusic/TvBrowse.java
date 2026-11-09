package com.sagar.usbmusic;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
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
 * The TV screen, laid out like YouTube Music: a left rail (Folders, Albums, Songs, Artists), the
 * lists on the right, and a folder / album / artist page with a centred cover and big buttons.
 * It talks to the same MusicService as the phone page, so both always show the same queue and state.
 */
final class TvBrowse {
    private static final int COLS = 4;
    private static final String[] TABS = {"Folders", "Albums", "Songs", "Artists"};
    private static final int[] TAB_ICONS = {
            R.drawable.ic_album, R.drawable.ic_album, R.drawable.ic_note, R.drawable.ic_person};
    private static final int REST = 0xFFE6E6EA;
    private static final long AMBIENT_MS = 30000;
    private static final int SEARCH_TAB = 4;

    private LinearLayout searchItem;
    private ImageView searchIcon;
    private TextView searchLabel;
    private EditText searchField;
    private String searchQuery = "";
    private int searchFocus = 0;
    private final Runnable doSearch = new Runnable() {
        @Override public void run() {
            runSearch();
        }
    };

    private final Activity act;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final FrameLayout content;
    private final TextView message;
    private final TextView toastView;
    private final LinearLayout rail;
    private final View divider;
    private final TvPlayer player;
    private final Runnable enterAmbient = new Runnable() {
        @Override public void run() {
            if (player.isOpen()) {
                player.setAmbient(true);
                rail.animate().alpha(0f).setDuration(1400).start();
            }
        }
    };
    private final LinearLayout[] railItems = new LinearLayout[TABS.length];
    private final ImageView[] railIcons = new ImageView[TABS.length];
    private final TextView[] railLabels = new TextView[TABS.length];
    private LinearLayout rescanItem;
    private ImageView rescanIcon;
    private TextView rescanLabel;
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
    private View heroPlay;
    private Runnable pendingTab;
    /** False while a tab is being previewed, so the new list does not take the focus off the rail. */
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
        LibraryIndex.Group group;
        boolean groupIsAlbum;
        /** A section title in the search results ("Songs", "Folders"...); not selectable. */
        String header;
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

        // ---- left rail
        rail = new LinearLayout(activity);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(px(28), px(36), px(28), px(30));
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        root.addView(rail, new FrameLayout.LayoutParams(px(360), -1));
        divider = new View(activity);
        divider.setBackgroundColor(0xFF1D1D1D);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Math.max(1, px(1)), -1);
        dl.leftMargin = px(360);
        root.addView(divider, dl);

        LinearLayout brand = new LinearLayout(activity);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(px(16), 0, 0, 0);
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.drawable.ic_launcher);
        brand.addView(logo, new LinearLayout.LayoutParams(px(56), px(56)));
        TextView name = TvUi.text(activity, "USB Music", 34, TvUi.TEXT, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.leftMargin = px(16);
        brand.addView(name, np);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, px(64));
        bp.bottomMargin = px(34);
        rail.addView(brand, bp);

        searchItem = railItem(R.drawable.ic_search, "Search");
        searchIcon = (ImageView) searchItem.getChildAt(0);
        searchLabel = (TextView) searchItem.getChildAt(1);
        TvUi.focusable(searchItem, 1.04f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                styleRail();
                if (pendingTab != null) handler.removeCallbacks(pendingTab);
                if (f && (tab != SEARCH_TAB || !stack.isEmpty())) {
                    pendingTab = new Runnable() {
                        @Override public void run() {
                            selectTab(SEARCH_TAB, false);
                        }
                    };
                    handler.postDelayed(pendingTab, 320);
                }
            }
        });
        searchItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selectTab(SEARCH_TAB, true);
            }
        });
        TvUi.pressToActivate(searchItem, new Runnable() {
            @Override public void run() {
                selectTab(SEARCH_TAB, true);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, px(88));
        slp.bottomMargin = px(8);
        rail.addView(searchItem, slp);

        for (int i = 0; i < TABS.length; i++) {
            if (i == 1 || i == 3) continue; // Albums and Artists are no longer in the rail; Search still opens them
            final int n = i;
            LinearLayout item = railItem(TAB_ICONS[i], TABS[i]);
            railItems[i] = item;
            railIcons[i] = (ImageView) item.getChildAt(0);
            railLabels[i] = (TextView) item.getChildAt(1);
            TvUi.focusable(item, 1.04f, new TvUi.FocusChange() {
                @Override public void onFocus(boolean f) {
                    styleRail();
                    if (pendingTab != null) handler.removeCallbacks(pendingTab);
                    if (f && (n != tab || !stack.isEmpty())) {
                        // Resting on a rail item for a moment opens it, so moving down the rail previews each tab.
                        pendingTab = new Runnable() {
                            @Override public void run() {
                                selectTab(n, false);
                            }
                        };
                        handler.postDelayed(pendingTab, 320);
                    }
                }
            });
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectTab(n, true);
                }
            });
            TvUi.pressToActivate(item, new Runnable() {
                @Override public void run() {
                    selectTab(n, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(88));
            lp.bottomMargin = px(8);
            rail.addView(item, lp);
        }
        rail.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1f));

        rescanItem = railItem(R.drawable.ic_refresh, "Rescan USB");
        rescanIcon = (ImageView) rescanItem.getChildAt(0);
        rescanLabel = (TextView) rescanItem.getChildAt(1);
        TvUi.focusable(rescanItem, 1.04f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                styleRail();
            }
        });
        final Runnable rescanAction = new Runnable() {
            @Override public void run() {
                act.startService(new Intent(act, MusicService.class).setAction(MusicService.ACTION_RESCAN));
                toast("Scanning the USB drive…");
            }
        };
        rescanItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                rescanAction.run();
            }
        });
        TvUi.pressToActivate(rescanItem, rescanAction);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, px(88));
        rp.bottomMargin = px(14);
        rail.addView(rescanItem, rp);

        LinearLayout qrBox = new LinearLayout(activity);
        qrBox.setOrientation(LinearLayout.HORIZONTAL);
        qrBox.setGravity(Gravity.CENTER_VERTICAL);
        qrBox.setPadding(px(12), px(12), px(12), px(12));
        qrBox.setBackground(TvUi.round(0x10FFFFFF, 20));
        FrameLayout qrCard = new FrameLayout(activity);
        qrCard.setBackground(TvUi.round(Color.WHITE, 10));
        qrCard.setPadding(px(6), px(6), px(6), px(6));
        qrView = new ImageView(activity);
        qrCard.addView(qrView, new FrameLayout.LayoutParams(px(76), px(76)));
        qrBox.addView(qrCard, new LinearLayout.LayoutParams(px(88), px(88)));
        LinearLayout qrText = new LinearLayout(activity);
        qrText.setOrientation(LinearLayout.VERTICAL);
        qrText.setPadding(px(14), 0, 0, 0);
        qrText.addView(TvUi.text(activity, "Open on phone", 20, TvUi.TEXT, true));
        urlView = TvUi.text(activity, "starting…", 18, TvUi.MUTED, false);
        qrText.addView(urlView);
        qrBox.addView(qrText, new LinearLayout.LayoutParams(0, -2, 1f));
        rail.addView(qrBox, new LinearLayout.LayoutParams(-1, -2));

        // ---- content
        content = new FrameLayout(activity);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
        cp.leftMargin = px(360) + Math.max(1, px(1));
        cp.topMargin = px(30);
        cp.bottomMargin = px(30);
        root.addView(content, cp);

        message = TvUi.text(activity, "", 30, TvUi.MUTED, false);
        message.setSingleLine(false);
        message.setGravity(Gravity.CENTER);
        message.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        mp.leftMargin = mp.rightMargin = px(160);
        content.addView(message, mp);

        player = new TvPlayer(activity, new TvPlayer.Host() {
            @Override public LibraryIndex index() {
                return idx;
            }

            @Override public void command(String cmd, String... kv) {
                TvBrowse.this.command(cmd, kv);
            }

            @Override public JSONObject query(String cmd, String... kv) {
                MusicService s = MusicService.get();
                return s == null ? null : s.command(cmd, kv);
            }

            @Override public void toast(String s) {
                TvBrowse.this.toast(s);
            }

            @Override public void barShown(boolean shown) {
                applyBar(shown);
            }

            @Override public void npOpened(boolean open) {
                int mode = open ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_BEFORE_DESCENDANTS;
                rail.setDescendantFocusability(mode);
                content.setDescendantFocusability(mode);
                handler.removeCallbacks(enterAmbient);
                if (open) handler.postDelayed(enterAmbient, AMBIENT_MS);
                else rail.animate().alpha(1f).setDuration(300).start();
            }

            @Override public void showMenu(String title, String sub, Track art, String[] labels, Runnable[] actions) {
                TvBrowse.this.showMenu(title, sub, art, labels, actions);
            }

            @Override public void goToCurrentFolder() {
                if (curId >= 0 && curId < idx.tracks.size()) goToFolder(idx.tracks.get(curId));
            }
        });
        // Order from the bottom: lists, now-playing page, rail, player bar.
        root.addView(player.np(), new FrameLayout.LayoutParams(-1, -1));
        divider.bringToFront();
        rail.bringToFront();
        root.addView(player.bar(), new FrameLayout.LayoutParams(-1, px(130), Gravity.BOTTOM));

        toastView = TvUi.text(activity, "", 28, Color.BLACK, true);
        toastView.setBackground(TvUi.round(Color.WHITE, 40));
        toastView.setPadding(px(38), px(18), px(38), px(18));
        toastView.setAlpha(0f);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = px(170);
        tl.leftMargin = px(360);
        root.addView(toastView, tl);

        styleRail();
    }

    View view() {
        return root;
    }

    void start() {
        if (!focusWatch) {
            focusWatch = true;
            root.getViewTreeObserver().addOnGlobalFocusChangeListener(
                    new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
                        @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                            if (newFocus != null && isInside(newFocus, content)) lastContentFocus = newFocus;
                        }
                    });
        }
        handler.post(refresh);
        // Before the list exists the system focuses the first rail item (Search), which would open
        // the Search tab after a moment. Start on the current tab instead.
        if (firstStart) {
            firstStart = false;
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    View f = root.findFocus();
                    if (f == null || isInside(f, rail)) railItems[tab].requestFocus();
                }
            }, 100);
        }
    }

    void stop() {
        handler.removeCallbacks(refresh);
    }

    /** Back button: goes up one level. Returns false when already at the top. */
    boolean onBack() {
        if (player.isOpen()) {
            player.closeNp();
            return true;
        }
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

    void command(String cmd, String... kv) {
        MusicService s = MusicService.get();
        if (s != null) {
            s.command(cmd, kv);
            tick();
        }
    }

    private void toast(String s) {
        toastView.setText(s);
        toastView.animate().cancel();
        toastView.animate().alpha(1f).setDuration(200).start();
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 1900);
    }

    private final Runnable hideToast = new Runnable() {
        @Override public void run() {
            toastView.animate().alpha(0f).setDuration(300).start();
        }
    };

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
            player.update(st, idx);
        } catch (Exception ignored) {
        }
    }

    /** Leaves room for the player bar when something is loaded. */
    private void applyBar(boolean shown) {
        int bottom = shown ? px(130) : 0;
        FrameLayout.LayoutParams rl = (FrameLayout.LayoutParams) rail.getLayoutParams();
        rl.bottomMargin = bottom;
        rail.setLayoutParams(rl);
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) divider.getLayoutParams();
        dl.bottomMargin = bottom;
        divider.setLayoutParams(dl);
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) content.getLayoutParams();
        cl.bottomMargin = shown ? px(130) : px(30);
        content.setLayoutParams(cl);
    }

    /** Called for every key press. True when the key only woke the screen from ambient mode. */
    boolean onActivity() {
        handler.removeCallbacks(enterAmbient);
        if (player.isOpen()) handler.postDelayed(enterAmbient, AMBIENT_MS);
        if (player.isAmbient()) {
            player.setAmbient(false);
            rail.animate().alpha(1f).setDuration(300).start();
            return true;
        }
        return false;
    }

    /** Remote's Menu/Options key: same as holding OK on the focused song. */
    boolean onMenuKey() {
        View f = root.findFocus();
        if (f instanceof EditText) return false;
        if (f != null && f.isLongClickable()) {
            f.performLongClick();
            return true;
        }
        return false;
    }

    void seekBy(int deltaMs) {
        player.seekBy(deltaMs);
    }

    boolean isPlaying() {
        return playing;
    }

    private View lastContentFocus;
    private boolean focusWatch;
    private boolean firstStart = true;

    private static boolean isInside(View v, View ancestor) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return v == ancestor;
    }

    /**
     * Down from the last row of a list, and Up from the progress line, would otherwise get stuck
     * (a ListView keeps the key). Returns true when it moved the focus itself.
     */
    boolean onNavKey(int keyCode) {
        if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN && keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) {
            return false;
        }
        View f = root.findFocus();
        if (f == null) return false;
        boolean up = keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP;
        if (list != null && !player.isOpen() && isInside(f, list)) {
            int pos = list.getPositionForView(f);
            // Up from the first song of a folder / album / artist page goes back to its Play button
            // (the buttons scroll out of the list, so the focus search would not find them).
            if (up && heroPlay != null && pos == list.getHeaderViewsCount()) {
                focusHero();
                return true;
            }
            // Same on the Folders page: Up from the first row goes to the Folder view / List view switch.
            if (up && tab == 0 && stack.isEmpty() && segGrid != null && pos == list.getHeaderViewsCount()) {
                focusSwitch();
                return true;
            }
            // Up and Down inside a list must never jump into the left rail: that would switch the
            // tab (and lose the open folder). The rail is only reached with Left.
            View n = f.focusSearch(up ? View.FOCUS_UP : View.FOCUS_DOWN);
            if (n != null && isInside(n, rail)) return true;
        }
        if (!player.hasTrack()) return false;
        boolean inBar = isInside(f, player.bar());
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (inBar) return false;
            if (player.isOpen()) {
                if (player.inLastQueueRow(f)) {
                    player.focusPlay();
                    return true;
                }
                return false;
            }
            // Only the very last row needs help; the list moves the focus itself everywhere else.
            if (list != null && isInside(f, list) && list.getPositionForView(f) == list.getCount() - 1) {
                player.focusPlay();
                return true;
            }
            return false;
        }
        if (inBar && player.isSeek(f)) {
            if (player.isOpen()) return player.focusQueue();
            if (lastContentFocus != null && lastContentFocus.isShown() && lastContentFocus.isFocusable()) {
                return lastContentFocus.requestFocus();
            }
        }
        return false;
    }

    // ---- popup menu -----------------------------------------------------------------------

    private void showMenu(String title, String sub, Track art, String[] labels, final Runnable[] actions) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);
        FrameLayout scrim = new FrameLayout(act);
        scrim.setBackgroundColor(0x99000000);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(TvUi.round(0xFF232326, 30));
        box.setPadding(px(30), px(30), px(30), px(24));
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(10), px(4), px(10), px(22));
        TvUi.ArtView a = new TvUi.ArtView(act, px(84), false, 10);
        if (art != null) TvArt.load(act, a, art, R.drawable.ic_note);
        else a.showIcon(0, R.drawable.ic_note, true);
        head.addView(a, new LinearLayout.LayoutParams(px(84), px(84)));
        LinearLayout t = new LinearLayout(act);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(TvUi.text(act, title, 30, TvUi.TEXT, true));
        t.addView(TvUi.text(act, sub, 22, TvUi.MUTED, false));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = px(20);
        head.addView(t, tp);
        box.addView(head);
        TextView first = null;
        for (int i = 0; i < labels.length; i++) {
            final int n = i;
            final TextView item = TvUi.text(act, labels[i], 30, TvUi.TEXT, true);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(px(30), 0, px(30), 0);
            TvUi.focusable(item, 1.02f, new TvUi.FocusChange() {
                @Override public void onFocus(boolean f) {
                    item.setBackground(f ? TvUi.round(Color.WHITE, 18) : null);
                    item.setTextColor(f ? Color.BLACK : TvUi.TEXT);
                }
            });
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    actions[n].run();
                }
            });
            TvUi.pressToActivate(item, new Runnable() {
                @Override public void run() {
                    d.dismiss();
                    actions[n].run();
                }
            });
            box.addView(item, new LinearLayout.LayoutParams(-1, px(88)));
            if (first == null) first = item;
        }
        scrim.addView(box, new FrameLayout.LayoutParams(px(760), -2, Gravity.CENTER));
        d.setContentView(scrim);
        d.setCancelable(true);
        d.show();
        d.getWindow().setLayout(-1, -1);
        if (first != null) first.requestFocus();
    }

    private void openSongMenu(RowHolder h) {
        final RowItem r = h.item;
        if (r == null || r.id < 0) return;
        final Track t = idx.tracks.get(r.id);
        final String id = String.valueOf(r.id);
        showMenu(t.title, subOf(t), t, new String[]{"Play next", "Add to queue", "Go to folder"}, new Runnable[]{
                new Runnable() {
                    @Override public void run() {
                        boolean idle = curId < 0;
                        command("playnext", "id", id);
                        toast(idle ? "Playing" : "Playing next");
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        boolean idle = curId < 0;
                        command("enqueue", "id", id);
                        toast(idle ? "Playing" : "Added to the queue");
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        goToFolder(t);
                    }
                }});
    }

    private void goToFolder(Track t) {
        LibraryIndex.Node n = idx.nodeFor(t.folder);
        if (n == null) {
            toast("This song is not inside a folder");
            return;
        }
        if (player.isOpen()) player.closeNp();
        List<LibraryIndex.Node> chain = new ArrayList<>();
        for (LibraryIndex.Node p = n; p != null; p = p.parent) chain.add(0, p);
        tab = 0;
        stack.clear();
        for (LibraryIndex.Node p : chain) stack.add(folderDetail(p));
        styleRail();
        showCurrent(false);
        focusHero();
    }

    private void updateMessage(MusicService s) {
        message.setText(s.isScanning() ? "Scanning the USB drive…"
                : "No music found.\nPlug the USB drive into the TV, then choose Rescan USB.");
    }

    // ---- rail -----------------------------------------------------------------------------

    private LinearLayout railItem(int icon, String label) {
        LinearLayout it = new LinearLayout(act);
        it.setOrientation(LinearLayout.HORIZONTAL);
        it.setGravity(Gravity.CENTER_VERTICAL);
        it.setPadding(px(30), 0, px(20), 0);
        ImageView ic = new ImageView(act);
        ic.setImageResource(icon);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        it.addView(ic, new LinearLayout.LayoutParams(px(40), px(40)));
        TextView t = TvUi.text(act, label, 32, REST, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = px(24);
        it.addView(t, tp);
        return it;
    }

    private void styleRailItem(LinearLayout item, ImageView icon, TextView label, boolean on) {
        boolean f = item.hasFocus();
        item.setBackground(f ? TvUi.round(Color.WHITE, 44) : on ? TvUi.round(0x1FFFFFFF, 44) : null);
        int c = f ? Color.BLACK : on ? Color.WHITE : REST;
        label.setTextColor(c);
        icon.setColorFilter(c, PorterDuff.Mode.SRC_IN);
    }

    private void styleRail() {
        for (int i = 0; i < railItems.length; i++) {
            if (railItems[i] == null) continue;
            styleRailItem(railItems[i], railIcons[i], railLabels[i], i == tab);
        }
        styleRailItem(rescanItem, rescanIcon, rescanLabel, false);
        styleRailItem(searchItem, searchIcon, searchLabel, tab == SEARCH_TAB);
    }

    private void selectTab(int n, boolean takeFocus) {
        if (pendingTab != null) handler.removeCallbacks(pendingTab);
        boolean same = n == tab && stack.isEmpty();
        tab = n;
        stack.clear();
        rootFocus = 0;
        styleRail();
        grab = takeFocus;
        if (!same) showCurrent(false);
        grab = true;
        if (takeFocus) focusFirst();
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
            t.setTextColor(REST);
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
        TvUi.pressToActivate(t, action);
        return t;
    }

    /** Round icon button, like the shuffle / play / add-to-queue buttons on a YouTube Music album page. */
    private ImageView circle(int icon, final boolean big, final Runnable action) {
        final ImageView b = new ImageView(act);
        int pad = px(big ? 30 : 22);
        b.setPadding(pad, pad, pad, pad);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setImageResource(icon);
        styleCircle(b, big, false);
        TvUi.focusable(b, big ? 1.12f : 1.14f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                styleCircle(b, big, f);
                // Back on the buttons: scroll up so the cover and title above them show again.
                if (f && list != null && list.getFirstVisiblePosition() > 0) {
                    list.smoothScrollToPositionFromTop(0, 0, 200);
                }
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

    private void styleCircle(ImageView b, boolean big, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(focused ? (big ? TvUi.ACCENT : Color.WHITE) : big ? Color.WHITE : 0x1AFFFFFF);
        b.setBackground(d);
        b.setColorFilter(focused ? (big ? Color.WHITE : Color.BLACK) : big ? Color.BLACK : Color.WHITE,
                PorterDuff.Mode.SRC_IN);
    }

    // ---- screens --------------------------------------------------------------------------

    private void showCurrent(boolean restoreFocus) {
        content.removeAllViews();
        content.addView(message);
        list = null;
        adapter = null;
        heroPlay = null;
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
                showFolders(focus);
                break;
            case 1:
                showGrid(albumCards(), "Albums", false, focus);
                break;
            case 2:
                showSongs(focus);
                break;
            case SEARCH_TAB:
                showSearch(restoreFocus ? searchFocus : 0);
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
        focusHero();
    }

    // ---- grid -----------------------------------------------------------------------------

    // ---- search ---------------------------------------------------------------------------

    private Detail albumDetail(LibraryIndex.Group g) {
        Detail d = new Detail();
        d.title = g.name;
        d.kind = "Album";
        d.sub1 = g.sub;
        d.scope = "album";
        d.ids.addAll(g.ids);
        d.all.addAll(g.ids);
        return d;
    }

    private Detail artistDetail(LibraryIndex.Group g) {
        Detail d = new Detail();
        d.title = g.name;
        d.kind = "Artist";
        d.scope = "artist";
        d.ids.addAll(g.ids);
        d.all.addAll(g.ids);
        return d;
    }

    private void showKeyboard(final View v) {
        v.postDelayed(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 250);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void styleSearchField(EditText f, boolean focused) {
        GradientDrawable d = TvUi.round(focused ? 0xFF2A2A2D : 0xFF212121, 16);
        d.setStroke(px(focused ? 4 : 2), focused ? Color.WHITE : 0xFF3A3A3A);
        f.setBackground(d);
    }

    /** The search page: a field on top (the TV's own on-screen keyboard, with voice) and results below. */
    private void showSearch(int focus) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        final EditText field = new EditText(act);
        field.setSingleLine(true);
        field.setHint("Search songs, albums, folders, artists");
        field.setHintTextColor(0xFF8A8A90);
        field.setTextColor(Color.WHITE);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        field.setPadding(px(34), 0, px(34), 0);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(searchQuery);
        field.setSelection(searchQuery.length());
        styleSearchField(field, false);
        TvUi.focusable(field, 1.015f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                styleSearchField(field, f);
            }
        });
        field.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showKeyboard(field);
            }
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void afterTextChanged(Editable e) {
                searchQuery = e.toString();
                handler.removeCallbacks(doSearch);
                handler.postDelayed(doSearch, 250);
            }
        });
        field.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId, KeyEvent ev) {
                hideKeyboard(field);
                handler.removeCallbacks(doSearch);
                runSearch();
                focusItem(1, 1); // row 0 is the "Songs" heading
                return true;
            }
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, px(84));
        fp.leftMargin = px(104);
        fp.rightMargin = px(120);
        fp.topMargin = px(14);
        fp.bottomMargin = px(14);
        box.addView(field, fp);

        ListView lv = newList();
        box.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));
        content.addView(box, new FrameLayout.LayoutParams(-1, -1));
        searchField = field;
        list = lv;
        runSearch();
        if (focus > 0) focusItem(focus, 1);
        else if (grab) field.requestFocus();
    }

    private void runSearch() {
        ListView lv = list;
        if (lv == null || tab != SEARCH_TAB || !stack.isEmpty()) return;
        List<RowItem> items = searchResults(searchQuery);
        if (items.isEmpty()) {
            message.setText(searchQuery.trim().isEmpty()
                    ? "Type to search your songs, folders, albums and artists."
                    : "No results for “" + searchQuery.trim() + "”");
            message.setVisibility(View.VISIBLE);
        } else {
            message.setVisibility(View.GONE);
        }
        RowAdapter a = new RowAdapter(items, "all", "");
        lv.setAdapter(a);
        adapter = a;
    }

    private static boolean hit(String q, String... fields) {
        for (String s : fields) {
            if (s != null && s.toLowerCase().contains(q)) return true;
        }
        return false;
    }

    private RowItem header(String text) {
        RowItem r = new RowItem();
        r.header = text;
        r.title = text;
        return r;
    }

    private List<RowItem> searchResults(String raw) {
        List<RowItem> out = new ArrayList<>();
        String q = raw.trim().toLowerCase();
        if (q.isEmpty()) return out;

        List<RowItem> songs = new ArrayList<>();
        for (int id = 0; id < idx.tracks.size() && songs.size() < 60; id++) {
            Track t = idx.tracks.get(id);
            if (hit(q, t.title, lastSegment(t.folder), t.artist, t.album, t.composer)) {
                songs.add(songItem(id, 0, false));
            }
        }
        List<RowItem> folders = new ArrayList<>();
        for (LibraryIndex.Node n : idx.folderNodes) {
            if (folders.size() >= 12) break;
            if (!hit(q, n.name)) continue;
            RowItem r = new RowItem();
            r.folder = n;
            r.title = n.name;
            String where = n.relPath();
            r.sub = (where.isEmpty() ? "Folder" : "Folder • " + where) + " • " + folderSub(n);
            r.art = n.children.isEmpty() && !n.all.isEmpty() ? idx.tracks.get(n.all.get(0)) : null;
            folders.add(r);
        }
        List<RowItem> albums = new ArrayList<>();
        for (LibraryIndex.Group g : idx.albums) {
            if (albums.size() >= 12) break;
            if (!hit(q, g.name, g.sub)) continue;
            RowItem r = new RowItem();
            r.group = g;
            r.groupIsAlbum = true;
            r.title = g.name;
            r.sub = (g.sub.isEmpty() ? "Album" : "Album • " + g.sub) + " • " + TvUi.plural(g.ids.size(), "song");
            r.art = idx.tracks.get(g.ids.get(0));
            albums.add(r);
        }
        List<RowItem> artists = new ArrayList<>();
        for (LibraryIndex.Group g : idx.artists) {
            if (artists.size() >= 12) break;
            if (!hit(q, g.name)) continue;
            RowItem r = new RowItem();
            r.group = g;
            r.title = g.name;
            r.sub = "Artist • " + TvUi.plural(g.ids.size(), "song");
            r.art = idx.tracks.get(g.ids.get(0));
            artists.add(r);
        }
        if (!songs.isEmpty()) {
            out.add(header("Songs"));
            out.addAll(songs);
        }
        if (!folders.isEmpty()) {
            out.add(header("Folders"));
            out.addAll(folders);
        }
        if (!albums.isEmpty()) {
            out.add(header("Albums"));
            out.addAll(albums);
        }
        if (!artists.isEmpty()) {
            out.add(header("Artists"));
            out.addAll(artists);
        }
        return out;
    }

    private static String lastSegment(String folder) {
        int i = folder.lastIndexOf('/');
        return i >= 0 ? folder.substring(i + 1) : folder;
    }

    private TextView heading(String s) {
        TextView h = TvUi.text(act, s, 48, TvUi.TEXT, true);
        h.setPadding(px(104), px(10), 0, px(24));
        return h;
    }

    private ListView newList() {
        ListView lv = new ListView(act);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setSelector(new ColorDrawable(Color.TRANSPARENT));
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lv.setVerticalScrollBarEnabled(false);
        lv.setItemsCanFocus(true);
        lv.setClipChildren(false);
        lv.setClipToPadding(false);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setPadding(0, px(14), 0, px(60));
        return lv;
    }

    // ---- Folders page: Folder view (cover grid) or List view ---------------------------------

    private View segGrid, segList;

    private boolean foldersAsList() {
        return act.getSharedPreferences("usbmusic_ui", Context.MODE_PRIVATE).getBoolean("folders_list", false);
    }

    private void switchFolders(boolean toList) {
        if (toList == foldersAsList()) return;
        act.getSharedPreferences("usbmusic_ui", Context.MODE_PRIVATE).edit().putBoolean("folders_list", toList).apply();
        grab = false;
        showCurrent(false);
        grab = true;
        final View target = toList ? segList : segGrid;
        if (target != null) {
            target.postDelayed(new Runnable() {
                @Override public void run() {
                    target.requestFocus();
                }
            }, 120);
        }
        toast(toList ? "List view" : "Folder view");
    }

    /** One half of the view switch: icon plus label. */
    private LinearLayout segment(int icon, String label, final boolean on, final boolean toList) {
        final LinearLayout seg = new LinearLayout(act);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setGravity(Gravity.CENTER_VERTICAL);
        seg.setPadding(px(28), 0, px(30), 0);
        final GradientDrawable bg = TvUi.round(on ? 0x2EFFFFFF : 0, 34);
        seg.setBackground(bg);
        final ImageView ic = new ImageView(act);
        ic.setImageResource(icon);
        ic.setColorFilter(on ? Color.WHITE : 0xFFCFCFD4, PorterDuff.Mode.SRC_IN);
        seg.addView(ic, new LinearLayout.LayoutParams(px(32), px(32)));
        final TextView t = TvUi.text(act, label, 27, on ? Color.WHITE : 0xFFCFCFD4, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = px(14);
        seg.addView(t, tp);
        TvUi.focusable(seg, 1.06f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                bg.setColor(f ? Color.WHITE : on ? 0x2EFFFFFF : 0);
                int c = f ? Color.BLACK : on ? Color.WHITE : 0xFFCFCFD4;
                t.setTextColor(c);
                ic.setColorFilter(c, PorterDuff.Mode.SRC_IN);
            }
        });
        Runnable action = new Runnable() {
            @Override public void run() {
                switchFolders(toList);
            }
        };
        seg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                switchFolders(toList);
            }
        });
        TvUi.pressToActivate(seg, action);
        return seg;
    }

    private View foldersHeader() {
        boolean asList = foldersAsList();
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.addView(heading("Folders"), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(px(8), px(8), px(8), px(8));
        box.setClipChildren(false);
        box.setBackground(TvUi.round(0x0FFFFFFF, 46));
        segGrid = segment(R.drawable.ic_view_grid, "Folder view", !asList, false);
        segList = segment(R.drawable.ic_view_list, "List view", asList, true);
        box.addView(segGrid, new LinearLayout.LayoutParams(-2, px(68)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(68));
        lp.leftMargin = px(12);
        box.addView(segList, lp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.rightMargin = px(96);
        bp.bottomMargin = px(14);
        row.addView(box, bp);
        return row;
    }

    private void showFolders(int focus) {
        ListView lv = newList();
        lv.addHeaderView(foldersHeader(), null, false);
        if (foldersAsList()) {
            List<RowItem> items = new ArrayList<>();
            for (LibraryIndex.Node n : idx.tree.sortedKids()) {
                RowItem r = new RowItem();
                r.folder = n;
                r.title = n.name;
                r.sub = folderSub(n);
                r.art = n.children.isEmpty() && !n.all.isEmpty() ? idx.tracks.get(n.all.get(0)) : null;
                items.add(r);
            }
            RowAdapter a = new RowAdapter(items, "tree", "");
            lv.setAdapter(a);
            adapter = a;
            content.addView(lv, new FrameLayout.LayoutParams(-1, -1));
            list = lv;
            focusItem(focus, 1);
        } else {
            CardAdapter a = new CardAdapter(foldersCards(), false);
            lv.setAdapter(a);
            adapter = a;
            content.addView(lv, new FrameLayout.LayoutParams(-1, -1));
            list = lv;
            focusItem(focus, COLS);
        }
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
                row.setClipToPadding(false);
                row.setPadding(px(104), px(14), 0, px(40));
                for (int c = 0; c < COLS; c++) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(320), -2);
                    lp.rightMargin = px(50);
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
        h.art = new TvUi.ArtView(act, px(320), circle, 14);
        c.addView(h.art, new LinearLayout.LayoutParams(px(320), px(320)));
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
        TvUi.focusable(c, 1.06f, new TvUi.FocusChange() {
            @Override public void onFocus(boolean f) {
                h.art.setRing(f);
            }
        });
        Runnable open = new Runnable() {
            @Override public void run() {
                if (h.open != null) h.open.run();
            }
        };
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (h.open != null) h.open.run();
            }
        });
        TvUi.pressToActivate(c, open);
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
        head.setClipChildren(false);
        head.setClipToPadding(false);
        head.addView(heading("Songs"));
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setClipChildren(false);
        btns.setClipToPadding(false);
        btns.setPadding(px(104), px(4), 0, px(22));
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
        final RowAdapter a = new RowAdapter(items, "all", "");
        lv.setAdapter(a);
        content.addView(lv, new FrameLayout.LayoutParams(-1, -1));
        list = lv;
        adapter = a;
        focusItem(focus, 1);
    }

    private View heroView(final Detail d) {
        LinearLayout hero = new LinearLayout(act);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setClipChildren(false);
        hero.setClipToPadding(false);
        hero.setPadding(px(60), px(6), px(60), px(34));

        TextView chip = TvUi.text(act, d.kind, 26, REST, true);
        hero.addView(chip, new LinearLayout.LayoutParams(-2, -2));

        TvUi.ArtView art = new TvUi.ArtView(act, px(330), false, 12);
        if (!d.children.isEmpty() || d.all.isEmpty()) art.showIcon(0, R.drawable.ic_folder, true);
        else TvArt.load(act, art, idx.tracks.get(d.all.get(0)), R.drawable.ic_note);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(330), px(330));
        ap.topMargin = px(14);
        hero.addView(art, ap);

        TextView title = TvUi.text(act, d.title, 54, TvUi.TEXT, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = px(24);
        hero.addView(title, tp);

        long ms = 0;
        for (int id : d.all) ms += idx.tracks.get(id).durationMs;
        StringBuilder meta = new StringBuilder(d.kind);
        if (d.sub1 != null && !d.sub1.isEmpty()) meta.append(" • ").append(d.sub1);
        meta.append(" • ").append(TvUi.plural(d.all.size(), "song"));
        if (ms > 0) meta.append(" • ").append(TvUi.span(ms));
        TextView m = TvUi.text(act, meta.toString(), 26, TvUi.MUTED, false);
        m.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = px(8);
        hero.addView(m, mp);

        if (!d.all.isEmpty()) {
            final String path = d.treePath == null ? "" : d.treePath;
            LinearLayout btns = new LinearLayout(act);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.CENTER);
            btns.setClipChildren(false);
            btns.setClipToPadding(false);
            ImageView shuffle = circle(R.drawable.ic_shuffle, false, new Runnable() {
                @Override public void run() {
                    shuffleAll(d.all, d.scope, path);
                }
            });
            ImageView play = circle(R.drawable.ic_play, true, new Runnable() {
                @Override public void run() {
                    playAll(d.all, d.scope, path);
                }
            });
            ImageView queue = circle(R.drawable.ic_queue, false, new Runnable() {
                @Override public void run() {
                    for (int id : d.all) command("enqueue", "id", String.valueOf(id));
                    toast("Added " + TvUi.plural(d.all.size(), "song") + " to the queue");
                }
            });
            heroPlay = play;
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(84), px(84));
            sl.rightMargin = px(26);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(px(120), px(120));
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(px(84), px(84));
            ql.leftMargin = px(26);
            btns.addView(shuffle, sl);
            btns.addView(play, pl);
            btns.addView(queue, ql);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.topMargin = px(26);
            hero.addView(btns, bp);
        }
        return hero;
    }

    private void showDetail(Detail d, boolean restoreFocus) {
        ListView lv = newList();
        lv.addHeaderView(heroView(d), null, false);
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
        final RowAdapter a = new RowAdapter(items, d.scope, d.treePath == null ? "" : d.treePath);
        a.detail = d;
        lv.setAdapter(a);
        content.addView(lv, new FrameLayout.LayoutParams(-1, -1));
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
        GradientDrawable bg;
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
        Detail detail;

        RowAdapter(List<RowItem> items, String scope, String path) {
            this.items = items;
            this.scope = scope;
            this.path = path;
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
            if (convert == null) convert = newRow();
            RowHolder h = (RowHolder) convert.getTag();
            h.position = pos;
            bindRow(h, items.get(pos), scope, path, detail);
            return convert;
        }
    }

    private View newRow() {
        FrameLayout wrap = new FrameLayout(act);
        wrap.setPadding(px(90), 0, px(60), px(6));
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);
        final RowHolder h = new RowHolder();
        h.inner = new LinearLayout(act);
        h.inner.setOrientation(LinearLayout.HORIZONTAL);
        h.inner.setGravity(Gravity.CENTER_VERTICAL);
        h.inner.setPadding(px(26), 0, px(30), 0);
        h.bg = TvUi.round(0, 14);
        h.inner.setBackground(h.bg);
        h.number = TvUi.text(act, "", 28, TvUi.MUTED, false);
        h.number.setGravity(Gravity.CENTER);
        h.inner.addView(h.number, new LinearLayout.LayoutParams(px(56), -2));
        h.art = new TvUi.ArtView(act, px(76), false, 10);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(76), px(76));
        ap.leftMargin = px(4);
        h.inner.addView(h.art, ap);
        LinearLayout meta = new LinearLayout(act);
        meta.setOrientation(LinearLayout.VERTICAL);
        h.title = TvUi.text(act, "", 31, TvUi.TEXT, true);
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
        wrap.addView(h.inner, new FrameLayout.LayoutParams(-1, px(100)));
        wrap.setTag(h);
        TvUi.focusable(h.inner, 1.01f, new TvUi.FocusChange() {
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
        // Folder, album and artist rows open on the key press itself (see TvUi.pressToActivate);
        // song rows keep click-on-release so that holding OK can open the menu instead.
        h.inner.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (h.item == null || h.item.header != null || h.item.id >= 0) return false;
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) clickRow(h);
                return true;
            }
        });
        // Holding OK (or the remote's Menu key) opens Play next / Add to queue / Go to folder.
        h.inner.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (h.item == null || h.item.id < 0) return false;
                openSongMenu(h);
                return true;
            }
        });
        return wrap;
    }

    private void bindRow(RowHolder h, RowItem r, String scope, String path, Detail detail) {
        h.item = r;
        h.scope = scope;
        h.path = path;
        h.detail = detail;
        boolean hdr = r.header != null;
        FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) h.inner.getLayoutParams();
        ilp.height = px(hdr ? 70 : 100);
        h.inner.setLayoutParams(ilp);
        h.inner.setFocusable(!hdr);
        h.inner.setClickable(!hdr);
        h.sub.setVisibility(hdr ? View.GONE : View.VISIBLE);
        h.dur.setVisibility(hdr ? View.GONE : View.VISIBLE);
        if (hdr) {
            h.title.setText(r.header.toUpperCase());
            h.number.setVisibility(View.GONE);
            h.art.setVisibility(View.GONE);
            h.current = false;
            styleRow(h);
            return;
        }
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
        if (h.item != null && h.item.header != null) {
            h.bg.setColor(0);
            h.bg.setStroke(0, 0);
            h.title.setTextColor(TvUi.MUTED);
            h.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24));
            h.eq.setVisibility(View.GONE);
            return;
        }
        h.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(31));
        boolean f = h.focused;
        // One background drawable per row whose colour changes: swapping drawables (or setting null)
        // asks for a layout, and doing that from a focus change inside a ListView loops forever.
        if (f) {
            h.bg.setColor(0x29FFFFFF);
            h.bg.setStroke(px(4), Color.WHITE);
        } else {
            h.bg.setColor(0);
            h.bg.setStroke(0, 0);
        }
        h.title.setTextColor(h.current ? TvUi.ACCENT : TvUi.TEXT);
        h.sub.setTextColor(TvUi.MUTED);
        h.dur.setTextColor(TvUi.MUTED);
        h.number.setTextColor(h.current ? TvUi.ACCENT : TvUi.MUTED);
        h.eq.setVisibility(h.current ? View.VISIBLE : View.GONE);
        h.eq.setRunning(playing);
    }

    private void clickRow(RowHolder h) {
        RowItem r = h.item;
        if (r == null) return;
        if (h.detail != null) h.detail.focus = h.position;
        if (tab == SEARCH_TAB) searchFocus = h.position;
        if (tab == 0 && stack.isEmpty() && h.detail == null) rootFocus = h.position;
        if (r.group != null) {
            open(r.groupIsAlbum ? albumDetail(r.group) : artistDetail(r.group));
        } else if (r.folder != null) {
            open(folderDetail(r.folder));
        } else {
            command("play", "id", String.valueOf(r.id), "scope", h.scope, "path", h.path);
        }
    }

    // ---- focus helpers --------------------------------------------------------------------

    private void focusFirst() {
        if (tab == SEARCH_TAB && searchField != null) {
            searchField.requestFocus();
            showKeyboard(searchField);
            return;
        }
        if (list == null) return;
        if (heroPlay != null) focusHero();
        else focusItem(0, COLS);
    }

    /** Scrolls the Folders page to the top and puts the focus on the half of the view switch that is on. */
    private void focusSwitch() {
        final ListView lv = list;
        if (lv == null) return;
        lv.smoothScrollToPositionFromTop(0, 0, 120);
        final View seg = foldersAsList() ? segList : segGrid;
        if (seg == null) return;
        lv.postDelayed(new Runnable() {
            @Override public void run() {
                if (lv != list) return;
                if (!seg.requestFocus()) {
                    lv.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (lv == list) seg.requestFocus();
                        }
                    }, 200);
                }
            }
        }, 260);
    }

    /** On a folder / album / artist page the remote starts on the big Play button. */
    private void focusHero() {
        final ListView lv = list;
        if (lv == null) return;
        // Scroll to the top without selecting anything: setSelection(0) would skip the (non-selectable)
        // header and put the first song at the top instead.
        lv.smoothScrollToPositionFromTop(0, 0, 120);
        final View target = heroPlay;
        lv.postDelayed(new Runnable() {
            @Override public void run() {
                if (lv != list) return;
                if (target == null) {
                    focusItem(0, 1);
                } else if (!target.requestFocus()) {
                    // The header was still being scrolled into place; try once more.
                    lv.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (lv == list) target.requestFocus();
                        }
                    }, 200);
                }
            }
        }, 260);
    }

    /** Puts the remote focus on item {@code index} (a card, or a row when cols is 1). */
    private void focusItem(final int index, final int cols) {
        final ListView lv = list;
        if (lv == null || !grab) return;
        int rowIdx = index / cols;
        final int focusPos = lv.getHeaderViewsCount() + rowIdx;
        // The first row scrolls to the very top so the heading (or the hero) stays in view
        // (setSelection(0) would skip the non-selectable header, so scroll instead).
        if (rowIdx == 0) lv.smoothScrollToPositionFromTop(0, 0, 120);
        else lv.setSelection(focusPos);
        lv.postDelayed(new Runnable() {
            @Override public void run() {
                if (lv != list) return;
                View row = lv.getChildAt(focusPos - lv.getFirstVisiblePosition());
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
        }, rowIdx == 0 ? 260 : 80);
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
