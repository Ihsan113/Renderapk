package com.danzku.htmlrender;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.graphics.SurfaceTexture;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.Color;
import android.app.Presentation;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headless WebView compositor source. Chromium's supported snapshot path is a
 * WebView attached to a Presentation on a VirtualDisplay backed by a SurfaceTexture.
 */
public final class OffscreenWebViewSource {
    private final Context context;
    private final android.os.Handler mainHandler;
    private final int width;
    private final int height;
    private final SurfaceTexture texture;
    private final android.view.Surface surface;
    private VirtualDisplay virtualDisplay;
    private Presentation presentation;
    private WebView webView;
    private final CountDownLatch pageReady = new CountDownLatch(1);
    private volatile boolean frameAvailable;
    private final Object frameLock = new Object();

    public OffscreenWebViewSource(Context context, int width, int height,
                                  SurfaceTexture texture, android.os.Handler mainHandler) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.texture = texture;
        this.surface = new android.view.Surface(texture);
        this.mainHandler = mainHandler;
    }

    /** Must be called after creation on the GL thread. */
    public void attachFrameListener() {
        texture.setOnFrameAvailableListener(t -> {
            synchronized (frameLock) {
                frameAvailable = true;
                frameLock.notifyAll();
            }
        }, mainHandler);
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

                presentation = new Presentation(context, virtualDisplay.getDisplay());
                Window window = presentation.getWindow();
                if (window == null) throw new IllegalStateException("Presentation window unavailable");
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                window.setGravity(Gravity.TOP | Gravity.START);
                window.setFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN);
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = width;
                lp.height = height;
                lp.gravity = Gravity.TOP | Gravity.START;
                window.setAttributes(lp);
                presentation.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

                FrameLayout root = new FrameLayout(context);
                root.setLayoutParams(new FrameLayout.LayoutParams(width, height));
                webView = new WebView(context);
                webView.setBackgroundColor(Color.BLACK);
                webView.setLayerType(View.LAYER_TYPE_NONE, null);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
                }
                WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(true);
                s.setAllowContentAccess(true);
                s.setUseWideViewPort(false);
                s.setLoadWithOverviewMode(false);
                s.setMediaPlaybackRequiresUserGesture(false);
                if (android.os.Build.VERSION.SDK_INT >= 16) {
                    s.setAllowFileAccessFromFileURLs(true);
                    s.setAllowUniversalAccessFromFileURLs(false);
                }
                webView.setLayoutParams(new FrameLayout.LayoutParams(width, height));
                webView.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        pageReady.countDown();
                    }
                });
                root.addView(webView);
                presentation.setContentView(root);
                presentation.show();
                window.setLayout(width, height);
                started.countDown();

                String baseUrl = android.net.Uri.fromFile(htmlFile.getParentFile()).toString() + "/";
                webView.loadDataWithBaseURL(baseUrl, readHtml(htmlFile), "text/html", "UTF-8", baseUrl);
                webView.post(() -> {
                    webView.measure(
                            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
                    webView.layout(0, 0, width, height);
                    webView.requestLayout();
                    webView.invalidate();
                });
            } catch (Throwable t) {
                failure[0] = t;
                started.countDown();
            }
        });
        if (!started.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Virtual display setup timeout");
        if (failure[0] != null) throw new Exception(failure[0]);
        if (!pageReady.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("WebView page load timeout");

        // Give Chromium a compositor turn so the first frame reaches SurfaceTexture.
        android.os.SystemClock.sleep(250);
        if (!waitForFrame(2_000)) {
            throw new IllegalStateException("WebView tidak menghasilkan frame pada VirtualDisplay");
        }
    }

    private String readHtml(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<html><body style='background:#111;color:white;font-family:sans-serif'><h2>HTML load error</h2><pre>"
                    + escape(e.toString()) + "</pre></body></html>";
        }
    }

    private String escape(String x) { return x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    public boolean waitForFrame(long timeoutMs) throws InterruptedException {
        long deadline = android.os.SystemClock.uptimeMillis() + Math.max(1, timeoutMs);
        synchronized (frameLock) {
            while (!frameAvailable) {
                long remaining = deadline - android.os.SystemClock.uptimeMillis();
                if (remaining <= 0) break;
                frameLock.wait(remaining);
            }
            boolean ready = frameAvailable;
            frameAvailable = false;
            return ready;
        }
    }

    public void stop() {
        CountDownLatch done = new CountDownLatch(1);
        mainHandler.post(() -> {
            try { if (presentation != null) presentation.dismiss(); } catch (Throwable ignored) {}
            try { if (webView != null) webView.destroy(); } catch (Throwable ignored) {}
            try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
            try { surface.release(); } catch (Throwable ignored) {}
            presentation = null;
            webView = null;
            virtualDisplay = null;
            done.countDown();
        });
        try { done.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public SurfaceTexture getTexture() { return texture; }
}
