package com.sagar.usbmusic;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** Shows the address (and a QR code for it) to open on the phone, plus a little status. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0F1115;
    private static final int CARD = 0xFF1A1E27;
    private static final int TEXT = 0xFFF2F4F8;
    private static final int MUTED = 0xFF8B93A1;
    private static final int TEAL = 0xFF2DD4BF;
    private static final int LINE = 0xFF3A404C;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView url;
    private TextView status;
    private ImageView qr;
    private String qrFor = "";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            MusicService svc = MusicService.get();
            if (svc != null) {
                String address = "http://" + WebServer.localIp() + ":" + svc.port();
                url.setText(address.replace("http://", ""));
                status.setText(svc.summary().trim());
                if (!address.equals(qrFor) && svc.port() > 0) {
                    qrFor = address;
                    qr.setImageBitmap(makeQr(address, dp(160)));
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        // One compact panel in the middle of the screen: info on the left, QR code on the right.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(round(CARD, 24, 0, 0));
        panel.setPadding(dp(32), dp(28), dp(32), dp(28));
        root.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- left: brand, headline, address, status, buttons
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        panel.addView(left, new LinearLayout.LayoutParams(dp(330), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = text("USB Music", 14, MUTED, true);
        name.setPadding(dp(10), 0, 0, 0);
        brand.addView(name);
        left.addView(brand);

        TextView headline = text("Play from your phone", 22, TEXT, true);
        headline.setPadding(0, dp(18), 0, dp(2));
        left.addView(headline);
        left.addView(text("Scan the code, or open this address in your phone's browser.", 12, MUTED, false));

        url = text("starting\u2026", 17, TEAL, true);
        url.setBackground(round(BG, 12, 0, 0));
        url.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        up.topMargin = dp(14);
        left.addView(url, up);
        TextView wifi = text("Phone and TV need to be on the same Wi-Fi.", 11, MUTED, false);
        wifi.setPadding(0, dp(6), 0, dp(14));
        left.addView(wifi);

        status = text("", 12, TEXT, false);
        status.setMaxLines(4);
        status.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(16), 0, 0);
        TextView play = pill("Play / Pause", true);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicService s = MusicService.get();
                if (s != null) s.toggleFromScreen();
            }
        });
        TextView rescan = pill("Rescan USB", false);
        rescan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(MainActivity.this, MusicService.class)
                        .setAction(MusicService.ACTION_RESCAN));
            }
        });
        buttons.addView(play);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(10);
        buttons.addView(rescan, gap);
        left.addView(buttons);

        // ---- right: QR code
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(36);
        panel.addView(right, rp);

        FrameCard qrCard = new FrameCard(this);
        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrCard.addView(qr, new LinearLayout.LayoutParams(dp(128), dp(128)));
        right.addView(qrCard);
        TextView scan = text("Scan to open", 11, MUTED, false);
        scan.setGravity(Gravity.CENTER);
        scan.setPadding(0, dp(8), 0, 0);
        right.addView(scan);

        setContentView(root);
        play.requestFocus();

        if (checkSelfPermission(audioPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{audioPermission()}, 1);
        } else {
            startMusicService(false);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        startMusicService(true);
    }

    private static String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void startMusicService(boolean rescan) {
        Intent i = new Intent(this, MusicService.class);
        if (rescan) i.setAction(MusicService.ACTION_RESCAN);
        startForegroundService(i);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    // ---- small view helpers ---------------------------------------------------------------

    /** White rounded card that holds the QR code. */
    private final class FrameCard extends LinearLayout {
        FrameCard(android.content.Context c) {
            super(c);
            setGravity(Gravity.CENTER);
            setBackground(round(Color.WHITE, 14, 0, 0));
            setPadding(dp(10), dp(10), dp(10), dp(10));
        }
    }

    private Bitmap makeQr(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
            int scale = Math.max(1, sizePx / m.getWidth());
            int w = m.getWidth() * scale, h = m.getHeight() * scale;
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = m.get(x / scale, y / scale) ? BG : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /** A rounded button that shows a bright outline when the TV remote focuses it. */
    private TextView pill(String label, boolean primary) {
        TextView t = text(label, 13, primary ? BG : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused},
                round(primary ? TEAL : BG, 20, TEXT, 2));
        bg.addState(new int[]{android.R.attr.state_pressed},
                round(primary ? 0xFF1FB3A1 : LINE, 20, 0, 0));
        bg.addState(new int[]{}, primary ? round(TEAL, 20, 0, 0) : round(BG, 20, LINE, 1f));
        t.setBackground(bg);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
