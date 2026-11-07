package com.sagar.usbmusic;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Looks up a cover online for each folder that has none and saves it as folder.jpg next to the
 * songs. It never touches the songs or their tags, never overwrites an existing cover file, and
 * keeps a list of the files it created so they can be removed again.
 */
final class CoverFetcher {
    /** The search service allows roughly 20 requests a minute. */
    private static final long GAP_MS = 3000;
    private static final String SEARCH =
            "https://itunes.apple.com/search?media=music&entity=album&limit=5&country=IN&term=";
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "the", "and", "hits", "songs", "best", "film", "movie", "original", "soundtrack",
            "music", "vol", "from", "with", "for"));

    private static final class Sample {
        final String dir;
        final String name;
        final Track track;
        final String artist;

        Sample(String dir, String name, Track track, String artist) {
            this.dir = dir;
            this.name = name;
            this.track = track;
            this.artist = artist;
        }
    }

    private static final class Stop extends Exception {
        Stop(String why) {
            super(why);
        }
    }

    private static final class RateLimited extends Exception {
    }

    private final Context ctx;
    private final MusicService svc;
    private final Object lock = new Object();
    private final ArrayDeque<Sample> queue = new ArrayDeque<>();
    private final Map<String, Sample> pending = new HashMap<>();
    private final Set<String> misses = new HashSet<>();
    private final File manifest;
    private final File missFile;
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger found = new AtomicInteger();
    private final AtomicInteger missed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final List<String> recent = new ArrayList<>();
    private volatile boolean running;
    private volatile String last = "";
    private int triesLeft = -1;
    private int writeFailures;

    CoverFetcher(Context ctx, MusicService svc) {
        this.ctx = ctx;
        this.svc = svc;
        manifest = new File(ctx.getFilesDir(), "covers-created.txt");
        missFile = new File(ctx.getFilesDir(), "covers-miss.txt");
        for (String line : readLines(missFile)) misses.add(line);
    }

    boolean isRunning() {
        return running;
    }

    boolean isAuto() {
        return prefs().getBoolean("covers_auto", false);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("usbmusic", Context.MODE_PRIVATE);
    }

    /** Short line for the TV screen; empty when there is nothing to say. */
    String line() {
        if (running) {
            return "Cover art: " + done.get() + " of " + total.get() + " folders, "
                    + found.get() + " found";
        }
        return found.get() > 0 ? "Cover art: " + found.get() + " found" : "";
    }

    /** Starts a run over the library. {@code max} limits how many online lookups to make, -1 = all. */
    void start(List<Track> library, int max) {
        synchronized (lock) {
            if (running) return;
            Map<String, Map<String, Integer>> artists = new HashMap<>();
            Map<String, Track> first = new LinkedHashMap<>();
            for (Track t : library) {
                if (t.dir == null) continue;
                if (!first.containsKey(t.dir)) first.put(t.dir, t);
                String a = t.primaryArtist();
                if (a.isEmpty()) continue;
                Map<String, Integer> m = artists.get(t.dir);
                if (m == null) {
                    m = new HashMap<>();
                    artists.put(t.dir, m);
                }
                Integer n = m.get(a);
                m.put(a, n == null ? 1 : n + 1);
            }
            queue.clear();
            pending.clear();
            for (Map.Entry<String, Track> e : first.entrySet()) {
                String dir = e.getKey();
                if (misses.contains(dir)) continue;
                String best = "";
                int bestN = 0;
                Map<String, Integer> m = artists.get(dir);
                if (m != null) {
                    for (Map.Entry<String, Integer> a : m.entrySet()) {
                        if (a.getValue() > bestN) {
                            best = a.getKey();
                            bestN = a.getValue();
                        }
                    }
                }
                Sample s = new Sample(dir, new File(dir).getName(), e.getValue(), best);
                queue.add(s);
                pending.put(dir, s);
            }
            total.set(queue.size());
            done.set(0);
            found.set(0);
            missed.set(0);
            failed.set(0);
            recent.clear();
            last = "";
            triesLeft = max;
            writeFailures = 0;
            running = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                work();
            }
        }, "usbmusic-covers");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
    }

    /** Move this folder to the front of the queue, e.g. because the phone is showing it. */
    void prioritize(Track t) {
        if (!running || t.dir == null) return;
        synchronized (lock) {
            Sample s = pending.get(t.dir);
            if (s != null) {
                queue.remove(s);
                queue.addFirst(s);
            }
        }
    }

    private void work() {
        int rateRetries = 0;
        try {
            while (running) {
                Sample s;
                synchronized (lock) {
                    s = queue.pollFirst();
                    if (s != null) pending.remove(s.dir);
                }
                if (s == null) break;
                boolean network;
                try {
                    network = process(s);
                } catch (RateLimited e) {
                    if (++rateRetries > 5) {
                        last = "the lookup service is limiting requests; try again later";
                        break;
                    }
                    synchronized (lock) {
                        queue.addFirst(s);
                        pending.put(s.dir, s);
                    }
                    last = "waiting a minute, the lookup service asked to slow down";
                    sleep(60000);
                    continue;
                } catch (Stop e) {
                    last = e.getMessage();
                    break;
                } catch (Exception e) {
                    failed.incrementAndGet();
                    last = String.valueOf(e);
                    network = true;
                }
                done.incrementAndGet();
                if (network) {
                    if (triesLeft > 0 && --triesLeft == 0) {
                        last = "test finished";
                        break;
                    }
                    sleep(GAP_MS);
                }
            }
        } finally {
            running = false;
            svc.onCoversFinished();
        }
    }

    /** Returns true when an online lookup was made. */
    private boolean process(Sample s) throws Stop, RateLimited {
        if (AlbumArt.hasSidecar(s.dir)) return false;
        if (AlbumArt.hasEmbedded(ctx, s.track)) return false;
        String query = cleanName(s.name);
        if (query.length() < 3) {
            markMiss(s.dir);
            return false;
        }
        byte[] jpg = lookup(s.artist.isEmpty() ? query : query + " " + s.artist, query);
        if (jpg == null && !s.artist.isEmpty()) {
            sleep(GAP_MS);
            jpg = lookup(query, query);
        }
        if (jpg == null) {
            markMiss(s.dir);
            return true;
        }
        File out = new File(s.dir, "folder.jpg");
        if (out.exists()) return true;
        File tmp = new File(s.dir, ".usbmusic-cover.tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            f.write(jpg);
        } catch (IOException e) {
            tmp.delete();
            failed.incrementAndGet();
            last = "cannot write to " + s.dir;
            if (++writeFailures >= 3) throw new Stop("no write access to the drive");
            return true;
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            failed.incrementAndGet();
            last = "cannot save into " + s.dir;
            if (++writeFailures >= 3) throw new Stop("no write access to the drive");
            return true;
        }
        writeFailures = 0;
        append(manifest, out.getAbsolutePath());
        found.incrementAndGet();
        synchronized (lock) {
            recent.add(out.getAbsolutePath());
            if (recent.size() > 8) recent.remove(0);
        }
        AlbumArt.clearCache();
        svc.onCoverAdded();
        return true;
    }

    private void markMiss(String dir) {
        missed.incrementAndGet();
        synchronized (lock) {
            if (misses.add(dir)) append(missFile, dir);
        }
    }

    // ---- online lookup -------------------------------------------------------------------

    private byte[] lookup(String term, String query) throws Stop, RateLimited {
        try {
            String body = new String(http(SEARCH + URLEncoder.encode(term, "UTF-8")), "UTF-8");
            JSONArray results = new JSONObject(body).optJSONArray("results");
            if (results == null) return null;
            Set<String> tokens = tokens(query);
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String name = r.optString("collectionName", "").toLowerCase(Locale.ROOT);
                for (String tok : tokens) {
                    if (!name.contains(tok)) continue;
                    String art = r.optString("artworkUrl100", "");
                    if (art.isEmpty()) break;
                    byte[] img = http(art.replace("100x100bb", "600x600bb"));
                    boolean jpeg = img.length > 2000 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8;
                    return jpeg ? img : null;
                }
            }
            return null;
        } catch (UnknownHostException e) {
            throw new Stop("no internet");
        } catch (JSONException e) {
            return null;
        } catch (IOException e) {
            last = String.valueOf(e);
            return null;
        }
    }

    private byte[] http(String address) throws IOException, RateLimited {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "USBMusic/1.1 (personal music player)");
        try {
            int code = c.getResponseCode();
            if (code == 403 || code == 429 || code == 503) throw new RateLimited();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** A folder name made searchable: "04 Dum_pukht [FLAC]" becomes "Dum pukht". */
    static String cleanName(String folder) {
        String s = folder.replace('_', ' ');
        s = s.replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", " ");
        s = s.replaceAll("(?i)\\b(flac|mp3|ost|tag|\\d{3}\\s?kbps)\\b", " ");
        s = s.replaceAll("^\\s*\\d{1,3}\\s*[-.)]?\\s+(?=\\p{L})", "");
        s = s.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
        s = s.replaceAll("[^\\p{L}\\p{N}&' ]+", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    private static Set<String> tokens(String query) {
        Set<String> out = new HashSet<>();
        for (String w : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) out.add(w);
        }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---- control API ----------------------------------------------------------------------

    JSONObject command(String cmd, Map<String, String> q, List<Track> library) throws JSONException {
        String c = cmd == null ? "status" : cmd;
        JSONObject extra = new JSONObject();
        switch (c) {
            case "test":
                start(library, 5);
                break;
            case "start":
                start(library, -1);
                break;
            case "stop":
                stop();
                break;
            case "auto":
                prefs().edit().putBoolean("covers_auto", "1".equals(q.get("on"))).apply();
                break;
            case "undo":
                extra.put("removed", undo());
                break;
            case "probe":
                for (Track t : library) {
                    if (t.dir != null) {
                        extra.put("dir", t.dir).put("writable", new File(t.dir).canWrite());
                        break;
                    }
                }
                break;
            default:
                break;
        }
        JSONObject o = new JSONObject()
                .put("running", running).put("auto", isAuto())
                .put("total", total.get()).put("done", done.get()).put("found", found.get())
                .put("missed", missed.get()).put("failed", failed.get()).put("last", last);
        synchronized (lock) {
            o.put("recent", new JSONArray(recent));
        }
        java.util.Iterator<String> keys = extra.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            o.put(k, extra.get(k));
        }
        return o;
    }

    /** Deletes the folder.jpg files this class created, and only those. */
    private int undo() {
        int n = 0;
        for (String path : readLines(manifest)) {
            File f = new File(path);
            if (f.getName().equals("folder.jpg") && f.isFile() && f.delete()) n++;
        }
        manifest.delete();
        AlbumArt.clearCache();
        svc.onCoverAdded();
        return n;
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) out.add(line);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void append(File f, String line) {
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line + "\n");
        } catch (IOException ignored) {
        }
    }
}
