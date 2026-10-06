package com.danzku.htmlrender;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

public final class HardwareCodecUtil {
    private HardwareCodecUtil() {}

    public static CodecInfo findEncoder(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        CodecInfo fallback = null;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean supported = false;
            for (String type : info.getSupportedTypes()) {
                if (mime.equalsIgnoreCase(type)) { supported = true; break; }
            }
            if (!supported) continue;

            MediaCodecInfo.CodecCapabilities caps;
            try { caps = info.getCapabilitiesForType(mime); }
            catch (Throwable ignored) { continue; }

            boolean surface = false;
            for (int f : caps.colorFormats) {
                if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                    surface = true;
                    break;
                }
            }
            if (!surface) continue;

            boolean hw = Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated();
            CodecInfo candidate = new CodecInfo(info.getName(), hw, mime);
            if (fallback == null) fallback = candidate;
            if (hw) return candidate;
        }
        return fallback;
    }

    public static CodecInfo findH264Encoder() { return findEncoder(RenderOptions.CODEC_AVC); }
    public static CodecInfo findHevcEncoder() { return findEncoder(RenderOptions.CODEC_HEVC); }

    public static final class CodecInfo {
        public final String name;
        public final boolean hardware;
        public final String mime;
        CodecInfo(String name, boolean hardware, String mime) {
            this.name = name;
            this.hardware = hardware;
            this.mime = mime;
        }
    }
}
