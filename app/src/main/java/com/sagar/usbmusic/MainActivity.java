package com.sagar.usbmusic;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shows the address to open on the phone and a little status. The music itself plays in the service. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView url;
    private TextView status;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            MusicService svc = MusicService.get();
            if (svc != null) {
                url.setText("http://" + WebServer.localIp() + ":" + svc.port());
                status.setText(svc.summary());
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#111418"));
        root.setPadding(pad, pad, pad, pad);

        root.addView(text("USB Music", 40, "#E8ECF1"));
        root.addView(text("Open this address in your phone's browser:", 20, "#8B96A5"));
        url = text("starting…", 44, "#4FC3F7");
        url.setPadding(0, dp(8), 0, dp(24));
        root.addView(url);
        status = text("", 22, "#E8ECF1");
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(32), 0, 0);
        buttons.setGravity(Gravity.START);
        Button play = new Button(this);
        play.setText("Play / Pause");
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicService s = MusicService.get();
                if (s != null) s.toggleFromScreen();
            }
        });
        Button rescan = new Button(this);
        rescan.setText("Rescan USB");
        rescan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(MainActivity.this, MusicService.class)
                        .setAction(MusicService.ACTION_RESCAN));
            }
        });
        buttons.addView(play);
        buttons.addView(rescan);
        root.addView(buttons);
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        } else {
            startMusicService(false);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        startMusicService(true);
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

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
