package com.danzku.htmlrender;

import android.app.Presentation;
import android.content.Context;
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
 * GPU source: WebView compositor -> VirtualDisplay Surface -> SurfaceTexture.
 * No Bitmap/readback is used. The SurfaceTexture is sampled by OpenGL as
 * samplerExternalOES and drawn directly to the MediaCodec input Surface.
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

    private final Object frameLock = new Object();
    private long frameSequence = 0;

    public OffscreenWebViewSource(Context context, int width, int height,
                                  android.graphics.SurfaceTexture texture, Handler mainHandler) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.texture = texture;
        this.surface = new Surface(texture);
        this.mainHandler = mainHandler;
    }

    public void attachFrameListener() {
        if (texture == null) throw new IllegalStateException("SurfaceTexture unavailable");
        texture.setOnFrameAvailableListener(t -> {
            synchronized (frameLock) {
                frameSequence++;
                frameLock.notifyAll();
            }
        }, mainHandler);
    }

    public long getFrameSequence() {
        synchronized (frameLock) { return frameSequence; }
    }

    /** Wait until WebView compositor has produced a frame newer than sequence. */
    public long awaitFrameAfter(long sequence, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(100, timeoutMs);
        synchronized (frameLock) {
            while (frameSequence <= sequence) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) throw new IllegalStateException("WebView GPU frame timeout");
                frameLock.wait(Math.min(remaining, 250));
            }
            return frameSequence;
        }
    }

    public void start(File htmlFile) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        mainHandler.post(() -> {
            try {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                if (dm == null) throw new IllegalStateException("DisplayManager unavailable");
                virtualDisplay = dm.createVirtualDisplay(
                        "HTMLRenderStudio-GPU-" + System.currentTimeMillis(),
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
                root.setBackgroundColor(android.graphics.Color.BLACK);
                root.setLayoutParams(new FrameLayout.LayoutParams(width, height));

                webView = new WebView(context);
                webView.setBackgroundColor(android.graphics.Color.BLACK);
                // NONE means use the normal hardware-accelerated WebView compositor.
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
                webView.post(this::layoutWebView);
                started.countDown();
            } catch (Throwable t) {
                failure[0] = t;
                started.countDown();
            }
        });

        if (!started.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Virtual display setup timeout");
        if (failure[0] != null) throw new Exception(failure[0]);
        if (!pageReady.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("WebView page load timeout");

        // Do not use a sleep as the frame synchronisation mechanism. Wait for the
        // compositor to actually submit at least one SurfaceTexture frame.
        long before = getFrameSequence();
        awaitFrameAfter(before, 5000);
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

    private String readHtml(File file) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void stop() {
        if (texture != null) texture.setOnFrameAvailableListener(null);
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
