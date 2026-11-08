package com.sagar.usbmusic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folder tree, albums and artists built from the track list. Mirrors buildIndex() in the phone page
 * (assets/index.html) so the TV screen groups songs exactly like the page does. A song's id is its
 * position in the library list, the same id the phone page and the /api commands use.
 */
final class LibraryIndex {

    static final class Node {
        final String name;
        final String full;
        final Node parent;
        final Map<String, Node> children = new LinkedHashMap<>();
        /** Songs directly in this folder. */
        final List<Integer> ids = new ArrayList<>();
        /** Songs in this folder and everything below it. */
        final List<Integer> all = new ArrayList<>();

        Node(String name, String full, Node parent) {
            this.name = name;
            this.full = full;
            this.parent = parent;
        }

        List<Node> sortedKids() {
            List<Node> l = new ArrayList<>(children.values());
            Collections.sort(l, BY_NODE);
            return l;
        }

        /** Parent folder names, e.g. "A / B" (empty for a top-level folder). */
        String relPath() {
            StringBuilder sb = new StringBuilder();
            for (Node p = parent; p != null && !p.name.isEmpty(); p = p.parent) {
                sb.insert(0, sb.length() == 0 ? p.name : p.name + " / ");
            }
            return sb.toString();
        }
    }

    /** An album or an artist: a name and its songs. */
    static final class Group {
        final String name;
        String sub;
        final List<Integer> ids = new ArrayList<>();

        Group(String name, String sub) {
            this.name = name;
            this.sub = sub;
        }
    }

    final List<Track> tracks;
    final Node tree = new Node("", "", null);
    final List<Node> folderNodes = new ArrayList<>();
    final List<Group> albums = new ArrayList<>();
    final List<Group> artists = new ArrayList<>();

    private static final Comparator<Node> BY_NODE = new Comparator<Node>() {
        @Override public int compare(Node a, Node b) {
            return natural(a.name, b.name);
        }
    };
    private static final Comparator<Group> BY_GROUP = new Comparator<Group>() {
        @Override public int compare(Group a, Group b) {
            return natural(a.name, b.name);
        }
    };

    LibraryIndex(List<Track> tracks) {
        this.tracks = tracks;
        Map<String, Group> albumMap = new LinkedHashMap<>();
        Map<String, Group> artistMap = new LinkedHashMap<>();
        for (int id = 0; id < tracks.size(); id++) {
            Track t = tracks.get(id);
            String pa = t.primaryArtist();
            if (!t.album.isEmpty()) {
                String k = t.album + "\n" + t.folder;
                Group a = albumMap.get(k);
                if (a == null) {
                    a = new Group(t.album, pa);
                    albumMap.put(k, a);
                } else if (!a.sub.equals(pa)) {
                    a.sub = "Various artists";
                }
                a.ids.add(id);
            }
            if (!pa.isEmpty()) {
                Group a = artistMap.get(pa);
                if (a == null) {
                    a = new Group(pa, "");
                    artistMap.put(pa, a);
                }
                a.ids.add(id);
            }
            addToTree(t.folder, id);
        }
        albums.addAll(albumMap.values());
        artists.addAll(artistMap.values());
        Collections.sort(albums, BY_GROUP);
        Collections.sort(artists, BY_GROUP);
        flatten(tree);
    }

    /** A leading "Music" folder is dropped; songs directly in it are left out of the tree. */
    private void addToTree(String folder, int id) {
        List<String> parts = new ArrayList<>();
        for (String p : folder.split("/")) {
            if (!p.isEmpty()) parts.add(p);
        }
        int skip = !parts.isEmpty() && parts.get(0).equalsIgnoreCase("music") ? 1 : 0;
        Node n = tree;
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) full.append('/');
            full.append(parts.get(i));
            if (i < skip) continue;
            Node c = n.children.get(parts.get(i));
            if (c == null) {
                c = new Node(parts.get(i), full.toString(), n == tree ? null : n);
                n.children.put(parts.get(i), c);
            }
            n = c;
            n.all.add(id);
        }
        if (n != tree) n.ids.add(id);
    }

    private void flatten(Node n) {
        for (Node c : n.sortedKids()) {
            folderNodes.add(c);
            flatten(c);
        }
    }

    /** Case-insensitive compare that reads digit runs as numbers ("2" before "10"). */
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
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
