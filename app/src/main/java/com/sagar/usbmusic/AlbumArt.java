package com.sagar.usbmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Finds the cover for a song and shrinks it for the phone page: the picture embedded in the file
 * first, then a folder.jpg (or similar) in the song's folder.
 */
final class AlbumArt {
    private static final int MAX_SIDE = 512;
    private static final long MAX_SIDECAR_BYTES = 8L * 1024 * 1024;
    private static final byte[] NONE = new byte[0];
    private static final LruCache<String, byte[]> CACHE = new LruCache<>(64);
    private static final String[] SIDECARS = {
            "folder.jpg", "Folder.jpg", "cover.jpg", "Cover.jpg", "folder.png", "cover.png"};

    private AlbumArt() {}

    /** JPEG bytes, or null when neither the song nor its folder has a picture. */
    static byte[] get(Context ctx, Track t) {
        String key = t.album.isEmpty() ? t.source : t.folder + "|" + t.album;
        byte[] hit = CACHE.get(key);
        if (hit == null) {
            byte[] raw = embedded(ctx, t);
            if (raw == null) raw = sidecar(t.dir);
            hit = raw == null ? null : shrink(raw);
            CACHE.put(key, hit == null ? NONE : hit);
        }
        return hit == null || hit.length == 0 ? null : hit;
    }

    /** Forget what was found so far, e.g. after new folder.jpg files were written. */
    static void clearCache() {
        CACHE.evictAll();
    }

    static boolean hasEmbedded(Context ctx, Track t) {
        return embedded(ctx, t) != null;
    }

    /** True when the folder already has a cover file. */
    static boolean hasSidecar(String dir) {
        return sidecar(dir) != null;
    }

    private static byte[] embedded(Context ctx, Track t) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (t.isFile) r.setDataSource(t.source);
            else r.setDataSource(ctx, t.uri());
            return r.getEmbeddedPicture();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] sidecar(String dir) {
        if (dir == null) return null;
        for (String name : SIDECARS) {
            File f = new File(dir, name);
            if (!f.isFile() || f.length() == 0 || f.length() > MAX_SIDECAR_BYTES) continue;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int n = 0;
                while (n < buf.length) {
                    int got = in.read(buf, n, buf.length - n);
                    if (got < 0) break;
                    n += got;
                }
                if (n == buf.length) return buf;
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static byte[] shrink(byte[] raw) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (bmp == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            bmp.recycle();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
