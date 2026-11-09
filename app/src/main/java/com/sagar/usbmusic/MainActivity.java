package com.sagar.usbmusic;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The TV screen. {@link TvBrowse} draws it; this class starts the service, asks for the audio
 * permission and routes the remote's media keys to the player.
 */
public class MainActivity extends Activity {
    private TvBrowse browse;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        browse = new TvBrowse(this);
        setContentView(browse.view());

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
        browse.start();
    }

    @Override protected void onPause() {
        browse.stop();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (!browse.onBack()) super.onBackPressed();
    }

    /** Any key wakes the screen from ambient mode (that key is then not used for anything else). */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (browse.onActivity(event.getKeyCode())) return true;
            if (browse.onNavKey(event.getKeyCode())) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Media keys work on every screen, whatever has the focus. */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && event.getRepeatCount() == 0 && browse.onMenuKey()) {
            return true;
        }
        if (event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    browse.seekBy(10000);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    browse.seekBy(-10000);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    browse.command("toggle");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (!browse.isPlaying()) browse.command("toggle");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (browse.isPlaying()) browse.command("toggle");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    browse.command("next");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    browse.command("prev");
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
