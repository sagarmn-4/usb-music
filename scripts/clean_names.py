"""
Clean up song file names and folder names on the drive.

  python clean_names.py plan  F:\\Music      work out the renames and write the list (plan.csv); touches nothing
  python clean_names.py apply F:\\Music      do the renames in plan.csv, files first, then folders from the deepest up
  python clean_names.py undo                 put every name back, using rename-log.json

What it cleans: site names and watermarks, format and bit-rate words (FLAC, WAV, 320Kbps), release-group tags,
underscores, stray symbols, doubled spaces, and ALL-CAPS names. What it keeps: track numbers ("01 Song"),
years such as "Gadar (2001)", and anything that looks like part of a title. A name that would collide with a
sibling is left as it is.
"""
import csv
import json
import os
import re
import sys

WORK = r'D:\Work\AI\usb-music-install\names'
AUDIO = ('.mp3', '.flac', '.m4a', '.wav')
KEEP_UPPER = {'dj', 'ii', 'iii', 'iv', 'vi', 'bgm', 'cd', 'tv', 'mc', 'ost', 'ar', 'sp', 'kk', 'pt'}

sys.stdout.reconfigure(encoding='utf-8')

DOMAIN = re.compile(r'(?i)(?:https?://)?(?:www\.)?[\w-]+\.(?:com|in|net|pk|org|info|co|me|tv)\b')
JUNK_IN_BRACKET = re.compile(
    r'(?i)\b(flac|wav|mp3|m4a|aac|lossless|\d{2,3}\s?kbps|kbps|vbr|cbr|ddr|xdr|rtrg|drs|bhatti\w*|songs?\s?pk|songsmp3|'
    r'pagalworld|mp3songs|full album|with cover|mp3 songs|rip|dm|lrc|djluv|djmaza|bollym4u|beautyspot|vmr|dru|hiq|untouched|\d{2,3}v)\b|^\s*(?:ds|dr|drs)\s*$')
TRAILING_JUNK = re.compile(
    r'(?i)[\s\-_,.]*\b(?:flac|wav|mp3|m4a|lossless|\d{2,3}\s?kbps|ddr|xdr|rtrg|mp3 songs|full album|songs mp3|ost|hiq|beautyspot|djluv|djmaza|320|128)\s*$')
LEADING_JUNK = re.compile(r'(?i)^\s*(?:flac|mp3)\s*[-_:]\s*')
YEAR = re.compile(r'\b(19[2-9]\d|20[0-3]\d)\b')


def smart_title(s):
    """ALL-CAPS text becomes Title Case; acronyms and short consonant clusters stay as they are."""
    letters = [c for c in s if c.isalpha()]
    if len(letters) < 4 or any(c.islower() for c in letters):
        return s

    def fix(m):
        w = m.group(0)
        if w.lower() in KEEP_UPPER or (len(w) >= 3 and not re.search(r'[AEIOUaeiou]', w)) or len(w) == 1:
            return w
        return w[0] + w[1:].lower()
    return re.sub(r"[^\W\d_]+(?:'[^\W\d_]+)?", fix, s)


def clean_base(name, is_file, numbered):
    s = name
    web_rip = is_file and '_' in s and s == s.lower()
    if not is_file:
        s = re.sub(r'(?<![\w])_([^_]+)_(?![\w])', r'[\1]', s)      # _tag_ is a bracket in disguise
    s = s.replace('_', ' ')
    s = re.sub(r'\([^()]*\([^()]*\)[^()]*\.(?:com|in|pk|net)\s*\)', ' ', s)    # "(Site (1) .com)" download tags
    year = None

    def bracket(m):
        nonlocal year
        inner = m.group(1)
        y = YEAR.search(inner)
        if JUNK_IN_BRACKET.search(inner) or DOMAIN.search(inner) or re.fullmatch(r'[\W_]*', inner):
            if y and not year:
                year = y.group(1)
            return ' '
        return m.group(0)
    s = re.sub(r'\[([^\]]*)\]', bracket, s)
    s = re.sub(r'\(([^)]*)\)', bracket, s)
    s = re.sub(r'\[([^\]]*)\]', lambda m: ('(%s)' % m.group(1)) if YEAR.fullmatch(m.group(1).strip()) else '(%s)' % m.group(1).strip(), s)
    if not is_file:
        s = re.sub(r'\s~.*$', '', s)                                  # everything after a tilde is a tag
    s = DOMAIN.sub(' ', s)
    s = LEADING_JUNK.sub('', s)
    s = re.sub(r'(?i)\bmp3 songs?\b', ' ', s)
    for _ in range(3):
        s2 = TRAILING_JUNK.sub('', s)
        if s2 == s:
            break
        s = s2
    s = re.sub(r"[~★☆♪♫•·|*<>:\"?\\/]+", ' ', s)
    s = re.sub(r'\(\s*\)', ' ', s)
    s = re.sub(r'\s*\(\s*', ' (', s)
    s = re.sub(r'\s*\)\s*', ') ', s)
    s = re.sub(r'(?<=\w)-\s+(?=\w)', ' - ', s)
    s = re.sub(r'(?<=\w)-\s+(?=\()', ' ', s)
    s = s.replace('{', '(').replace('}', ')')
    s = re.sub(r'[\[\]]', ' ', s)
    s = re.sub(r'\(\s*\(([^()]*)\)\s*\)', r'(\1)', s)
    s = re.sub(r'\(\s+', '(', s)
    s = re.sub(r'\s+\)', ')', s)
    s = re.sub(r'\s+', ' ', s).strip(' -_.,')
    if year and not YEAR.search(s) and not is_file:
        s = '%s (%s)' % (s, year)
    s = re.sub(r'(?<=\S)\((\d{4})\)', r' (\1)', s)
    if not is_file:
        m = re.match(r'^(.*\S)\s+(19[2-9]\d|20[0-3]\d)$', s)
        if m and not re.match(r'^\d+$', m.group(1)):
            s = '%s (%s)' % (m.group(1), m.group(2))              # "Shaitan 2011" -> "Shaitan (2011)"
    if is_file and numbered:
        m = re.match(r'^\s*(\d{1,3})\s*[-._)]*\s*(.*)$', s)
        if m and m.group(2):
            s = '%02d %s' % (int(m.group(1)), m.group(2).strip(' -_.'))
    if is_file and re.fullmatch(r'\d{1,3}', s):
        s = '%02d' % int(s)
    if web_rip and s == s.lower():
        s = ' '.join(w[:1].upper() + w[1:] for w in s.split(' '))
    s = smart_title(s)
    return re.sub(r'\s+', ' ', s).strip(' -_.,')


