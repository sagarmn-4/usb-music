"""
Singer and musician tags for the songs on the USB drive, run from a PC with the drive attached.

  python artist_tags.py dryrun F:\\Music      reads the tags, looks the albums up online, writes a report; never writes to a song
  python artist_tags.py dryrun F:\\Music --only Swades     only folders whose path contains "Swades"

The report (report.csv, summary.txt) and the lookup cache go to the work folder (default
D:\\Work\\AI\\usb-music-install\\tags). Lookups are cached, so a stopped run carries on where it left off.

Credits come from the iTunes catalogue search. It does not label roles, so: the track's listed artist is
the singer when it differs from the album's artist, and the album's artist is the musician.
"""
import argparse
import csv
import difflib
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict

import mutagen
from mutagen.wave import WAVE

AUDIO = {'.mp3', '.flac', '.m4a', '.wav'}
GAP = 3.2                       # seconds between online lookups (the service allows about 20 a minute)
STRONG_ALBUM = 0.90             # how well the folder name must match the album name (0.80 let 'Amitabh' pass as 'Shamitabh')
STRONG_TITLE = 0.85             # how well the song title must match the track name
MAX_DUR_DIFF = 6.0              # seconds
UA = {'User-Agent': 'USBMusic-tags/1.0 (personal music library)'}

sys.stdout.reconfigure(encoding='utf-8')


# ---------------------------------------------------------------- reading the files

def first(v):
    if v is None:
        return ''
    if isinstance(v, (list, tuple)):
        return str(v[0]).strip() if v else ''
    return str(v).strip()


def read_tags(path):
    """Returns dict with title, artist, composer, album, length (s) or None when unreadable."""
    ext = os.path.splitext(path)[1].lower()
    try:
        if ext == '.wav':
            f = WAVE(path)
            t = f.tags
            g = lambda k: first(t.get(k).text) if t is not None and t.get(k) is not None else ''
            return {'title': g('TIT2'), 'artist': g('TPE1'), 'composer': g('TCOM'), 'album': g('TALB'),
                    'length': f.info.length}
        f = mutagen.File(path, easy=True)
        if f is None:
            return None
        g = lambda k: first(f.get(k)) if f.tags is not None else ''
        return {'title': g('title'), 'artist': g('artist'), 'composer': g('composer'), 'album': g('album'),
                'length': f.info.length}
    except Exception as e:
        return {'error': str(e), 'title': '', 'artist': '', 'composer': '', 'album': '', 'length': 0.0}


# ---------------------------------------------------------------- names

def clean_folder_name(name):
    s = name.replace('_', ' ')
    s = re.sub(r'\[[^\]]*\]|\([^)]*\)', ' ', s)
    s = re.sub(r'(?i)\b(flac|mp3|ost|tag|\d{3}\s?kbps)\b', ' ', s)
    s = re.sub(r'^\s*\d{1,3}\s*[-.)]?\s+(?=[^\W\d_])', '', s)
    s = re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', s)
    s = re.sub(r"[^\w&' ]+", ' ', s)
    return re.sub(r'\s+', ' ', s).strip()


def norm_title(s):
    s = os.path.splitext(s)[0] if re.search(r'\.\w{2,4}$', s) else s
    s = re.sub(r'^\s*\d+\s*[-.)]\s*', '', s.lower())
    s = re.sub(r'[()\[\]]', ' ', s)
    return re.sub(r'[^a-z0-9 ]+', ' ', s).strip()


def norm_album(s):
    s = s.lower()
    s = re.sub(r'\((original|from|music|deluxe|motion|film|bonus|remaster)[^)]*\)', ' ', s)
    s = re.sub(r'\b(original motion picture soundtrack|original soundtrack|soundtrack|ost|ep|single)\b', ' ', s)
    return ' '.join(re.sub(r'[^a-z0-9 ]+', ' ', s).split())


def ratio(a, b):
    return difflib.SequenceMatcher(None, a, b).ratio()


def tokens(s):
    return {w for w in re.split(r'[^a-z0-9]+', s.lower()) if len(w) >= 3}


# ---------------------------------------------------------------- online lookups with a cache

