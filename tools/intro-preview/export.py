#!/usr/bin/env python3
"""Read-only, 1883-only pilot export. Does not certify detection accuracy or scan media."""
import argparse
import json
import os
import sqlite3
import tempfile
from pathlib import Path


def export(root):
    root = Path(root).resolve()
    with sqlite3.connect((root / 'jellyfin.db').as_uri() + '?mode=ro', uri=True) as db:
        db.execute('PRAGMA query_only=ON')
        db.execute('ATTACH DATABASE ? AS intro', ((root / 'introskipper/introskipper-v2.db').as_uri() + '?mode=ro',))
        rows = db.execute('''
            SELECT p.ProviderValue, e.ParentIndexNumber, e.IndexNumber,
                   e.RunTimeTicks, s.StartTicks, s.EndTicks
            FROM BaseItems e
            JOIN BaseItemProviders p ON p.ItemId=e.SeriesId AND lower(p.ProviderId)='tmdb'
            JOIN intro.Segments s ON lower(replace(e.Id,'-',''))=lower(replace(s.ItemId,'-',''))
            WHERE p.ProviderValue='118357' AND e.Path LIKE '/media/series/1883/%'
                AND s.Type=0 AND s.State=0
                AND NOT EXISTS (SELECT 1 FROM intro.DisabledItems d
                    WHERE lower(replace(d.ItemId,'-',''))=lower(replace(e.Id,'-','')))
            ORDER BY e.ParentIndexNumber,e.IndexNumber
        ''').fetchall()
    markers = []
    seen = set()
    for tmdb, season, episode, duration, start, end in rows:
        key = (tmdb, season, episode)
        if key in seen:
            raise ValueError('Ambiguous episode markers; nothing exported')
        seen.add(key)
        if not all(isinstance(x, int) for x in (season, episode, duration, start, end)):
            raise ValueError('Incomplete marker metadata')
        if season < 0 or episode < 1 or not 0 <= start < end < duration or end-start > 180*10_000_000:
            raise ValueError('Invalid marker range')
        markers.append(dict(tmdb=tmdb, season=season, episode=episode,
                            duration_ms=duration//10000, start_ms=start//10000, end_ms=end//10000))
    if not markers:
        raise ValueError('No pilot markers found; nothing exported')
    return dict(schema=1, preview_only=True, accuracy_verified=False, markers=markers)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', default='/opt/jellyfin/config/data')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    payload = export(args.data)
    target = Path(args.output).resolve()
    if target.suffix != '.json':
        parser.error('Output must end in .json')
    data_root = Path(args.data).resolve()
    if data_root == target or data_root in target.parents:
        parser.error('Export outside Jellyfin data directory')
    with tempfile.NamedTemporaryFile(mode='w', dir=target.parent, delete=False) as f:
        json.dump(payload, f, separators=(',', ':'))
        f.write('\n')
        temp = f.name
    os.replace(temp, target)
    print('Exported %d preview markers to %s' % (len(payload['markers']), target))


if __name__ == '__main__':
    main()
