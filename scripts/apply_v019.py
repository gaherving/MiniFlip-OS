from pathlib import Path

rotation_path = Path('app/src/main/java/com/marilu/miniflip/RotationController.java')
build_path = Path('app/build.gradle')

text = rotation_path.read_text(encoding='utf-8')

text = text.replace(
    'import java.lang.reflect.Method;\nimport java.util.concurrent.ExecutorService;',
    'import java.lang.reflect.Method;\nimport java.util.ArrayList;\nimport java.util.List;\nimport java.util.concurrent.ExecutorService;',
    1
)

text = text.replace(
    '''                if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) {\n                    displayId = display.getDisplayId();\n                }''',
    '''                if (display != null) {\n                    // On some One UI / Flex Window builds the cover task is exposed\n                    // through display 0. Do not discard it just because it is the\n                    // default display.\n                    displayId = display.getDisplayId();\n                }''',
    1
)

text = text.replace(
    '''                if (requested >= 0 && requested != Display.DEFAULT_DISPLAY) {''',
    '''                if (requested >= 0) {''',
    1
)

anchor = '''        private boolean coverDisplayIsOn() {\n            if (displayManager == null) return true;\n            Display cover = displayManager.getDisplay(coverDisplayId);\n            return cover != null && cover.getState() != Display.STATE_OFF;\n        }\n\n'''
helper = '''        private boolean coverDisplayIsOn() {\n            if (displayManager == null) return true;\n            Display cover = displayManager.getDisplay(coverDisplayId);\n            return cover != null && cover.getState() != Display.STATE_OFF;\n        }\n\n        private int[] activeDisplayIds() {\n            List<Integer> ids = new ArrayList<>();\n\n            if (displayManager != null) {\n                try {\n                    Display[] displays = displayManager.getDisplays();\n                    if (displays != null) {\n                        for (Display display : displays) {\n                            if (display == null || display.getState() == Display.STATE_OFF) continue;\n                            int id = display.getDisplayId();\n                            if (!ids.contains(id)) ids.add(id);\n                        }\n                    }\n                } catch (Throwable ignored) {}\n            }\n\n            if (!ids.contains(coverDisplayId)) ids.add(coverDisplayId);\n            if (!ids.contains(Display.DEFAULT_DISPLAY)) ids.add(Display.DEFAULT_DISPLAY);\n\n            int[] result = new int[ids.size()];\n            for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);\n            return result;\n        }\n\n'''
if anchor not in text:
    raise SystemExit('Could not find coverDisplayIsOn anchor')
text = text.replace(anchor, helper, 1)

old = '''        private void prepareExternalDisplayRotation() {\n            if (!hasShizukuPermission() || externalRotationPrepared) return;\n\n            externalRotationPrepared = true;\n            final int displayId = coverDisplayId;\n            shellExecutor.execute(() -> {\n                boolean fixed = runShell(\n                        "wm fixed-to-user-rotation -d " + displayId + " enabled"\n                                + " || wm set-fix-to-user-rotation -d " + displayId + " enabled"\n                );\n\n                // This is the important part for third-party apps: their own requested\n                // portrait/landscape orientation must not be allowed to override the cover display.\n                boolean ignoreRequests = runShell(\n                        "wm set-ignore-orientation-request -d " + displayId + " true"\n                );\n\n                if (!fixed || !ignoreRequests) {\n                    externalRotationPrepared = false;\n                }\n            });\n        }\n'''
new = '''        private void prepareExternalDisplayRotation() {\n            if (!hasShizukuPermission() || externalRotationPrepared) return;\n\n            externalRotationPrepared = true;\n            final int[] displayIds = activeDisplayIds();\n            shellExecutor.execute(() -> {\n                boolean allOk = true;\n\n                // Samsung can render a Flex Window app through a different active\n                // DisplayContent from the Irving OS activity. Prepare every active\n                // display instead of assuming the cover is always display 1.\n                for (int displayId : displayIds) {\n                    boolean fixed = runShell(\n                            "wm fixed-to-user-rotation -d " + displayId + " enabled"\n                    );\n                    boolean ignoreRequests = runShell(\n                            "wm set-ignore-orientation-request -d " + displayId + " true"\n                    );\n                    allOk = allOk && fixed && ignoreRequests;\n                }\n\n                if (!allOk) externalRotationPrepared = false;\n            });\n        }\n'''
if old not in text:
    raise SystemExit('Could not find prepareExternalDisplayRotation')
text = text.replace(old, new, 1)

old = '''            if (hasShizukuPermission()) {\n                prepareExternalDisplayRotation();\n                final int targetRotation = rotation;\n                final int displayId = coverDisplayId;\n                shellExecutor.execute(() -> runShell(\n                        "wm user-rotation -d " + displayId + " lock " + targetRotation\n                                + " || wm set-user-rotation lock -d " + displayId + " " + targetRotation\n                ));\n                return;\n            }\n'''
new = '''            if (hasShizukuPermission()) {\n                prepareExternalDisplayRotation();\n                final int targetRotation = rotation;\n                final int[] displayIds = activeDisplayIds();\n                shellExecutor.execute(() -> {\n                    for (int displayId : displayIds) {\n                        runShell(\n                                "wm user-rotation -d " + displayId + " lock " + targetRotation\n                        );\n                    }\n\n                    // One UI also consults the global user-rotation setting for\n                    // some Flex Window tasks, so keep it synchronized as a fallback.\n                    runShell("settings put system accelerometer_rotation 0");\n                    runShell("settings put system user_rotation " + targetRotation);\n                });\n                return;\n            }\n'''
if old not in text:
    raise SystemExit('Could not find Shizuku sensor rotation branch')
text = text.replace(old, new, 1)

old = '''            if (hasShizukuPermission()) {\n                final int displayId = coverDisplayId;\n                shellExecutor.execute(() -> {\n                    runShell(\n                            "wm user-rotation -d " + displayId + " free"\n                                    + " || wm set-user-rotation free -d " + displayId\n                    );\n                    runShell("wm set-ignore-orientation-request -d " + displayId + " false");\n                    runShell(\n                            "wm fixed-to-user-rotation -d " + displayId + " default"\n                                    + " || wm set-fix-to-user-rotation -d " + displayId + " disabled"\n                    );\n                });\n            }\n'''
new = '''            if (hasShizukuPermission()) {\n                final int[] displayIds = activeDisplayIds();\n                shellExecutor.execute(() -> {\n                    for (int displayId : displayIds) {\n                        runShell("wm user-rotation -d " + displayId + " free");\n                        runShell("wm set-ignore-orientation-request -d " + displayId + " false");\n                        runShell("wm fixed-to-user-rotation -d " + displayId + " default");\n                    }\n                    runShell("settings put system accelerometer_rotation 1");\n                });\n            }\n'''
if old not in text:
    raise SystemExit('Could not find Shizuku onDestroy branch')
text = text.replace(old, new, 1)

rotation_path.write_text(text, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 18', 'versionCode 19')
build = build.replace(
    "versionName '0.18-irving-os-phone-background-fix'",
    "versionName '0.19-irving-os-all-display-rotation'"
)
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.19 all-display rotation patch')
