package com.danzku.htmlrender;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HtmlVideoEncoder {
    public interface Listener {
        void onProgress(int percent, String message);
        void onFinished(String outputPath, String codecName, boolean hardware);
        void onError(Throwable error);
    }

    private final Context context;
    private final Handler mainHandler;
    private final String sourcePath;
    private final String outputPath;
    private final RenderOptions options;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public HtmlVideoEncoder(Context context, Handler mainHandler, String sourcePath, String outputPath,
                            RenderOptions options, Listener listener) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.sourcePath = sourcePath;
        this.outputPath = outputPath;
        this.options = options;
        this.listener = listener;
    }

    public void render() {
        new Thread(() -> {
            try {
                renderInternal();
            } catch (Throwable t) {
                if (!cancelled.get()) listener.onError(t);
            }
        }, "html-render-thread").start();
    }

    public void cancel() { cancelled.set(true); }

    private void renderInternal() throws Exception {
        if (Build.VERSION.SDK_INT < 26) throw new IllegalStateException("VirtualDisplay renderer requires Android 8.0+");
        HardwareCodecUtil.CodecInfo info = HardwareCodecUtil.findEncoder(options.mime);
        if (info == null) throw new IllegalStateException("Encoder Surface tidak tersedia untuk " + options.mime);

        MediaCodec codec = null;
        Surface encoderSurface = null;
        GlVideoRenderer gl = null;
        MediaMuxer muxer = null;
        OffscreenWebViewSource source = null;
        SurfaceTexture webTexture = null;
        Bitmap fallbackBitmap = null;
        MuxerState muxerState = new MuxerState();

        try {
            codec = MediaCodec.createByCodecName(info.name);
            MediaFormat format = MediaFormat.createVideoFormat(options.mime, options.width, options.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, options.bitrateMbps * 1_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, options.fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (Build.VERSION.SDK_INT >= 21) {
                MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(options.mime);
                if (caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                }
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = codec.createInputSurface();
            codec.start();
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            gl = new GlVideoRenderer();
            gl.init(encoderSurface);

            webTexture = gl.createWebViewSurfaceTexture();
            source = new OffscreenWebViewSource(context, options.width, options.height, webTexture, mainHandler);
            source.attachFrameListener();
            source.start(new File(sourcePath));

            String codecTitle = info.hardware ? "hardware" : "software fallback";
            String alphaTitle = options.alphaMode == RenderOptions.ALPHA_OPAQUE ? "alpha opaque" : "alpha composited";
            listener.onProgress(0, (options.mime.equals(RenderOptions.CODEC_HEVC) ? "HEVC" : "H.264")
                    + " " + codecTitle + " • " + options.bitrateMbps + " Mbps • " + alphaTitle + " • GPU texture");

            int frames = Math.max(1, options.durationSec * options.fps);
            long frameNs = 1_000_000_000L / options.fps;
            long frameMs = Math.max(1, 1000L / options.fps);
            for (int i = 0; i < frames; i++) {
                if (cancelled.get()) throw new IOException("Render dibatalkan");
                // The WebView is rendered on a virtual display, so the source continues even when the app task is minimized.
                gl.renderExternal(webTexture, i * frameNs, options.alphaMode);
                drainEncoder(codec, muxer, muxerState, false);
                SystemClock.sleep(frameMs);

                if (i % Math.max(1, options.fps / 2) == 0) {
                    int percent = Math.min(99, (i * 100) / frames);
                    listener.onProgress(percent, "Rendering frame " + (i + 1) + "/" + frames);
                }
            }

            codec.signalEndOfInputStream();
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            boolean eos = false;
            while (!eos && SystemClock.elapsedRealtime() < deadline) {
                if (cancelled.get()) throw new IOException("Render dibatalkan");
                eos = drainEncoder(codec, muxer, muxerState, true);
            }
            if (!eos) throw new IOException("Encoder EOS timeout");
            if (!muxerState.started) throw new IOException("Encoder produced no output track");

            listener.onProgress(100, "Selesai");
            listener.onFinished(outputPath, info.name, info.hardware);
        } finally {
            if (source != null) source.stop();
            if (webTexture != null) {
                try { webTexture.release(); } catch (Throwable ignored) {}
            }
            if (gl != null) gl.release();
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                codec.release();
            }
            if (encoderSurface != null) encoderSurface.release();
            if (muxer != null) {
                try { if (muxerState.started) muxer.stop(); } catch (Throwable ignored) {}
                muxer.release();
            }
            if (fallbackBitmap != null && !fallbackBitmap.isRecycled()) fallbackBitmap.recycle();
            if (cancelled.get()) {
                try { new File(outputPath).delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean drainEncoder(MediaCodec codec, MediaMuxer muxer, MuxerState state, boolean wait) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(info, wait ? 10_000 : 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.started) throw new IllegalStateException("Encoder format changed twice");
                state.track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                state.started = true;
                continue;
            }
            if (outIndex < 0) continue;

            ByteBuffer out = codec.getOutputBuffer(outIndex);
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (out != null && info.size > 0 && state.started
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                out.position(info.offset);
                out.limit(info.offset + info.size);
                muxer.writeSampleData(state.track, out, info);
            }
            codec.releaseOutputBuffer(outIndex, false);
            if (eos) return true;
            wait = false;
        }
    }

    private static final class MuxerState {
        int track = -1;
        boolean started;
    }
}
