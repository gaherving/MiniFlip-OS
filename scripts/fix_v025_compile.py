from pathlib import Path

path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
text = path.read_text(encoding='utf-8')
old = '''    private void openLocalAdbSetup() {\n        try {\n            Intent intent = new Intent(this, IrvingAdbSetupActivity.class);\n            Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(currentDisplayId()).toBundle();\n            startActivity(intent, options);\n        } catch (Throwable e) {\n            startActivity(new Intent(this, IrvingAdbSetupActivity.class));\n        }\n    }\n'''
new = '''    private void openLocalAdbSetup() {\n        try {\n            startActivity(new Intent(this, IrvingAdbSetupActivity.class));\n        } catch (Throwable e) {\n            Toast.makeText(this, "No se pudo abrir la configuración de funciones avanzadas", Toast.LENGTH_LONG).show();\n        }\n    }\n'''
if old not in text:
    raise SystemExit('v0.25 setup block not found')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Fixed Irving OS v0.25 setup activity launch')
