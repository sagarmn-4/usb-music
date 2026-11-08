package com.sagar.usbmusic;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Finds music on removable (USB) volumes. */
final class LibraryScanner {
    private static final Set<String> EXT = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "mid", "amr"));
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            "android", "lost.dir", "system volume information", "$recycle.bin"));
    private static final int MAX_DEPTH = 12;
    private static final int MAX_TRACKS = 20000;

    private LibraryScanner() {}

    static List<Track> scan(Context ctx, StringBuilder log) {
        List<Track> out = new ArrayList<>();
        queryMediaStore(ctx, out, log);
        if (out.isEmpty()) {
            log.append("MediaStore found nothing, trying a file scan\n");
            scanFiles(out, log);
        }
        Collections.sort(out, new Comparator<Track>() {
            @Override public int compare(Track a, Track b) {
                int c = natural(a.folder, b.folder);
                return c != 0 ? c : natural(a.sortName, b.sortName);
            }
        });
        log.append("total ").append(out.size()).append(" tracks\n");
        return out;
    }

    private static void scanFiles(List<Track> out, StringBuilder log) {
        File[] volumes = new File("/storage").listFiles();
        if (volumes == null) {
            log.append("/storage cannot be listed\n");
            return;
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            for (File v : volumes) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self") || !v.isDirectory()) continue;
                int before = out.size();
                walk(mmr, v, v, 0, out);
                log.append("volume ").append(n).append(": ").append(out.size() - before)
                        .append(" tracks, readable=").append(v.canRead()).append('\n');
            }
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static void walk(MediaMetadataRetriever mmr, File root, File dir, int depth,
                             List<Track> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_TRACKS) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH && !SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                    walk(mmr, root, f, depth + 1, out);
                }
                continue;
            }
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            if (!EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) continue;
            String rel = dir.getAbsolutePath().substring(root.getAbsolutePath().length());
            while (rel.startsWith("/")) rel = rel.substring(1);
            String base = name.substring(0, dot);
            String[] tags = readTags(mmr, f.getAbsolutePath());
            out.add(new Track(tags[0].isEmpty() ? base : tags[0],
                    rel.isEmpty() ? "(top folder)" : rel,
                    f.getAbsolutePath(), true, tags[1], tags[2], base, toInt(tags[3]), dir.getAbsolutePath(), ""));
        }
    }

    /** True when any folder in a relative path is one the file scan would skip. */
    private static boolean inSkippedDir(String rel) {
        for (String part : rel.split("/")) {
            if (SKIP_DIRS.contains(part.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Returns {title, artist, album, durationMs}; any of them may be empty. */
    private static String[] readTags(MediaMetadataRetriever mmr, String path) {
        String[] r = {"", "", "", ""};
        try {
            mmr.setDataSource(path);
            r[0] = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            String artist = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (artist.isEmpty()) {
                artist = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            }
            r[1] = artist;
            r[2] = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            r[3] = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception ignored) {
        }
        return r;
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.equals("<unknown>") ? "" : s;
    }

    private static void queryMediaStore(Context ctx, List<Track> out, StringBuilder log) {
        final boolean canCheckFiles = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
        try {
            for (String vol : MediaStore.getExternalVolumeNames(ctx)) {
                if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(vol)) continue;
                Uri base = MediaStore.Audio.Media.getContentUri(vol);
                String[] proj = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.COMPOSER};
                int before = out.size();
                try (Cursor c = ctx.getContentResolver().query(base, proj, null, null, null)) {
                    if (c == null) continue;
                    while (c.moveToNext() && out.size() < MAX_TRACKS) {
                        String display = c.getString(1);
                        String title = c.getString(2);
                        String fileBase = display == null ? "Unknown" : display;
                        int dot = fileBase.lastIndexOf('.');
                        if (dot > 0) fileBase = fileBase.substring(0, dot);
                        if (title == null || title.isEmpty()) title = fileBase;
                        String rel = c.getString(3);
                        if (rel == null || rel.isEmpty() || rel.equals("/")) rel = "(top folder)";
                        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                        if (inSkippedDir(rel)) continue;
                        String data = c.getString(7);
                        // The media database keeps rows for files that were renamed or deleted while the
                        // drive was elsewhere; with file access we can tell and leave them out.
                        if (canCheckFiles && data != null && !new File(data).exists()) continue;
                        Uri uri = ContentUris.withAppendedId(base, c.getLong(0));
                        String artist = clean(c.getString(4));
                        String composer = clean(c.getString(8));
                        // Android's scanner does not read the tag block inside WAV files; do it here.
                        if ((artist.isEmpty() || composer.isEmpty()) && data != null
                                && data.toLowerCase(Locale.ROOT).endsWith(".wav")) {
                            String[] w = WavTags.read(data);
                            if (artist.isEmpty()) artist = w[0];
                            if (composer.isEmpty()) composer = w[1];
                        }
                        out.add(new Track(title, rel, uri.toString(), false,
                                artist, clean(c.getString(5)), fileBase, c.getInt(6),
                                data == null ? null : new File(data).getParent(), composer));
                    }
                }
                log.append("MediaStore ").append(vol).append(": ").append(out.size() - before)
                        .append(" tracks\n");
            }
        } catch (Exception e) {
            log.append("MediaStore failed: ").append(e).append('\n');
        }
    }

    /** Case-insensitive compare where digit runs compare by value, so "2" sorts before "10". */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                char lx = Character.toLowerCase(x), ly = Character.toLowerCase(y);
                if (lx != ly) return lx - ly;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
