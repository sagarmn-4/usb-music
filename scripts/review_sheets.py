"""
Contact sheets of the folder.jpg covers that could not be verified, so they can be approved by eye.

  python review_sheets.py [output folder]

Reads the results of the batch runs (tags-run\\batch-NN), takes the latest verdict for every folder, and for each
folder whose cover did not match (or could not be checked) draws the cover on the drive next to the official
cover. Covers that look like junk (the same picture in several folders, or a "Downloaded from ..." banner)
are listed separately and left out of the sheets. Nothing on the drive is touched.
"""
import csv
import hashlib
import json
import os
import re
import sys
from collections import defaultdict

from PIL import Image, ImageDraw, ImageFont

BASE = r'D:\Work\AI\usb-music-install\tags-run'
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BASE, 'review')
TILE = 150
PER_SHEET = 24            # tiles per sheet (6 columns x 4 rows)
COLS = 6

sys.stdout.reconfigure(encoding='utf-8')


def latest_state():
    nums = sorted(int(m.group(1)) for m in (re.fullmatch(r'batch-(\d+)', x) for x in os.listdir(BASE)) if m)
    verdict, info = {}, {}
    for n in nums:
        w = os.path.join(BASE, 'batch-%02d' % n)
        cc = os.path.join(w, 'covers-check.csv')
        fj = os.path.join(w, 'folders.json')
        if os.path.exists(cc):
            for r in csv.DictReader(open(cc, encoding='utf-8-sig')):
                verdict[r['dir']] = (r['verdict'], r['ncc'])
        if os.path.exists(fj):
            for f in json.load(open(fj, encoding='utf-8')):
                info[f['dir']] = f
    return verdict, info


def looks_like_banner(im):
    """Bright lime or yellow banners such as 'Downloaded from ...' covers."""
    g = im.convert('RGB').resize((48, 48))
    lime = 0
    for r, gg, b in g.getdata():
        if gg > 170 and r > 120 and b < 90 and gg >= r:
            lime += 1
    return lime / (48 * 48) > 0.30


def main():
    os.makedirs(OUT, exist_ok=True)
    verdict, info = latest_state()
    cands = []
    for d, (v, ncc) in verdict.items():
        jpg = os.path.join(d, 'folder.jpg')
        if (v == 'MISMATCH' or v.startswith('unverified')) and os.path.isfile(jpg):
            cands.append(d)
    cands.sort()

    # the same picture in several folders is a placeholder, not a cover
    digest = {d: hashlib.md5(open(os.path.join(d, 'folder.jpg'), 'rb').read()).hexdigest() for d in cands}
    count = defaultdict(int)
    for h in digest.values():
        count[h] += 1
    junk, review = [], []
    for d in cands:
        try:
            im = Image.open(os.path.join(d, 'folder.jpg'))
            small = min(im.size) < 200
            banner = looks_like_banner(im)
        except Exception:
            junk.append((d, 'unreadable image'))
            continue
        if count[digest[d]] >= 3:
            junk.append((d, 'same picture in %d folders' % count[digest[d]]))
        elif banner:
            junk.append((d, 'banner-style picture'))
        elif small:
            junk.append((d, 'very small picture'))
        else:
            review.append(d)

    font = ImageFont.load_default(size=13)
    small_font = ImageFont.load_default(size=11)
    cache = os.path.join(BASE, 'batch-%02d' % 1, 'art-cache')
    rows = []
    for s in range(0, len(review), PER_SHEET):
        chunk = review[s:s + PER_SHEET]
        w = COLS * (TILE * 2 + 12) + 12
        h = ((len(chunk) + COLS - 1) // COLS) * (TILE + 46) + 12
        sheet = Image.new('RGB', (w, h), (18, 18, 18))
        dr = ImageDraw.Draw(sheet)
        for k, d in enumerate(chunk):
            idx = s + k + 1
            x = 12 + (k % COLS) * (TILE * 2 + 12)
            y = 12 + (k // COLS) * (TILE + 46)
            mine = Image.open(os.path.join(d, 'folder.jpg')).convert('RGB').resize((TILE, TILE))
            sheet.paste(mine, (x, y))
            f = info.get(d, {})
            official = None
            art = f.get('art', '')
            if art:
                key = re.sub(r'\W+', '_', art)[-60:]
                for bdir in sorted(os.listdir(BASE)):
                    p = os.path.join(BASE, bdir, 'art-cache', key + '.jpg')
                    if os.path.exists(p):
                        official = Image.open(p).convert('RGB').resize((TILE, TILE))
                        break
            if official is not None:
                sheet.paste(official, (x + TILE, y))
            else:
                dr.rectangle([x + TILE, y, x + 2 * TILE - 1, y + TILE - 1], outline=(70, 70, 70))
                dr.text((x + TILE + 12, y + TILE // 2 - 8), 'no official\ncover to show', fill=(130, 130, 130), font=small_font)
            name = os.path.basename(d)
            dr.text((x, y + TILE + 3), '#%d  %s' % (idx, name[:34]), fill=(255, 255, 255), font=font)
            dr.text((x, y + TILE + 20), 'left: on the drive   right: official', fill=(140, 140, 140), font=small_font)
            rows.append({'number': idx, 'folder': d, 'verdict': verdict[d][0], 'ncc': verdict[d][1],
                         'album': f.get('album', '')})
        sheet.save(os.path.join(OUT, 'sheet-%02d.png' % (s // PER_SHEET + 1)))
    with open(os.path.join(OUT, 'review.csv'), 'w', newline='', encoding='utf-8-sig') as fh:
        wr = csv.DictWriter(fh, fieldnames=['number', 'folder', 'verdict', 'ncc', 'album'])
        wr.writeheader()
        wr.writerows(rows)
    with open(os.path.join(OUT, 'junk.txt'), 'w', encoding='utf-8') as fh:
        for d, why in junk:
            fh.write('%s    (%s)\n' % (d, why))
    print('folders with a cover that did not match or could not be checked:', len(cands))
    print('  left out as junk:', len(junk))
    print('  on the sheets:', len(review), 'in', (len(review) + PER_SHEET - 1) // PER_SHEET, 'sheets ->', OUT)
    for why in sorted({w for _, w in junk}):
        print('    junk (%s): %d' % (why, sum(1 for _, x in junk if x == why)))


if __name__ == '__main__':
    main()
