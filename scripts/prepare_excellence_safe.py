from pathlib import Path
from PIL import Image

project = Path('/home/ubuntu/maximus-tv-player-native')
source = project / 'app/src/main/res/drawable-nodpi/excellence_logo.png'
target = project / 'app/src/main/res/drawable-nodpi/excellence_logo_safe.png'
image = Image.open(source).convert('RGBA')
canvas = Image.new('RGBA', image.size, (8, 11, 22, 255))
inner = image.resize((int(image.width * 0.78), int(image.height * 0.78)), Image.Resampling.LANCZOS)
left = (canvas.width - inner.width) // 2
top = (canvas.height - inner.height) // 2
canvas.alpha_composite(inner, (left, top))
canvas.save(target, format='PNG', optimize=True)
print(target)
