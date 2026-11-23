"""Report native-size frame variation and invariant alpha for a staged pack."""
import json
from functools import reduce
from pathlib import Path
from PIL import Image, ImageChops

root = Path(__file__).resolve().parents[1]
pack = root / 'build/catalog-32-v2'
results = []
for entry in json.loads((pack / 'catalog-animations.json').read_text(encoding='utf-8-sig')):
    item = {'id': entry['id']}
    for size, key in ((32, 'texture'), (64, 'handheld_texture')):
        path = pack / 'resourcepack/assets' / entry['namespace'] / 'textures/item' / (entry[key] + '.png')
        with Image.open(path).convert('RGBA') as strip:
            assert strip.size == (size, size * 16), path
            frames = [strip.crop((0, i * size, size, (i + 1) * size)) for i in range(16)]
            assert len({f.getchannel('A').tobytes() for f in frames}) == 1, path
            distinct = len({f.tobytes() for f in frames})
            rgb = [frame.convert('RGB') for frame in frames]
            high = reduce(ImageChops.lighter, rgb)
            low = reduce(ImageChops.darker, rgb)
            delta = reduce(ImageChops.lighter, ImageChops.subtract(high, low).split())
            peak = delta.getextrema()[1]
            changing = sum(delta.histogram()[8:])
            item[str(size)] = {'distinct_frames': distinct, 'peak_rgb_delta': peak, 'pixels_delta_at_least_8': changing}
    item['needs_motion_review'] = any(item[str(s)]['distinct_frames'] < 3 or item[str(s)]['pixels_delta_at_least_8'] < 3 for s in (32, 64))
    results.append(item)
output = root / 'docs/design/catalog-32-v2/previews/motion-checks.json'
output.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'count': len(results), 'needs_review': [r for r in results if r['needs_motion_review']]}, ensure_ascii=False, indent=2))
