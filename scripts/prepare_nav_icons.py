from collections import deque
from pathlib import Path
import numpy as np
from PIL import Image

root = Path('/home/ubuntu/maximus-tv-player-native')
src_dir = root / 'assets'
out_dir = root / 'app/src/main/res/drawable-nodpi'
out_dir.mkdir(parents=True, exist_ok=True)
icons = {
    'home': 'icon_home_transparent.png',
    'live': 'icon_live_transparent.png',
    'movies': 'icon_movies_transparent.png',
    'series': 'icon_series_transparent.png',
    'favorites': 'icon_favorites_transparent.png',
    'settings': 'icon_settings_transparent.png',
}

for name, filename in icons.items():
    src = src_dir / filename
    with Image.open(src) as original:
        image = original.convert('RGBA').resize((320, 320), Image.Resampling.LANCZOS)
    data = np.array(image)
    rgb = data[:, :, :3].astype(np.int16)
    alpha = data[:, :, 3]
    green_fringe = (rgb[:, :, 1] > 55) & (rgb[:, :, 1] > rgb[:, :, 0] * 1.35) & (rgb[:, :, 1] > rgb[:, :, 2] * 1.20)
    dark_background = (rgb.max(axis=2) < 28)
    candidate = (alpha < 16) | green_fringe | dark_background
    visited = np.zeros(candidate.shape, dtype=bool)
    queue = deque()
    h, w = candidate.shape
    for x in range(w):
        if candidate[0, x]: queue.append((0, x))
        if candidate[h - 1, x]: queue.append((h - 1, x))
    for y in range(h):
        if candidate[y, 0]: queue.append((y, 0))
        if candidate[y, w - 1]: queue.append((y, w - 1))
    while queue:
        y, x = queue.popleft()
        if visited[y, x]:
            continue
        visited[y, x] = True
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if 0 <= ny < h and 0 <= nx < w and candidate[ny, nx] and not visited[ny, nx]:
                queue.append((ny, nx))
    remove = visited | green_fringe
    data[remove, 3] = 0
    data[:, :, :3][data[:, :, 3] == 0] = 0
    out = out_dir / f'nav_{name}_3d.png'
    Image.fromarray(data, mode='RGBA').save(out, format='PNG', optimize=True)
    print(out, out.stat().st_size)
