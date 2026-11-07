#!/usr/bin/env python3
"""Export per-show Preview intro files from read-only Jellyfin databases."""
import argparse
from collections import Counter, defaultdict
from contextlib import closing
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile
import zipfile


def norm(value):
    return str(value).replace('-', '').lower()


def rows(path, query):
    with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)) as db:
        return db.execute(query).fetchall()


def collect(root):
    intro = root / 'introskipper/introskipper-v2.db'
    disabled = {norm(i) for i, in rows(intro, 'SELECT ItemId FROM DisabledItems')}
    segments = defaultdict(list)
    for item, start, end in rows(intro, 'SELECT ItemId, StartTicks, EndTicks FROM Segments WHERE Type=0 AND State=0'):
        segments[norm(item)].append((start, end))
    # Count all episode identities before filtering, so even an unmarked alternate cut blocks ambiguity.
    episodes = defaultdict(set)
    for item, tmdb, season, episode, duration in rows(root / 'jellyfin.db', """
        SELECT e.Id, p.ProviderValue, e.ParentIndexNumber, e.IndexNumber, e.RunTimeTicks
        FROM BaseItems e JOIN BaseItemProviders p ON p.ItemId=e.SeriesId
        WHERE lower(p.ProviderId)='tmdb' AND e.Path LIKE '/media/series/%'
    """):
        tmdb = str(tmdb)
        if re.fullmatch(r'[1-9][0-9]{0,11}', tmdb) and type(season) is int and type(episode) is int and season >= 0 and episode >= 1:
            episodes[(tmdb, season, episode)].add((norm(item), duration))
    files, skipped = defaultdict(list), Counter()
    for (tmdb, season, episode), versions in sorted(episodes.items()):
        if not any(item in segments for item, _ in versions):
            continue
        if len(versions) != 1:
            skipped['ambiguous'] += 1
            continue
        item, duration = next(iter(versions))
        if item in disabled or len(segments[item]) != 1:
            skipped['disabled or multiple segments'] += 1
            continue
        start, end = segments[item][0]
        if all(type(v) is int for v in (duration, start, end)):
            start, end, duration = (v // 10000 for v in (start, end, duration))
            if 0 <= start < end < duration <= 86400000 and end-start <= 180000:
                files[tmdb].append(dict(tmdb=tmdb, season=season, episode=episode,
                                        duration_ms=duration, start_ms=start, end_ms=end))
                continue
        skipped['invalid timing'] += 1
    return files, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', default='/opt/jellyfin/config/data')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root, target = Path(args.data).resolve(), Path(args.output).resolve()
    if target.suffix != '.zip' or root in target.parents:
        parser.error('Choose a .zip output outside Jellyfin data')
    files, skipped = collect(root)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as f:
        temp = Path(f.name)
    shows = count = 0
    try:
        with zipfile.ZipFile(temp, 'w', zipfile.ZIP_DEFLATED) as archive:
            for tmdb, markers in files.items():
                payload = json.dumps(dict(schema=1, preview_only=True, accuracy_verified=False,
                                          markers=markers), separators=(',', ':'))
                if len(markers) > 10000 or len(payload) > 1024*1024:
                    skipped['oversized show'] += len(markers)
                    continue
                archive.writestr('intro-preview-shows/' + tmdb + '.json', payload)
                count += len(markers)
                shows += 1
        if not shows:
            raise SystemExit('No usable markers; no export written')
        os.replace(temp, target)
    finally:
        if temp.exists():
            temp.unlink()
    print('Exported %d episodes across %d shows to %s' % (count, shows, target))
    print('Skipped:', dict(skipped))


if __name__ == '__main__':
    main()
