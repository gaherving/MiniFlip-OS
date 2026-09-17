from pathlib import Path
import base64
import hashlib

parts_dir = Path('scripts/bg_v022')
parts = []
for name in ('00.b64', '01.b64', '02.b64', '03.b64'):
    path = parts_dir / name
    if not path.exists():
        raise SystemExit(f'Missing wallpaper chunk: {name}')
    parts.append(path.read_text(encoding='utf-8').strip())

data = base64.b64decode(''.join(parts), validate=True)
expected = '968d1b4d3aa88d35c8ebbf8767fafca55d087cc911440b0f623763cb6f279be2'
actual = hashlib.sha256(data).hexdigest()
if actual != expected:
    raise SystemExit(f'Wallpaper SHA mismatch: {actual}')
if not (data.startswith(b'\xff\xd8\xff') and data.endswith(b'\xff\xd9')):
    raise SystemExit('Wallpaper is not a valid JPEG stream')

out = Path('app/src/main/res/drawable-nodpi/irving_default_bg.jpg')
out.parent.mkdir(parents=True, exist_ok=True)
out.write_bytes(data)
print(f'Restored valid Irving OS wallpaper: {len(data)} bytes, sha256={actual}')
