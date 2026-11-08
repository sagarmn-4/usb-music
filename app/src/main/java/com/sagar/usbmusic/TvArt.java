package com.sagar.usbmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads cover pictures for the TV screen off the main thread and keeps a small cache. */
final class TvArt {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(48 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount() / 1024;
        }
    };
    /** Keys known to have no picture; cleared when covers may have changed. */
    private static final Set<String> NONE = Collections.synchronizedSet(new HashSet<String>());

    private TvArt() {}

    static String key(Track t) {
        return t.album.isEmpty() ? t.source : t.folder + "|" + t.album;
    }

    /** Forget "no picture" answers and cached pictures, e.g. after new covers were written. */
    static void reset() {
        NONE.clear();
        CACHE.evictAll();
    }

    /** Shows the song's cover on the tile; a coloured placeholder first, or when there is none. */
    static void load(Context ctx, final TvUi.ArtView v, final Track t, int iconRes) {
        final String key = key(t);
        v.setTag(key);
        v.showIcon(TvUi.hash(key), iconRes, false);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            v.showBitmap(hit);
            return;
        }
        if (NONE.contains(key)) return;
        final Context app = ctx.getApplicationContext();
        final int want = v.size();
        POOL.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                try {
                    byte[] raw = AlbumArt.get(app, t);
                    if (raw != null) bmp = decode(raw, want);
                } catch (Exception ignored) {
                }
                final Bitmap result = bmp;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (result == null) {
                            NONE.add(key);
                            return;
                        }
                        CACHE.put(key, result);
                        if (key.equals(v.getTag())) v.showBitmap(result);
                    }
                });
            }
        });
    }

    private static Bitmap decode(byte[] raw, int want) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= want) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }
}
