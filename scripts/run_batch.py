"""
One batch of the tag and cover job: the next 100 folders of the drive that have not been done yet.

  python run_batch.py            look the albums up (cached), check the folder.jpg covers, write the tags and covers, no backups

Each run does exactly one batch and prints which folders were done and what happened to each. Run it again
for the next batch. `done.json` in the work folder remembers the folders that are finished.
"""
import csv
import json
import os
import shutil
import subprocess
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = r'F:\Music'
BASE = r'D:\Work\AI\usb-music-install\tags-run'
OLD_CACHE = r'D:\Work\AI\usb-music-install\tags\cache.json'
OLD_LOG = r'D:\Work\AI\usb-music-install\tags\write-log.json'
SIZE = 100
AUDIO = ('.mp3', '.flac', '.m4a', '.wav')

sys.stdout.reconfigure(encoding='utf-8')


def run(args):
    env = dict(os.environ, PYTHONIOENCODING='utf-8')
    p = subprocess.run([sys.executable, '-I', os.path.join(TOOLS, 'artist_tags.py')] + args, env=env)
    return p.returncode


def main():
    os.makedirs(BASE, exist_ok=True)
    cache = os.path.join(BASE, 'cache.json')
    if not os.path.exists(cache) and os.path.exists(OLD_CACHE):
        shutil.copy2(OLD_CACHE, cache)
    done_path = os.path.join(BASE, 'done.json')
    if os.path.exists(done_path):
        done = set(json.load(open(done_path, encoding='utf-8')))
    else:
        done = set()
        if os.path.exists(OLD_LOG):          # the ten folders written earlier
            for e in json.load(open(OLD_LOG, encoding='utf-8')):
                if e.get('status') == 'ok':
                    done.add(os.path.dirname(e['path']))

    folders = []
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d.lower() not in ('$recycle.bin', 'system volume information')]
        if any(os.path.splitext(f)[1].lower() in AUDIO for f in fn):
            folders.append(dp)
    folders.sort()
    todo = [d for d in folders if d not in done][:SIZE]
    if not todo:
        print('ALL FOLDERS DONE', flush=True)
        return 0
    import re
    nums = [int(m.group(1)) for m in (re.fullmatch(r'batch-(\d+)', x) for x in os.listdir(BASE)) if m]
    n = max(nums, default=0) + 1
    work = os.path.join(BASE, 'batch-%02d' % n)
    os.makedirs(work, exist_ok=True)
    dirs_file = os.path.join(work, 'dirs.txt')
    open(dirs_file, 'w', encoding='utf-8').write('\n'.join(todo))
    print('BATCH %d: %d folders (%d folders left in total before this batch)' % (n, len(todo), len(folders) - len(done)), flush=True)

    if run(['dryrun', ROOT, '--work', work, '--cache', cache, '--dirs-file', dirs_file]) != 0:
        print('lookup step failed; nothing was written', flush=True)
        return 2
    run(['coverscheck', ROOT, '--work', work])
    rc = run(['write', '--report', os.path.join(work, 'report.csv'), '--work', work,
              '--batch', str(SIZE), '--no-backup'])

    # what happened to each folder
    log = json.load(open(os.path.join(work, 'write-log.json'), encoding='utf-8')) if os.path.exists(os.path.join(work, 'write-log.json')) else []
    wrote = {}
    failed = set()
    for e in log:
        d = os.path.dirname(e['path'])
        if e['status'] == 'ok':
            wrote[d] = wrote.get(d, 0) + 1
        else:
            failed.add(d)
    info = {f['dir']: f for f in json.load(open(os.path.join(work, 'folders.json'), encoding='utf-8'))}
    verdict = {}
    cc = os.path.join(work, 'covers-check.csv')
    if os.path.exists(cc):
        verdict = {r['dir']: r['verdict'] for r in csv.DictReader(open(cc, encoding='utf-8-sig'))}
    lines = []
    for d in todo:
        name = os.path.relpath(d, ROOT)
        if d in failed:
            lines.append('FAILED  %s' % name)
        elif d in wrote:
            lines.append('done    %-60s %d files updated' % (name, wrote[d]))
        else:
            f = info.get(d, {})
            why = f.get('why') or ('cover %s' % verdict.get(d, 'not checked') if verdict.get(d) != 'match' else 'already complete')
            lines.append('skipped %-60s %s' % (name, why))
    if failed:
        stop_at = min(todo.index(d) for d in failed)
        finished = todo[:stop_at]
    else:
        finished = todo
    done.update(finished)
    json.dump(sorted(done), open(done_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('\n'.join(lines), flush=True)
    print('BATCH %d COMPLETE: %d folders finished, %d files updated, %d folders skipped, %d failed' % (
        n, len(finished), sum(wrote.values()), sum(1 for l in lines if l.startswith('skipped')), len(failed)), flush=True)
    return 3 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
