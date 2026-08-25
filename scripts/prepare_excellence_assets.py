from pathlib import Path
from PIL import Image

source = Path('/home/ubuntu/upload/file_00000000b05c81f7b26dfd5cad5e9b96.png')
project = Path('/home/ubuntu/maximus-tv-player-native')
image = Image.open(source).convert('RGBA')

# Preserve the provided artwork as the main high-resolution resource.
main = project / 'app/src/main/res/drawable-nodpi/excellence_logo.png'
main.parent.mkdir(parents=True, exist_ok=True)
image.save(main, format='PNG', optimize=True)

# Generate launcher sizes from the same logo, keeping the gold-on-navy identity.
for density, size in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    directory = project / f'app/src/main/res/mipmap-{density}'
    directory.mkdir(parents=True, exist_ok=True)
    resized = image.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(directory / 'ic_launcher.png', format='PNG', optimize=True)

# A larger TV banner resource is useful for launcher/leanback surfaces.
banner = image.resize((512, 512), Image.Resampling.LANCZOS)
banner.save(project / 'app/src/main/res/drawable-nodpi/excellence_banner.png', format='PNG', optimize=True)

print(main)
print(project / 'app/src/main/res/drawable-nodpi/excellence_banner.png')
