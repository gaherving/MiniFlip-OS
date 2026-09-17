from pathlib import Path
from PIL import Image

p = Path('app/src/main/res/drawable-nodpi/irving_default_bg.jpg')
if not p.exists():
    raise SystemExit('Missing Irving background')
with Image.open(p) as im:
    im.verify()
print('Irving background valid:', p.stat().st_size, 'bytes')
