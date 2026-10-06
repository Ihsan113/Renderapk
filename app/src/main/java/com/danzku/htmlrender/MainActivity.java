package com.danzku.htmlrender;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_HTML = 10;
    private static final int REQ_NOTIFICATION = 11;
    private TextView sourceName, codecLabel, durationLabel, bitrateLabel, queueLabel, previewStatus;
    private WebView previewWebView;
    private Spinner resolutionSpinner, fpsSpinner, codecSpinner, qualitySpinner, alphaSpinner, presetSpinner, templateSpinner;
    private final ArrayList<String> sourcePaths = new ArrayList<>();
    private final ArrayList<String> sourceNames = new ArrayList<>();
    private int durationSec = 6;
    private int bitrateMbps = 8;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(getColor(R.color.bg));
        getWindow().setNavigationBarColor(getColor(R.color.bg));

        sourceName = findViewById(R.id.sourceName);
        codecLabel = findViewById(R.id.codecLabel);
        durationLabel = findViewById(R.id.durationLabel);
        bitrateLabel = findViewById(R.id.bitrateLabel);
        queueLabel = findViewById(R.id.queueLabel);
        previewStatus = findViewById(R.id.previewStatus);
        previewWebView = findViewById(R.id.previewWebView);
        configurePreview();
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        fpsSpinner = findViewById(R.id.fpsSpinner);
        codecSpinner = findViewById(R.id.codecSpinner);
        qualitySpinner = findViewById(R.id.qualitySpinner);
        alphaSpinner = findViewById(R.id.alphaSpinner);
        presetSpinner = findViewById(R.id.presetSpinner);
        templateSpinner = findViewById(R.id.templateSpinner);

        setAdapter(resolutionSpinner, new String[]{"1280 × 720", "1920 × 1080"});
        setAdapter(fpsSpinner, new String[]{"30 FPS", "60 FPS"});
        setAdapter(codecSpinner, new String[]{"H.264 (AVC)", "H.265 (HEVC)"});
        setAdapter(qualitySpinner, new String[]{"Economy", "Standard", "High", "Ultra"});
        setAdapter(alphaSpinner, new String[]{"Alpha: Opaque", "Alpha: Black Composite", "Alpha: White Composite"});
        setAdapter(presetSpinner, new String[]{"Custom", "Stock 1080p", "Cinematic 1080p", "Lightweight Mobile"});
        setAdapter(templateSpinner, new String[]{"No template", "GPU Test: Business Growth", "Finance Motion", "Tech Glow"});

        findViewById(R.id.pickButton).setOnClickListener(v -> pickHtml());
        findViewById(R.id.demoButton).setOnClickListener(v -> useDemo());
        findViewById(R.id.renderButton).setOnClickListener(v -> startRender());
        presetSpinner.setOnItemSelectedListener(new SimpleItemListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { applyPreset(position); }
        });
        templateSpinner.setOnItemSelectedListener(new SimpleItemListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { if (position > 0) useTemplate(position); }
        });
        qualitySpinner.setOnItemSelectedListener(new SimpleItemListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int[] values = {5, 8, 14, 20};
                bitrateMbps = values[position];
                SeekBar seek = findViewById(R.id.bitrateSeek);
                seek.setProgress(bitrateMbps - 2);
                updateBitrateLabel();
            }
        });

        SeekBar duration = findViewById(R.id.durationSeek);
        duration.setProgress(5);
        duration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                durationSec = p + 1;
                durationLabel.setText("Durasi: " + durationSec + " detik");
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });

        SeekBar bitrate = findViewById(R.id.bitrateSeek);
        bitrate.setProgress(bitrateMbps - 2);
        bitrate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                bitrateMbps = p + 2;
                updateBitrateLabel();
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });

        useDemo();
        updateCodecLabel();
        updateBitrateLabel();
    }

    private void setAdapter(Spinner spinner, String[] values) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
    }

    private void updateBitrateLabel() { bitrateLabel.setText("Bitrate: " + bitrateMbps + " Mbps (VBR)"); }

    private void updateCodecLabel() {
        HardwareCodecUtil.CodecInfo avc = HardwareCodecUtil.findH264Encoder();
        HardwareCodecUtil.CodecInfo hevc = HardwareCodecUtil.findHevcEncoder();
        String a = avc == null ? "H.264: tidak tersedia" : "H.264: " + (avc.hardware ? "HW" : "SW") + " • " + avc.name;
        String h = hevc == null ? "HEVC: tidak tersedia" : "HEVC: " + (hevc.hardware ? "HW" : "SW") + " • " + hevc.name;
        codecLabel.setText(a + "\n" + h);
    }

    private void useDemo() { templateSpinner.setSelection(1); useTemplate(1); }

    private void configurePreview() {
        previewWebView.setBackgroundColor(android.graphics.Color.BLACK);
        WebSettings settings = previewWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        previewWebView.setWebChromeClient(new WebChromeClient());
        previewWebView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                previewStatus.setText("Preview aktif • WebView GPU");
            }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                previewStatus.setText("Preview renderer WebView berhenti");
                return true;
            }
        });
    }

    private void loadPreview(File file) {
        if (file == null || !file.isFile()) {
            previewStatus.setText("Preview: file tidak tersedia");
            return;
        }
        previewStatus.setText("Preview: memuat " + file.getName());
        previewWebView.loadUrl(Uri.fromFile(file).toString());
    }

    private void useTemplate(int position) {
        String asset = position == 1 ? "templates/business_growth_gpu_test.html" : position == 2 ? "templates/finance.html" : "templates/tech.html";
        try {
            File dir = new File(getFilesDir(), "templates");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Gagal membuat folder template");
            File file = new File(dir, new File(asset).getName());
            try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            sourcePaths.clear(); sourceNames.clear();
            sourcePaths.add(file.getAbsolutePath()); sourceNames.add(file.getName());
            updateQueueLabel();
            loadPreview(file);
        } catch (Exception e) { toast("Template gagal dimuat: " + e.getMessage()); }
    }

    private void pickHtml() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("text/html");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQ_HTML);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_HTML || resultCode != RESULT_OK || data == null) return;

        try {
            sourcePaths.clear(); sourceNames.clear();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) addUri(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                addUri(data.getData());
            }
            if (sourcePaths.isEmpty()) throw new IllegalStateException("Tidak ada file HTML");
            updateQueueLabel();
        } catch (Exception e) { toast("Gagal import HTML: " + e.getMessage()); }
    }

    private void addUri(Uri uri) throws Exception {
        File file = new File(getCacheDir(), "source_" + System.currentTimeMillis() + "_" + sourcePaths.size() + ".html");
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IllegalStateException("Tidak bisa membuka file");
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        sourcePaths.add(file.getAbsolutePath());
        sourceNames.add(queryName(uri));
        if (sourcePaths.size() == 1) loadPreview(file);
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {}
        return uri.toString();
    }

    private void updateQueueLabel() {
        if (sourcePaths.isEmpty()) {
            queueLabel.setText("Queue: kosong");
            sourceName.setText("Belum ada HTML");
            previewStatus.setText("Preview: belum ada HTML");
            previewWebView.loadDataWithBaseURL(null, "<html><body style='margin:0;background:#05070d;color:#9aa;display:grid;place-items:center;height:100vh;font-family:sans-serif'><div>Belum ada HTML</div></body></html>", "text/html", "UTF-8", null);
            return;
        }
        sourceName.setText(sourceNames.get(0) + (sourceNames.size() > 1 ? " +" + (sourceNames.size() - 1) : ""));
        queueLabel.setText("Queue: " + sourceNames.size() + " HTML • render berurutan");
    }

    private void applyPreset(int position) {
        if (position == 1) { // stock
            resolutionSpinner.setSelection(1); fpsSpinner.setSelection(0); codecSpinner.setSelection(0); qualitySpinner.setSelection(2); bitrateMbps = 12;
        } else if (position == 2) {
            resolutionSpinner.setSelection(1); fpsSpinner.setSelection(1); codecSpinner.setSelection(1); qualitySpinner.setSelection(3); bitrateMbps = 20;
        } else if (position == 3) {
            resolutionSpinner.setSelection(0); fpsSpinner.setSelection(0); codecSpinner.setSelection(0); qualitySpinner.setSelection(1); bitrateMbps = 8;
        }
        SeekBar b = findViewById(R.id.bitrateSeek);
        b.setProgress(Math.max(0, bitrateMbps - 2));
        updateBitrateLabel();
    }

    private void startRender() {
        if (sourcePaths.isEmpty()) { toast("Pilih HTML atau template dulu."); return; }

        int pos = resolutionSpinner.getSelectedItemPosition();
        int width = pos == 0 ? 1280 : 1920;
        int height = pos == 0 ? 720 : 1080;
        int fps = fpsSpinner.getSelectedItemPosition() == 0 ? 30 : 60;
        String mime = codecSpinner.getSelectedItemPosition() == 0 ? RenderOptions.CODEC_AVC : RenderOptions.CODEC_HEVC;
        HardwareCodecUtil.CodecInfo ci = HardwareCodecUtil.findEncoder(mime);
        if (ci == null) { toast("Encoder " + mime + " dengan input Surface tidak tersedia di perangkat."); return; }

        int alpha = alphaSpinner.getSelectedItemPosition();
        String preset = String.valueOf(presetSpinner.getSelectedItem());
        RenderOptions options = new RenderOptions(width, height, fps, durationSec, mime, bitrateMbps,
                qualitySpinner.getSelectedItemPosition(), alpha, preset);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
        RenderService.start(this, sourcePaths.size());

        Intent i = new Intent(this, RenderActivity.class);
        i.putStringArrayListExtra("sourcePaths", sourcePaths);
        i.putStringArrayListExtra("sourceNames", sourceNames);
        i.putExtra("options", options.toBundle());
        startActivity(i);
    }

    private void toast(String s) { Toast.makeText(this, s == null ? "Terjadi kesalahan" : s, Toast.LENGTH_LONG).show(); }

    private abstract static class SimpleItemListener implements android.widget.AdapterView.OnItemSelectedListener {
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
