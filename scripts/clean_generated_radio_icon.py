from collections import deque
from pathlib import Path
import numpy as np
from PIL import Image

root = Path('/home/ubuntu/maximus-tv-player-native')
source = root / 'app/src/main/res/drawable-nodpi/nav_radio_3d.png'
target = root / 'app/src/main/res/drawable-nodpi/nav_radio_3d_clean.png'
image = Image.open(source).convert('RGBA').resize((320, 320), Image.Resampling.LANCZOS)
data = np.array(image)
rgb = data[:, :, :3].astype(np.int16)
alpha = data[:, :, 3]
green_fringe = (rgb[:, :, 1] > 55) & (rgb[:, :, 1] > rgb[:, :, 0] * 1.35) & (rgb[:, :, 1] > rgb[:, :, 2] * 1.20)
dark_background = (rgb.max(axis=2) < 42)
candidate = (alpha < 16) | green_fringe | dark_background
visited = np.zeros(candidate.shape, dtype=bool)
queue = deque()
height, width = candidate.shape
for x in range(width):
    if candidate[0, x]: queue.append((0, x))
    if candidate[height - 1, x]: queue.append((height - 1, x))
for y in range(height):
    if candidate[y, 0]: queue.append((y, 0))
    if candidate[y, width - 1]: queue.append((y, width - 1))
while queue:
    y, x = queue.popleft()
    if visited[y, x]:
        continue
    visited[y, x] = True
    for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
        if 0 <= ny < height and 0 <= nx < width and candidate[ny, nx] and not visited[ny, nx]:
            queue.append((ny, nx))
remove = visited | green_fringe
data[remove, 3] = 0
data[:, :, :3][data[:, :, 3] == 0] = 0
Image.fromarray(data, mode='RGBA').save(target, format='PNG', optimize=True)
print(target, target.stat().st_size)