class Online:
    def __init__(self, path, offline=False):
        self.offline = offline
        self.path = path
        self.cache = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {}
        self.calls = 0

    def save(self):
        tmp = self.path + '.tmp'
        json.dump(self.cache, open(tmp, 'w', encoding='utf-8'), ensure_ascii=False)
        os.replace(tmp, self.path)

    def get(self, key, url):
        if key in self.cache:
            return self.cache[key]
        if self.offline:
            return None
        for attempt in range(6):
            time.sleep(GAP)
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
                    data = json.load(r)
                break
            except urllib.error.HTTPError as e:
                if e.code in (403, 429, 503):
                    print('   service asked to slow down, waiting a minute', flush=True)
                    time.sleep(60)
                    continue
                data = None
                break
            except Exception as e:
                print('   lookup failed:', e, flush=True)
                time.sleep(10)
                data = None
        else:
            data = None
        if data is None:
            return None
        self.calls += 1
        self.cache[key] = data
        if self.calls % 10 == 0:
            self.save()
        return data

    def search_albums(self, term):
        url = ('https://itunes.apple.com/search?media=music&entity=album&limit=5&country=IN&term='
               + urllib.parse.quote(term))
        d = self.get('s:' + term.lower(), url)
        return [] if d is None else [{'id': r['collectionId'], 'name': r['collectionName'], 'artist': r['artistName'], 'art': r.get('artworkUrl100', '')}
                                     for r in d.get('results', []) if r.get('collectionId')]

    def album_tracks(self, cid):
        url = 'https://itunes.apple.com/lookup?id=%d&entity=song&country=IN' % cid
        d = self.get('a:%d' % cid, url)
        if d is None:
            return None, []
        res = d.get('results', [])
        album_artist = res[0].get('artistName', '') if res else ''
        tracks = [{'name': t['trackName'], 'artist': t['artistName'], 'ms': t.get('trackTimeMillis', 0)}
                  for t in res if t.get('wrapperType') == 'track']
        return album_artist, tracks


def pick_album(results, query, artist_hint):
    best = None
    q = norm_album(query) or query.lower()
    for r in results:
        n = norm_album(r['name']) or r['name'].lower()
        sim = ratio(q, n)
        qt, nt = tokens(q), tokens(n)
        if qt and qt <= nt:                       # every word of the folder name is in the album name
            sim = max(sim, 0.9 if len(qt) > 1 else 0.82)
        if artist_hint and artist_hint.lower() in r['artist'].lower() and sim >= 0.85:
            sim += 0.05
        if best is None or sim > best[0]:
            best = (sim, r)
    return best


# ---------------------------------------------------------------- writing

def _synchsafe(b):
    return (b[0] & 0x7F) << 21 | (b[1] & 0x7F) << 14 | (b[2] & 0x7F) << 7 | (b[3] & 0x7F)


def _sha(path, start, end):
    import hashlib
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        f.seek(start)
        left = end - start
        while left > 0:
            chunk = f.read(min(1 << 20, left))
            if not chunk:
                raise ValueError('file is cut short')
            h.update(chunk)
            left -= len(chunk)
    return h.hexdigest()


def payload_hash(path):
    """Fingerprint of the sound itself (not the tags), so a write can be proven to leave it untouched."""
    import struct
    ext = os.path.splitext(path)[1].lower()
    size = os.path.getsize(path)
    with open(path, 'rb') as f:
        head = f.read(12)
        if ext == '.wav':
            if head[:4] != b'RIFF' or head[8:12] != b'WAVE':
                raise ValueError('not a RIFF/WAVE file')
            while True:
                hdr = f.read(8)
                if len(hdr) < 8:
                    break
                cid, n = hdr[:4], struct.unpack('<I', hdr[4:])[0]
                if cid == b'data':
                    return _sha(path, f.tell(), f.tell() + n)
                f.seek(n + (n & 1), 1)
            raise ValueError('no data chunk')
        if ext == '.mp3':
            start = 0
            if head[:3] == b'ID3':
                start = 10 + _synchsafe(head[6:10]) + (10 if head[5] & 0x10 else 0)
            end = size
            if size >= 128:
                f.seek(size - 128)
                if f.read(3) == b'TAG':
                    end = size - 128
            return _sha(path, start, end)
        if ext == '.flac':
            pos = 0
            if head[:3] == b'ID3':
                pos = 10 + _synchsafe(head[6:10])
            f.seek(pos)
            if f.read(4) != b'fLaC':
                raise ValueError('not a FLAC file')
            while True:
                h4 = f.read(4)
                if len(h4) < 4:
                    raise ValueError('metadata is cut short')
                last, n = h4[0] & 0x80, int.from_bytes(h4[1:4], 'big')
                f.seek(n, 1)
                if last:
                    break
            return _sha(path, f.tell(), size)
        if ext == '.m4a':
            import hashlib
            h = hashlib.sha256()
            pos = 0
            found = False
            while pos + 8 <= size:
                f.seek(pos)
                hdr = f.read(8)
                n, kind = struct.unpack('>I', hdr[:4])[0], hdr[4:8]
                hlen = 8
                if n == 1:
                    n, hlen = struct.unpack('>Q', f.read(8))[0], 16
                elif n == 0:
                    n = size - pos
                if kind == b'mdat':
                    found = True
                    left = n - hlen
                    while left > 0:
                        chunk = f.read(min(1 << 20, left))
                        if not chunk:
                            raise ValueError('mdat is cut short')
                        h.update(chunk)
                        left -= len(chunk)
                if n < hlen:
                    raise ValueError('bad atom')
                pos += n
            if not found:
                raise ValueError('no mdat atom')
            return h.hexdigest()
    raise ValueError('unsupported format ' + ext)


