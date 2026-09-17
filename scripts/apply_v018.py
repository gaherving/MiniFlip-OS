from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
build_path = Path('app/build.gradle')

text = main_path.read_text(encoding='utf-8')
text = text.replace(
    'wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);',
    'wallpaperView.setScaleType(ImageView.ScaleType.FIT_XY);',
    1
)
main_path.write_text(text, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 17', 'versionCode 18')
build = build.replace(
    "versionName '0.17-irving-os-cover-launch'",
    "versionName '0.18-irving-os-phone-background-fix'"
)
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.18 phone/background finishing patch')
