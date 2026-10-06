package com.danzku.htmlrender;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
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
        if (Build.VERSION.SDK_INT < 26) throw new IllegalStateException("Renderer requires Android 8.0+");

        HardwareCodecUtil.CodecInfo info = HardwareCodecUtil.findEncoder(options.mime);
        if (info == null) throw new IllegalStateException("Encoder Surface tidak tersedia untuk " + options.mime);
        if (!info.hardware) throw new IllegalStateException("Hardware encoder tidak tersedia untuk " + options.mime + " pada perangkat ini; software fallback sengaja dinonaktifkan untuk GPU render mode.");

        MediaCodec codec = null;
        Surface encoderSurface = null;
        GlVideoRenderer gl = null;
        MediaMuxer muxer = null;
        OffscreenWebViewSource source = null;
        android.graphics.SurfaceTexture webTexture = null;
        MuxerState muxerState = new MuxerState();
        boolean success = false;
        String finishedCodecName = info.name;
        boolean finishedHardware = info.hardware;

        try {
            File output = new File(outputPath);
            File parent = output.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Gagal membuat folder output");
            }
            if (output.exists() && !output.delete()) throw new IOException("Gagal mengganti output lama");

            codec = MediaCodec.createByCodecName(info.name);
            MediaFormat format = MediaFormat.createVideoFormat(options.mime, options.width, options.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, options.bitrateMbps * 1_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, options.fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(options.mime);
            if (caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = codec.createInputSurface();
            codec.start();
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            gl = new GlVideoRenderer();
            gl.init(encoderSurface);
            // Direct GPU path: WebView compositor -> SurfaceTexture/OES -> OpenGL -> MediaCodec Surface.
            webTexture = gl.createWebViewSurfaceTexture();
            source = new OffscreenWebViewSource(context, options.width, options.height, webTexture, mainHandler);
            source.attachFrameListener();
            source.start(new File(sourcePath));

            String gpuRenderer = gl.getGlRenderer();
            String codecTitle = "HW encoder: " + info.name;
            String alphaTitle = options.alphaMode == RenderOptions.ALPHA_OPAQUE ? "opaque" : "composite";
            listener.onProgress(0, (options.mime.equals(RenderOptions.CODEC_HEVC) ? "HEVC" : "H.264")
                    + " • " + codecTitle + " • GPU: " + gpuRenderer + " • " + options.bitrateMbps + " Mbps • " + alphaTitle);

            int frames = Math.max(1, options.durationSec * options.fps);
            long frameNs = 1_000_000_000L / options.fps;
            long frameMs = Math.max(1, 1000L / options.fps);
            long startMs = SystemClock.elapsedRealtime();
            long consumedSequence = source.getFrameSequence() - 1;

            for (int i = 0; i < frames; i++) {
                if (cancelled.get()) throw new IOException("Render dibatalkan");

                // Synchronise to an actual WebView compositor frame. No Bitmap/readback.
                consumedSequence = source.awaitFrameAfter(consumedSequence, 5000);
                gl.renderExternal(webTexture, i * frameNs, options.alphaMode);
                drainEncoder(codec, muxer, muxerState, false);

                // Pace the WebView animation near the requested output FPS without sleeping
                // before the first frame has actually arrived.
                long expectedElapsed = (long) i * frameMs;
                long actualElapsed = SystemClock.elapsedRealtime() - startMs;
                long sleep = expectedElapsed - actualElapsed;
                if (sleep > 0) SystemClock.sleep(Math.min(sleep, frameMs));

                if (i == 0 || i % Math.max(1, options.fps / 2) == 0 || i == frames - 1) {
                    int percent = Math.min(99, ((i + 1) * 100) / frames);
                    listener.onProgress(percent, "GPU frame " + (i + 1) + "/" + frames);
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

            if (!output.isFile() || output.length() < 4096) {
                throw new IOException("Output video kosong atau rusak (size=" + output.length() + ")");
            }
            success = true;
            listener.onProgress(100, "Finalisasi MP4…");
        } finally {
            if (source != null) source.stop();
            if (webTexture != null) {
                try { webTexture.release(); } catch (Throwable ignored) {}
            }
            if (gl != null) gl.release();
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (encoderSurface != null) {
                try { encoderSurface.release(); } catch (Throwable ignored) {}
            }
            if (muxer != null) {
                try { if (muxerState.started) muxer.stop(); } catch (Throwable ignored) {}
                try { muxer.release(); } catch (Throwable ignored) {}
            }
            if (cancelled.get()) {
                try { new File(outputPath).delete(); } catch (Throwable ignored) {}
                success = false;
            }
        }

        // IMPORTANT: only publish after MediaMuxer has been stopped/released.
        // Previously onFinished() ran before the finally block closed the MP4,
        // which could expose an incomplete/corrupt video to MediaStore.
        if (success && !cancelled.get()) {
            File finalFile = new File(outputPath);
            validateMp4(finalFile);
            listener.onFinished(outputPath, finishedCodecName, finishedHardware);
        }
    }

    private void validateMp4(File file) throws IOException {
        if (!file.isFile() || file.length() < 4096) {
            throw new IOException("MP4 final kosong atau terlalu kecil: " + file.length() + " bytes");
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!"yes".equalsIgnoreCase(hasVideo)) {
                throw new IOException("MP4 tidak memiliki video track");
            }
            long durationMs = 0;
            try { durationMs = Long.parseLong(duration == null ? "0" : duration); } catch (NumberFormatException ignored) {}
            if (durationMs <= 0) {
                throw new IOException("MP4 memiliki video track tetapi durasinya 0 ms");
            }
        } catch (RuntimeException e) {
            throw new IOException("MP4 tidak dapat dibaca: " + e.getMessage(), e);
        } finally {
            try { retriever.release(); } catch (Throwable ignored) {}
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