def riff_ok(path):
    import struct
    with open(path, 'rb') as f:
        head = f.read(8)
    n = os.path.getsize(path)
    return head[:4] == b'RIFF' and struct.unpack('<I', head[4:8])[0] + 8 in (n, n - 1)


def read_state(path):
    """What the file already has: artist, composer and whether it carries a picture."""
    ext = os.path.splitext(path)[1].lower()
    if ext in ('.wav', '.mp3'):
        from mutagen.id3 import ID3, ID3NoHeaderError
        try:
            t = WAVE(path).tags if ext == '.wav' else ID3(path)
        except ID3NoHeaderError:
            t = None                         # no tag block yet
        if t is None:
            return '', '', False
        g = lambda k: first(t.get(k).text) if t.get(k) is not None else ''
        return g('TPE1'), g('TCOM'), bool(t.getall('APIC'))
    if ext == '.flac':
        from mutagen.flac import FLAC
        f = FLAC(path)
        return first(f.get('artist')), first(f.get('composer')), bool(f.pictures)
    from mutagen.mp4 import MP4
    m = MP4(path)
    return first(m.get('\xa9ART')), first(m.get('\xa9wrt')), bool(m.get('covr'))


def apply_tags(path, artist, composer, art):
    """Writes into the file at `path` (a copy, except for WAV which is appended to in place)."""
    ext = os.path.splitext(path)[1].lower()
    if ext in ('.wav', '.mp3'):
        from mutagen.id3 import ID3, ID3NoHeaderError, TPE1, TCOM, APIC
        if ext == '.wav':
            f = WAVE(path)
            if f.tags is None:
                f.add_tags()
            tags = f.tags
        else:
            try:
                tags = ID3(path)
            except ID3NoHeaderError:
                tags = ID3()
        if artist:
            tags.setall('TPE1', [TPE1(encoding=1, text=[artist])])
        if composer:
            tags.setall('TCOM', [TCOM(encoding=1, text=[composer])])
        if art:
            tags.setall('APIC', [APIC(encoding=0, mime='image/jpeg', type=3, desc='Cover', data=art)])
        if ext == '.wav':
            f.save(v2_version=3)
        else:
            tags.save(path, v2_version=3)
    elif ext == '.flac':
        from mutagen.flac import FLAC, Picture
        f = FLAC(path)
        if artist:
            f['artist'] = [artist]
        if composer:
            f['composer'] = [composer]
        if art:
            import io
            from PIL import Image
            im = Image.open(io.BytesIO(art))
            pic = Picture()
            pic.type, pic.mime, pic.data = 3, 'image/jpeg', art
            pic.width, pic.height, pic.depth = im.size[0], im.size[1], 24
            f.add_picture(pic)
        f.save()
    else:
        from mutagen.mp4 import MP4, MP4Cover
        m = MP4(path)
        if artist:
            m['\xa9ART'] = [artist]
        if composer:
            m['\xa9wrt'] = [composer]
        if art:
            m['covr'] = [MP4Cover(art, imageformat=MP4Cover.FORMAT_JPEG)]
        m.save()


