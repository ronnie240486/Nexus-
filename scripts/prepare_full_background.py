from pathlib import Path
from PIL import Image

src = Path('/home/ubuntu/maximus-tv-player-native/assets/excellence_main_background_3d.png')
out = Path('/home/ubuntu/maximus-tv-player-native/app/src/main/res/drawable-nodpi/excellence_main_background_3d.jpg')
out.parent.mkdir(parents=True, exist_ok=True)
with Image.open(src) as image:
    image = image.convert('RGB')
    image.thumbnail((1920, 1080), Image.Resampling.LANCZOS)
    image.save(out, format='JPEG', quality=84, optimize=True, progressive=True)
print(out, out.stat().st_size)
