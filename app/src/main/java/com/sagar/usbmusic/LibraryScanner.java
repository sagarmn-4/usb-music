package com.sagar.usbmusic;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
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
        File[] volumes = new File("/storage").listFiles();
        if (volumes == null) {
            log.append("/storage cannot be listed\n");
        } else {
            for (File v : volumes) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self") || !v.isDirectory()) continue;
                int before = out.size();
                walk(v, v, 0, out);
                log.append("volume ").append(n).append(": ").append(out.size() - before)
                        .append(" tracks, readable=").append(v.canRead()).append('\n');
            }
        }
        if (out.isEmpty()) {
            log.append("file scan found nothing, trying MediaStore\n");
            queryMediaStore(ctx, out, log);
        }
        Collections.sort(out, new Comparator<Track>() {
            @Override public int compare(Track a, Track b) {
                int c = natural(a.folder, b.folder);
                return c != 0 ? c : natural(a.title, b.title);
            }
        });
        log.append("total ").append(out.size()).append(" tracks\n");
        return out;
    }

    private static void walk(File root, File dir, int depth, List<Track> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_TRACKS) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH && !SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                    walk(root, f, depth + 1, out);
                }
                continue;
            }
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            if (!EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) continue;
            String rel = dir.getAbsolutePath().substring(root.getAbsolutePath().length());
            while (rel.startsWith("/")) rel = rel.substring(1);
            out.add(new Track(name.substring(0, dot), rel.isEmpty() ? "(top folder)" : rel,
                    f.getAbsolutePath(), true));
        }
    }

    private static void queryMediaStore(Context ctx, List<Track> out, StringBuilder log) {
        try {
            for (String vol : MediaStore.getExternalVolumeNames(ctx)) {
                if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(vol)) continue;
                Uri base = MediaStore.Audio.Media.getContentUri(vol);
                String[] proj = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.RELATIVE_PATH};
                int before = out.size();
                try (Cursor c = ctx.getContentResolver().query(base, proj, null, null, null)) {
                    if (c == null) continue;
                    while (c.moveToNext() && out.size() < MAX_TRACKS) {
                        String display = c.getString(1);
                        String title = c.getString(2);
                        if (title == null || title.isEmpty()) {
                            title = display == null ? "Unknown" : display;
                            int dot = title.lastIndexOf('.');
                            if (dot > 0) title = title.substring(0, dot);
                        }
                        String rel = c.getString(3);
                        if (rel == null || rel.isEmpty() || rel.equals("/")) rel = "(top folder)";
                        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                        Uri uri = ContentUris.withAppendedId(base, c.getLong(0));
                        out.add(new Track(title, rel, uri.toString(), false));
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
