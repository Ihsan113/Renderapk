package com.danzku.htmlrender;

import android.os.Bundle;

/** Immutable render settings passed from the UI to the renderer. */
public final class RenderOptions {
    public static final String CODEC_AVC = "video/avc";
    public static final String CODEC_HEVC = "video/hevc";

    public static final int QUALITY_ECONOMY = 0;
    public static final int QUALITY_STANDARD = 1;
    public static final int QUALITY_HIGH = 2;
    public static final int QUALITY_ULTRA = 3;

    public static final int ALPHA_OPAQUE = 0;
    public static final int ALPHA_BLACK = 1;
    public static final int ALPHA_WHITE = 2;

    public final int width;
    public final int height;
    public final int fps;
    public final int durationSec;
    public final String mime;
    public final int bitrateMbps;
    public final int quality;
    public final int alphaMode;
    public final String preset;

    public RenderOptions(int width, int height, int fps, int durationSec,
                         String mime, int bitrateMbps, int quality, int alphaMode, String preset) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.durationSec = durationSec;
        this.mime = mime;
        this.bitrateMbps = Math.max(2, Math.min(30, bitrateMbps));
        this.quality = quality;
        this.alphaMode = alphaMode;
        this.preset = preset == null ? "Custom" : preset;
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putInt("width", width);
        b.putInt("height", height);
        b.putInt("fps", fps);
        b.putInt("duration", durationSec);
        b.putString("mime", mime);
        b.putInt("bitrateMbps", bitrateMbps);
        b.putInt("quality", quality);
        b.putInt("alphaMode", alphaMode);
        b.putString("preset", preset);
        return b;
    }

    public static RenderOptions fromBundle(Bundle b) {
        return new RenderOptions(
                b.getInt("width", 1280), b.getInt("height", 720),
                b.getInt("fps", 30), b.getInt("duration", 6),
                b.getString("mime", CODEC_AVC), b.getInt("bitrateMbps", 8),
                b.getInt("quality", QUALITY_STANDARD), b.getInt("alphaMode", ALPHA_OPAQUE),
                b.getString("preset", "Custom"));
    }
}