def movie_numbered(base, folder, allowed=()):
    """'[aR] Pardes - 06 - Title Music - sweetumz' -> '06 Title Music' when the first part is the folder's movie."""
    base = re.sub(r'^\s*\[[^\]]{1,10}\]\s*', '', base)
    m = re.match(r'^(.+?)\s*-\s*(\d{1,3})\s*-\s*(.+)$', base)
    if not m:
        return None
    norm = lambda x: re.sub(r'[^a-z0-9]+', '', x.lower())
    movie, num, title = m.group(1), m.group(2), m.group(3)
    f = norm(folder)
    a = norm(movie)
    if not a or not f or not (a in allowed or a in f or f in a or sum(1 for x, y in zip(a, f) if x == y) >= 0.6 * min(len(a), len(f))):
        return None
    parts = [t.strip() for t in re.split(r'\s+-\s+', title)]
    if len(parts) > 1 and re.fullmatch(r'[a-z0-9]{3,}', parts[-1]) and any(c.isupper() for c in parts[-2]):
        parts = parts[:-1]                                              # the ripper's user name
    return '%02d %s' % (int(num), ' - '.join(parts))


def drop_artist(base, artist):
    """'01 Benny Dayal, Shefali Alvaris - Badtameez Dil' -> '01 Badtameez Dil' when the middle part is the artist tag."""
    m = re.match(r'^(\d{1,3})\s+(.+?)\s+-\s+(.+)$', base)
    if not m:
        return base
    norm = lambda x: re.sub(r'[^a-z0-9]+', '', x.lower())
    mid = norm(m.group(2))
    a = norm(artist or '')
    if mid in ('na', 'various', 'variousartists', 'unknown', 'unknownartist'):
        return '%s %s' % (m.group(1), m.group(3))
    if not a:
        return base
    if mid and (mid == a or (len(mid) > 4 and mid in a) or (len(a) > 4 and a in mid)):
        return '%s %s' % (m.group(1), m.group(3))
    return base


def read_artist(path):
    try:
        import mutagen
        f = mutagen.File(path, easy=not path.lower().endswith('.wav'))
        if path.lower().endswith('.wav'):
            from mutagen.wave import WAVE
            t = WAVE(path).tags
            return str(t.get('TPE1')) if t is not None and t.get('TPE1') is not None else ''
        v = f.get('artist') if f is not None else None
        return str(v[0]) if v else ''
    except Exception:
        return ''


