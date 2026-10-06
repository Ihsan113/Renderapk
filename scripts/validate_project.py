from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []

# XML validation for Android resources/manifest.
for path in list((ROOT/'app/src/main/res').rglob('*.xml')) + [ROOT/'app/src/main/AndroidManifest.xml']:
    try:
        ET.parse(path)
    except Exception as e:
        errors.append(f"XML: {path}: {e}")

# Basic source invariants for the GPU pipeline.
checks = {
    'OffscreenWebViewSource.java': [
        'android.app.Presentation',
        'VIRTUAL_DISPLAY_FLAG_PRESENTATION',
        'SurfaceTexture',
        'FLAG_HARDWARE_ACCELERATED',
        'setOnFrameAvailableListener',
    ],
    'GlVideoRenderer.java': [
        'EGL_RECORDABLE_ANDROID',
        'samplerExternalOES',
        'eglPresentationTimeANDROID',
        'eglSwapBuffers',
    ],
    'HtmlVideoEncoder.java': [
        'COLOR_FormatSurface',
        'createInputSurface',
        'signalEndOfInputStream',
    ],
    'MainActivity.java': [
        'previewWebView',
        'previewStatus',
        'loadPreview',
    ],
}
for name, needles in checks.items():
    candidates = list((ROOT/'app/src/main/java').rglob(name))
    if not candidates:
        errors.append(f"Missing source: {name}")
        continue
    text = candidates[0].read_text(errors='replace')
    for needle in needles:
        if needle not in text:
            errors.append(f"{name}: missing `{needle}`")

# Catch common accidental Java escape sequences in strings introduced by patching.
for path in (ROOT/'app/src/main/java').rglob('*.java'):
    text = path.read_text(errors='replace')
    if re.search(r'\\;', text):
        errors.append(f"Java: suspicious escape \\\\; in {path}")

if errors:
    print('VALIDATION FAILED')
    for e in errors: print('-', e)
    raise SystemExit(1)
print('VALIDATION OK')
print('Android compilation must still be run by Gradle/GitHub Actions with the Android SDK.')
