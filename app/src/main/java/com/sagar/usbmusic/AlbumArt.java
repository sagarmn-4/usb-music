package com.sagar.usbmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;

/** Pulls the embedded cover picture out of a song and shrinks it for the phone page. */
final class AlbumArt {
    private static final int MAX_SIDE = 512;
    private static final byte[] NONE = new byte[0];
    private static final LruCache<String, byte[]> CACHE = new LruCache<>(64);

    private AlbumArt() {}

    /** JPEG bytes, or null when the song has no embedded picture. */
    static byte[] get(Context ctx, Track t) {
        String key = t.album.isEmpty() ? t.source : t.folder + "|" + t.album;
        byte[] hit = CACHE.get(key);
        if (hit == null) {
            hit = load(ctx, t);
            CACHE.put(key, hit == null ? NONE : hit);
        }
        return hit == null || hit.length == 0 ? null : hit;
    }

    private static byte[] load(Context ctx, Track t) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (t.isFile) r.setDataSource(t.source);
            else r.setDataSource(ctx, t.uri());
            byte[] raw = r.getEmbeddedPicture();
            if (raw == null) return null;

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
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