def write_one(path, artist, composer, art, backup_root):
    """Backs the file up, writes, and checks the sound is unchanged and the new tags read back."""
    import shutil
    ext = os.path.splitext(path)[1].lower()
    before = payload_hash(path)
    if backup_root:
        rel = os.path.relpath(path, os.path.splitdrive(path)[0] + os.sep)
        copy = os.path.join(backup_root, rel)
        os.makedirs(os.path.dirname(copy), exist_ok=True)
        if not os.path.exists(copy):
            shutil.copy2(path, copy)
        if payload_hash(copy) != before:
            raise ValueError('backup copy does not match the original')
    len_before = mutagen.File(path).info.length
    if ext == '.wav':
        target = path                       # a small append at the end of the file
    else:
        target = os.path.join(os.path.dirname(path), '.usbmusic-write' + ext)
        shutil.copy2(path, target)
    try:
        apply_tags(target, artist, composer, art)
        problems = check_written(target, before, artist, composer, art, len_before)
        if problems:
            raise ValueError('; '.join(problems))
        if target != path:
            os.replace(target, path)
            problems = check_written(path, before, artist, composer, art, len_before)
            if problems:
                raise ValueError('after the swap: ' + '; '.join(problems))
    finally:
        if target != path and os.path.exists(target):
            os.remove(target)
    return 'ok'


def check_written(path, before, artist, composer, art, len_before):
    problems = []
    if payload_hash(path) != before:
        problems.append('sound data changed')
    if path.lower().endswith('.wav') and not riff_ok(path):
        problems.append('RIFF size does not match the file')
    a, c, has_pic = read_state(path)
    if artist and a != artist:
        problems.append('artist did not read back')
    if composer and c != composer:
        problems.append('composer did not read back')
    if art and not has_pic:
        problems.append('cover did not read back')
    if abs(mutagen.File(path).info.length - len_before) > 0.01:
        problems.append('length changed')
    return problems


def pick_folders(rows, verdicts, n):
    """n folders with a verified cover, spread over WAV, MP3 and FLAC, smaller ones first."""
    by_dir = defaultdict(list)
    for r in rows:
        by_dir[os.path.dirname(r['path'])].append(r)
    cands = defaultdict(list)
    for d, rs in by_dir.items():
        if verdicts.get(d) != 'match':
            continue
        if not any(r['new_artist'] or r['new_composer'] for r in rs):
            continue
        fmt = Counter(r['format'] for r in rs).most_common(1)[0][0]
        if fmt in ('wav', 'mp3', 'flac') and 4 <= len(rs) <= 14:
            cands[fmt].append(d)
    for k in cands:
        cands[k].sort()
    order = ['wav', 'mp3', 'flac', 'wav', 'mp3', 'flac', 'wav', 'mp3', 'flac', 'wav']
    chosen = []
    idx = Counter()
    for fmt in order:
        if len(chosen) >= n:
            break
        if idx[fmt] < len(cands[fmt]):
            chosen.append(cands[fmt][idx[fmt]])
            idx[fmt] += 1
    return chosen


