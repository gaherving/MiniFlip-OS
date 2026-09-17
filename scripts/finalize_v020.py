from pathlib import Path

build_path = Path('app/build.gradle')
build = build_path.read_text(encoding='utf-8')

if 'buildFeatures {' not in build:
    build = build.replace(
        '    compileSdk 35\n',
        '    compileSdk 35\n\n    buildFeatures {\n        aidl true\n    }\n',
        1
    )

build = build.replace('versionCode 19', 'versionCode 20')
build = build.replace(
    "versionName '0.19-irving-os-all-display-rotation'",
    "versionName '0.20-irving-os-userservice-rotation'"
)
build_path.write_text(build, encoding='utf-8')
print('Finalized Irving OS v0.20 build version with AIDL enabled')
