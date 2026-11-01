"""Validate bundled profile artwork without downloading or executing archive contents."""
import json
from pathlib import Path
from PIL import Image
root = Path(__file__).resolve().parents[2] / 'app/src/main/assets/profile-avatars'
catalog = json.loads((root / 'catalog.json').read_text())
assert catalog['version'] == 1
assert len(catalog['avatars']) == 28
seen = set()
total = 0
for i, item in enumerate(catalog['avatars'], 1):
    key = f'avatar_{i:02d}'
    assert item['id'] == key and item['file'] == key + '.webp'
    assert key not in seen
    seen.add(key)
    file = root / item['file']
    with Image.open(file) as image:
        assert image.size == (384, 384)
        assert image.mode == 'RGBA'
        assert image.getbbox() is not None
    total += file.stat().st_size
assert total < 1024 * 1024
assert {p.name for p in root.iterdir()} == {'catalog.json'} | {a['file'] for a in catalog['avatars']}
print(f'Profile assets: {len(seen)} valid, {total} bytes')