def run_write(a):
    rows = list(csv.DictReader(open(a.report, encoding='utf-8-sig')))
    verdicts = {}
    cc = os.path.join(a.work, 'covers-check.csv')
    if os.path.exists(cc):
        verdicts = {r['dir']: r['verdict'] for r in csv.DictReader(open(cc, encoding='utf-8-sig'))}
    by_dir = defaultdict(list)
    for r in rows:
        by_dir[os.path.dirname(r['path'])].append(r)

    def needs_write(d):
        art_ok = verdicts.get(d) == 'match' and os.path.isfile(os.path.join(d, 'folder.jpg'))
        for r in by_dir[d]:
            if r['format'] not in a.formats.split(',') or r['action'].startswith('skip: file could not'):
                continue
            cur_a, cur_c, has_pic = read_state(r['path'])
            if (r['new_artist'] and not cur_a) or (r['new_composer'] and not cur_c) or (art_ok and not has_pic):
                return True
        return False

    if a.batch:
        dirs = []
        for d in sorted(by_dir):
            if needs_write(d):          # tags for strong track matches; the cover only where it was verified
                dirs.append(d)
                if len(dirs) >= a.batch:
                    break
        import shutil
        need = sum(os.path.getsize(r['path']) for d in dirs for r in by_dir[d] if os.path.exists(r['path']))
        free = shutil.disk_usage(os.path.splitdrive(a.backup)[0] + os.sep).free
        print('batch: %d folders, %.1f GB of songs' % (len(dirs), need / 1e9), flush=True)
        if not a.no_backup and free < need * 1.1 + 20e9:
            print('not enough room for the backups; stopping before any change', flush=True)
            return
    elif a.pick:
        dirs = pick_folders(rows, verdicts, a.pick)
    else:
        dirs = sorted({os.path.dirname(r['path']) for r in rows if a.only.lower() in r['path'].lower()})
    formats = a.formats.split(',')
    print('folders chosen (%d):' % len(dirs), flush=True)
    for d in dirs:
        print('   ' + d, flush=True)
    log = []
    failed = False
    for d in dirs:
        jpg = os.path.join(d, 'folder.jpg')
        art = open(jpg, 'rb').read() if verdicts.get(d) == 'match' and os.path.isfile(jpg) else None
        for r in [x for x in rows if os.path.dirname(x['path']) == d and x['format'] in formats]:
            p = r['path']
            entry = {'path': p, 'before_artist': r['existing_artist'], 'before_composer': r['existing_composer']}
            try:
                if r['action'].startswith('skip: file could not'):
                    continue
                cur_a, cur_c, has_pic = read_state(p)
                want_a = r['new_artist'] if not cur_a else ''
                want_c = r['new_composer'] if not cur_c else ''
                want_art = art if (art and not has_pic) else None
                if not (want_a or want_c or want_art):
                    continue
                entry.update(artist=want_a, composer=want_c, cover=bool(want_art))
                entry['status'] = write_one(p, want_a, want_c, want_art, None if a.no_backup else a.backup)
            except Exception as e:
                entry['status'] = 'FAILED: %s' % e
            print('%s -> %s' % (os.path.relpath(p, a.root_for_print), entry['status']), flush=True)
            log.append(entry)
            if entry['status'] != 'ok':
                failed = True
                break
        if failed:
            print('stopping at the first failure; the original is in the backup folder', flush=True)
            break
    log_path = os.path.join(a.work, 'write-log.json')
    old = json.load(open(log_path, encoding='utf-8')) if os.path.exists(log_path) else []
    json.dump(old + log, open(log_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('written: %d ok of %d attempted' % (sum(1 for e in log if e['status'] == 'ok'), len(log)), flush=True)


# ---------------------------------------------------------------- do the folder.jpg covers belong to the matched album?

def gray_vector(img, n=32):
    g = img.convert('L').resize((n, n))
    px = list(g.tobytes())
    m = sum(px) / len(px)
    d = [v - m for v in px]
    return d


def ncc(a, b):
    num = sum(x * y for x, y in zip(a, b))
    den = (sum(x * x for x in a) * sum(y * y for y in b)) ** 0.5
    return num / den if den else 0.0


def run_coverscheck(a):
    """Compares each folder.jpg with the official cover of the album the folder matched (no writes to the drive)."""
    import io
    from PIL import Image
    info = json.load(open(os.path.join(a.work, 'folders.json'), encoding='utf-8'))
    cache_path = os.path.join(a.work, 'art-cache')
    os.makedirs(cache_path, exist_ok=True)
    out, counts = [], Counter()
    for n, f in enumerate(info, 1):
        d = f['dir']
        jpg = os.path.join(d, 'folder.jpg')
        row = {'dir': d, 'album': f['album'], 'album_sim': f['album_sim'], 'ncc': '', 'verdict': ''}
        if not os.path.isfile(jpg):
            row['verdict'] = 'no folder.jpg'
        elif not f['album']:
            row['verdict'] = 'unverified: no confident album match'
        elif not f['art']:
            row['verdict'] = 'unverified: no official cover to compare'
        else:
            key = re.sub(r'\W+', '_', f['art'])[-60:]
            local = os.path.join(cache_path, key + '.jpg')
            if not os.path.exists(local):
                url = re.sub(r'\d+x\d+bb', '300x300bb', f['art'])
                try:
                    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
                        open(local, 'wb').write(r.read())
                except Exception as e:
                    row['verdict'] = 'unverified: could not download the official cover'
            if not row['verdict']:
                try:
                    c = ncc(gray_vector(Image.open(jpg)), gray_vector(Image.open(local)))
                    row['ncc'] = round(c, 2)
                    row['verdict'] = 'match' if c >= a.threshold else 'MISMATCH'
                except Exception as e:
                    row['verdict'] = 'unverified: image could not be read'
        counts[row['verdict'].split(':')[0]] += 1
        out.append(row)
        if n % 50 == 0:
            print('checked %d of %d' % (n, len(info)), flush=True)
    with open(os.path.join(a.work, 'covers-check.csv'), 'w', newline='', encoding='utf-8-sig') as fh:
        w = csv.DictWriter(fh, fieldnames=['verdict', 'dir', 'album', 'album_sim', 'ncc'])
        w.writeheader()
        w.writerows(sorted(({k: r[k] for k in ('verdict', 'dir', 'album', 'album_sim', 'ncc')} for r in out), key=lambda r: r['verdict']))
    print(dict(counts), flush=True)
    print('report: ' + os.path.join(a.work, 'covers-check.csv'), flush=True)


# ---------------------------------------------------------------- matching helpers

GENERIC = re.compile(r'(?i)^(cd|disc|disk|part)?\s*[a-z0-9]{1,2}$|^(cd|disc|disk)\s*\d+$')


def search_name(dirpath):
    """The folder name to search for. A generic name (A, CD1, Disc 2) borrows the parent folder's name."""
    d = dirpath
    for _ in range(3):
        name = os.path.basename(d)
        if name.lower() == 'music':
            break
        q = clean_folder_name(name)
        if len(q) >= 3 and not GENERIC.match(name.strip()):
            return q
        d = os.path.dirname(d)
    return clean_folder_name(os.path.basename(dirpath))


def title_variants(s):
    s = norm_title(s)
    raw = s
    out = {raw}
    # "01 - Singer, Singer - Title" file names: also try just the part after the last dash
    return out


def title_sim(a, b):
    def variants(x):
        base = os.path.splitext(x)[0] if re.search(r'\.\w{2,4}$', x) else x
        base = re.sub(r'^\s*\d+\s*[-.)]\s*', '', base)
        out = {norm_title(base)}
        if ' - ' in base:
            out.add(norm_title(base.rsplit(' - ', 1)[1]))
        return {v for v in out if v}
    best = 0.0
    for x in variants(a):
        for y in variants(b):
            best = max(best, ratio(x, y))
    return best


def match_songs(songs, tracks, relaxed=False):
    """Assign songs to album tracks. Returns {file name: (track index, similarity, duration difference)}."""
    sim_min, dur_max = (0.7, 3.0) if relaxed else (STRONG_TITLE, MAX_DUR_DIFF)
    scored = []
    for fn, full, tg in songs:
        if not tg or tg.get('error'):
            continue
        title = tg['title'] or fn
        for i, t in enumerate(tracks):
            sim = max(title_sim(title, t['name']), title_sim(fn, t['name']))
            dd = abs(t['ms'] / 1000.0 - tg['length'])
            if sim >= sim_min and dd <= dur_max:
                scored.append((sim - dd / 60.0, fn, i, sim, dd))
    assign, used = {}, set()
    for sc, fn, i, sim, dd in sorted(scored, reverse=True):
        if fn in assign or i in used:
            continue
        assign[fn] = (i, sim, dd)
        used.add(i)
    if not relaxed:
        # a second chance for songs whose names differ but whose length fits a track that is still free
        for fn, full, tg in songs:
            if fn in assign or not tg or tg.get('error'):
                continue
            title = tg['title'] or fn
            best = None
            for i, t in enumerate(tracks):
                if i in used:
                    continue
                dd = abs(t['ms'] / 1000.0 - tg['length'])
                sim = max(title_sim(title, t['name']), title_sim(fn, t['name']))
                if dd <= 2.0 and sim >= 0.5 and (best is None or sim - dd / 60.0 > best[0]):
                    best = (sim - dd / 60.0, i, sim, dd)
            if best:
                assign[fn] = (best[1], best[2], best[3])
                used.add(best[1])
    return assign


# ---------------------------------------------------------------- embedding a planned picture

def run_art(a):
    """Embeds the picture chosen in plan.json into every song of those folders that has no picture yet."""
    plan = json.load(open(a.plan, encoding='utf-8'))
    log, failed = [], False
    for n, (d, p) in enumerate(sorted(plan.items()), 1):
        if not os.path.isdir(d) or not os.path.isfile(p['path']):
            print('[%d/%d] %s -> skipped (picture or folder missing)' % (n, len(plan), os.path.basename(d)), flush=True)
            continue
        art = open(p['path'], 'rb').read()
        done = 0
        for fn in sorted(os.listdir(d)):
            ext = os.path.splitext(fn)[1].lower()
            if ext not in ('.wav', '.mp3', '.flac', '.m4a'):
                continue
            path = os.path.join(d, fn)
            try:
                _, _, has_pic = read_state(path)
                if has_pic:
                    continue
                write_one(path, '', '', art, None)
                done += 1
                log.append({'path': path, 'source': p['source'], 'status': 'ok'})
            except Exception as e:
                log.append({'path': path, 'source': p['source'], 'status': 'FAILED: %s' % e})
                print('FAILED %s: %s' % (path, e), flush=True)
                failed = True
                break
        print('[%d/%d] %s -> %d files (%s cover)' % (n, len(plan), os.path.basename(d), done, p['source']), flush=True)
        if failed:
            print('stopping at the first failure', flush=True)
            break
    json.dump(log, open(os.path.join(a.work, 'art-log.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('embedded: %d ok of %d attempted' % (sum(1 for e in log if e['status'] == 'ok'), len(log)), flush=True)


# ---------------------------------------------------------------- the dry run

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('command', choices=['dryrun', 'write', 'coverscheck', 'art'])
    ap.add_argument('--plan', default='', help='art: plan.json from art_plan.py')
    ap.add_argument('root', nargs='?', default='')
    ap.add_argument('--work', default=r'D:\Work\AI\usb-music-install\tags')
    ap.add_argument('--only', default='')
    ap.add_argument('--limit', type=int, default=0, help='only the first N folders (for a quick trial)')
    ap.add_argument('--report', default='', help='write: the report.csv from a dry run')
    ap.add_argument('--backup', default=r'D:\Work\AI\usb-music-install\tags-backup',
                    help='write: a copy of every file is put here before it is changed')
    ap.add_argument('--formats', default='wav,mp3,flac,m4a', help='write: comma separated, e.g. wav,mp3')
    ap.add_argument('--no-backup', dest='no_backup', action='store_true', help='write: do not copy the originals first')
    ap.add_argument('--dirs-file', dest='dirs_file', default='', help='dryrun: only the folders listed in this file, one per line')
    ap.add_argument('--cache', default='', help='dryrun: the lookup cache file to share between runs')
    ap.add_argument('--batch', type=int, default=0, help='write: the next N folders (in path order) that still need changes')
    ap.add_argument('--pick', type=int, default=0, help='write: choose this many folders with a verified cover automatically')
    ap.add_argument('--root-for-print', dest='root_for_print', default='F:\\Music')
    ap.add_argument('--threshold', type=float, default=0.80, help='coverscheck: similarity needed to call a cover a match')
    ap.add_argument('--offline', action='store_true', help='dryrun: use only what is already cached, no lookups')
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    if a.command == 'write':
        run_write(a)
        return
    if a.command == 'coverscheck':
        run_coverscheck(a)
        return
    if a.command == 'art':
        run_art(a)
        return
    if not a.root:
        ap.error('dryrun needs the music folder')
    online = Online(a.cache or os.path.join(a.work, 'cache.json'), offline=a.offline)

    # 1. read every song
    folders = defaultdict(list)
    for dirpath, dirs, files in os.walk(a.root):
        dirs[:] = [d for d in dirs if d.lower() not in ('$recycle.bin', 'system volume information')]
        for fn in sorted(files):
            if os.path.splitext(fn)[1].lower() in AUDIO:
                folders[dirpath].append(fn)
    paths = sorted(p for p in folders if a.only.lower() in p.lower())
    if a.dirs_file:
        wanted = {l.strip().lower() for l in open(a.dirs_file, encoding='utf-8') if l.strip()}
        paths = [p for p in paths if p.lower() in wanted]
    if a.limit:
        paths = paths[:a.limit]
    print('folders with songs: %d (of %d), reading tags...' % (len(paths), len(folders)), flush=True)

    rows = []
    folder_info = []
    stats = Counter()
    root_name = os.path.basename(os.path.normpath(a.root)).lower()
    for n, dirpath in enumerate(paths, 1):
        names = folders[dirpath]
        songs = []
        for fn in names:
            full = os.path.join(dirpath, fn)
            tg = read_tags(full)
            songs.append((fn, full, tg))
        folder = os.path.basename(dirpath)
        rel = os.path.relpath(dirpath, a.root)
        artists = Counter(first(s[2]['artist']).split(',')[0] for s in songs if s[2] and s[2].get('artist'))
        artist_hint = artists.most_common(1)[0][0] if artists else ''

        # 2. find the album online
        query = search_name(dirpath)
        album_artist, tracks, album_name, album_sim, why = '', [], '', 0.0, ''
        album_art = ''
        if rel == '.' or folder.lower() == root_name:
            why = 'songs directly in the top folder'
        elif len(query) < 3:
            why = 'folder name too short to search'
        else:
            term = query + (' ' + artist_hint if artist_hint else '')
            results = online.search_albums(term)
            if not results and artist_hint:
                results = online.search_albums(query)
            if not results and len(query.split()) > 3:
                results = online.search_albums(' '.join(query.split()[:3]))
            pick = pick_album(results, query, artist_hint) if results else None
            if pick is None:
                why = 'no album found online'
            elif pick[0] >= STRONG_ALBUM:
                album_sim, r = pick
                album_name, album_art = r['name'], r.get('art', '')
                album_artist, tracks = online.album_tracks(r['id'])
                if album_artist is None:
                    why = 'could not read the album track list'
            elif pick[0] >= 0.6:
                # a near match on the name is accepted when the songs themselves line up with the album's tracks
                aa, tr = online.album_tracks(pick[1]['id'])
                good = match_songs(songs, tr, relaxed=True) if aa is not None else {}
                if aa is not None and len(good) >= 3 and len(good) >= 0.6 * len(songs):
                    album_sim, r = pick
                    album_name, album_art, album_artist, tracks = r['name'], r.get('art', ''), aa, tr
                    why = ''
                else:
                    why = 'album match too weak (%.2f: %s)' % (pick[0], pick[1]['name'])
            else:
                why = 'album match too weak (%.2f: %s)' % (pick[0], pick[1]['name'])
        folder_info.append({'dir': dirpath, 'album': album_name, 'album_sim': round(album_sim, 2), 'art': album_art, 'why': why})
        print('[%d/%d] %s -> %s' % (n, len(paths), rel, (album_name + ' (%.2f)' % album_sim) if album_name else why), flush=True)

        # 3. match each song to a track
        assign = match_songs(songs, tracks)

        for fn, full, tg in songs:
            ext = os.path.splitext(fn)[1].lower().lstrip('.')
            row = {'path': full, 'format': ext, 'existing_artist': '', 'existing_composer': '',
                   'album': album_name, 'album_match': round(album_sim, 2) if album_name else '',
                   'track_match': '', 'duration_diff_s': '', 'singer': '', 'musician': '',
                   'new_artist': '', 'new_composer': '', 'action': ''}
            if not tg or tg.get('error'):
                row['action'] = 'skip: file could not be read'
                rows.append(row)
                stats['skip: unreadable'] += 1
                continue
            row['existing_artist'], row['existing_composer'] = tg['artist'], tg['composer']
            if fn in assign:
                i, sim, dd = assign[fn]
                t = tracks[i]
                singer = '' if t['artist'].strip().lower() == album_artist.strip().lower() else t['artist']
                musician = '' if album_artist.strip().lower() in ('various artists', '') else album_artist
                row.update(track_match=round(sim, 2), duration_diff_s=round(dd, 1), singer=singer, musician=musician)
                want_artist = singer or musician
                if tg['artist']:
                    row['action'] = 'skip artist: file already has one'
                    want_artist = ''
                if want_artist:
                    row['new_artist'] = want_artist
                if musician and not tg['composer']:
                    row['new_composer'] = musician
                if row['new_artist'] or row['new_composer']:
                    row['action'] = 'write'
                    stats['write'] += 1
                elif not row['action']:
                    row['action'] = 'skip: nothing to add'
                    stats['skip: nothing to add'] += 1
                else:
                    stats['skip: already has artist'] += 1
            else:
                row['action'] = 'skip: ' + (why or 'no strong track match')
                stats['skip: ' + ('no album' if why else 'no strong track match')] += 1
            rows.append(row)
        online.save()

    json.dump(folder_info, open(os.path.join(a.work, 'folders.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    # 4. write the report
    cols = ['action', 'path', 'format', 'existing_artist', 'existing_composer', 'album', 'album_match',
            'track_match', 'duration_diff_s', 'singer', 'musician', 'new_artist', 'new_composer']
    with open(os.path.join(a.work, 'report.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    by_fmt = Counter(r['format'] for r in rows)
    write_fmt = Counter(r['format'] for r in rows if r['action'] == 'write')
    lines = ['Songs read: %d' % len(rows), '']
    lines += ['%-6s total %5d, would write %5d' % (k, by_fmt[k], write_fmt[k]) for k in sorted(by_fmt)]
    lines += ['', 'Outcomes:'] + ['  %-34s %5d' % (k, v) for k, v in stats.most_common()]
    open(os.path.join(a.work, 'summary.txt'), 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    print('\n'.join(lines), flush=True)
    print('report: ' + os.path.join(a.work, 'report.csv'), flush=True)


if __name__ == '__main__':
    main()
