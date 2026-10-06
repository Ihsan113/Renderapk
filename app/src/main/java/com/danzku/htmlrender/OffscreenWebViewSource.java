package com.danzku.htmlrender;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.Uri;
import android.os.Handler;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebView source hosted on a hardware-accelerated Presentation/VirtualDisplay.
 *
 * Primary capture path for MVP 0.2.4 is a deterministic WebView->Bitmap snapshot,
 * followed by GPU texture upload into the MediaCodec input surface. The direct
 * SurfaceTexture/OES path remains available in GlVideoRenderer for a later
 * zero-copy mode, but the bitmap path is used here because it is much more
 * reliable across Android/WebView vendor implementations.
 */
public final class OffscreenWebViewSource {
    private final Context context;
    private final Handler mainHandler;
    private final int width;
    private final int height;
    private final android.graphics.SurfaceTexture texture;
    private final Surface surface;
    private VirtualDisplay virtualDisplay;
    private Presentation presentation;
    private WebView webView;
    private final CountDownLatch pageReady = new CountDownLatch(1);

    public OffscreenWebViewSource(Context context, int width, int height,
                                  android.graphics.SurfaceTexture texture, Handler mainHandler) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.texture = texture;
        this.surface = new Surface(texture);
        this.mainHandler = mainHandler;
    }

    /** Kept for direct SurfaceTexture mode compatibility. */
    public void attachFrameListener() {
        if (texture == null) return;
        texture.setOnFrameAvailableListener(t -> { }, mainHandler);
    }

    public void start(File htmlFile) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        mainHandler.post(() -> {
            try {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                if (dm == null) throw new IllegalStateException("DisplayManager unavailable");
                virtualDisplay = dm.createVirtualDisplay(
                        "HTMLRenderStudio-" + System.currentTimeMillis(),
                        width, height, 160, surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                if (virtualDisplay == null) throw new IllegalStateException("VirtualDisplay unavailable");

                Display display = virtualDisplay.getDisplay();
                if (display == null || !display.isValid()) {
                    throw new IllegalStateException("VirtualDisplay display invalid");
                }

                presentation = new Presentation(context, display);
                Window window = presentation.getWindow();
                if (window == null) throw new IllegalStateException("Presentation window unavailable");
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                window.setGravity(Gravity.TOP | Gravity.START);
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = width;
                lp.height = height;
                lp.gravity = Gravity.TOP | Gravity.START;
                window.setAttributes(lp);

                FrameLayout root = new FrameLayout(context);
                root.setBackgroundColor(Color.BLACK);
                root.setLayoutParams(new FrameLayout.LayoutParams(width, height));

                webView = new WebView(context);
                webView.setBackgroundColor(Color.BLACK);
                webView.setLayerType(View.LAYER_TYPE_NONE, null);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
                }
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                settings.setMediaPlaybackRequiresUserGesture(false);
                settings.setUseWideViewPort(false);
                settings.setLoadWithOverviewMode(false);
                if (android.os.Build.VERSION.SDK_INT >= 16) {
                    settings.setAllowFileAccessFromFileURLs(true);
                    settings.setAllowUniversalAccessFromFileURLs(false);
                }
                FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(width, height);
                wlp.leftMargin = 0;
                wlp.topMargin = 0;
                webView.setLayoutParams(wlp);
                webView.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        pageReady.countDown();
                    }
                });

                root.addView(webView);
                presentation.setContentView(root);
                presentation.show();
                window.setLayout(width, height);

                String basePath = htmlFile.getParentFile() == null ? "/" : htmlFile.getParentFile().getAbsolutePath() + "/";
                String baseUrl = Uri.fromFile(new File(basePath)).toString();
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                webView.loadDataWithBaseURL(baseUrl, readHtml(htmlFile), "text/html", "UTF-8", baseUrl);
                webView.post(() -> layoutWebView());
                started.countDown();
            } catch (Throwable t) {
                failure[0] = t;
                started.countDown();
            }
        });

        if (!started.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Virtual display setup timeout");
        if (failure[0] != null) throw new Exception(failure[0]);
        if (!pageReady.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("WebView page load timeout");
        // Allow CSS animation/layout and compositor state to settle before the first snapshot.
        Thread.sleep(300);
    }

    private void layoutWebView() {
        if (webView == null) return;
        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        webView.measure(wSpec, hSpec);
        webView.layout(0, 0, width, height);
        webView.requestLayout();
        webView.invalidate();
    }

    /**
     * Captures the current WebView frame into a caller-owned bitmap.
     * WebView drawing happens on the main thread; the caller can then upload the bitmap
     * to the GPU texture on the GL thread.
     */
    public void captureBitmap(Bitmap target, long timeoutMs) throws Exception {
        if (target == null) throw new IllegalArgumentException("target == null");
        if (target.getWidth() != width || target.getHeight() != height) {
            throw new IllegalArgumentException("Bitmap size mismatch");
        }
        CountDownLatch done = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        mainHandler.post(() -> {
            try {
                if (webView == null) throw new IllegalStateException("WebView unavailable");
                Canvas canvas = new Canvas(target);
                canvas.drawColor(Color.BLACK);
                layoutWebView();
                webView.draw(canvas);
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                done.countDown();
            }
        });
        if (!done.await(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("WebView frame capture timeout");
        }
        if (error[0] != null) throw new Exception(error[0]);
    }

    private String readHtml(File file) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void stop() {
        CountDownLatch done = new CountDownLatch(1);
        mainHandler.post(() -> {
            try { if (presentation != null) presentation.dismiss(); } catch (Throwable ignored) {}
            try { if (webView != null) webView.stopLoading(); } catch (Throwable ignored) {}
            try { if (webView != null) webView.destroy(); } catch (Throwable ignored) {}
            try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
            try { surface.release(); } catch (Throwable ignored) {}
            presentation = null;
            webView = null;
            virtualDisplay = null;
            done.countDown();
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public android.graphics.SurfaceTexture getTexture() { return texture; }
}
