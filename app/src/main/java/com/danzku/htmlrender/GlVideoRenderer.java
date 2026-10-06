package com.danzku.htmlrender;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GPU texture stage: one reusable GL texture is updated each frame and drawn
 * directly into the MediaCodec input Surface.
 */
public final class GlVideoRenderer {
    private static final float[] VERTICES = {
            -1f, -1f,  1f, 1f,
             1f, -1f,  0f, 1f,
            -1f,  1f,  1f, 0f,
             1f,  1f,  0f, 0f
    };
    private static final String VS =
            "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;" +
            "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}";
    private static final String FS =
            "precision mediump float; varying vec2 vTex; uniform sampler2D uTex;" +
            "uniform vec4 uBg; uniform int uAlphaMode;" +
            "void main(){ vec4 c=texture2D(uTex,vTex);" +
            "if(uAlphaMode==1 || uAlphaMode==2){c.rgb=mix(uBg.rgb,c.rgb,c.a);c.a=1.0;}" +
            "else{c.a=1.0;} gl_FragColor=c;}";
    private static final String EXTERNAL_VS =
            "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uTexMatrix;" +
            "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=(uTexMatrix*vec4(aTex,0.0,1.0)).xy;}";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String EXTERNAL_FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vTex; uniform samplerExternalOES uTex;" +
            "uniform vec4 uBg; uniform int uAlphaMode;" +
            "void main(){vec4 c=texture2D(uTex,vTex);" +
            "if(uAlphaMode==1 || uAlphaMode==2){c.rgb=mix(uBg.rgb,c.rgb,c.a);c.a=1.0;}" +
            "else{c.a=1.0;}gl_FragColor=c;}";

    private final FloatBuffer vertexBuffer;
    private android.opengl.EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private android.opengl.EGLContext context = EGL14.EGL_NO_CONTEXT;
    private android.opengl.EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texture;
    private int posLoc;
    private int texLoc;
    private int samplerLoc;
    private int bgLoc;
    private int alphaLoc;
    private int externalProgram;
    private int externalPosLoc;
    private int externalTexLoc;
    private int externalSamplerLoc;
    private int externalMatrixLoc;
    private int externalBgLoc;
    private int externalAlphaLoc;
    private int externalTexture;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean textureAllocated;

    public GlVideoRenderer() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);
    }

    public void init(Surface encoderSurface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("EGL display unavailable");
        int[] major = new int[1], minor = new int[1];
        if (!EGL14.eglInitialize(display, major, 0, minor, 0)) throw new IllegalStateException("eglInitialize failed");

        int[] configs = new int[1];
        android.opengl.EGLConfig[] configArr = new android.opengl.EGLConfig[1];
        int[] attrib = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        if (!EGL14.eglChooseConfig(display, attrib, 0, configArr, 0, 1, configs, 0) || configs[0] == 0) {
            throw new IllegalStateException("No EGL config");
        }
        int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configArr[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("EGL context failed");
        int[] surfaceAttrib = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(display, configArr[0], encoderSurface, surfaceAttrib, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("EGL window surface failed");
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw new IllegalStateException("eglMakeCurrent failed");

        surfaceWidth = query(EGL14.EGL_WIDTH);
        surfaceHeight = query(EGL14.EGL_HEIGHT);
        program = link(VS, FS);
        posLoc = GLES20.glGetAttribLocation(program, "aPos");
        texLoc = GLES20.glGetAttribLocation(program, "aTex");
        samplerLoc = GLES20.glGetUniformLocation(program, "uTex");
        bgLoc = GLES20.glGetUniformLocation(program, "uBg");
        alphaLoc = GLES20.glGetUniformLocation(program, "uAlphaMode");
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        externalProgram = link(EXTERNAL_VS, EXTERNAL_FS);
        externalPosLoc = GLES20.glGetAttribLocation(externalProgram, "aPos");
        externalTexLoc = GLES20.glGetAttribLocation(externalProgram, "aTex");
        externalSamplerLoc = GLES20.glGetUniformLocation(externalProgram, "uTex");
        externalMatrixLoc = GLES20.glGetUniformLocation(externalProgram, "uTexMatrix");
        externalBgLoc = GLES20.glGetUniformLocation(externalProgram, "uBg");
        externalAlphaLoc = GLES20.glGetUniformLocation(externalProgram, "uAlphaMode");
        checkGl("init");
    }

    /** Creates the SurfaceTexture target consumed by an off-screen WebView VirtualDisplay. */
    public android.graphics.SurfaceTexture createWebViewSurfaceTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        externalTexture = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        android.graphics.SurfaceTexture st = new android.graphics.SurfaceTexture(externalTexture);
        st.setDefaultBufferSize(surfaceWidth, surfaceHeight);
        return st;
    }

    public void render(Bitmap bitmap, long presentationNs, int alphaMode) {
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (!textureAllocated) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            textureAllocated = true;
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        }
        GLES20.glUniform1i(samplerLoc, 0);
        // 1 = black composite, 2 = white composite. H.264/HEVC do not carry alpha here.
        if (alphaMode == RenderOptions.ALPHA_WHITE) GLES20.glUniform4f(bgLoc, 1f, 1f, 1f, 1f);
        else GLES20.glUniform4f(bgLoc, 0f, 0f, 0f, 1f);
        GLES20.glUniform1i(alphaLoc, alphaMode);

        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        vertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(texLoc);
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posLoc);
        GLES20.glDisableVertexAttribArray(texLoc);
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationNs);
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new IllegalStateException("eglSwapBuffers failed");
        checkGl("render");
    }

    public void renderExternal(android.graphics.SurfaceTexture source, long presentationNs, int alphaMode) {
        source.updateTexImage();
        float[] matrix = new float[16];
        source.getTransformMatrix(matrix);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(externalProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
        GLES20.glUniform1i(externalSamplerLoc, 0);
        GLES20.glUniformMatrix4fv(externalMatrixLoc, 1, false, matrix, 0);
        if (alphaMode == RenderOptions.ALPHA_WHITE) GLES20.glUniform4f(externalBgLoc, 1f, 1f, 1f, 1f);
        else GLES20.glUniform4f(externalBgLoc, 0f, 0f, 0f, 1f);
        GLES20.glUniform1i(externalAlphaLoc, alphaMode);
        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(externalPosLoc);
        GLES20.glVertexAttribPointer(externalPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        vertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(externalTexLoc);
        GLES20.glVertexAttribPointer(externalTexLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(externalPosLoc);
        GLES20.glDisableVertexAttribArray(externalTexLoc);
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationNs);
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new IllegalStateException("eglSwapBuffers failed");
        checkGl("renderExternal");
    }

    private int query(int what) {
        int[] value = new int[1];
        EGL14.eglQuerySurface(display, eglSurface, what, value, 0);
        return value[0];
    }

    public void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (texture != 0) {
                GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                texture = 0;
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (externalProgram != 0) {
                GLES20.glDeleteProgram(externalProgram);
                externalProgram = 0;
            }
            if (externalTexture != 0) {
                GLES20.glDeleteTextures(1, new int[]{externalTexture}, 0);
                externalTexture = 0;
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Shader compile: " + log);
        }
        return shader;
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p);
        int[] ok = new int[1]; GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f);
        if (ok[0] == 0) throw new IllegalStateException("Program link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static void checkGl(String where) {
        int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR) throw new IllegalStateException(where + " GL error 0x" + Integer.toHexString(err));
    }
}
