from pathlib import Path
from PIL import Image

src = Path('/home/ubuntu/maximus-tv-player-native/assets')
out = Path('/home/ubuntu/maximus-tv-player-native/app/src/main/res/drawable-nodpi')
out.mkdir(parents=True, exist_ok=True)
assets = {
    'excellence_home_hero.png': ('excellence_home_hero.jpg', (1600, 900), 88),
    'home_movies_card.png': ('home_movies_card.jpg', (800, 450), 86),
    'home_series_card.png': ('home_series_card.jpg', (800, 450), 86),
    'home_cartoons_card.png': ('home_cartoons_card.jpg', (800, 450), 86),
}
for source, (target, size, quality) in assets.items():
    image = Image.open(src / source).convert('RGB')
    image.thumbnail(size, Image.Resampling.LANCZOS)
    canvas = Image.new('RGB', size, '#070B15')
    left = (size[0] - image.width) // 2
    top = (size[1] - image.height) // 2
    canvas.paste(image, (left, top))
    canvas.save(out / target, 'JPEG', quality=quality, optimize=True, progressive=True)
    print(target, canvas.size, (out / target).stat().st_size)
