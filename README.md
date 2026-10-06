# HTML Render Studio MVP 0.2

Mobile-first Android renderer for turning HTML/CSS/JS animations into MP4.

## Pipeline

HTML/CSS/JS → hardware-accelerated WebView → reusable OpenGL ES texture → MediaCodec Surface → H.264/HEVC MP4.

The renderer avoids per-frame texture allocation by uploading the captured frame into one reusable GPU texture and drawing that texture directly to the encoder input surface.

## Included features

- Hardware H.264/AVC and HEVC encoder detection through MediaCodec.
- Software fallback when a surface encoder exists but Android reports it as software.
- Bitrate control from 2–30 Mbps and four quality presets; VBR is used when supported by the selected encoder.
- Alpha-aware GPU stage with opaque, black composite, and white composite modes.
- Batch import of multiple HTML files and sequential queue rendering.
- Foreground render session so the process receives foreground-service protection while rendering; tapping Back/minimize keeps the render Activity task in the background.
- Progress in the app and foreground notification.
- Built-in visual templates: Business Growth, Finance Motion, Tech Glow.
- Output to `Movies/HTMLRenderStudio` through MediaStore.

## Important alpha note

H.264 and normal HEVC MP4 output does not preserve a transparent alpha channel in this pipeline. The alpha stage is therefore used for GPU-side compositing before the video encoder. A future transparent-output format (for example a dedicated alpha-capable workflow) would be a separate encoder/container feature.

## Background rendering note

The foreground service keeps the rendering session in the foreground process. The WebView itself still lives in `RenderActivity`, because PixelCopy currently captures from its Window. This MVP is intended to establish the end-to-end architecture first; a future engine can move the WebView to a dedicated off-screen/virtual-display renderer for UI-free background rendering.

## Build

GitHub Actions workflow: `.github/workflows/build.yml`

Build command:

```bash
gradle --no-daemon :app:assembleDebug
```

The workflow uploads `app-debug.apk` as the `HTMLRenderStudio-debug` artifact.

## 0.2.1 build fix
- Fixed `Presentation` import: `android.app.Presentation` (not `android.view.Presentation`).
