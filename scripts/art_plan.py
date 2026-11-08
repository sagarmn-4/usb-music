"""
Decide which picture to embed for the folders whose cover could not be verified, and write plan.json.

  python art_plan.py [review folder]

Uses the review folder made by review_sheets.py:
  - a folder whose album matched but whose own cover differs gets the official cover of that album (600 px);
  - a folder with no confident album keeps its own folder.jpg;
  - the covers listed in junk.txt are left out.
Nothing on the drive is touched.
"""
import csv
import json
import os
import re
import sys
import urllib.request

BASE = r'D:\Work\AI\usb-music-install\tags-run'
REVIEW = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BASE, 'review')
UA = {'User-Agent': 'USBMusic-tags/1.0 (personal music library)'}

sys.stdout.reconfigure(encoding='utf-8')


def latest_info():
    nums = sorted(int(m.group(1)) for m in (re.fullmatch(r'batch-(\d+)', x) for x in os.listdir(BASE)) if m)
    info = {}
    for n in nums:
        fj = os.path.join(BASE, 'batch-%02d' % n, 'folders.json')
        if os.path.exists(fj):
            for f in json.load(open(fj, encoding='utf-8')):
                info[f['dir']] = f
    return info


def main():
    info = latest_info()
    junk = {l.rsplit('    (', 1)[0] for l in open(os.path.join(REVIEW, 'junk.txt'), encoding='utf-8') if l.strip()}
    rows = list(csv.DictReader(open(os.path.join(REVIEW, 'review.csv'), encoding='utf-8-sig')))
    out_dir = os.path.join(REVIEW, 'official-600')
    os.makedirs(out_dir, exist_ok=True)
    plan, official, own, failed = {}, 0, 0, []
    for r in rows:
        d = r['folder']
        if d in junk:
            continue
        f = info.get(d, {})
        if r['verdict'] == 'MISMATCH' and f.get('album') and f.get('art'):
            url = re.sub(r'\d+x\d+bb', '600x600bb', f['art'])
            local = os.path.join(out_dir, re.sub(r'\W+', '_', f['art'])[-60:] + '.jpg')
            if not os.path.exists(local):
                try:
                    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as resp:
                        open(local, 'wb').write(resp.read())
                except Exception as e:
                    failed.append((d, str(e)))
                    continue
            plan[d] = {'source': 'official', 'path': local, 'album': f['album']}
            official += 1
        elif r['verdict'].startswith('unverified'):
            plan[d] = {'source': 'drive', 'path': os.path.join(d, 'folder.jpg'), 'album': ''}
            own += 1
    json.dump(plan, open(os.path.join(REVIEW, 'plan.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('folders planned: %d (official cover: %d, own folder.jpg: %d), could not fetch: %d, junk left out: %d' % (
        len(plan), official, own, len(failed), len(junk)))
    for d, e in failed[:5]:
        print('  fetch failed:', d, e)


if __name__ == '__main__':
    main()
