package com.sagar.usbmusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Plays the USB music on the TV and serves the phone remote. All player state is touched
 * on the main thread only; HTTP threads reach it through {@link #onMain}.
 */
public class MusicService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    static final String ACTION_RESCAN = "com.sagar.usbmusic.RESCAN";
    private static final String CHANNEL = "usbmusic";
    private static final int NOTIF_ID = 1;
    private static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;

    private static volatile MusicService instance;

    static MusicService get() {
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private List<Track> library = new ArrayList<>();
    private int version;
    private boolean scanning;
    private boolean scannedOnce;
    private String scanLog = "";

    private final List<Track> queue = new ArrayList<>();
    /** What the queue was built from: "all", "folder", "album" or "artist" (relative to scopeRef). */
    private String scopeKind = "all";
    private Track scopeRef;
    /** Folder path for the "tree" scope: that folder and everything below it. */
    private String scopePath = "";
    private int qpos = -1;
    private Track current;

    private MediaPlayer mp;
    private boolean prepared;
    private boolean wantPlay;
    private boolean pausedByFocus;
    private boolean shuffle;
    private int repeat = REPEAT_OFF;
    private int errorStreak;
    private String lastError = "";

    private AudioManager audio;
    private AudioFocusRequest focusReq;
    private NotificationManager notifications;
    private WebServer server;
    private CoverFetcher covers;
    private volatile int artVersion;
    private long lastArtBump;
    private WifiManager.WifiLock wifiLock;

    private final BroadcastReceiver storageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean mounted = Intent.ACTION_MEDIA_MOUNTED.equals(i.getAction());
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    rescan();
                }
            }, mounted ? 3000 : 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "usbmusic");
            wifiLock.acquire();
        } catch (Exception ignored) {
        }

        covers = new CoverFetcher(this, this);
        server = new WebServer(this, this);
        if (!server.start()) lastError = "Could not open a network port";

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addDataScheme("file");
        registerReceiver(storageReceiver, f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_RESCAN.equals(intent == null ? null : intent.getAction()) || !scannedOnce) {
            rescan();
        }
        updateNotification();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        instance = null;
        try {
            unregisterReceiver(storageReceiver);
        } catch (Exception ignored) {
        }
        if (server != null) server.stop();
        releasePlayer();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    // ---- main-thread plumbing -------------------------------------------------------------

    <T> T onMain(Callable<T> c) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
            FutureTask<T> task = new FutureTask<>(c);
            main.post(task);
            return task.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- library --------------------------------------------------------------------------

    void rescan() {
        if (scanning) return;
        scanning = true;
        scannedOnce = true;
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder log = new StringBuilder();
                final List<Track> found = LibraryScanner.scan(MusicService.this, log);
                final String logText = log.toString();
                main.post(new Runnable() {
                    @Override public void run() {
                        applyLibrary(found, logText);
                    }
                });
            }
        }, "usbmusic-scan").start();
    }

    private void applyLibrary(List<Track> found, String logText) {
        library = found;
        if (covers.isAuto()) covers.start(new ArrayList<>(found), -1);
        scanLog = logText;
        version++;
        scanning = false;

        Map<String, Track> bySource = new HashMap<>();
        for (Track t : found) bySource.put(t.source, t);
        List<Track> kept = new ArrayList<>();
        for (Track t : queue) {
            Track n = bySource.get(t.source);
            if (n != null) kept.add(n);
        }
        queue.clear();
        queue.addAll(kept);
        if (current != null) {
            Track n = bySource.get(current.source);
            if (n == null) {
                releasePlayer();
                current = null;
                wantPlay = false;
                qpos = -1;
                queue.clear();
            } else {
                current = n;
                qpos = queue.indexOf(n);
            }
        }
        updateNotification();
    }

    // ---- playback -------------------------------------------------------------------------

    private void playId(int id, String scope, String path) {
        if (id < 0 || id >= library.size()) return;
        Track t = library.get(id);
        scopeKind = scope == null ? "folder" : scope;
        scopePath = path == null ? "" : path;
        scopeRef = t;
        buildQueue(t);
        startTrack(t);
    }

    /** Rebuilds the queue for the current scope, with {@code first} as the current entry. */
    private void buildQueue(Track first) {
        queue.clear();
        for (Track x : library) {
            if (inScope(x)) queue.add(x);
        }
        if (shuffle) {
            Collections.shuffle(queue);
            queue.remove(first);
            queue.add(0, first);
        }
        qpos = queue.indexOf(first);
    }

    private boolean inScope(Track x) {
        Track r = scopeRef;
        if (r == null || scopeKind.equals("all")) return true;
        switch (scopeKind) {
            case "album":
                if (!r.album.isEmpty()) return x.album.equals(r.album) && x.folder.equals(r.folder);
                break;
            case "tree":
                if (!scopePath.isEmpty()) {
                    return x.folder.equals(scopePath) || x.folder.startsWith(scopePath + "/");
                }
                break;
            case "artist":
                if (!r.primaryArtist().isEmpty()) return x.primaryArtist().equals(r.primaryArtist());
                break;
            default:
                break;
        }
        return x.folder.equals(r.folder);
    }

    /** The track with this library index, or null. Safe to call from any thread. */
    Track trackAt(final int id) {
        return onMain(new Callable<Track>() {
            @Override public Track call() {
                return id >= 0 && id < library.size() ? library.get(id) : null;
            }
        });
    }

    private void startTrack(Track t) {
        releasePlayer();
        current = t;
        prepared = false;
        wantPlay = true;
        pausedByFocus = false;
        requestFocus();
        try {
            MediaPlayer m = new MediaPlayer();
            mp = m;
            m.setAudioAttributes(attributes());
            m.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            m.setOnPreparedListener(this);
            m.setOnCompletionListener(this);
            m.setOnErrorListener(this);
            m.setDataSource(this, t.uri());
            m.prepareAsync();
        } catch (Exception e) {
            failCurrent(e.toString());
        }
        updateNotification();
    }

    private void releasePlayer() {
        if (mp != null) {
            MediaPlayer m = mp;
            mp = null;
            prepared = false;
            m.setOnPreparedListener(null);
            m.setOnCompletionListener(null);
            m.setOnErrorListener(null);
            try {
                m.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void failCurrent(String why) {
        errorStreak++;
        lastError = "Cannot play " + (current == null ? "track" : current.title) + " (" + why + ")";
        if (errorStreak < 5 && queue.size() > 1) {
            advance(true);
        } else {
            releasePlayer();
            wantPlay = false;
            updateNotification();
        }
    }

    @Override public void onPrepared(MediaPlayer m) {
        if (m != mp) return;
        prepared = true;
        errorStreak = 0;
        if (wantPlay) {
            m.start();
        }
        updateNotification();
    }

    @Override public void onCompletion(MediaPlayer m) {
        if (m != mp) return;
        if (repeat == REPEAT_ONE) {
            m.seekTo(0);
            m.start();
        } else {
            advance(true);
        }
    }

    @Override public boolean onError(MediaPlayer m, int what, int extra) {
        if (m != mp) return true;
        prepared = false;
        failCurrent("error " + what + "/" + extra);
        return true;
    }

    private void advance(boolean auto) {
        if (queue.isEmpty()) return;
        int n = qpos + 1;
        if (n >= queue.size()) {
            if (repeat == REPEAT_ALL || !auto) {
                if (shuffle && current != null) {
                    Track last = queue.get(queue.size() - 1);
                    Collections.shuffle(queue);
                    if (queue.size() > 1 && queue.get(0) == last) {
                        Collections.swap(queue, 0, queue.size() - 1);
                    }
                }
                n = 0;
            } else {
                // end of the queue: stay on the last track, stopped
                wantPlay = false;
                if (prepared && mp != null) {
                    try {
                        mp.seekTo(0);
                    } catch (Exception ignored) {
                    }
                }
                updateNotification();
                return;
            }
        }
        qpos = n;
        startTrack(queue.get(n));
    }

    private void togglePlay() {
        if (current == null) {
            if (!library.isEmpty()) {
                int start = shuffle ? (int) (Math.random() * library.size()) : 0;
                playId(start, "all", null);
            }
            return;
        }
        if (!prepared || mp == null) {
            wantPlay = !wantPlay;
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                wantPlay = false;
                pausedByFocus = false;
            } else {
                requestFocus();
                mp.start();
                wantPlay = true;
            }
        } catch (Exception e) {
            failCurrent(e.toString());
        }
        updateNotification();
    }

    private void previous() {
        if (current == null) return;
        try {
            if (prepared && mp != null && mp.getCurrentPosition() > 3000) {
                mp.seekTo(0);
                return;
            }
        } catch (Exception ignored) {
        }
        int p = qpos - 1;
        if (p < 0) p = repeat == REPEAT_ALL ? queue.size() - 1 : 0;
        if (p < 0 || queue.isEmpty()) return;
        qpos = p;
        startTrack(queue.get(p));
    }

    private void seek(int ms) {
        if (!prepared || mp == null) return;
        try {
            int dur = mp.getDuration();
            mp.seekTo(Math.max(0, dur > 0 ? Math.min(ms, dur) : ms));
        } catch (Exception ignored) {
        }
    }

    private void setShuffle(boolean on) {
        shuffle = on;
        if (current != null && !queue.isEmpty()) buildQueue(current);
    }

    private void setRepeat(String mode) {
        if ("all".equals(mode)) repeat = REPEAT_ALL;
        else if ("one".equals(mode)) repeat = REPEAT_ONE;
        else repeat = REPEAT_OFF;
    }

    private void setVolume(int v) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, v)),
                AudioManager.FLAG_SHOW_UI);
    }

    private AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private void requestFocus() {
        if (focusReq == null) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes())
                    .setOnAudioFocusChangeListener(this, main)
                    .build();
        }
        audio.requestAudioFocus(focusReq);
    }

    @Override public void onAudioFocusChange(int change) {
        if (mp == null || !prepared) return;
        try {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                if (mp.isPlaying()) mp.pause();
                wantPlay = false;
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (mp.isPlaying()) {
                    mp.pause();
                    pausedByFocus = true;
                }
            } else if (change == AudioManager.AUDIOFOCUS_GAIN && pausedByFocus) {
                pausedByFocus = false;
                mp.start();
            }
        } catch (Exception ignored) {
        }
        updateNotification();
    }

    // ---- state for the UI -----------------------------------------------------------------

    private boolean isPlaying() {
        try {
            return prepared && mp != null && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    JSONObject state() throws JSONException {
        JSONObject o = new JSONObject();
        int pos = 0, dur = 0;
        try {
            if (prepared && mp != null) {
                pos = mp.getCurrentPosition();
                dur = mp.getDuration();
            }
        } catch (Exception ignored) {
        }
        o.put("playing", isPlaying());
        o.put("loading", current != null && !prepared && wantPlay);
        o.put("id", current == null ? -1 : library.indexOf(current));
        o.put("title", current == null ? "" : current.title);
        o.put("folder", current == null ? "" : current.folder);
        o.put("artist", current == null ? "" : current.artist);
        o.put("album", current == null ? "" : current.album);
        o.put("pos", pos);
        o.put("dur", dur);
        o.put("shuffle", shuffle);
        o.put("repeat", repeat == REPEAT_ALL ? "all" : repeat == REPEAT_ONE ? "one" : "off");
        o.put("vol", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        o.put("maxVol", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        o.put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));
        o.put("count", library.size());
        o.put("ver", version);
        o.put("art", artVersion);
        o.put("scanning", scanning);
        o.put("error", lastError);
        return o;
    }

    private JSONObject libraryJson() throws JSONException {
        JSONArray tracks = new JSONArray();
        for (Track t : library) {
            tracks.put(new JSONArray().put(t.title).put(t.folder).put(t.artist).put(t.album).put(t.durationMs));
        }
        return new JSONObject().put("ver", version).put("tracks", tracks);
    }

    /** Handles /api/<command>. Returns null for an unknown command. */
    JSONObject api(final String cmd, final Map<String, String> q) {
        return onMain(new Callable<JSONObject>() {
            @Override public JSONObject call() throws Exception {
                switch (cmd) {
                    case "state":
                        break;
                    case "library":
                        return libraryJson();
                    case "covers":
                        return covers.command(q.get("cmd"), q, new ArrayList<>(library));
                    case "debug":
                        return new JSONObject().put("log", scanLog)
                                .put("ip", WebServer.localIp()).put("port", port());
                    case "play":
                        lastError = "";
                        playId(intArg(q, "id", -1), q.get("scope"), q.get("path"));
                        break;
                    case "toggle":
                        togglePlay();
                        break;
                    case "next":
                        advance(false);
                        break;
                    case "prev":
                        previous();
                        break;
                    case "seek":
                        seek(intArg(q, "ms", 0));
                        break;
                    case "shuffle":
                        setShuffle("1".equals(q.get("on")));
                        break;
                    case "repeat":
                        setRepeat(q.get("mode"));
                        break;
                    case "volume":
                        setVolume(intArg(q, "v", audio.getStreamVolume(AudioManager.STREAM_MUSIC)));
                        break;
                    case "rescan":
                        rescan();
                        break;
                    default:
                        return null;
                }
                return state();
            }
        });
    }

    private static int intArg(Map<String, String> q, String key, int def) {
        try {
            return Integer.parseInt(q.get(key));
        } catch (Exception e) {
            return def;
        }
    }

    // ---- cover art ------------------------------------------------------------------------

    /** The phone asked for a cover that does not exist yet: look this folder up first. */
    void prioritizeCover(Track t) {
        if (covers != null) covers.prioritize(t);
    }

    /** A cover file was written. Tells the phone page to look again, at most every few seconds. */
    void onCoverAdded() {
        main.post(new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                if (now - lastArtBump > 4000) {
                    lastArtBump = now;
                    artVersion++;
                }
            }
        });
    }

    void onCoversFinished() {
        main.post(new Runnable() {
            @Override public void run() {
                artVersion++;
                updateNotification();
            }
        });
    }

    // ---- info for the on-TV screen --------------------------------------------------------

    int port() {
        return server == null ? -1 : server.port();
    }

    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(scanning ? "Scanning USB…\n" : library.size() + " songs found\n");
        String cl = covers == null ? "" : covers.line();
        if (!cl.isEmpty()) sb.append(cl).append('\n');
        if (current != null) {
            sb.append(isPlaying() ? "Playing: " : "Paused: ").append(current.title).append('\n');
        }
        if (!lastError.isEmpty()) sb.append(lastError).append('\n');
        if (library.isEmpty() && !scanning && scannedOnce) {
            sb.append("\nScan log:\n").append(scanLog);
        }
        return sb.toString();
    }

    void toggleFromScreen() {
        togglePlay();
    }

    // ---- notification ---------------------------------------------------------------------

    private Notification buildNotification() {
        String text = current != null
                ? (isPlaying() ? "Playing: " : "Paused: ") + current.title
                : "Open http://" + WebServer.localIp() + ":" + port() + " on your phone";
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("USB Music")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        notifications.notify(NOTIF_ID, buildNotification());
    }
}