def run_plan(root):
    os.makedirs(WORK, exist_ok=True)
    rows = []
    taken = {}                                    # parent -> names (lower) already used or planned
    for dp, dn, fn in os.walk(root, topdown=False):
        dn[:] = [d for d in dn if d.lower() not in ('$recycle.bin', 'system volume information')]
        has_audio = [f for f in fn if os.path.splitext(f)[1].lower() in AUDIO]
        names = {n.lower() for n in os.listdir(dp)}
        nums = sum(1 for f in has_audio if re.match(r'^\s*\d{1,3}\s*[-._)\s]', f))
        numbered = bool(has_audio) and nums >= max(2, 0.6 * len(has_audio))
        planned = set()
        # prefixes like "[aR] Movie - 06 - Title" shared by most files of this folder
        prefixes = []
        for f in has_audio:
            b0 = re.sub(r'^\s*\[[^\]]{1,10}\]\s*', '', os.path.splitext(f)[0])
            mm = re.match(r'^(.+?)\s*-\s*(\d{1,3})\s*-\s*(.+)$', b0)
            if mm:
                prefixes.append(re.sub(r'[^a-z0-9]+', '', mm.group(1).lower()))
        allowed = set()
        if prefixes:
            top = max(set(prefixes), key=prefixes.count)
            if prefixes.count(top) >= 0.6 * len(has_audio):
                allowed.add(top)
        for f in sorted(has_audio):
            base, ext = os.path.splitext(f)
            mv = movie_numbered(base, os.path.basename(dp), allowed)
            if mv:
                base, numbered_here = mv, True
            else:
                base = re.sub(r'^\s*\[[^\]]{1,10}\]\s*', '', base)
                numbered_here = numbered
            new = clean_base(base, True, numbered_here)
            if numbered_here:
                new = drop_artist(new, read_artist(os.path.join(dp, f)))
            new = new + ext.lower()
            if new and new != f and not new.startswith('.'):
                key = new.lower()
                clash = (key in names and key != f.lower()) or key in planned
                if clash:
                    rows.append({'type': 'file', 'old': os.path.join(dp, f), 'new': os.path.join(dp, new), 'status': 'skipped: name already used'})
                else:
                    planned.add(key)
                    rows.append({'type': 'file', 'old': os.path.join(dp, f), 'new': os.path.join(dp, new), 'status': 'rename'})
    # folders: only those that hold songs somewhere below, never the top folder itself
    root_n = os.path.normpath(root)
    for dp, dn, fn in os.walk(root, topdown=False):
        if os.path.normpath(dp) == root_n:
            continue
        if not any(os.path.splitext(f)[1].lower() in AUDIO for _, _, files in os.walk(dp) for f in files):
            continue
        parent, name = os.path.split(dp)
        new = clean_base(name, False, False)
        if not new or new == name or re.fullmatch(r'(?i)(flac|wav|mp3|albums?)', name.strip()):
            continue
        sibs = {n.lower() for n in os.listdir(parent)}
        taken_here = taken.setdefault(parent, set())
        key = new.lower()
        if (key in sibs and key != name.lower()) or key in taken_here:
            rows.append({'type': 'folder', 'old': dp, 'new': os.path.join(parent, new), 'status': 'skipped: name already used'})
        else:
            taken_here.add(key)
            rows.append({'type': 'folder', 'old': dp, 'new': os.path.join(parent, new), 'status': 'rename'})
    with open(os.path.join(WORK, 'plan.csv'), 'w', newline='', encoding='utf-8-sig') as fh:
        w = csv.DictWriter(fh, fieldnames=['type', 'status', 'old', 'new'])
        w.writeheader()
        w.writerows(rows)
    c = {}
    for r in rows:
        c[(r['type'], r['status'].split(':')[0])] = c.get((r['type'], r['status'].split(':')[0]), 0) + 1
    print('plan written to', os.path.join(WORK, 'plan.csv'))
    for k, v in sorted(c.items()):
        print('  %-7s %-8s %d' % (k[0], k[1], v))


def run_apply():
    rows = list(csv.DictReader(open(os.path.join(WORK, 'plan.csv'), encoding='utf-8-sig')))
    todo = [r for r in rows if r['status'] == 'rename']
    files = [r for r in todo if r['type'] == 'file']
    folders = sorted((r for r in todo if r['type'] == 'folder'), key=lambda r: -r['old'].count(os.sep))
    log_path = os.path.join(WORK, 'rename-log.json')
    log = json.load(open(log_path, encoding='utf-8')) if os.path.exists(log_path) else []
    done = failed = 0
    for r in files + folders:
        old, new = r['old'], r['new']
        try:
            if not os.path.exists(old):
                raise FileNotFoundError('source is gone')
            if os.path.exists(new) and os.path.normcase(new) != os.path.normcase(old):
                raise FileExistsError('target exists')
            if os.path.normcase(new) == os.path.normcase(old):
                tmp = old + '.__case'
                os.rename(old, tmp)
                os.rename(tmp, new)
            else:
                os.rename(old, new)
            log.append({'type': r['type'], 'old': old, 'new': new})
            done += 1
        except Exception as e:
            failed += 1
            print('SKIPPED %s: %s' % (old, e), flush=True)
        if done and done % 500 == 0:
            json.dump(log, open(log_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    json.dump(log, open(log_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('renamed %d (files %d, folders %d), skipped %d' % (done, len(files), len(folders), failed), flush=True)


def run_undo():
    log_path = os.path.join(WORK, 'rename-log.json')
    log = json.load(open(log_path, encoding='utf-8'))
    back = 0
    for e in reversed(log):
        try:
            if os.path.exists(e['new']) and not os.path.exists(e['old']):
                os.rename(e['new'], e['old'])
                back += 1
        except Exception as ex:
            print('could not restore', e['new'], ex)
    print('restored %d names' % back)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'plan':
        run_plan(sys.argv[2])
    elif cmd == 'apply':
        run_apply()
    elif cmd == 'undo':
        run_undo()
    else:
        print(__doc__)
