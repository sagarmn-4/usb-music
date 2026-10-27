package com.sagar.usbmusic;

import android.net.Uri;

import java.io.File;

final class Track {
    final String title;
    final String folder;
    /** A file path, or a content:// URI when the track came from MediaStore. */
    final String source;
    final boolean isFile;

    Track(String title, String folder, String source, boolean isFile) {
        this.title = title;
        this.folder = folder;
        this.source = source;
        this.isFile = isFile;
    }

    Uri uri() {
        return isFile ? Uri.fromFile(new File(source)) : Uri.parse(source);
    }
}
