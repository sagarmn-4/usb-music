package com.sagar.usbmusic;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reads the artist and composer from the ID3 block that tag editors put inside a WAV file. Android's
 * media scanner does not look there, so the scan asks this class when a WAV has no artist.
 */
final class WavTags {
    private WavTags() {}

    /** {artist, composer}; an empty string for anything the file does not have. */
    static String[] read(String path) {
        String[] r = {"", ""};
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            byte[] head = new byte[12];
            f.readFully(head);
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F') return r;
            long len = f.length();
            long pos = 12;
            byte[] hdr = new byte[8];
            while (pos + 8 <= len) {
                f.seek(pos);
                f.readFully(hdr);
                String id = new String(hdr, 0, 4, StandardCharsets.ISO_8859_1);
                long size = (hdr[4] & 0xFFL) | (hdr[5] & 0xFFL) << 8 | (hdr[6] & 0xFFL) << 16 | (hdr[7] & 0xFFL) << 24;
                if (id.equalsIgnoreCase("id3 ")) {
                    byte[] body = new byte[(int) Math.min(size, 1 << 20)];
                    f.readFully(body);
                    parse(body, r);
                    return r;
                }
                pos += 8 + size + (size & 1);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return r;
    }

    private static void parse(byte[] b, String[] out) {
        if (b.length < 10 || b[0] != 'I' || b[1] != 'D' || b[2] != '3') return;
        int version = b[3] & 0xFF;
        int pos = 10;
        int end = Math.min(b.length, 10 + synchsafe(b, 6));
        if ((b[5] & 0x40) != 0 && pos + 4 <= end) {
            pos += version >= 4 ? synchsafe(b, pos) : 4 + big32(b, pos);
        }
        while (pos + 10 <= end && b[pos] != 0) {
            String id = new String(b, pos, 4, StandardCharsets.ISO_8859_1);
            int size = version >= 4 ? synchsafe(b, pos + 4) : big32(b, pos + 4);
            int start = pos + 10;
            if (size < 1 || start + size > b.length) return;
            if (id.equals("TPE1")) out[0] = text(b, start, size);
            else if (id.equals("TCOM")) out[1] = text(b, start, size);
            pos = start + size;
        }
    }

    private static String text(byte[] b, int off, int len) {
        int enc = b[off] & 0xFF;
        Charset cs = enc == 1 ? StandardCharsets.UTF_16
                : enc == 2 ? StandardCharsets.UTF_16BE
                : enc == 3 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        String s = new String(b, off + 1, len - 1, cs);
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
    }

    private static int synchsafe(byte[] b, int o) {
        return (b[o] & 0x7F) << 21 | (b[o + 1] & 0x7F) << 14 | (b[o + 2] & 0x7F) << 7 | (b[o + 3] & 0x7F);
    }

    private static int big32(byte[] b, int o) {
        return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF);
    }
}
