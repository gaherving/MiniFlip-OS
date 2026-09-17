from pathlib import Path

main_path = Path('app/src/main/java/com/marilu/miniflip/MainActivity.java')
build_path = Path('app/build.gradle')

text = main_path.read_text(encoding='utf-8')

old = '''    private float homeDownX;\n    private float homeDownY;\n    private boolean homeStartedOnInteractive;\n    private boolean homeHoldTriggered;\n    private boolean homeSwipeTriggered;\n'''
new = '''    private float homeDownX;\n    private float homeDownY;\n    private boolean homeStartedOnInteractive;\n    private boolean homeHoldTriggered;\n    private boolean homeSwipeTriggered;\n\n    private float appsDownX;\n    private float appsDownY;\n    private boolean appsSwipeTriggered;\n'''
if old not in text:
    raise SystemExit('Could not find home gesture fields')
text = text.replace(old, new, 1)

old = '''    private FrameLayout buildAppsPage() {\n        FrameLayout page = new FrameLayout(this);\n'''
new = '''    private FrameLayout buildAppsPage() {\n        GestureAppsLayout page = new GestureAppsLayout(this);\n'''
if old not in text:
    raise SystemExit('Could not find buildAppsPage header')
text = text.replace(old, new, 1)

anchor = '''    private boolean isHomeInteractiveAt(float rawX, float rawY) {\n'''
method = '''    private boolean handleAppsGesture(MotionEvent event) {\n        if (currentPage != PAGE_APPS) return false;\n\n        switch (event.getActionMasked()) {\n            case MotionEvent.ACTION_DOWN:\n                appsDownX = event.getX();\n                appsDownY = event.getY();\n                appsSwipeTriggered = false;\n                return false;\n\n            case MotionEvent.ACTION_MOVE:\n                if (appsSwipeTriggered) return true;\n\n                float dx = event.getX() - appsDownX;\n                float dy = event.getY() - appsDownY;\n\n                // Horizontal swipe to the left returns to Home, while vertical\n                // scrolling in the app drawer continues to work normally.\n                if (dx < -dp(44)\n                        && Math.abs(dx) > Math.abs(dy) * 1.18f\n                        && Math.abs(dy) < dp(96)) {\n                    appsSwipeTriggered = true;\n                    if (search != null) search.clearFocus();\n                    showPage(PAGE_HOME);\n                    return true;\n                }\n                return false;\n\n            case MotionEvent.ACTION_UP:\n            case MotionEvent.ACTION_CANCEL:\n                return appsSwipeTriggered;\n\n            default:\n                return false;\n        }\n    }\n\n'''
if anchor not in text:
    raise SystemExit('Could not find home interactive anchor')
text = text.replace(anchor, method + anchor, 1)

old = '''    private void magnifyDockAt(float x, float y) {\n        int active = dockHitIndex(x, y);\n        if (active < 0) {\n            restoreDockScale();\n            return;\n        }\n\n        float maxScale = getDockMaxScale();\n        for (int i = 0; i < dock.getChildCount(); i++) {\n            View child = dock.getChildAt(i);\n            int distance = Math.abs(i - active);\n            float scale;\n            if (distance == 0) scale = maxScale;\n            else if (distance == 1) scale = 1f + ((maxScale - 1f) * 0.42f);\n            else scale = 1f;\n\n            child.animate().cancel();\n            child.animate()\n                    .scaleX(scale)\n                    .scaleY(scale)\n                    .translationY(scale > 1f ? -dp(5) : 0f)\n                    .setInterpolator(dockInterpolator)\n                    .setDuration(55)\n                    .start();\n        }\n    }\n\n    private void restoreDockScale() {\n        if (dock == null) return;\n        for (int i = 0; i < dock.getChildCount(); i++) {\n            View child = dock.getChildAt(i);\n            child.animate().cancel();\n            child.animate()\n                    .scaleX(1f)\n                    .scaleY(1f)\n                    .translationY(0f)\n                    .setInterpolator(dockInterpolator)\n                    .setDuration(95)\n                    .start();\n        }\n    }\n'''
new = '''    private void magnifyDockAt(float x, float y) {\n        int active = dockHitIndex(x, y);\n        if (active < 0) {\n            restoreDockScale();\n            return;\n        }\n\n        // Apply scale directly on each motion event instead of restarting a\n        // ViewPropertyAnimator every few pixels. This removes the lag that was\n        // visible when sliding quickly across the dock.\n        float maxScale = getDockMaxScale();\n        float influenceRadius = Math.max(dp(42), dp(getDockIconSizeDp() + 28));\n\n        for (int i = 0; i < dock.getChildCount(); i++) {\n            View child = dock.getChildAt(i);\n            float centerX = (child.getLeft() + child.getRight()) / 2f;\n            float distance = Math.abs(x - centerX);\n            float influence = 1f - Math.min(1f, distance / influenceRadius);\n            influence = influence * influence * (3f - 2f * influence);\n            float scale = 1f + ((maxScale - 1f) * influence);\n\n            child.animate().cancel();\n            child.setPivotX(child.getWidth() / 2f);\n            child.setPivotY(child.getHeight());\n            child.setScaleX(scale);\n            child.setScaleY(scale);\n\n            float liftRatio = maxScale <= 1f ? 0f : (scale - 1f) / (maxScale - 1f);\n            child.setTranslationY(-dp(6) * liftRatio);\n        }\n    }\n\n    private void restoreDockScale() {\n        if (dock == null) return;\n        for (int i = 0; i < dock.getChildCount(); i++) {\n            View child = dock.getChildAt(i);\n            child.animate().cancel();\n            child.animate()\n                    .scaleX(1f)\n                    .scaleY(1f)\n                    .translationY(0f)\n                    .setInterpolator(dockInterpolator)\n                    .setDuration(60)\n                    .start();\n        }\n    }\n'''
if old not in text:
    raise SystemExit('Could not find dock magnification methods')
text = text.replace(old, new, 1)

anchor = '''    private static class DragPayload {\n'''
inner = '''    private class GestureAppsLayout extends FrameLayout {\n        GestureAppsLayout(Context context) {\n            super(context);\n            setClickable(true);\n        }\n\n        @Override\n        public boolean dispatchTouchEvent(MotionEvent event) {\n            boolean consume = handleAppsGesture(event);\n            if (consume) return true;\n            return super.dispatchTouchEvent(event);\n        }\n    }\n\n'''
if anchor not in text:
    raise SystemExit('Could not find DragPayload anchor')
text = text.replace(anchor, inner + anchor, 1)

main_path.write_text(text, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace("versionCode 15", "versionCode 16")
build = build.replace("versionName '0.15-irving-os-visual-assets'", "versionName '0.16-irving-os-fluid-dock-swipe-back'")
build_path.write_text(build, encoding='utf-8')

print('Applied Irving OS v0.16 interaction patch')
