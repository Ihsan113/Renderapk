package com.danzku.htmlrender;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class RenderActivity extends Activity {
    private TextView status, queueStatus, resultHint;
    private WebView renderPreviewWebView;
    private VideoView resultVideoView;
    private ArrayList<String> sourcePaths;
    private ArrayList<String> sourceNames;
    private RenderOptions options;
    private int currentIndex;
    private HtmlVideoEncoder currentEncoder;
    private boolean cancelled;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_render);

        sourcePaths = getIntent().getStringArrayListExtra("sourcePaths");
        sourceNames = getIntent().getStringArrayListExtra("sourceNames");
        if (sourcePaths == null) sourcePaths = new ArrayList<>();
        if (sourceNames == null) sourceNames = new ArrayList<>();
        Bundle optionBundle = getIntent().getBundleExtra("options");
        options = RenderOptions.fromBundle(optionBundle == null ? new Bundle() : optionBundle);

        status = findViewById(R.id.renderStatus);
        queueStatus = findViewById(R.id.renderQueueStatus);
        resultHint = findViewById(R.id.resultHint);
        renderPreviewWebView = findViewById(R.id.renderPreviewWebView);
        resultVideoView = findViewById(R.id.resultVideoView);
        configurePreview();

        findViewById(R.id.cancelButton).setOnClickListener(v -> cancelQueue());
        findViewById(R.id.backgroundButton).setOnClickListener(v -> {
            Toast.makeText(this, "Render tetap berjalan di foreground service.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        });

        currentIndex = 0;
        updateQueueStatus();
        if (!sourcePaths.isEmpty()) loadRenderPreview(new File(sourcePaths.get(0)));
        startNext();
    }

    private void configurePreview() {
        renderPreviewWebView.setBackgroundColor(Color.rgb(5, 7, 13));
        WebSettings s = renderPreviewWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        renderPreviewWebView.setWebViewClient(new WebViewClient());
    }

    private void loadRenderPreview(File file) {
        if (file == null || !file.isFile()) return;
        renderPreviewWebView.loadUrl(Uri.fromFile(file).toString());
    }

    private void startNext() {
        if (cancelled) return;
        if (currentIndex >= sourcePaths.size()) {
            RenderService.update(100, "Semua render selesai");
            stopService(new Intent(this, RenderService.class));
            status.setText("Semua render selesai");
            queueStatus.setText("Queue selesai • " + sourcePaths.size() + " file");
            return;
        }

        File source = new File(sourcePaths.get(currentIndex));
        if (!source.exists()) {
            onError(new IllegalStateException("File tidak ditemukan: " + source.getName()));
            return;
        }
        updateQueueStatus();
        loadRenderPreview(source);
        status.setText("Menyiapkan render GPU texture…");

        File temp = new File(getCacheDir(), "render_" + currentIndex + "_" + System.currentTimeMillis() + ".mp4");
        currentEncoder = new HtmlVideoEncoder(this, new android.os.Handler(getMainLooper()),
                source.getAbsolutePath(), temp.getAbsolutePath(), options,
                new HtmlVideoEncoder.Listener() {
                    @Override public void onProgress(int percent, String message) {
                        RenderService.update(percent, queueMessage(message));
                        runOnUiThread(() -> status.setText(message + " • " + percent + "%"));
                    }
                    @Override public void onFinished(String outputPath, String codecName, boolean hardware) {
                        runOnUiThread(() -> {
                            try {
                                Uri resultUri = publishResult(new File(outputPath), codecName, hardware);
                                currentEncoder = null;
                                currentIndex++;
                                if (currentIndex >= sourcePaths.size()) {
                                    showResult(resultUri);
                                    RenderService.update(100, "Semua render selesai");
                                    stopService(new Intent(RenderActivity.this, RenderService.class));
                                    status.setText("Render selesai");
                                    queueStatus.setText("Queue selesai • " + sourcePaths.size() + " file");
                                } else {
                                    startNext();
                                }
                            } catch (Throwable t) { onError(t); }
                        });
                    }
                    @Override public void onError(Throwable error) { runOnUiThread(() -> onError(error)); }
                });
        currentEncoder.render();
    }

    private String queueMessage(String message) {
        return "Item " + (currentIndex + 1) + "/" + sourcePaths.size() + " • " + message;
    }

    private void updateQueueStatus() {
        if (sourcePaths.isEmpty()) {
            queueStatus.setText("Queue kosong");
            return;
        }
        String current = currentIndex < sourceNames.size() ? sourceNames.get(currentIndex) : "selesai";
        queueStatus.setText("Queue " + Math.min(currentIndex + 1, sourcePaths.size()) + "/" + sourcePaths.size() + " • " + current);
    }

    private void cancelQueue() {
        cancelled = true;
        if (currentEncoder != null) currentEncoder.cancel();
        stopService(new Intent(this, RenderService.class));
        status.setText("Render dibatalkan");
        Toast.makeText(this, "Queue render dibatalkan", Toast.LENGTH_LONG).show();
    }

    private void onError(Throwable error) {
        cancelled = true;
        if (currentEncoder != null) currentEncoder.cancel();
        stopService(new Intent(this, RenderService.class));
        String msg = error == null ? "unknown" : (error.getMessage() == null ? error.toString() : error.getMessage());
        status.setText("ERROR: " + msg);
        Toast.makeText(this, "Render gagal: " + msg, Toast.LENGTH_LONG).show();
    }

    private Uri publishResult(File file, String codecName, boolean hardware) {
        try {
            if (file == null || !file.isFile() || file.length() < 4096) {
                throw new IllegalStateException("Output video kosong atau tidak valid");
            }
            String original = currentIndex < sourceNames.size() ? sourceNames.get(currentIndex) : "video";
            String base = original.replaceAll("(?i)\\.html?$", "").replaceAll("[^a-zA-Z0-9._-]+", "_");
            if (base.isEmpty()) base = "HTMLRender";
            String suffix = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String name = base + "_" + suffix + ".mp4";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HTMLRenderStudio");
            if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            try (FileInputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Tidak bisa membuka output stream");
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            }
            file.delete();
            status.setText("Selesai • " + (hardware ? "HW encoder" : "SW fallback") + " • " + name);
            return uri;
        } catch (Exception e) {
            throw new RuntimeException("Gagal menyimpan video: " + e.getMessage(), e);
        }
    }

    private void showResult(Uri uri) {
        if (uri == null) return;
        renderPreviewWebView.setVisibility(View.GONE);
        resultVideoView.setVisibility(View.VISIBLE);
        resultVideoView.setVideoURI(uri);
        resultVideoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            resultVideoView.start();
        });
        resultHint.setText("Preview hasil render • tap untuk play/pause");
        resultHint.setVisibility(View.VISIBLE);
        resultVideoView.setOnClickListener(v -> {
            if (resultVideoView.isPlaying()) resultVideoView.pause();
            else resultVideoView.start();
        });
    }

    @Override public void onBackPressed() {
        moveTaskToBack(true);
    }
}
