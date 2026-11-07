package com.sagar.usbmusic;

import android.net.Uri;

import java.io.File;

final class Track {
    final String title;
    final String folder;
    /** A file path, or a content:// URI when the track came from MediaStore. */
    final String source;
    final boolean isFile;
    final String artist;
    final String album;
    /** File-name based key, so tracks keep their on-disk order whatever the tags say. */
    final String sortName;
    final int durationMs;
    /** Folder the song sits in on the drive, when known. */
    final String dir;
    /** The composer or musician from the tags; empty when the file has none. */
    final String composer;

    Track(String title, String folder, String source, boolean isFile,
          String artist, String album, String sortName, int durationMs, String dir,
          String composer) {
        this.title = title;
        this.folder = folder;
        this.source = source;
        this.isFile = isFile;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.sortName = sortName;
        this.durationMs = durationMs;
        this.dir = dir;
        this.composer = composer == null ? "" : composer;
    }

    /** First credited artist, e.g. "A" for "A, B". Empty when the file has no artist tag. */
    String primaryArtist() {
        String[] parts = artist.split(",\\s|;");
        return parts.length == 0 ? "" : parts[0].trim();
    }

    Uri uri() {
        return isFile ? Uri.fromFile(new File(source)) : Uri.parse(source);
    }
}
